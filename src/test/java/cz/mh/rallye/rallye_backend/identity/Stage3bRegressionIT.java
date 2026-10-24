package cz.mh.rallye.rallye_backend.identity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import cz.mh.rallye.rallye_backend.audit.application.*;
import cz.mh.rallye.rallye_backend.audit.persistence.AuditStore;
import cz.mh.rallye.rallye_backend.crews.application.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.support.TestDatabaseInitializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cross-module gaps only; isolated real services, not a second copy of each module's tests. */
@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@ActiveProfiles("it")
@ContextConfiguration(initializers=TestDatabaseInitializer.class)
@Import(IdentityIT.Fixtures.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class Stage3bRegressionIT {
    static final String SCHEMA="it_3b_regression_"+UUID.randomUUID().toString().replace("-","");
    static final Path PHOTOS=temporaryPhotos();
    private static Path temporaryPhotos() {
        try {return Files.createTempDirectory("it_3b_regression_photos_").toAbsolutePath();}
        catch(IOException failure) {throw new UncheckedIOException(failure);}
    }
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry properties) {
        properties.add("spring.flyway.schemas",()->SCHEMA);
        properties.add("spring.flyway.default-schema",()->SCHEMA);
        properties.add("spring.datasource.hikari.connection-init-sql",()->"SET search_path TO "+SCHEMA);
        properties.add("rallye.crews.photo-directory",()->PHOTOS.toString());
    }
    @Autowired AuthService auth;
    @Autowired AdminService admins;
    @Autowired CrewService crews;
    @Autowired CrewTransferService transfers;
    @Autowired AccountCancellationService cancellation;
    @Autowired AuditRetentionService retention;
    @Autowired AuditExportService exports;
    @Autowired IdentityIT.TestMail mail;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @MockitoSpyBean AuditStore audit;

    record User(String email,AuthService.LoginSession session) {
        UUID id() {return session.principal().accountId();}
        AuthService.Principal principal() {return session.principal();}
        @Override public String toString() {return "TestUser[redacted]";}
    }
    User root;
    User account() {
        String email=UUID.randomUUID()+"@example.test";
        auth.register(email,"test-password","Captain");
        auth.verifyEmail(mail.token(email,"EMAIL_VERIFICATION"));
        return new User(email,auth.login(email,"test-password",true));
    }
    @BeforeAll void bootstrap() throws Exception {
        root=account();String sql=AdministrationIT.bootstrapBody(root.id());
        new TransactionTemplate(manager).executeWithoutResult(status->jdbc.execute(sql));
    }
    void guard() {
        assertEquals("rallye_test",jdbc.queryForObject("SELECT current_database()",String.class));
        assertEquals("rallye_test",jdbc.queryForObject("SELECT current_user",String.class));
        assertTrue(SCHEMA.matches("it_3b_regression_[0-9a-f]{32}"));
        assertEquals(SCHEMA,jdbc.queryForObject("SELECT current_schema()",String.class));
    }
    @BeforeEach void allowExplicitCleanupOnlyInOwnSchema() {
        guard();jdbc.update("UPDATE audit_cleanup_control SET last_success_local_date=NULL WHERE id=1");
    }
    CrewService.View crew(User owner) {
        return crews.create(owner.principal(),UUID.randomUUID(),"Crew",null,null,false,null);
    }
    AuditExportFilter all() {return new AuditExportFilter(null,null,null,null,null);}
    String csv(User actor) throws IOException {
        var bytes=new ByteArrayOutputStream();exports.stream(actor.principal(),all(),bytes);
        return bytes.toString(StandardCharsets.UTF_8);
    }
    String error(Runnable operation,int status) {
        var failure=assertThrows(AuthFailure.class,operation::run);assertEquals(status,failure.status());return failure.code();
    }
    int command(UUID key) {return jdbc.queryForObject("SELECT count(*) FROM admin_role_command WHERE command_key=?",Integer.class,key);}
    long events(String type,UUID target) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE event_type=? AND target_id=?",Long.class,type,target);
    }
    boolean exists(User user) {return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM user_account WHERE id=?)",Boolean.class,user.id());}
    void ageAudit(UUID crew,UUID transfer) {
        // Only audit timestamps are fixtures. Do not age sessions, tokens or commands; their replay
        // must stay valid while the old evidence disappears. Production time remains unchanged.
        jdbc.update("UPDATE audit_event SET occurred_at=? WHERE target_id IN (?,?)",
                Timestamp.from(clock.instant().minus(Duration.ofDays(366))),crew,transfer);
    }

    @Test void roleRevocationAfterExportPreparationRevokesAdministrationButPreservesOwnership() throws Exception {
        var user=account();var own=crew(user);
        var orphan=crews.create(root.principal(),UUID.randomUUID(),"Orphan",null,null,true,null);
        admins.changeRole(root.principal(),user.id(),true,UUID.randomUUID());
        try {
            mvc.perform(get("/api/v1/admin/audit-events").header("Authorization","Bearer "+user.session().secret()))
                    .andExpect(status().isOk());
            exports.prepare(user.principal(),all());
            admins.changeRole(root.principal(),user.id(),false,UUID.randomUUID());
            var bytes=new ByteArrayOutputStream();
            assertEquals(403,assertThrows(AuthFailure.class,()->exports.stream(user.principal(),all(),bytes)).status());
            assertEquals(0,bytes.size());
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE event_type='AUDIT_EXPORT' AND actor_id=?",Integer.class,user.id()));
            assertEquals("ADMIN_REQUIRED",error(()->admins.audit(user.principal(),null,null,null,null,null,0,10),403));
            assertEquals("ADMIN_REQUIRED",error(()->crews.captain(user.principal(),orphan.id(),orphan.version(),user.id(),UUID.randomUUID()),403));
            for(String endpoint:List.of("/api/v1/admin/audit-events","/api/v1/admin/audit-events/export"))
                mvc.perform(get(endpoint).header("Authorization","Bearer "+user.session().secret())).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/crews/"+own.id()).header("Authorization","Bearer "+user.session().secret()))
                    .andExpect(status().isOk());
            var updated=crews.update(user.principal(),own.id(),own.version(),"Still owned",null,null);
            assertEquals(user.id(),updated.captainAccountId());assertEquals(own.version()+1,updated.version());
            assertNotNull(auth.authenticate(user.session().secret()));
            admins.changeRole(root.principal(),user.id(),true,UUID.randomUUID());
            assertTrue(csv(user).startsWith("\ufeff\"id\";"));
            assertEquals(2,events("ADMIN_GRANTED",user.id()));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE event_type='AUDIT_EXPORT' AND actor_id=?",Integer.class,user.id()));
        } finally {admins.changeRole(root.principal(),user.id(),false,UUID.randomUUID());}
    }

    @Test void acceptedTransferReplaySurvivesRetentionAndFormerCaptainCancellationPreservesHistory() {
        var former=account();var next=account();var moved=crew(former);var ownOther=crew(former);
        UUID createKey=UUID.randomUUID(),acceptKey=UUID.randomUUID();
        var transfer=transfers.create(former.principal(),moved.id(),createKey,next.email(),moved.version());
        String token=mail.token(next.email(),"CREW_TRANSFER");
        var accepted=transfers.decide(next.principal(),transfer.id(),acceptKey,"accept",token);
        var unrelated=crew(next);
        ageAudit(moved.id(),transfer.id());assertTrue(retention.runScheduled());
        assertEquals(0,events("CREW_TRANSFER_ACCEPTED",transfer.id()));
        assertEquals(accepted,transfers.decide(next.principal(),transfer.id(),acceptKey,"accept",token));
        assertEquals(0,events("CREW_TRANSFER_ACCEPTED",transfer.id()),"Replay must not recreate retained-away audit");
        cancellation.self(former.principal(),"test-password",UUID.randomUUID());
        assertFalse(exists(former));assertNull(auth.authenticate(former.session().secret()));
        assertEquals(next.id(),crews.detail(next.principal(),moved.id()).captainAccountId());
        assertEquals(accepted.crewVersion(),crews.detail(next.principal(),moved.id()).version());
        assertNull(crews.detail(root.principal(),ownOther.id()).captainAccountId());
        assertEquals(next.id(),crews.detail(next.principal(),unrelated.id()).captainAccountId());
        assertEquals(0,command(createKey));assertEquals(0,command(acceptKey));
        assertEquals("ACCEPTED",jdbc.queryForObject("SELECT status FROM crew_transfer WHERE id=?",String.class,transfer.id()));
        assertEquals(former.id(),jdbc.queryForObject("SELECT from_account_id FROM crew_transfer WHERE id=?",UUID.class,transfer.id()));
        assertEquals(1,events("ACCOUNT_DELETED",former.id()));
    }

    @Test void adminCaptainReplacementRetentionAndCancellationAuditFailureRemainAtomic() {
        var former=account();var next=account();var moved=crew(former);
        UUID transferKey=UUID.randomUUID(),captainKey=UUID.randomUUID(),cancelKey=UUID.randomUUID();
        var pending=transfers.create(former.principal(),moved.id(),transferKey,next.email(),moved.version());
        var assigned=crews.captain(root.principal(),moved.id(),moved.version(),next.id(),captainKey);
        ageAudit(moved.id(),pending.id());assertTrue(retention.runScheduled());
        assertEquals(0,events("CREW_CAPTAIN_CHANGED",moved.id()));
        assertEquals(assigned,crews.captain(root.principal(),moved.id(),moved.version(),next.id(),captainKey));
        var marker=new IllegalStateException("Injected cross-module cancellation audit failure");
        doAnswer(call->{call.callRealMethod();throw marker;}).when(audit)
                .append(any(),any(),eq(root.id()),eq(AuditService.Event.ACCOUNT_DELETED),eq(former.id()));
        try {
            Throwable failure=assertThrows(RuntimeException.class,()->cancellation.administrator(root.principal(),former.id(),cancelKey));
            while(failure.getCause()!=null)failure=failure.getCause();assertSame(marker,failure);
            assertTrue(exists(former));assertNotNull(auth.authenticate(former.session().secret()));
            assertEquals(1,command(transferKey));assertEquals(1,command(captainKey));assertEquals(0,command(cancelKey));
            assertEquals(0,events("ACCOUNT_DELETED",former.id()));
        } finally {
            doCallRealMethod().when(audit).append(any(),any(),eq(root.id()),eq(AuditService.Event.ACCOUNT_DELETED),eq(former.id()));
        }
        cancellation.administrator(root.principal(),former.id(),cancelKey);
        cancellation.administrator(root.principal(),former.id(),cancelKey);
        assertFalse(exists(former));assertEquals(0,command(transferKey));assertEquals(0,command(captainKey));
        assertEquals(1,command(cancelKey));assertEquals(1,events("ACCOUNT_DELETED",former.id()));
        assertEquals(assigned,crews.detail(next.principal(),moved.id()));
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM crew_transfer WHERE id=?",String.class,pending.id()));
    }

    @Test void retentionRacingFormerCaptainCancellationPreservesNewOwnerAndCancellationAudit() throws Exception {
        var former=account();var next=account();var moved=crew(former);
        UUID captainKey=UUID.randomUUID();
        var assigned=crews.captain(root.principal(),moved.id(),moved.version(),next.id(),captainKey);
        ageAudit(moved.id(),UUID.randomUUID());
        long before=jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE event_type='AUDIT_CLEANUP'",Long.class);
        var start=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var clean=executor.submit(()->{start.await(10,TimeUnit.SECONDS);return retention.runScheduled();});
            var cancel=executor.submit(()->{start.await(10,TimeUnit.SECONDS);cancellation.self(former.principal(),"test-password",UUID.randomUUID());return true;});
            assertTrue(clean.get(20,TimeUnit.SECONDS));assertTrue(cancel.get(20,TimeUnit.SECONDS));
        }
        assertFalse(exists(former));assertNull(auth.authenticate(former.session().secret()));
        assertEquals(assigned,crews.detail(next.principal(),moved.id()));assertEquals(0,command(captainKey));
        assertEquals(1,events("ACCOUNT_DELETED",former.id()));
        assertEquals(before+1,jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE event_type='AUDIT_CLEANUP'",Long.class));
    }

    @AfterAll void cleanupOwnFixtures() throws IOException {
        guard();jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");
        try(var paths=Files.walk(PHOTOS)) {for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
}
