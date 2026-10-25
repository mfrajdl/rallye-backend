package cz.mh.rallye.rallye_backend.identity;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import cz.mh.rallye.rallye_backend.support.TestDatabaseInitializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@ActiveProfiles("it")
@ContextConfiguration(initializers=TestDatabaseInitializer.class)
@Import({IdentityIT.Fixtures.class,AccountCancellationServiceIT.Fixtures.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MobileExchangeIT {
    static final String SCHEMA="it_mobile_exchange_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        MobileLoginIT.properties(p);
        p.add("rallye.auth.apple-vault.active-key-id",()->"test");p.add("rallye.auth.apple-vault.keys.test",()->Base64.getEncoder().encodeToString(new byte[32]));
        p.add("spring.flyway.schemas",()->SCHEMA);p.add("spring.flyway.default-schema",()->SCHEMA);
        p.add("spring.datasource.hikari.connection-init-sql",()->"SET search_path TO "+SCHEMA);
    }
    @Autowired MobileExchangeService exchange;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired MobileLoginService mobile;
    @Autowired ExternalFlowService flows;
    @Autowired ExternalLoginService login;
    @Autowired AuthService auth;
    @Autowired AccountCancellationService cancellation;
    @Autowired AccountCancellationServiceIT.ControlledFacts participation;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager manager;
    @Autowired PreRaceAccountParticipationFacts facts;
    @MockitoBean ExternalOidcVerifier verifier;
    @Autowired MobileGrantStore grantStore;
    @MockitoSpyBean AccountStore accounts;
    @MockitoSpyBean cz.mh.rallye.rallye_backend.audit.application.AuditService audit;
    final SecretTokens secrets=new SecretTokens();
    final Map<String,ExternalOidcVerifier.Identity> proofs=new ConcurrentHashMap<>();
    record Account(ExternalProviders.Provider provider,String subject,String email,UUID id) {}
    record Run(ExternalProviders.Provider provider,String state,String correlation,UUID requestId,String code) {}
    String proof(ExternalProviders.Provider provider,String subject,String email) {
        String code=UUID.randomUUID().toString();
        proofs.put(code,new ExternalOidcVerifier.Identity(ExternalProviders.issuer(provider),subject,email,true,"Test user","synthetic-refresh-"+code));return code;
    }
    String state(String url){return org.springframework.web.util.UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("state");}
    Account account(ExternalProviders.Provider provider) {
        String subject=UUID.randomUUID().toString(),email=UUID.randomUUID()+"@example.test";
        var flow=flows.start(provider,ExternalFlowService.Purpose.LOGIN,null,"web",false,null);
        var out=login.callback(provider,state(flow.authorizationUrl()),flow.correlation(),proof(provider,subject,email),"Test user");
        login.complete(out.registrationSecret(),email,"Test user");
        return new Account(provider,subject,email,jdbc.queryForObject("SELECT account_id FROM user_identity WHERE provider=? AND subject=?",UUID.class,provider.name(),subject));
    }
    AuthService.LoginSession session(Account a) {
        var flow=flows.start(a.provider(),ExternalFlowService.Purpose.LOGIN,null,"web",false,null);
        return login.callback(a.provider(),state(flow.authorizationUrl()),flow.correlation(),proof(a.provider(),a.subject(),a.email()),null).session();
    }
    Run run(ExternalProviders.Provider provider,String subject,String email) {
        var start=mobile.start(provider,ExternalFlowService.Purpose.LOGIN,"android",true,secrets.create());
        String ticket=java.net.URI.create(start.browserStartUrl()).getQuery().substring("ticket=".length());
        var launch=mobile.launch(ticket);return new Run(provider,state(launch.flow().authorizationUrl()),launch.flow().correlation(),start.requestId(),proof(provider,subject,email));
    }
    Run run(Account a){return run(a.provider(),a.subject(),a.email());}
    ExternalLoginService.Outcome finish(Run r){return login.callback(r.provider(),r.state(),r.correlation(),r.code(),"Test user");}
    int grants(UUID attempt){return jdbc.queryForObject("SELECT count(*) FROM external_auth_grant WHERE attempt_id=?",Integer.class,attempt);}
    int sessions(UUID account){return jdbc.queryForObject("SELECT count(*) FROM user_session WHERE account_id=?",Integer.class,account);}
    void guard(){assertEquals("rallye_test",jdbc.queryForObject("SELECT current_database()",String.class));assertEquals("rallye_test",jdbc.queryForObject("SELECT current_user",String.class));assertEquals(SCHEMA,jdbc.queryForObject("SELECT current_schema()",String.class));}
    @BeforeEach void setup(){guard();doAnswer(call->proofs.get(call.getArgument(1,String.class))).when(verifier).exchange(any(),any(),any(),any(),any());}
    record Handoff(Account account,Run run,String code,String verifier) {}
    Handoff handoff(ExternalProviders.Provider provider,boolean remember) {
        return handoff(account(provider),remember,false);
    }
    Handoff handoff(Account a,boolean remember,boolean nearDeadline) {
        String pkce=secrets.create();var start=mobile.start(a.provider(),ExternalFlowService.Purpose.LOGIN,"android",remember,MobileExchangeService.challenge(pkce));
        String ticket=java.net.URI.create(start.browserStartUrl()).getQuery().substring("ticket=".length());var launch=mobile.launch(ticket);
        var r=new Run(a.provider(),state(launch.flow().authorizationUrl()),launch.flow().correlation(),start.requestId(),proof(a.provider(),a.subject(),a.email()));
        if(nearDeadline)clock.advance(Duration.ofSeconds(599));
        return new Handoff(a,r,finish(r).handoffCode(),pkce);
    }
    AuthService.LoginSession exchange(Handoff h){return exchange.exchange(h.code(),h.run().requestId(),h.verifier());}
    int logins(UUID account){return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE actor_id=? AND event_type='LOGIN_SUCCEEDED'",Integer.class,account);}
    void unconsumed(Handoff h){assertNull(jdbc.queryForObject("SELECT consumed_at FROM external_auth_grant WHERE attempt_id=?",java.sql.Timestamp.class,h.run().requestId()));}
    void invalid(Runnable call){assertEquals("EXTERNAL_HANDOFF_INVALID",assertThrows(AuthFailure.class,call::run).code());}
    @Test void googleAndAppleExchangeCreateExistingHashedSessionsAndAuditWithBothIdleLimits() {
        for(var provider:ExternalProviders.Provider.values())for(boolean remember:List.of(false,true)) {
            var h=handoff(provider,remember);assertEquals(0,sessions(h.account().id()));assertEquals(0,logins(h.account().id()));
            var result=exchange(h);assertEquals(h.account().id(),result.principal().accountId());assertEquals(remember,result.principal().rememberMe());
            var row=jdbc.queryForMap("SELECT * FROM user_session WHERE id=?",result.principal().sessionId());
            assertEquals(secrets.hash(result.secret()),row.get("token_hash"));assertFalse(row.containsValue(result.secret()));
            assertEquals(((java.sql.Timestamp)row.get("created_at")).toInstant().plus(AuthService.idle(remember)),((java.sql.Timestamp)row.get("expires_at")).toInstant());
            assertEquals(1,sessions(h.account().id()));assertEquals(1,logins(h.account().id()));
            assertNotNull(jdbc.queryForObject("SELECT consumed_at FROM external_auth_grant WHERE attempt_id=?",java.sql.Timestamp.class,h.run().requestId()));
            assertEquals(result.principal(),auth.authenticate(result.secret()));
        }
    }
    @Test void realHttpExchangeBearerMeAndLogoutUseExistingAuthentication() throws Exception {
        var h=handoff(ExternalProviders.Provider.GOOGLE,false);
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/external/mobile/exchanges")
            .header("X-Rallye-Client","mobile").contentType("application/json").content("{\"code\":\""+h.code()+"\",\"requestId\":\""+h.run().requestId()+"\",\"codeVerifier\":\""+h.verifier()+"\"}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().doesNotExist("Set-Cookie"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store")).andReturn();
        String token=tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.getResponse().getContentAsString()).get("token").asString();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me").header("Authorization","Bearer "+token))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.id").value(h.account().id().toString()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/logout").header("Authorization","Bearer "+token))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me").header("Authorization","Bearer "+token))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        assertNull(auth.authenticate(token));assertEquals(1,logins(h.account().id()));
    }
    @Test void replayDoesNotIssueAnotherSessionOrAudit(){var h=handoff(ExternalProviders.Provider.GOOGLE,true);exchange(h);invalid(()->exchange(h));assertEquals(1,sessions(h.account().id()));assertEquals(1,logins(h.account().id()));}
    @Test void wrongCodeRequestAndPkceDoNotConsumeGrantAndLegitimateRetryWorks(){
        var h=handoff(ExternalProviders.Provider.GOOGLE,false);
        invalid(()->exchange.exchange(secrets.create(),h.run().requestId(),h.verifier()));
        invalid(()->exchange.exchange(h.code(),UUID.randomUUID(),h.verifier()));
        invalid(()->exchange.exchange(h.code(),h.run().requestId(),secrets.create()));
        unconsumed(h);assertEquals(0,sessions(h.account().id()));assertEquals(0,logins(h.account().id()));assertNotNull(exchange(h));
    }
    @Test void verifierOfAnotherAttemptCannotExchangeGrant(){
        var h=handoff(ExternalProviders.Provider.GOOGLE,false);var other=handoff(h.account(),true,false);
        invalid(()->exchange.exchange(h.code(),h.run().requestId(),other.verifier()));
        invalid(()->exchange.exchange(h.code(),other.run().requestId(),h.verifier()));
        unconsumed(h);unconsumed(other);exchange(h);exchange(other);assertEquals(2,sessions(h.account().id()));
    }
    @Test void exact60SecondBoundaryIsExpiredWithoutSessionOrAudit(){
        var h=handoff(ExternalProviders.Provider.GOOGLE,false);clock.advance(Duration.ofSeconds(60));invalid(()->exchange(h));unconsumed(h);assertEquals(0,sessions(h.account().id()));assertEquals(0,logins(h.account().id()));
    }
    @Test void issuedGrantRemainsValidAfterAuthorizationDeadline(){
        var h=handoff(account(ExternalProviders.Provider.GOOGLE),false,true);clock.advance(Duration.ofSeconds(2));assertNotNull(exchange(h));
    }
    @Test void disabledAndPendingAccountsCannotExchange(){
        for(String status:List.of("DISABLED","PENDING_EMAIL")) {
            var h=handoff(ExternalProviders.Provider.GOOGLE,false);jdbc.update("UPDATE user_account SET status=? WHERE id=?",status,h.account().id());
            invalid(()->exchange(h));unconsumed(h);assertEquals(0,sessions(h.account().id()));assertEquals(0,logins(h.account().id()));
        }
    }
    @Test void mutatedAttemptAndUncompletedFlowAreRejected(){
        for(String mutation:List.of("challenge","flow","provider")) {
            var h=handoff(ExternalProviders.Provider.GOOGLE,false);
            if(mutation.equals("challenge"))jdbc.update("UPDATE mobile_login_attempt SET pkce_challenge=? WHERE id=?",secrets.create(),h.run().requestId());
            else if(mutation.equals("provider"))jdbc.update("UPDATE mobile_login_attempt SET provider='APPLE' WHERE id=?",h.run().requestId());
            else jdbc.update("UPDATE external_auth_flow SET consumed_at=NULL WHERE state_hash=?",secrets.hash(h.run().state()));
            invalid(()->exchange(h));unconsumed(h);assertEquals(0,sessions(h.account().id()));
        }
    }
    @Test void concurrentExchangeHasExactlyOneWinnerAndOneAudit() throws Exception {
        var h=handoff(ExternalProviders.Provider.GOOGLE,true);var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> call=()->{barrier.await(5,TimeUnit.SECONDS);try{exchange(h);return true;}catch(AuthFailure failure){assertEquals("EXTERNAL_HANDOFF_INVALID",failure.code());return false;}};
            var a=pool.submit(call);var b=pool.submit(call);assertNotEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
        assertEquals(1,sessions(h.account().id()));assertEquals(1,logins(h.account().id()));
    }
    @Test void cancellationAndAnonymizationDeleteGrantAndNeverAllowLaterExchange(){
        for(var provider:ExternalProviders.Provider.values())for(boolean historical:List.of(false,true)) {
            var h=handoff(provider,true);var existing=session(h.account());if(historical)participation.overrides.put(h.account().id(),new AccountParticipationFacts.Facts(true,false));
            cancellation.self(existing.principal(),null,true,UUID.randomUUID());invalid(()->exchange(h));
            assertEquals(0,grants(h.run().requestId()));assertEquals(0,sessions(h.account().id()));assertNull(auth.authenticate(existing.secret()));
            if(historical)assertEquals("ANONYMIZED",jdbc.queryForObject("SELECT status FROM user_account WHERE id=?",String.class,h.account().id()));
            if(provider==ExternalProviders.Provider.APPLE)assertTrue(jdbc.queryForObject("SELECT count(*) FROM apple_revocation_credential WHERE account_id=?",Integer.class,h.account().id())>0);
        }
    }
    @Test void exchangeBeforeCancellationProducesOnlySubsequentlyRevokedSessions(){
        var h=handoff(ExternalProviders.Provider.GOOGLE,false);var existing=session(h.account());var issued=exchange(h);
        cancellation.self(existing.principal(),null,true,UUID.randomUUID());assertNull(auth.authenticate(issued.secret()));assertEquals(0,sessions(h.account().id()));invalid(()->exchange(h));
    }
    @Test void concurrentExchangeAndCancellationCannotLeaveUsableSession() throws Exception {
        var h=handoff(ExternalProviders.Provider.GOOGLE,false);var existing=session(h.account());var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var issued=pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);try{return exchange(h);}catch(AuthFailure failure){assertEquals("EXTERNAL_HANDOFF_INVALID",failure.code());return null;}});
            var cancelled=pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);cancellation.self(existing.principal(),null,true,UUID.randomUUID());return true;});
            var result=issued.get(20,TimeUnit.SECONDS);assertTrue(cancelled.get(20,TimeUnit.SECONDS));if(result!=null)assertNull(auth.authenticate(result.secret()));
        }
        assertEquals(0,sessions(h.account().id()));assertEquals(0,grants(h.run().requestId()));assertNull(auth.authenticate(existing.secret()));
    }
    @Test void sessionInsertFailureRollsBackConsumedGrantAndInsertedSessionAndCanRetry(){
        var h=handoff(ExternalProviders.Provider.GOOGLE,false);AccountStore target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(accounts);
        var marker=new IllegalStateException("injected session failure");doAnswer(call->{call.callRealMethod();throw marker;}).when(target).insertSession(eq(h.account().id()),any(),anyBoolean(),any(),any());
        try {Throwable failure=assertThrows(RuntimeException.class,()->exchange(h));while(failure.getCause()!=null)failure=failure.getCause();assertSame(marker,failure);}
        finally {doCallRealMethod().when(target).insertSession(eq(h.account().id()),any(),anyBoolean(),any(),any());}
        unconsumed(h);assertEquals(0,sessions(h.account().id()));assertEquals(0,logins(h.account().id()));assertNotNull(exchange(h));
    }
    @Test void auditFailureRollsBackGrantSessionAndAuditAndCanRetry(){
        var h=handoff(ExternalProviders.Provider.GOOGLE,false);
        cz.mh.rallye.rallye_backend.audit.application.AuditService target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit);
        var marker=new IllegalStateException("injected audit failure");
        doAnswer(call->{call.callRealMethod();throw marker;}).when(target).record(eq(h.account().id()),eq(cz.mh.rallye.rallye_backend.audit.application.AuditService.Event.LOGIN_SUCCEEDED),eq(h.account().id()));
        try {Throwable failure=assertThrows(RuntimeException.class,()->exchange(h));while(failure.getCause()!=null)failure=failure.getCause();assertSame(marker,failure);}
        finally {doCallRealMethod().when(target).record(any(),any(),any());}
        unconsumed(h);assertEquals(0,sessions(h.account().id()));assertEquals(0,logins(h.account().id()));assertNotNull(exchange(h));assertEquals(1,logins(h.account().id()));
    }
    @AfterAll void cleanup(){guard();assertTrue(SCHEMA.matches("it_mobile_exchange_[0-9a-f]{32}"));jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");}
}
