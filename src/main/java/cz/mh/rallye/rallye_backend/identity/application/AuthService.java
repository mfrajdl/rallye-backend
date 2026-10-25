package cz.mh.rallye.rallye_backend.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.List;
import java.util.UUID;
import cz.mh.rallye.rallye_backend.identity.persistence.AccountStore;
import cz.mh.rallye.rallye_backend.identity.persistence.AdminStore;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import cz.mh.rallye.rallye_backend.audit.application.AuditService.Event;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AuthService {
    public record Principal(UUID accountId, UUID sessionId, boolean rememberMe) { }
    public record UserView(UUID id, String email, String displayName,List<String> roles) {
        public UserView{roles=List.copyOf(roles);}
        public UserView(UUID id,String email,String displayName){this(id,email,displayName,currentRoles(false));}
    }
    // USER is the existing implicit ROLE_USER authority; ADMIN is read from the current database state.
    static List<String> currentRoles(boolean administrator){return administrator?List.of("USER","ADMIN"):List.of("USER");}
    public record LoginSession(String secret, Principal principal) {
        @Override public String toString() { return "LoginSession[redacted]"; }
    }
    private final AccountStore store;
    private final SecretTokens tokens;
    private final PasswordEncoder passwords;
    private final AccountMailSender mail;
    private final Clock clock;
    private final String dummyHash;
    private final AuditService audit;
    private final AdminStore roles;

    public AuthService(AccountStore store, SecretTokens tokens, PasswordEncoder passwords,
                       AccountMailSender mail, Clock clock, AuditService audit, AdminStore roles) {
        this.store = store; this.tokens = tokens; this.passwords = passwords; this.mail = mail; this.clock = clock;
        this.dummyHash = passwords.encode(tokens.create());
        this.audit = audit; this.roles = roles;
    }
    public static String normalizeEmail(String email) { return email.strip().toLowerCase(Locale.ROOT); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    public static Duration idle(boolean remember) { return remember ? Duration.ofDays(30) : Duration.ofHours(24); }

    @Transactional
    public void register(String email, String password, String name) {
        PasswordPolicy.validate(password);
        String normalized = normalizeEmail(email);
        String hash = passwords.encode(password); // Also perform work on duplicate registration.
        UUID id = UUID.randomUUID();
        Instant now = now();
        roles.lock();
        if (store.create(id, normalized, name.strip(), hash, now)) {
            issue(id, normalized, "EMAIL_VERIFICATION", Duration.ofHours(24), now);
        }
    }
    @Transactional
    public void requestVerification(String email) {
        var account = store.byEmail(normalizeEmail(email));
        if (account == null) return;
        account = store.lockAccount(account.id());
        if (account != null && "PENDING_EMAIL".equals(account.status())) {
            issue(account.id(), account.email(), "EMAIL_VERIFICATION", Duration.ofHours(24), now());
        }
    }
    @Transactional
    public void verifyEmail(String secret) {
        var action = store.action(tokens.hash(secret));
        if (action == null || !"EMAIL_VERIFICATION".equals(action.purpose())) throw AuthFailure.token();
        var account = store.lockAccount(action.accountId());
        Instant now = now();
        if (account == null || !"PENDING_EMAIL".equals(account.status())
                || !store.consume(action.id(), "EMAIL_VERIFICATION", now)) throw AuthFailure.token();
        store.activate(account.id(), now);
    }
    @Transactional
    public LoginSession login(String email, String password, boolean remember) {
        var account = store.byEmail(normalizeEmail(email));
        if (account == null) {
            passwords.matches(password, dummyHash);
            failedLogin();
            throw AuthFailure.credentials();
        }
        account = store.lockAccount(account.id());
        if(account==null) {
            passwords.matches(password,dummyHash);failedLogin();throw AuthFailure.credentials();
        }
        boolean valid = passwords.matches(password, account.passwordHash() == null ? dummyHash : account.passwordHash());
        if (!valid || account.passwordHash() == null || !"ACTIVE".equals(account.status())) {
            failedLogin(); throw AuthFailure.credentials();
        }
        String secret = tokens.create();
        Instant now = now();
        UUID session = store.insertSession(account.id(), tokens.hash(secret), remember, now, now.plus(idle(remember)));
        audit.record(account.id(), Event.LOGIN_SUCCEEDED, account.id());
        return new LoginSession(secret, new Principal(account.id(), session, remember));
    }
    @Transactional
    public Principal authenticate(String secret) {
        var session = store.session(tokens.hash(secret));
        if (session == null) return null;
        var account = store.lockAccount(session.accountId());
        Instant now = now();
        if (account == null || !"ACTIVE".equals(account.status())
                || !store.touch(session.id(), now, now.plus(idle(session.rememberMe())))) return null;
        return new Principal(account.id(), session.id(), session.rememberMe());
    }
    @Transactional
    public void logout(Principal principal) {
        store.lockAccount(principal.accountId());
        if (store.revoke(principal.sessionId(), now())) {
            audit.record(principal.accountId(), Event.LOGOUT_SUCCEEDED, principal.accountId());
        }
    }
    @Transactional
    public void requestReset(String email) {
        var account = store.byEmail(normalizeEmail(email));
        if (account == null) return;
        account = store.lockAccount(account.id());
        if (account != null && "ACTIVE".equals(account.status()) && account.passwordHash() != null) {
            issue(account.id(), account.email(), "PASSWORD_RESET", Duration.ofMinutes(30), now());
        }
    }
    @Transactional
    public void resetPassword(String secret, String password) {
        PasswordPolicy.validate(password);
        var action = store.action(tokens.hash(secret));
        if (action == null || !"PASSWORD_RESET".equals(action.purpose())) throw AuthFailure.token();
        var account = store.lockAccount(action.accountId());
        Instant now = now();
        if (account == null || !"ACTIVE".equals(account.status()) || account.passwordHash() == null
                || !store.consume(action.id(), "PASSWORD_RESET", now)) throw AuthFailure.token();
        store.password(account.id(), passwords.encode(password), now);
        store.revokeAll(account.id(), now);
        audit.record(account.id(), Event.PASSWORD_RESET_COMPLETED, account.id());
    }
    @Transactional
    public void changePassword(Principal principal, String currentPassword, String newPassword) {
        PasswordPolicy.validate(newPassword);
        var account = store.lockAccount(principal.accountId());
        Instant now = now();
        if (account == null || !"ACTIVE".equals(account.status())
                || !store.isActiveSession(principal.sessionId(), principal.accountId(), now)) {
            throw AuthFailure.credentials();
        }
        if (account.passwordHash() == null || !passwords.matches(currentPassword, account.passwordHash())) {
            throw AuthFailure.credentials();
        }
        store.password(account.id(), passwords.encode(newPassword), now);
        store.revokeOthers(account.id(), principal.sessionId(), now);
        audit.record(account.id(), Event.PASSWORD_CHANGED, account.id());
    }
    @Transactional(readOnly = true)
    public UserView me(Principal principal) {
        var account = store.account(principal.accountId());
        if (account == null || !"ACTIVE".equals(account.status())
                || !store.isActiveSession(principal.sessionId(), principal.accountId(), now())) throw AuthFailure.credentials();
        return new UserView(account.id(), account.email(), account.name(),currentRoles(roles.administrator(account.id())));
    }
    private void issue(UUID account, String email, String purpose, Duration life, Instant now) {
        String secret = tokens.create();
        store.insertAction(account, purpose, tokens.hash(secret), now, now.plus(life));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { mail.send(email, purpose, secret); }
                catch (RuntimeException failure) {
                    // No recipient, token, password or SMTP diagnostics in application logs.
                    org.slf4j.LoggerFactory.getLogger(AuthService.class)
                            .warn("Account email delivery failed; check SMTP configuration. Request can be repeated.");
                }
            }
        });
    }
    @Transactional(readOnly = true)
    public boolean isAdministrator(Principal principal) { return roles.administrator(principal.accountId()); }
    private static void failedLogin() {
        org.slf4j.LoggerFactory.getLogger(AuthService.class).info("Local login rejected");
    }
}
