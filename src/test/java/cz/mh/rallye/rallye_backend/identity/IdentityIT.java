package cz.mh.rallye.rallye_backend.identity;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.support.TestDatabaseInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@ActiveProfiles("it")
@ContextConfiguration(initializers = TestDatabaseInitializer.class)
@Import(IdentityIT.Fixtures.class)
class IdentityIT {
    static final class Delivery {
        final String email, purpose, token;
        Delivery(String email, String purpose, String token) { this.email=email; this.purpose=purpose; this.token=token; }
    }
    static final class TestMail implements AccountMailSender {
        final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
        @Override public void send(String email, String purpose, String token) { deliveries.add(new Delivery(email,purpose,token)); }
        String token(String email, String purpose) {
            return deliveries.stream().filter(item -> item.email.equals(email) && item.purpose.equals(purpose))
                    .reduce((a,b) -> b).orElseThrow().token;
        }
    }
    @TestConfiguration static class Fixtures {
        @Bean @Primary TestMail testMail() { return new TestMail(); }
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }
    @Autowired AuthService auth;
    @Autowired TestMail mail;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    private final List<String> ownEmails = new java.util.ArrayList<>();
    private String email() {
        String value = UUID.randomUUID() + "@example.test"; ownEmails.add(value); return value;
    }
    @AfterEach void cleanupOnlyOwnFixtures() {
        for (String email : ownEmails) {
            for (String table : List.of("user_session", "user_action_token", "user_identity")) {
                jdbc.update("DELETE FROM " + table + " WHERE account_id IN (SELECT id FROM user_account WHERE normalized_email=?)", email);
            }
            jdbc.update("DELETE FROM user_account WHERE normalized_email=?", email);
        }
    }
    private String activatedAccount() {
        String email=email(); auth.register(email,"password","Person");
        auth.verifyEmail(mail.token(email,"EMAIL_VERIFICATION")); return email;
    }
    @Test void registrationVerificationAndLoginPersistOnlyHashes() {
        String email=email(); auth.register(email,"password","Person");
        assertThrows(AuthFailure.class, () -> auth.login(email,"password",false));
        String token=mail.token(email,"EMAIL_VERIFICATION");
        assertThrows(AuthFailure.class, () -> auth.verifyEmail("invalid"));
        auth.verifyEmail(token); assertThrows(AuthFailure.class, () -> auth.verifyEmail(token));
        var login=auth.login(email,"password",false);
        assertNotNull(auth.authenticate(login.secret()));
        assertTrue(jdbc.queryForObject("SELECT password_hash FROM user_identity WHERE account_id=?",String.class,login.principal().accountId()).startsWith("$argon2id$"));
        assertEquals(new SecretTokens().hash(login.secret()), jdbc.queryForObject("SELECT token_hash FROM user_session WHERE id=?",String.class,login.principal().sessionId()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM user_action_token WHERE token_hash=?",Integer.class,token));
    }
    @Test void activationExpiresAt24Hours() {
        String email=email(); auth.register(email,"password","Person");
        String token=mail.token(email,"EMAIL_VERIFICATION"); clock.advance(Duration.ofHours(24));
        assertThrows(AuthFailure.class, () -> auth.verifyEmail(token));
    }
    @Test void independentSessionsAndLogout() {
        String email=activatedAccount(); var first=auth.login(email,"password",false); var second=auth.login(email,"password",true);
        auth.logout(first.principal()); assertNull(auth.authenticate(first.secret())); assertNotNull(auth.authenticate(second.secret()));
    }
    @Test void idleWindowsAreSlidingAndBoundariesExpire() {
        String email=activatedAccount(); var normal=auth.login(email,"password",false); var persistent=auth.login(email,"password",true);
        clock.advance(Duration.ofHours(23)); assertNotNull(auth.authenticate(normal.secret()));
        clock.advance(Duration.ofHours(24)); assertNull(auth.authenticate(normal.secret()));
        assertNotNull(auth.authenticate(persistent.secret()));
        clock.advance(Duration.ofDays(29)); assertNotNull(auth.authenticate(persistent.secret()));
        clock.advance(Duration.ofDays(30)); assertNull(auth.authenticate(persistent.secret()));
    }
    @Test void passwordChangeKeepsOnlyCallingSessionAndRequiresCurrentPassword() {
        String email=activatedAccount(); var first=auth.login(email,"password",false); var second=auth.login(email,"password",false);
        assertThrows(AuthFailure.class, () -> auth.changePassword(first.principal(),"wrong","new-password"));
        assertNotNull(auth.authenticate(second.secret()));
        auth.changePassword(first.principal(),"password","new-password");
        assertNotNull(auth.authenticate(first.secret())); assertNull(auth.authenticate(second.secret()));
        assertThrows(AuthFailure.class, () -> auth.login(email,"password",false)); assertNotNull(auth.login(email,"new-password",false));
    }
    @Test void resetExpiresAndSuccessfulResetRevokesAllSessionsAndTokens() {
        String email=activatedAccount(); var first=auth.login(email,"password",false); var second=auth.login(email,"password",true);
        auth.requestReset(email); String expired=mail.token(email,"PASSWORD_RESET"); clock.advance(Duration.ofMinutes(30));
        assertThrows(AuthFailure.class, () -> auth.resetPassword(expired,"new-password"));
        auth.requestReset(email); String token=mail.token(email,"PASSWORD_RESET"); auth.resetPassword(token,"new-password");
        assertThrows(AuthFailure.class, () -> auth.resetPassword(token,"other-password"));
        assertNull(auth.authenticate(first.secret())); assertNull(auth.authenticate(second.secret()));
        assertNotNull(auth.login(email,"new-password",false));
    }
    @Test void concurrentTokenConsumptionSucceedsExactlyOnce() throws Exception {
        String email=activatedAccount(); auth.requestReset(email); String token=mail.token(email,"PASSWORD_RESET");
        CyclicBarrier barrier=new CyclicBarrier(2);
        Callable<Boolean> consume=() -> { barrier.await(); try { auth.resetPassword(token,"new-password"); return true; }
            catch(AuthFailure expected) { return false; } };
        try(var executor=Executors.newFixedThreadPool(2)) {
            var one=executor.submit(consume); var two=executor.submit(consume);
            assertNotEquals(one.get(),two.get());
        }
    }
    @Test void duplicateCaseInsensitiveRegistrationCannotOverwritePassword() {
        String email=activatedAccount(); auth.register(email.toUpperCase(java.util.Locale.ROOT),"attacker-password","Other");
        assertNotNull(auth.login(email,"password",false));
        assertThrows(AuthFailure.class, () -> auth.login(email,"attacker-password",false));
    }
    @Test void concurrentActivationSucceedsExactlyOnce() throws Exception {
        String email=email(); auth.register(email,"password","Person"); String token=mail.token(email,"EMAIL_VERIFICATION");
        CyclicBarrier barrier=new CyclicBarrier(2);
        Callable<Boolean> activate=() -> { barrier.await(); try { auth.verifyEmail(token); return true; }
            catch(AuthFailure expected) { return false; } };
        try(var executor=Executors.newFixedThreadPool(2)) {
            var one=executor.submit(activate); var two=executor.submit(activate);
            assertNotEquals(one.get(30, java.util.concurrent.TimeUnit.SECONDS),two.get(30, java.util.concurrent.TimeUnit.SECONDS));
        }
    }
    @Test void concurrentOldPasswordLoginCannotSurviveReset() throws Exception {
        String email=activatedAccount(); auth.requestReset(email); String token=mail.token(email,"PASSWORD_RESET");
        CyclicBarrier barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var login=executor.submit(() -> { barrier.await(); try { return auth.login(email,"password",false); }
                catch(AuthFailure expected) { return null; } });
            var reset=executor.submit(() -> { barrier.await(); auth.resetPassword(token,"new-password"); return true; });
            var old=login.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(reset.get(30, java.util.concurrent.TimeUnit.SECONDS));
            if(old!=null) assertNull(auth.authenticate(old.secret()));
            assertNotNull(auth.login(email,"new-password",false));
        }
    }
    @Test void resetResponsesDoNotRevealAccountExistenceAndBearerWorksOverHttp() throws Exception {
        String email = activatedAccount();
        String known = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/auth/password-reset-requests").header("X-Rallye-Client", "mobile")
                        .contentType("application/json").content("{\"email\":\"" + email + "\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String unknown = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/auth/password-reset-requests").header("X-Rallye-Client", "mobile")
                        .contentType("application/json").content("{\"email\":\"" + email() + "\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertEquals(known, unknown);
        var login = auth.login(email, "password", false);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + login.secret()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + login.secret()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        assertNull(auth.authenticate(login.secret()));
    }
}
