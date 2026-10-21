package cz.mh.rallye.rallye_backend.identity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import cz.mh.rallye.rallye_backend.audit.application.AuditService.Event;
import cz.mh.rallye.rallye_backend.audit.persistence.AuditStore;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.AdminStore;
import cz.mh.rallye.rallye_backend.support.TestDatabaseInitializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** All fixture accounts, role guards and Flyway history live in this run's own schema. */
@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@ActiveProfiles("it")
@ContextConfiguration(initializers = TestDatabaseInitializer.class)
@Import(IdentityIT.Fixtures.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdministrationIT {
    static final String SCHEMA="it_admin_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void isolatedSchema(DynamicPropertyRegistry properties) {
        properties.add("spring.flyway.schemas",()->SCHEMA);
        properties.add("spring.flyway.default-schema",()->SCHEMA);
        properties.add("spring.datasource.hikari.connection-init-sql",()->"SET search_path TO "+SCHEMA);
    }
    @Autowired AuthService auth;
    @Autowired AdminService admin;
    @Autowired IdentityIT.TestMail mail;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @MockitoSpyBean AuditStore auditStore;
    @MockitoSpyBean AdminStore roleStore;
    UUID root;
    String rootEmail;
    AuthService.LoginSession rootLogin;
    String password="test-password";

    UUID account() {
        String email=UUID.randomUUID()+"@example.test";
        auth.register(email,password,"Test administrator");
        auth.verifyEmail(mail.token(email,"EMAIL_VERIFICATION"));
        return jdbc.queryForObject("SELECT id FROM user_account WHERE normalized_email=?",UUID.class,email);
    }
    String email(UUID id) { return jdbc.queryForObject("SELECT email FROM user_account WHERE id=?",String.class,id); }
    static String bootstrapBody(UUID id) throws java.io.IOException {
        return Files.readString(Path.of("ops/admin/bootstrap-first-admin.sql")).lines()
                .filter(line -> !line.startsWith("\\") && !line.equals("BEGIN;") && !line.equals("COMMIT;"))
                .collect(java.util.stream.Collectors.joining("\n"))
                .replace(":'account_id'", "'"+id+"'");
    }
    void bootstrap(UUID id) {
        try {
            String sql=bootstrapBody(id);
            new TransactionTemplate(transactions).executeWithoutResult(status->jdbc.execute(sql));
        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }
    @BeforeEach void prepare(TestInfo info) {
        if (info.getTestMethod().orElseThrow().getName().equals("concurrentBootstrapCreatesExactlyOneFirstAdministrator")) return;
        var administrators=jdbc.queryForList("SELECT account_id FROM user_account_role WHERE role='ADMIN'",UUID.class);
        if (administrators.isEmpty()) { root=account(); bootstrap(root); }
        else if (root==null) root=administrators.getFirst();
        // This is strictly test-owned schema fixture cleanup, never the shared application schema.
        jdbc.update("DELETE FROM user_account_role WHERE account_id<>?",root);
        rootEmail=email(root);
        rootLogin=auth.login(rootEmail,password,false);
    }
    List<Boolean> race(Callable<Boolean> first,Callable<Boolean> second) throws Exception {
        var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->{barrier.await(10,TimeUnit.SECONDS);return first.call();});
            var b=executor.submit(()->{barrier.await(10,TimeUnit.SECONDS);return second.call();});
            return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
    }
    boolean tryBootstrap(UUID id) { try {bootstrap(id);return true;} catch(DataAccessException failure){return false;} }
    boolean tryRevoke(AuthService.Principal actor,UUID target) {
        try {admin.changeRole(actor,target,false,UUID.randomUUID());return true;}catch(AuthFailure failure){return false;}
    }
    long count(String type,UUID target) { return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE event_type=? AND target_id=?",Long.class,type,target); }

    // Read committed state outside the failing service transaction, not pending changes
    // in a test-managed transaction. Never print hashes if an assertion fails.
    record Snapshot(List<java.util.Map<String,Object>> account, String passwordHash,
                    List<java.util.Map<String,Object>> sessions, List<java.util.Map<String,Object>> tokens,
                    List<java.util.Map<String,Object>> roles, List<java.util.Map<String,Object>> commands,
                    List<java.util.Map<String,Object>> events) {
        @Override public String toString() { return "Snapshot[redacted]"; }
    }
    Snapshot snapshot(UUID target) {
        var read = new TransactionTemplate(transactions);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setReadOnly(true);
        return read.execute(status -> new Snapshot(
                jdbc.queryForList("SELECT status,updated_at FROM user_account WHERE id=?",target),
                jdbc.queryForObject("SELECT password_hash FROM user_identity WHERE account_id=? AND provider='LOCAL'",String.class,target),
                jdbc.queryForList("SELECT id,remember_me,created_at,last_used_at,expires_at,revoked_at FROM user_session WHERE account_id=? ORDER BY id",target),
                jdbc.queryForList("SELECT id,purpose,created_at,expires_at,consumed_at FROM user_action_token WHERE account_id=? ORDER BY id",target),
                jdbc.queryForList("SELECT role,granted_at FROM user_account_role WHERE account_id=? ORDER BY role",target),
                jdbc.queryForList("SELECT actor_id,command_key,grant_role,created_at,expires_at FROM admin_role_command WHERE target_id=? ORDER BY actor_id,command_key",target),
                jdbc.queryForList("SELECT id,occurred_at,actor_kind,actor_id,event_type FROM audit_event WHERE target_id=? ORDER BY id",target)));
    }
    void unchanged(Snapshot before, UUID target) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Service transaction must have completed before checking rollback");
        Snapshot after = snapshot(target);
        assertAll("Committed state after simulated failure",
                () -> assertEquals(before.account(),after.account(),"Account status/timestamp changed"),
                () -> assertTrue(java.util.Objects.equals(before.passwordHash(),after.passwordHash()),"Password hash changed"),
                () -> assertEquals(before.sessions(),after.sessions(),"Sessions changed"),
                () -> assertEquals(before.tokens(),after.tokens(),"One-time token state changed"),
                () -> assertEquals(before.roles(),after.roles(),"Role changed"),
                () -> assertEquals(before.commands(),after.commands(),"Idempotency record changed"),
                () -> assertEquals(before.events(),after.events(),"Audit event persisted"));
    }
    void auditFailureRollsBack(Event event, UUID target, Runnable operation) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "No test-managed outer transaction allowed");
        Snapshot before = snapshot(target);
        var simulated = new IllegalStateException("Test audit failure");
        doAnswer(invocation -> {
            invocation.callRealMethod(); // Include a real audit INSERT in the transaction being rolled back.
            throw simulated;
        }).when(auditStore).append(any(),any(),any(),eq(event),eq(target));
        try {
            var translated = assertThrows(InvalidDataAccessApiUsageException.class,operation::run);
            assertSame(simulated,translated.getMostSpecificCause(),"Must be the injected audit failure, not an unrelated data access error");
            verify(auditStore,times(1)).append(any(),any(),any(),eq(event),eq(target));
        } finally { reset(auditStore); }
        unchanged(before,target);
    }

    @Test @Order(1) void concurrentBootstrapCreatesExactlyOneFirstAdministrator() throws Exception {
        UUID a=account(),b=account();
        jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT test_reject_bootstrap CHECK(event_type<>'ADMIN_BOOTSTRAPPED')");
        try {
            assertThrows(DataAccessException.class,()->bootstrap(a));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_account_role",Long.class));
            assertEquals(false,jdbc.queryForObject("SELECT bootstrapped FROM admin_control WHERE id=1",Boolean.class));
        } finally { jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT test_reject_bootstrap"); }
        var outcomes=race(()->tryBootstrap(a),()->tryBootstrap(b));
        assertEquals(1,outcomes.stream().filter(Boolean::booleanValue).count());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM user_account_role",Long.class));
        assertTrue(jdbc.queryForObject("SELECT bootstrapped FROM admin_control WHERE id=1",Boolean.class));
        root=jdbc.queryForObject("SELECT account_id FROM user_account_role",UUID.class);
        assertEquals(1,count("ADMIN_BOOTSTRAPPED",root));
        bootstrap(root); assertEquals(1,count("ADMIN_BOOTSTRAPPED",root));
        UUID other=root.equals(a)?b:a;
        assertThrows(DataAccessException.class,()->bootstrap(other));
    }
    @Test @Order(2) void bootstrapRejectsUnknownPendingAndUnverifiedTargets() {
        assertThrows(DataAccessException.class,()->bootstrap(UUID.randomUUID()));
        String email=UUID.randomUUID()+"@example.test"; auth.register(email,password,"Pending");
        UUID pending=jdbc.queryForObject("SELECT id FROM user_account WHERE email=?",UUID.class,email);
        assertThrows(DataAccessException.class,()->bootstrap(pending));
        UUID unverified=account(); jdbc.update("UPDATE user_account SET email_verified_at=NULL WHERE id=?",unverified);
        assertThrows(DataAccessException.class,()->bootstrap(unverified));
    }
    @Test @Order(3) void existingSessionGainsAndLosesAdminAndRequestsAreIdempotent() throws Exception {
        UUID target=account(); var login=auth.login(email(target),password,false);
        mvc.perform(get("/api/v1/admin/audit-events").header("Authorization","Bearer "+login.secret())).andExpect(status().isForbidden());
        UUID key=UUID.randomUUID();
        admin.changeRole(rootLogin.principal(),target,true,key);
        admin.changeRole(rootLogin.principal(),target,true,key);
        assertEquals(1,count("ADMIN_GRANTED",target));
        assertEquals(409,assertThrows(AuthFailure.class,()->admin.changeRole(rootLogin.principal(),target,false,key)).status());
        mvc.perform(get("/api/v1/admin/audit-events").header("Authorization","Bearer "+login.secret())).andExpect(status().isOk());
        admin.changeRole(rootLogin.principal(),target,false,UUID.randomUUID());
        assertEquals(1,count("ADMIN_REVOKED",target));
        mvc.perform(get("/api/v1/admin/audit-events").header("Authorization","Bearer "+login.secret())).andExpect(status().isForbidden());
        assertNotNull(auth.authenticate(login.secret()));
    }
    @Test @Order(2) void bootstrapRejectsInconsistentFlagWithoutChangingRolesOrAudit() {
        long events=count("ADMIN_BOOTSTRAPPED",root);
        jdbc.update("UPDATE admin_control SET bootstrapped=false WHERE id=1");
        try {
            assertThrows(DataAccessException.class,()->bootstrap(root));
            assertThrows(DataAccessException.class,()->bootstrap(account()));
            assertEquals(false,jdbc.queryForObject("SELECT bootstrapped FROM admin_control WHERE id=1",Boolean.class));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM user_account_role",Long.class));
            assertEquals(events,count("ADMIN_BOOTSTRAPPED",root));
        } finally { jdbc.update("UPDATE admin_control SET bootstrapped=true WHERE id=1"); }
    }
    @Test @Order(2) void missingControlRowPreventsBootstrapAndRoleChanges() {
        UUID target=account(),key=UUID.randomUUID();
        jdbc.update("DELETE FROM admin_control WHERE id=1");
        try {
            assertThrows(DataAccessException.class,()->bootstrap(target));
            assertThrows(DataAccessException.class,()->admin.changeRole(rootLogin.principal(),target,true,key));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_account_role WHERE account_id=?",Long.class,target));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM admin_role_command WHERE command_key=?",Long.class,key));
            assertEquals(0,count("ADMIN_GRANTED",target));
        } finally { jdbc.update("INSERT INTO admin_control(id,bootstrapped) VALUES (1,true)"); }
    }
    @Test @Order(4) void lastAdminCannotBeDeletedDeactivatedUnverifiedOrHaveRoleRemoved() {
        assertThrows(DataAccessException.class,()->jdbc.update("DELETE FROM user_account_role WHERE account_id=?",root));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?",root));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE user_account SET email_verified_at=NULL WHERE id=?",root));
        assertThrows(DataAccessException.class,()->jdbc.update("DELETE FROM user_account WHERE id=?",root));
        assertTrue(auth.isAdministrator(rootLogin.principal()));
        assertThrows(AuthFailure.class,()->admin.changeRole(rootLogin.principal(),root,false,UUID.randomUUID()));
    }
    @Test @Order(5) void auditFailureRollsBackRoleAndIdempotencyRecord() {
        UUID target=account(),key=UUID.randomUUID();
        auditFailureRollsBack(Event.ADMIN_GRANTED,target,()->admin.changeRole(rootLogin.principal(),target,true,key));
        // The failed command must not reserve its key or leave its role/audit behind.
        admin.changeRole(rootLogin.principal(),target,true,key);
        admin.changeRole(rootLogin.principal(),target,true,key);
        assertEquals(1,count("ADMIN_GRANTED",target));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM user_account_role WHERE account_id=?",Long.class,target));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM admin_role_command WHERE actor_id=? AND command_key=?",Long.class,root,key));
    }
    @Test @Order(5) void idempotencyWriteFailureRollsBackRoleAndAlreadyInsertedAudit() {
        UUID target=account(),key=UUID.randomUUID();
        Snapshot before=snapshot(target);
        var simulated=new DataIntegrityViolationException("Test idempotency persistence failure");
        doAnswer(invocation -> {
            invocation.callRealMethod(); // Role, audit and command have all been written before this failure.
            throw simulated;
        }).when(roleStore).saveCommand(eq(root),eq(key),eq(target),eq(true),any());
        try {
            assertSame(simulated,assertThrows(DataIntegrityViolationException.class,
                    ()->admin.changeRole(rootLogin.principal(),target,true,key)));
            verify(roleStore).saveCommand(eq(root),eq(key),eq(target),eq(true),any());
        } finally { reset(roleStore); }
        unchanged(before,target);
        admin.changeRole(rootLogin.principal(),target,true,key);
        assertEquals(1,count("ADMIN_GRANTED",target));
    }
    @Test @Order(6) void securityAuditContainsOnlySafeIdentifiersAndPasswordsStillWork() {
        UUID target=account(); String email=email(target);
        var login=auth.login(email,password,false); auth.logout(login.principal());
        assertEquals(1,count("LOGIN_SUCCEEDED",target)); assertEquals(1,count("LOGOUT_SUCCEEDED",target));
        var current=auth.login(email,password,false);
        auth.changePassword(current.principal(),password,"changed-password");
        assertEquals(1,count("PASSWORD_CHANGED",target));
        auth.requestReset(email); auth.resetPassword(mail.token(email,"PASSWORD_RESET"),"reset-password");
        assertEquals(1,count("PASSWORD_RESET_COMPLETED",target)); assertNull(auth.authenticate(current.secret()));
        assertNotNull(auth.login(email,"reset-password",false));
        String json=admin.audit(rootLogin.principal(),null,target,null,null,null,0,100).toString();
        assertFalse(json.contains(email)); assertFalse(json.contains("reset-password"));
        assertFalse(json.contains(login.secret())); assertFalse(json.contains("password_hash"));
    }
    @Test @Order(7) void loginLogoutChangeAndResetRollbackIfAuditCannotBeStored() {
        UUID target=account(); String email=email(target);
        auditFailureRollsBack(Event.LOGIN_SUCCEEDED,target,()->auth.login(email,password,false));
        var login=auth.login(email,password,false);
        var other=auth.login(email,password,false);
        auth.requestReset(email); String token=mail.token(email,"PASSWORD_RESET");
        auditFailureRollsBack(Event.LOGOUT_SUCCEEDED,target,()->auth.logout(login.principal()));
        auditFailureRollsBack(Event.PASSWORD_CHANGED,target,()->auth.changePassword(login.principal(),password,"different-password"));
        auditFailureRollsBack(Event.PASSWORD_RESET_COMPLETED,target,()->auth.resetPassword(token,"different-password"));
        assertNotNull(auth.authenticate(login.secret())); assertNotNull(auth.authenticate(other.secret()));
        assertNotNull(auth.login(email,password,false));
        auth.resetPassword(token,"different-password"); // Failed audit did not consume the reset token.
        assertEquals(1,count("PASSWORD_RESET_COMPLETED",target));
        assertNull(auth.authenticate(login.secret())); assertNull(auth.authenticate(other.secret()));
        assertNotNull(auth.login(email,"different-password",false));
    }
    @Test @Order(8) void filtersPaginationAndExpiryAreEnforced() {
        UUID target=account(),key=UUID.randomUUID();
        admin.changeRole(rootLogin.principal(),target,true,key);
        var page=admin.audit(rootLogin.principal(),root,target,Event.ADMIN_GRANTED,null,null,0,1);
        assertEquals(1,page.items().size()); assertEquals(target,page.items().getFirst().targetId());
        clock.advance(Duration.ofHours(24)); // renew the actor session after idle expiry
        rootLogin=auth.login(rootEmail,password,false);
        admin.changeRole(rootLogin.principal(),target,false,key);
        assertEquals(1,count("ADMIN_REVOKED",target));
    }
    @Test @Order(9) void concurrentSameKeyCreatesOneRoleAndOneAudit() throws Exception {
        UUID target=account(),key=UUID.randomUUID();
        var outcomes=race(()->{admin.changeRole(rootLogin.principal(),target,true,key);return true;},
                ()->{admin.changeRole(rootLogin.principal(),target,true,key);return true;});
        assertTrue(outcomes.stream().allMatch(Boolean::booleanValue)); assertEquals(1,count("ADMIN_GRANTED",target));
    }
    @Test @Order(10) void mutuallyConcurrentRevocationsCannotRemoveAllAdministrators() throws Exception {
        UUID other=account(); admin.changeRole(rootLogin.principal(),other,true,UUID.randomUUID());
        var otherLogin=auth.login(email(other),password,false);
        var outcomes=race(()->tryRevoke(rootLogin.principal(),other),()->tryRevoke(otherLogin.principal(),root));
        assertEquals(1,outcomes.stream().filter(Boolean::booleanValue).count());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM user_account_role",Long.class));
    }
    @AfterAll void removeOnlyThisRunsPrivateSchema() {
        assertTrue(SCHEMA.matches("it_admin_[0-9a-f]{32}"));
        assertEquals("rallye_test",jdbc.queryForObject("SELECT current_database()",String.class));
        assertEquals("rallye_test",jdbc.queryForObject("SELECT current_user",String.class));
        jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");
    }
}
