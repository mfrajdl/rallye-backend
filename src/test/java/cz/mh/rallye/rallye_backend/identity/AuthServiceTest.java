package cz.mh.rallye.rallye_backend.identity;

import java.time.Duration;
import java.util.UUID;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.AccountStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    AccountStore store;
    PasswordEncoder passwords;
    AccountMailSender mail;
    MutableClock clock;
    AuthService service;
    UUID account = UUID.randomUUID();
    UUID session = UUID.randomUUID();
    @BeforeEach void setup() {
        store = mock(AccountStore.class); passwords = mock(PasswordEncoder.class); mail = mock(AccountMailSender.class);
        when(passwords.encode(anyString())).thenReturn("argon-hash");
        clock = new MutableClock();
        service = new AuthService(store, new SecretTokens(), passwords, mail, clock);
    }
    AccountStore.Account active() { return new AccountStore.Account(account, "crew@example.test", "Name", "ACTIVE", "argon-hash"); }
    @Test void registerStoresHashAndSendsOnlyAfterCommit() {
        when(store.create(any(), anyString(), anyString(), anyString(), any())).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.register(" Crew@Example.test ", "password", " Name ");
            verify(store).create(any(), eq("crew@example.test"), eq("Name"), eq("argon-hash"), eq(clock.instant()));
            verify(store).insertAction(any(), eq("EMAIL_VERIFICATION"), matches("[0-9a-f]{64}"),
                    eq(clock.instant()), eq(clock.instant().plus(Duration.ofHours(24))));
            verifyNoInteractions(mail);
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
            verify(mail).send(eq("crew@example.test"), eq("EMAIL_VERIFICATION"), matches("[A-Za-z0-9_-]{43}"));
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
    @Test void duplicateRegistrationDoesNotChangeExistingCredentials() {
        service.register("crew@example.test", "password", "Name");
        verify(store, never()).insertAction(any(), any(), any(), any(), any());
        verify(store, never()).password(any(), any(), any());
        verifyNoInteractions(mail);
    }
    @Test void invalidVerificationDoesNotActivate() {
        assertEquals("INVALID_OR_EXPIRED_TOKEN", assertThrows(AuthFailure.class, () -> service.verifyEmail("missing")).code());
        verify(store, never()).activate(any(), any());
    }
    @Test void expiredOrConsumedVerificationDoesNotActivate() {
        UUID id = UUID.randomUUID();
        when(store.action(anyString())).thenReturn(new AccountStore.Action(id, account, "EMAIL_VERIFICATION", clock.instant(), null));
        when(store.lockAccount(account)).thenReturn(new AccountStore.Account(account, "crew@example.test", "N", "PENDING_EMAIL", "hash"));
        when(store.consume(any(), anyString(), any())).thenReturn(false);
        assertThrows(AuthFailure.class, () -> service.verifyEmail("expired"));
        verify(store, never()).activate(any(), any());
    }
    @Test void validVerificationActivatesAfterConditionalConsumption() {
        UUID id = UUID.randomUUID();
        when(store.action(anyString())).thenReturn(new AccountStore.Action(id, account, "EMAIL_VERIFICATION", clock.instant().plusSeconds(5), null));
        when(store.lockAccount(account)).thenReturn(new AccountStore.Account(account, "crew@example.test", "N", "PENDING_EMAIL", "hash"));
        when(store.consume(id, "EMAIL_VERIFICATION", clock.instant())).thenReturn(true);
        service.verifyEmail("valid");
        var order = inOrder(store);
        order.verify(store).lockAccount(account);
        order.verify(store).consume(id, "EMAIL_VERIFICATION", clock.instant());
        order.verify(store).activate(account, clock.instant());
    }
    @Test void missingAndWrongPasswordUseSameErrorAndPasswordWork() {
        var missing = assertThrows(AuthFailure.class, () -> service.login("missing@example.test", "bad", false));
        verify(passwords).matches("bad", "argon-hash");
        when(store.byEmail(anyString())).thenReturn(active()); when(store.lockAccount(account)).thenReturn(active());
        var wrong = assertThrows(AuthFailure.class, () -> service.login("crew@example.test", "bad", false));
        assertEquals(missing.code(), wrong.code()); assertEquals(missing.status(), wrong.status());
        verify(store, never()).insertSession(any(), any(), anyBoolean(), any(), any());
    }
    @Test void unverifiedAccountCannotLoginEvenWithCorrectPassword() {
        var pending = new AccountStore.Account(account, "crew@example.test", "N", "PENDING_EMAIL", "argon-hash");
        when(store.byEmail(anyString())).thenReturn(pending); when(store.lockAccount(account)).thenReturn(pending);
        when(passwords.matches(any(), any())).thenReturn(true);
        assertThrows(AuthFailure.class, () -> service.login("crew@example.test", "password", false));
    }
    @Test void loginStoresOnlyTokenHashAndCorrectIdleTimeout() {
        when(store.byEmail(anyString())).thenReturn(active()); when(store.lockAccount(account)).thenReturn(active());
        when(passwords.matches(any(), any())).thenReturn(true);
        when(store.insertSession(any(), anyString(), anyBoolean(), any(), any())).thenReturn(session);
        var login = service.login("crew@example.test", "password", true);
        verify(store).insertSession(eq(account), eq(new SecretTokens().hash(login.secret())), eq(true),
                eq(clock.instant()), eq(clock.instant().plus(Duration.ofDays(30))));
        assertEquals(Duration.ofHours(24), AuthService.idle(false));
        assertEquals("LoginSession[redacted]", login.toString());
    }
    @Test void expiredOrRevokedSessionIsNotAuthenticated() {
        when(store.session(any())).thenReturn(new AccountStore.Session(session, account, false, clock.instant()));
        when(store.lockAccount(account)).thenReturn(active());
        assertNull(service.authenticate("old"));
    }
    @Test void logoutRevokesOnlyCurrentSession() {
        service.logout(new AuthService.Principal(account, session, false));
        verify(store).revoke(session, clock.instant()); verify(store, never()).revokeAll(any(), any());
    }
    @Test void resetConsumesOnceChangesHashAndRevokesAll() {
        UUID id = UUID.randomUUID();
        when(store.action(any())).thenReturn(new AccountStore.Action(id, account, "PASSWORD_RESET", clock.instant().plusSeconds(10), null));
        when(store.lockAccount(account)).thenReturn(active());
        when(store.consume(id, "PASSWORD_RESET", clock.instant())).thenReturn(true);
        service.resetPassword("secret", "new-password");
        verify(store).password(account, "argon-hash", clock.instant());
        verify(store).revokeAll(account, clock.instant());
    }
    @Test void changeRequiresCurrentPasswordAndKeepsCurrentSession() {
        when(store.lockAccount(account)).thenReturn(active());
        when(store.isActiveSession(session, account, clock.instant())).thenReturn(true);
        var principal = new AuthService.Principal(account, session, false);
        assertThrows(AuthFailure.class, () -> service.changePassword(principal, "bad", "new-password"));
        verify(store, never()).password(any(), any(), any());
        when(passwords.matches("correct", "argon-hash")).thenReturn(true);
        service.changePassword(principal, "correct", "new-password");
        verify(store).revokeOthers(account, session, clock.instant());
        verify(store, never()).revokeAll(any(), any());
    }
    @Test void revokedCurrentSessionCannotChangePassword() {
        when(store.lockAccount(account)).thenReturn(active());
        assertThrows(AuthFailure.class, () -> service.changePassword(new AuthService.Principal(account, session, false), "correct", "new-password"));
        verify(passwords, never()).matches(any(), any());
    }
    @Test void unknownResetRequestHasNoSideEffects() {
        service.requestReset("missing@example.test"); verifyNoInteractions(mail);
        verify(store, never()).insertAction(any(), any(), any(), any(), any());
    }
}
