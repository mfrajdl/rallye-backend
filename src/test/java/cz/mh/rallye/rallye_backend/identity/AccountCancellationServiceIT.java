package cz.mh.rallye.rallye_backend.identity;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import cz.mh.rallye.rallye_backend.crews.application.*;
import cz.mh.rallye.rallye_backend.crews.PhotoFilesTest;
import cz.mh.rallye.rallye_backend.shared.persistence.CommandStore;
import cz.mh.rallye.rallye_backend.audit.persistence.AuditStore;
import cz.mh.rallye.rallye_backend.audit.application.AuditService.Event;
import cz.mh.rallye.rallye_backend.support.TestDatabaseInitializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AccountCancellationServiceIT {
    static final String SCHEMA="it_cancel_service_"+UUID.randomUUID().toString().replace("-","");
    static final Path PHOTOS=temporaryDirectory();
    static Path temporaryDirectory() {
        try {return Files.createTempDirectory("it_cancel_photos_").toAbsolutePath();}
        catch(IOException failure) {throw new UncheckedIOException(failure);}
    }
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry properties) {
        properties.add("spring.flyway.schemas",()->SCHEMA);properties.add("spring.flyway.default-schema",()->SCHEMA);
        properties.add("spring.datasource.hikari.connection-init-sql",()->"SET search_path TO "+SCHEMA);
        properties.add("rallye.crews.photo-directory",()->PHOTOS.toString());
    }
    static class ControlledFacts implements AccountParticipationFacts {
        final AccountParticipationFacts actual;
        final Map<UUID,Facts> overrides=new ConcurrentHashMap<>();
        ControlledFacts(AccountParticipationFacts actual) {this.actual=actual;}
        public Facts forAccount(UUID account) {return overrides.containsKey(account)?overrides.get(account):actual.forAccount(account);}
    }
    @TestConfiguration static class Fixtures {
        @Bean @Primary ControlledFacts controlledFacts(@Qualifier("preRaceAccountParticipationFacts") AccountParticipationFacts actual) {return new ControlledFacts(actual);}
    }
    @Autowired AccountCancellationService service;
    @Autowired AuthService auth;
    @Autowired AdminService admins;
    @Autowired CrewService crews;
    @Autowired CrewTransferService transfers;
    @Autowired IdentityIT.TestMail mail;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ControlledFacts facts;
    @Autowired CommandStore commands;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @MockitoSpyBean AccountStore accounts;
    @MockitoSpyBean AuditStore audit;
    record User(UUID id,String email,AuthService.LoginSession session) {
        AuthService.Principal principal() {return session.principal();}
        @Override public String toString() {return "TestUser[redacted]";}
    }
    User root,owner;
    User account() {
        String email=UUID.randomUUID()+"@example.test";auth.register(email,"test-password","Captain");
        auth.verifyEmail(mail.token(email,"EMAIL_VERIFICATION"));var login=auth.login(email,"test-password",true);
        return new User(login.principal().accountId(),email,login);
    }
    <T> T tx(Supplier<T> work) {return new TransactionTemplate(manager).execute(status->work.get());}
    @BeforeAll void bootstrap() throws Exception {
        root=account();String sql=AdministrationIT.bootstrapBody(root.id());tx(()->{jdbc.execute(sql);return null;});
    }
    @BeforeEach void fixtures() {owner=account();}
    @AfterEach void clearFacts() {facts.overrides.clear();}
    String error(Runnable work,int status) {var error=assertThrows(AuthFailure.class,work::run);assertEquals(status,error.status());return error.code();}
    int count(String table,UUID id) {return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE account_id=?",Integer.class,id);}
    boolean exists(UUID id) {return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM user_account WHERE id=?)",Boolean.class,id);}
    int cancellations(UUID target) {return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE target_id=? AND event_type IN ('ACCOUNT_DELETED','ACCOUNT_ANONYMIZED')",Integer.class,target);}
    CrewService.View crew(User user) {return crews.create(user.principal(),UUID.randomUUID(),"Crew",null,null,false,null);}
    void failure(Runnable work,RuntimeException marker) {
        Throwable thrown=assertThrows(RuntimeException.class,work::run);
        while(thrown.getCause()!=null)thrown=thrown.getCause();assertSame(marker,thrown);
    }
    @Test void selfDeletesAllCredentialsAndAllowsFreshRegistration() {
        var second=auth.login(owner.email(),"test-password",false);
        auth.requestReset(owner.email());String reset=mail.token(owner.email(),"PASSWORD_RESET");
        service.self(owner.principal(),"test-password",UUID.randomUUID());
        assertFalse(exists(owner.id()));assertNull(auth.authenticate(owner.session().secret()));assertNull(auth.authenticate(second.secret()));
        for(String table:List.of("user_identity","user_session","user_action_token","user_account_role"))assertEquals(0,count(table,owner.id()));
        error(()->auth.resetPassword(reset,"new-password"),400);
        error(()->service.self(owner.principal(),"test-password",UUID.randomUUID()),401);
        auth.register(owner.email(),"new-password","New person");auth.verifyEmail(mail.token(owner.email(),"EMAIL_VERIFICATION"));
        var fresh=auth.login(owner.email(),"new-password",false);
        assertNotEquals(owner.id(),fresh.principal().accountId());assertEquals(0,count("user_account_role",fresh.principal().accountId()));
        assertEquals(1,cancellations(owner.id()));
    }
    @Test void wrongPasswordLeavesSessionsAndAccountUntouched() {
        assertEquals("INVALID_CREDENTIALS",error(()->service.self(owner.principal(),"wrong",UUID.randomUUID()),401));
        assertTrue(exists(owner.id()));assertNotNull(auth.authenticate(owner.session().secret()));assertEquals(0,cancellations(owner.id()));
    }
    @Test void adminCanDeletePendingAndDisabledButCannotUseEndpointOnSelf() {
        assertEquals("SELF_ACCOUNT_CANCELLATION_FORBIDDEN",error(()->service.administrator(root.principal(),root.id(),UUID.randomUUID()),403));
        String email=UUID.randomUUID()+"@example.test";auth.register(email,"test-password","Pending");
        UUID pending=jdbc.queryForObject("SELECT id FROM user_account WHERE email=?",UUID.class,email);
        service.administrator(root.principal(),pending,UUID.randomUUID());assertFalse(exists(pending));
        jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?",owner.id());
        service.administrator(root.principal(),owner.id(),UUID.randomUUID());assertFalse(exists(owner.id()));
    }
    @Test void ordinaryOrRevokedAdminCannotCancelOtherAccount() {
        assertEquals("ADMIN_REQUIRED",error(()->service.administrator(owner.principal(),root.id(),UUID.randomUUID()),403));
        admins.changeRole(root.principal(),owner.id(),true,UUID.randomUUID());
        admins.changeRole(root.principal(),owner.id(),false,UUID.randomUUID());
        error(()->service.administrator(owner.principal(),root.id(),UUID.randomUUID()),403);assertTrue(exists(root.id()));
    }
    @Test void lastAdministratorRemainsActive() {
        assertEquals("LAST_ADMIN_REQUIRED",error(()->service.self(root.principal(),"test-password",UUID.randomUUID()),409));
        assertTrue(exists(root.id()));assertNotNull(auth.authenticate(root.session().secret()));assertEquals(1,count("user_account_role",root.id()));
    }
    @Test void historicalBranchAnonymizesAndFreesEmailWithoutKeepingIdentities() {
        facts.overrides.put(owner.id(),new AccountParticipationFacts.Facts(true,false));
        service.administrator(root.principal(),owner.id(),UUID.randomUUID());
        var account=accounts.account(owner.id());assertEquals("ANONYMIZED",account.status());assertNull(account.email());assertNull(account.name());
        assertNull(account.passwordHash());assertEquals(0,count("user_identity",owner.id()));assertNull(auth.authenticate(owner.session().secret()));
        assertNotNull(jdbc.queryForObject("SELECT cancelled_at FROM user_account WHERE id=?",Timestamp.class,owner.id()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE target_id=? AND event_type='ACCOUNT_ANONYMIZED'",Integer.class,owner.id()));
        auth.register(owner.email(),"new-password","New");assertNotEquals(owner.id(),accounts.byEmail(owner.email()).id());
    }
    @Test void runningParticipationBlocksEvenWithoutCurrentlyOwnedCrew() {
        facts.overrides.put(owner.id(),new AccountParticipationFacts.Facts(true,true));
        assertEquals("ACCOUNT_PARTICIPATION_RUNNING",error(()->service.self(owner.principal(),"test-password",UUID.randomUUID()),409));
        assertTrue(exists(owner.id()));assertNotNull(auth.authenticate(owner.session().secret()));assertEquals(0,cancellations(owner.id()));
    }
    @Test void orphaningPreservesActualPhotoAndCancelsBothTransferDirections() throws Exception {
        User other=account();var own=crew(owner);var theirs=crew(other);
        var image=PhotoFilesTest.image("png",2,2);
        var photo=crews.upload(owner.principal(),own.id(),0,new ByteArrayInputStream(image));
        var outgoing=transfers.create(owner.principal(),own.id(),UUID.randomUUID(),other.email(),photo.version());
        var incoming=transfers.create(other.principal(),theirs.id(),UUID.randomUUID(),owner.email(),0);
        UUID photoId=jdbc.queryForObject("SELECT photo_id FROM crew WHERE id=?",UUID.class,own.id());
        byte[] before=crews.photo(root.principal(),own.id()).bytes();
        service.self(owner.principal(),"test-password",UUID.randomUUID());
        var after=crews.detail(root.principal(),own.id());assertNull(after.captainAccountId());assertEquals(photo.version()+1,after.version());
        assertEquals(photoId,jdbc.queryForObject("SELECT photo_id FROM crew WHERE id=?",UUID.class,own.id()));
        assertArrayEquals(before,crews.photo(root.principal(),own.id()).bytes());
        for(UUID id:List.of(outgoing.id(),incoming.id()))assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM crew_transfer WHERE id=?",String.class,id));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE actor_id=? AND event_type='CREW_TRANSFER_CANCELLED' AND resolution_reason='ACCOUNT_CANCELLED'",Integer.class,owner.id()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE target_id=? AND event_type='CREW_CAPTAIN_CHANGED' AND previous_captain_id=? AND new_captain_id IS NULL",Integer.class,own.id(),owner.id()));
        assertEquals(other.id(),crews.detail(other.principal(),theirs.id()).captainAccountId());
    }
    @Test void adminReplayWorksAfterDeletionAndConflictsForChangedTarget() {
        UUID key=UUID.randomUUID();service.administrator(root.principal(),owner.id(),key);
        assertFalse(exists(owner.id()));service.administrator(root.principal(),owner.id(),key);assertEquals(1,cancellations(owner.id()));
        assertEquals("IDEMPOTENCY_CONFLICT",error(()->service.administrator(root.principal(),UUID.randomUUID(),key),409));
        assertEquals("ACCOUNT_NOT_FOUND",error(()->service.administrator(root.principal(),owner.id(),UUID.randomUUID()),404));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM admin_role_command WHERE actor_id=? AND command_key=?",Integer.class,root.id(),key));
    }
    @Test void expiredOtherActorRecordsAreRemovedAndNoPasswordEntersLedgerOrAudit() {
        UUID key=UUID.randomUUID(),keep=UUID.randomUUID(),cancelKey=UUID.randomUUID();
        tx(()->{commands.saveRole(root.id(),key,owner.id(),true,clock.instant().minus(Duration.ofDays(2)));
            commands.saveRole(root.id(),keep,root.id(),true,clock.instant());return null;});
        service.administrator(root.principal(),owner.id(),cancelKey);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM admin_role_command WHERE command_key=?",Integer.class,key));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM admin_role_command WHERE command_key=?",Integer.class,keep));
        String saved=jdbc.queryForObject("SELECT request_data::text || result_data::text FROM admin_role_command WHERE command_key=?",String.class,cancelKey);
        assertFalse(saved.contains(owner.email()));assertFalse(saved.contains("test-password"));
    }
    @Test void auditFailureRollsBackCredentialsCrewsTransfersAndLedgerCleanup() {
        User other=account();var crew=crew(owner);
        var transfer=transfers.create(owner.principal(),crew.id(),UUID.randomUUID(),other.email(),0);
        int rows=jdbc.queryForObject("SELECT count(*) FROM admin_role_command WHERE ?=ANY(related_account_ids)",Integer.class,owner.id());
        var marker=new IllegalStateException("Injected cancellation audit failure");
        doAnswer(call->{call.callRealMethod();throw marker;}).when(audit).append(any(),any(),eq(root.id()),eq(Event.ACCOUNT_DELETED),eq(owner.id()));
        failure(()->service.administrator(root.principal(),owner.id(),UUID.randomUUID()),marker);
        assertTrue(exists(owner.id()));assertNotNull(auth.authenticate(owner.session().secret()));
        assertEquals(owner.id(),crews.detail(owner.principal(),crew.id()).captainAccountId());
        assertEquals(0,crews.detail(owner.principal(),crew.id()).version());
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM crew_transfer WHERE id=?",String.class,transfer.id()));
        assertEquals(rows,jdbc.queryForObject("SELECT count(*) FROM admin_role_command WHERE ?=ANY(related_account_ids)",Integer.class,owner.id()));
        assertEquals(0,cancellations(owner.id()));
    }
    @Test void cleanupFailureRollsBackEarlierCredentialDeletion() {
        UUID bad=UUID.randomUUID();tx(()->{commands.saveRole(root.id(),bad,root.id(),true,clock.instant());return null;});
        jdbc.update("UPDATE admin_role_command SET operation='UNKNOWN' WHERE command_key=?",bad);
        try {
            Throwable thrown=assertThrows(RuntimeException.class,()->service.administrator(root.principal(),owner.id(),UUID.randomUUID()));
            while(thrown.getCause()!=null)thrown=thrown.getCause();assertInstanceOf(IllegalStateException.class,thrown);
            assertTrue(thrown.getMessage().contains("Unrecognized idempotency record format"));
            assertTrue(exists(owner.id()));assertNotNull(auth.authenticate(owner.session().secret()));assertEquals(1,count("user_identity",owner.id()));
            assertEquals(0,cancellations(owner.id()));
        } finally {jdbc.update("DELETE FROM admin_role_command WHERE command_key=?",bad);}
    }
    @Test void unknownSchemaVersionFailsClosedBeforeChanges() {
        int rank=jdbc.queryForObject("SELECT max(installed_rank)+1 FROM flyway_schema_history",Integer.class);
        jdbc.update("INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,installed_by,execution_time,success) VALUES (?,'11','Future test migration','SQL','V11__future.sql',current_user,0,true)",rank);
        try {
            assertEquals("PARTICIPATION_CHECK_UNAVAILABLE",error(()->service.self(owner.principal(),"test-password",UUID.randomUUID()),503));
            assertTrue(exists(owner.id()));assertNotNull(auth.authenticate(owner.session().secret()));assertEquals(0,cancellations(owner.id()));
        } finally {jdbc.update("DELETE FROM flyway_schema_history WHERE installed_rank=?",rank);}
    }
    @Test void missingHistoryTableIsAlsoUnavailable() {
        new TransactionTemplate(manager).executeWithoutResult(status->{
            jdbc.execute("ALTER TABLE flyway_schema_history RENAME TO temporarily_missing_history");
            try {assertEquals("PARTICIPATION_CHECK_UNAVAILABLE",error(()->service.administrator(root.principal(),owner.id(),UUID.randomUUID()),503));}
            finally {status.setRollbackOnly();}
        });
        assertTrue(exists(owner.id()));assertNotNull(auth.authenticate(owner.session().secret()));
    }
    void pausedLookup(String email,CountDownLatch found,CountDownLatch release) {
        var once=new AtomicBoolean();
        doAnswer(call->{
            var result=call.callRealMethod();
            if(email.equals(call.getArgument(0)) && result!=null && once.compareAndSet(false,true)) {
                found.countDown();assertTrue(release.await(15,TimeUnit.SECONDS));
            }
            return result;
        }).when(accounts).byEmail(anyString());
    }
    @Test void loginFindingDeletedAccountBeforeLockReturnsCredentialsError() throws Exception {
        var found=new CountDownLatch(1);var release=new CountDownLatch(1);pausedLookup(owner.email(),found,release);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var result=executor.submit(()->error(()->auth.login(owner.email(),"test-password",false),401));
            try {assertTrue(found.await(10,TimeUnit.SECONDS));service.self(owner.principal(),"test-password",UUID.randomUUID());}
            finally {release.countDown();}
            assertEquals("INVALID_CREDENTIALS",result.get(15,TimeUnit.SECONDS));
        }
        assertEquals(0,count("user_session",owner.id()));
    }
    @Test void resetAndVerificationRequestsFindingDeletedAccountAreGenericNoOps() throws Exception {
        for(boolean verification:List.of(false,true)) {
            User user=account();var found=new CountDownLatch(1);var release=new CountDownLatch(1);pausedLookup(user.email(),found,release);
            try(var executor=Executors.newSingleThreadExecutor()) {
                var result=executor.submit(()->{if(verification)auth.requestVerification(user.email());else auth.requestReset(user.email());});
                try {assertTrue(found.await(10,TimeUnit.SECONDS));service.administrator(root.principal(),user.id(),UUID.randomUUID());}
                finally {release.countDown();}
                result.get(15,TimeUnit.SECONDS);
            }
            assertEquals(0,count("user_action_token",user.id()));
        }
    }
    Object[] race(Supplier<Object> one,Supplier<Object> two) throws Exception {
        var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->{barrier.await();try{return one.get();}catch(AuthFailure error){return error.code();}});
            var second=executor.submit(()->{barrier.await();try{return two.get();}catch(AuthFailure error){return error.code();}});
            return new Object[]{first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS)};
        }
    }
    @Test void twoAdministratorsCannotBothCancelTheirAccounts() throws Exception {
        User other=account();admins.changeRole(root.principal(),owner.id(),true,UUID.randomUUID());admins.changeRole(root.principal(),other.id(),true,UUID.randomUUID());
        jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?",root.id());
        try {
            Object[] results=race(()->{service.self(owner.principal(),"test-password",UUID.randomUUID());return "deleted";},
                    ()->{service.self(other.principal(),"test-password",UUID.randomUUID());return "deleted";});
            assertEquals(1,Arrays.stream(results).filter("deleted"::equals).count());assertEquals(1,Arrays.stream(results).filter("LAST_ADMIN_REQUIRED"::equals).count());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM user_account_role r JOIN user_account a ON a.id=r.account_id WHERE a.status='ACTIVE' AND a.email_verified_at IS NOT NULL",Integer.class));
        } finally {
            jdbc.update("UPDATE user_account SET status='ACTIVE' WHERE id=?",root.id());
            tx(()->{jdbc.update("DELETE FROM user_account_role WHERE account_id IN (?,?)",owner.id(),other.id());return null;});
        }
    }
    @Test void concurrentRegistrationCannotResurrectCancelledIdentity() throws Exception {
        UUID old=owner.id();race(()->{service.self(owner.principal(),"test-password",UUID.randomUUID());return "deleted";},
                ()->{auth.register(owner.email(),"new-password","Fresh");return "registered";});
        assertFalse(exists(old));assertEquals(0,count("user_session",old));
        auth.register(owner.email(),"new-password","Fresh");
        var fresh=accounts.byEmail(owner.email());assertNotEquals(old,fresh.id());assertEquals("PENDING_EMAIL",fresh.status());
    }
    @Test void cancellationSerializesWithAcceptRejectAndResend() throws Exception {
        for(String operation:List.of("accept","reject","resend")) {
            User source=account(),target=account();var crew=crew(source);
            var transfer=transfers.create(source.principal(),crew.id(),UUID.randomUUID(),target.email(),0);
            String token=mail.token(target.email(),"CREW_TRANSFER");
            Object[] results=race(()->{service.self(target.principal(),"test-password",UUID.randomUUID());return "deleted";},
                    ()->operation.equals("resend")?transfers.resend(source.principal(),crew.id(),transfer.id(),UUID.randomUUID())
                            :transfers.decide(target.principal(),transfer.id(),UUID.randomUUID(),operation,token));
            assertEquals("deleted",results[0]);assertFalse(exists(target.id()));
            String status=jdbc.queryForObject("SELECT status FROM crew_transfer WHERE id=?",String.class,transfer.id());
            assertNotEquals("PENDING",status);assertNull(auth.authenticate(target.session().secret()));
            UUID captain=jdbc.queryForObject("SELECT captain_account_id FROM crew WHERE id=?",UUID.class,crew.id());
            assertTrue(captain==null || captain.equals(source.id()));assertEquals(1,cancellations(target.id()));
        }
    }
    @Test void cancellationSerializesWithAdminCaptainAssignment() throws Exception {
        var crew=crews.create(root.principal(),UUID.randomUUID(),"Orphan",null,null,true,null);
        Object[] result=race(()->{service.self(owner.principal(),"test-password",UUID.randomUUID());return "deleted";},
                ()->crews.captain(root.principal(),crew.id(),0,owner.id(),UUID.randomUUID()));
        assertEquals("deleted",result[0]);assertNull(crews.detail(root.principal(),crew.id()).captainAccountId());assertFalse(exists(owner.id()));
    }
    @Test void actualBearerHttpSelfCancellationInvalidatesOriginalCredentials() throws Exception {
        var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/account-cancellations")
                .header("Authorization","Bearer "+owner.session().secret()).header("Idempotency-Key",UUID.randomUUID())
                .contentType("application/json").content("{\"currentPassword\":\"test-password\"}");
        mvc.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        mvc.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }
    @Test void explicitConfirmationCannotReplaceMissingOrWrongLocalPassword() {
        for(String password:java.util.Arrays.asList(null,"wrong-password")) {
            assertEquals("INVALID_CREDENTIALS",error(()->service.self(owner.principal(),password,true,UUID.randomUUID()),401));
            assertNotNull(auth.authenticate(owner.session().secret()));
            assertEquals(1,count("user_identity",owner.id()));
        }
    }
    @AfterAll void cleanupOwnFixtures() throws Exception {
        assertTrue(SCHEMA.matches("it_cancel_service_[0-9a-f]{32}"));
        assertEquals("rallye_test",jdbc.queryForObject("SELECT current_database()",String.class));
        assertEquals("rallye_test",jdbc.queryForObject("SELECT current_user",String.class));
        jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");
        assertTrue(PHOTOS.getFileName().toString().startsWith("it_cancel_photos_"));
        try(var entries=Files.newDirectoryStream(PHOTOS)) {for(Path file:entries)Files.delete(file);}
        Files.delete(PHOTOS);
    }
}
