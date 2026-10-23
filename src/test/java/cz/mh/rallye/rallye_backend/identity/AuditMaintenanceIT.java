package cz.mh.rallye.rallye_backend.identity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import cz.mh.rallye.rallye_backend.audit.application.*;
import cz.mh.rallye.rallye_backend.audit.persistence.AuditStore;
import cz.mh.rallye.rallye_backend.audit.persistence.AuditRetentionStore;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.AccountStore;
import cz.mh.rallye.rallye_backend.shared.persistence.*;
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

@SpringBootTest
@ActiveProfiles("it")
@ContextConfiguration(initializers=TestDatabaseInitializer.class)
@Import(IdentityIT.Fixtures.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AuditMaintenanceIT {
    static final String SCHEMA="it_audit_retention_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry properties) {
        properties.add("spring.flyway.schemas",()->SCHEMA);properties.add("spring.flyway.default-schema",()->SCHEMA);
        properties.add("spring.datasource.hikari.connection-init-sql",()->"SET search_path TO "+SCHEMA);
    }
    @Autowired AuditExportService exports;
    @Autowired AuditRetentionService retention;
    @Autowired CommandCleanupStore cleanup;
    @Autowired CommandStore commands;
    @Autowired AuthService auth;
    @Autowired IdentityIT.TestMail mail;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired AccountParticipationFacts facts;
    @MockitoSpyBean AuditStore audit;
    AuthService.LoginSession root;
    <T> T tx(Supplier<T> work) {return new TransactionTemplate(manager).execute(status->work.get());}
    AuthService.LoginSession account() {
        String email=UUID.randomUUID()+"@example.test";auth.register(email,"test-password","Captain");
        auth.verifyEmail(mail.token(email,"EMAIL_VERIFICATION"));return auth.login(email,"test-password",true);
    }
    void identityGuard() {
        assertEquals("rallye_test",jdbc.queryForObject("SELECT current_database()",String.class));
        assertEquals("rallye_test",jdbc.queryForObject("SELECT current_user",String.class));assertTrue(SCHEMA.matches("it_audit_retention_[0-9a-f]{32}"));
    }
    @BeforeAll void bootstrap() throws Exception {
        root=account();String sql=AdministrationIT.bootstrapBody(root.principal().accountId());tx(()->{jdbc.execute(sql);return null;});
    }
    @BeforeEach void resetOnlyOwnSchema() {
        identityGuard();assertEquals(SCHEMA,jdbc.queryForObject("SELECT current_schema()",String.class));
        jdbc.update("DELETE FROM audit_event");jdbc.update("DELETE FROM admin_role_command");
        jdbc.update("UPDATE audit_cleanup_control SET last_success_local_date=NULL WHERE id=1");
    }
    UUID event(Instant at,String type,UUID actor,UUID target,String targetType,String reason,String metadata) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO audit_event(id,occurred_at,actor_kind,actor_id,event_type,target_id,target_type,resolution_reason,metadata)
                VALUES (?,?,?,?,?,?,?,?,?::jsonb)
                """,id,Timestamp.from(at),actor==null?"SYSTEM":"USER",actor,type,target,targetType,reason,metadata);return id;
    }
    AuditExportFilter all() {return new AuditExportFilter(null,null,null,null,null);}
    String export(AuditExportFilter filter) throws IOException {
        var output=new ByteArrayOutputStream();exports.stream(root.principal(),filter,output);return output.toString(StandardCharsets.UTF_8);
    }
    long count(String type) {return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE event_type=?",Long.class,type);}
    @Test void exportIncludesAllStructuredFieldsAndMetadataWithoutItsOwnAudit() throws Exception {
        UUID target=UUID.randomUUID(),actor=root.principal().accountId();
        UUID id=event(Instant.parse("2026-10-25T01:30:00Z"),"ACCOUNT_DELETED",actor,target,"ACCOUNT","=formula;\"line\nnext","{\"cutoff\":\"2026-01-15T12:00:00Z\",\"note\":\"Český; text\\n\\\"\"}");
        var filter=new AuditExportFilter(null,null,AuditService.Event.ACCOUNT_DELETED,actor,target);
        String csv=export(filter);var parsed=csv(csv);
        assertEquals(2,parsed.size());assertEquals(14,parsed.get(0).size());assertEquals(id.toString(),parsed.get(1).get(0));
        assertEquals("2026-10-25T02:30:00+01:00",parsed.get(1).get(1));assertEquals("DELETED",parsed.get(1).get(7));
        assertEquals("'=formula;\"line\nnext",parsed.get(1).get(12));
        assertTrue(parsed.get(1).get(13).contains("2026-01-15T13:00:00+01:00"));assertTrue(parsed.get(1).get(13).contains("Český"));
        assertFalse(csv.contains("AUDIT_EXPORT"));assertEquals(1,count("AUDIT_EXPORT"));
        assertEquals(1L,jdbc.queryForObject("SELECT (metadata->>'exportedCount')::bigint FROM audit_event WHERE event_type='AUDIT_EXPORT'",Long.class));
        assertEquals(target.toString(),jdbc.queryForObject("SELECT metadata->'filters'->>'targetAccountId' FROM audit_event WHERE event_type='AUDIT_EXPORT'",String.class));
    }
    @Test void filtersDistinguishActorFromAccountTargetAndUseExclusiveUntil() throws Exception {
        UUID actor=root.principal().accountId(),target=UUID.randomUUID();Instant from=clock.instant(),until=from.plusSeconds(2);
        UUID good=event(from,"LOGIN_SUCCEEDED",actor,target,"ACCOUNT",null,"{}");
        event(until,"LOGIN_SUCCEEDED",actor,target,"ACCOUNT",null,"{}");
        event(from,"LOGIN_SUCCEEDED",actor,target,"CREW",null,"{}");
        event(from,"LOGIN_SUCCEEDED",UUID.randomUUID(),target,"ACCOUNT",null,"{}");
        event(from.minusMillis(1),"LOGIN_SUCCEEDED",actor,target,"ACCOUNT",null,"{}");
        var rows=csv(export(new AuditExportFilter(from,until,AuditService.Event.LOGIN_SUCCEEDED,actor,target)));
        assertEquals(2,rows.size());assertEquals(good.toString(),rows.get(1).get(0));
    }
    @Test void cursorExportHasNoPageLimitAndOrdersByTimeThenUuid() throws Exception {
        seedMany(2105,clock.instant());var rows=csv(export(all()));assertEquals(2106,rows.size());
        String previous="";for(int i=1;i<rows.size();i++) {String id=rows.get(i).get(0);assertTrue(previous.compareTo(id)<0);previous=id;}
        assertEquals(2105L,jdbc.queryForObject("SELECT (metadata->>'exportedCount')::bigint FROM audit_event WHERE event_type='AUDIT_EXPORT'",Long.class));
    }
    void seedMany(int count,Instant at) {
        var data=new ArrayList<Object[]>();for(int i=0;i<count;i++)data.add(new Object[]{UUID.randomUUID(),Timestamp.from(at),UUID.randomUUID()});
        jdbc.batchUpdate("INSERT INTO audit_event(id,occurred_at,actor_kind,event_type,target_id) VALUES (?,?,'SYSTEM','LOGIN_SUCCEEDED',?)",data);
    }
    @Test void snapshotSurvivesConcurrentCleanupAndNewInsertWithoutBlockingCleanup() throws Exception {
        seedMany(2105,clock.instant().minus(Duration.ofDays(366)));
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var first=new AtomicBoolean(true);
        var bytes=new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] value,int offset,int length) {
                if(first.compareAndSet(true,false)) {started.countDown();try {assertTrue(release.await(20,TimeUnit.SECONDS));}
                    catch(InterruptedException failure) {Thread.currentThread().interrupt();throw new IllegalStateException(failure);}}
                super.write(value,offset,length);
            }
        };
        try(var executor=Executors.newFixedThreadPool(2)) {
            var export=executor.submit(()->{exports.stream(root.principal(),all(),bytes);return true;});
            UUID added;
            try {
                assertTrue(started.await(10,TimeUnit.SECONDS));assertTrue(executor.submit(retention::runScheduled).get(10,TimeUnit.SECONDS));
                added=event(clock.instant(),"LOGIN_SUCCEEDED",null,UUID.randomUUID(),"ACCOUNT",null,"{}");
            } finally {release.countDown();}
            assertTrue(export.get(20,TimeUnit.SECONDS));
            var rows=csv(bytes.toString(StandardCharsets.UTF_8));assertEquals(2106,rows.size());
            assertFalse(bytes.toString(StandardCharsets.UTF_8).contains(added.toString()));
            assertEquals(1,count("AUDIT_CLEANUP"));assertEquals(1,count("AUDIT_EXPORT"));assertEquals(1,count("LOGIN_SUCCEEDED"));
        }
    }
    @Test void retentionDeletesStrictlyOlderRowsIncludingMaintenanceEventsAndPreservesOtherTables() {
        Instant cutoff=clock.instant().minus(Duration.ofDays(365));
        UUID boundary=event(cutoff,"LOGIN_SUCCEEDED",null,UUID.randomUUID(),"ACCOUNT",null,"{}");
        event(cutoff.minusMillis(1),"AUDIT_EXPORT",root.principal().accountId(),UUID.randomUUID(),"AUDIT_LOG",null,"{}");
        event(cutoff.minusMillis(1),"AUDIT_CLEANUP",null,UUID.randomUUID(),"AUDIT_LOG",null,"{}");
        UUID crew=UUID.randomUUID();jdbc.update("INSERT INTO crew(id,captain_account_id,name,created_at,updated_at) VALUES (?,?,'Crew',?,?)",crew,root.principal().accountId(),Timestamp.from(clock.instant()),Timestamp.from(clock.instant()));
        assertTrue(retention.runScheduled());assertEquals(1,count("LOGIN_SUCCEEDED"));assertEquals(0,count("AUDIT_EXPORT"));assertEquals(1,count("AUDIT_CLEANUP"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE id=?",Integer.class,boundary));
        assertEquals(2L,jdbc.queryForObject("SELECT (metadata->>'deletedCount')::bigint FROM audit_event WHERE event_type='AUDIT_CLEANUP'",Long.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM crew WHERE id=?",Integer.class,crew));
        assertNotNull(auth.authenticate(root.secret()));assertFalse(retention.runScheduled());assertEquals(1,count("AUDIT_CLEANUP"));
    }
    void captainCommand(UUID previous,UUID crew,UUID key,boolean legacy,boolean evidence) {
        UUID actor=root.principal().accountId();Instant old=clock.instant().minus(Duration.ofDays(366));
        tx(()->{commands.save(actor,key,crew,"CREW_CAPTAIN","[\""+crew+"\",0,\""+actor+"\"]",
                "{\"id\":\""+crew+"\",\"captainAccountId\":\""+actor+"\"}",old);
            commands.relate(actor,key,previous);return null;});
        if(legacy)jdbc.update("UPDATE admin_role_command SET related_account_ids=NULL WHERE command_key=?",key);
        if(evidence) {
            UUID auditId=event(old,"CREW_CAPTAIN_CHANGED",actor,crew,"CREW",null,"{}");
            jdbc.update("UPDATE audit_event SET previous_captain_id=?,new_captain_id=? WHERE id=?",previous,actor,auditId);
        }
    }
    @Test void verifiedAndExpiredCommandRemainsSafeAfterItsAuditEvidenceIsDeleted() {
        UUID previous=UUID.randomUUID(),crew=UUID.randomUUID(),key=UUID.randomUUID();captainCommand(previous,crew,key,false,true);
        assertTrue(retention.runScheduled());assertEquals(0,count("CREW_CAPTAIN_CHANGED"));
        assertTrue(jdbc.queryForObject("SELECT ?=ANY(related_account_ids) FROM admin_role_command WHERE command_key=?",Boolean.class,previous,key));
        assertEquals(1,tx(()->cleanup.deleteRelated(previous,"former@example.test")));
    }
    @Test void verifiedCaptainCommandNeedsNoRetainedAuditToRemainSafeForCancellation() {
        UUID previous=UUID.randomUUID(),key=UUID.randomUUID();
        captainCommand(previous,UUID.randomUUID(),key,false,false);
        assertTrue(retention.runScheduled());assertEquals(1,count("AUDIT_CLEANUP"));
        assertEquals(1,tx(()->cleanup.deleteRelated(previous,"former@example.test")));
    }
    @Test void missingLegacyEvidenceAndUnknownFormatPreventAllDeletion() {
        for(boolean unknown:List.of(false,true)) {
            UUID key=UUID.randomUUID(),crew=UUID.randomUUID();captainCommand(UUID.randomUUID(),crew,key,true,false);
            if(unknown)jdbc.update("UPDATE admin_role_command SET operation='UNKNOWN' WHERE command_key=?",key);
            event(clock.instant().minus(Duration.ofDays(366)),"LOGIN_SUCCEEDED",null,UUID.randomUUID(),"ACCOUNT",null,"{}");
            long before=jdbc.queryForObject("SELECT count(*) FROM audit_event",Long.class);
            try {
                Throwable failure=assertThrows(RuntimeException.class,retention::runScheduled);while(failure.getCause()!=null)failure=failure.getCause();
                assertInstanceOf(IllegalStateException.class,failure);
                assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM audit_event",Long.class));
                assertNull(jdbc.queryForObject("SELECT last_success_local_date FROM audit_cleanup_control",java.sql.Date.class));
                assertNull(jdbc.queryForObject("SELECT related_account_ids FROM admin_role_command WHERE command_key=?",java.sql.Array.class,key));
            } finally {jdbc.update("DELETE FROM admin_role_command WHERE command_key=?",key);}
        }
    }
    @Test void unrelatedCaptainAuditCannotCertifyLegacyCommandAndCleanupRollsBack() {
        UUID crew=UUID.randomUUID(),key=UUID.randomUUID();
        captainCommand(UUID.randomUUID(),crew,key,true,false);
        // Same crew, actor and new captain, but a different operation: there is no reliable
        // identifier with which retention could link this event to the legacy command.
        UUID unrelated=event(clock.instant().minus(Duration.ofDays(367)),"CREW_CAPTAIN_CHANGED",
                root.principal().accountId(),crew,"CREW",null,"{}");
        jdbc.update("UPDATE audit_event SET previous_captain_id=?,new_captain_id=? WHERE id=?",
                UUID.randomUUID(),root.principal().accountId(),unrelated);
        UUID oldEvent=event(clock.instant().minus(Duration.ofDays(366)),"LOGIN_SUCCEEDED",null,UUID.randomUUID(),"ACCOUNT",null,"{}");
        long before=jdbc.queryForObject("SELECT count(*) FROM audit_event",Long.class);
        var failure=assertThrows(RuntimeException.class,retention::runScheduled);
        Throwable rootCause=failure;while(rootCause.getCause()!=null)rootCause=rootCause.getCause();
        assertInstanceOf(IllegalStateException.class,rootCause);
        assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM audit_event",Long.class));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE id IN (?,?)",Integer.class,unrelated,oldEvent));
        assertEquals(0,count("AUDIT_CLEANUP"));
        assertNull(jdbc.queryForObject("SELECT last_success_local_date FROM audit_cleanup_control",java.sql.Date.class));
        assertNull(jdbc.queryForObject("SELECT related_account_ids FROM admin_role_command WHERE command_key=?",java.sql.Array.class,key));
    }
    @Test void evenPlausibleAuditDoesNotProveLegacyCommandWithoutCorrelationId() {
        UUID key=UUID.randomUUID();captainCommand(UUID.randomUUID(),UUID.randomUUID(),key,true,true);
        assertThrows(RuntimeException.class,retention::runScheduled);
        assertEquals(1,count("CREW_CAPTAIN_CHANGED"));assertEquals(0,count("AUDIT_CLEANUP"));
        assertNull(jdbc.queryForObject("SELECT related_account_ids FROM admin_role_command WHERE command_key=?",java.sql.Array.class,key));
    }
    @Test void cleanupAuditFailureRollsBackDeletionBackfillAndSuccessfulDay() {
        UUID previous=UUID.randomUUID(),crew=UUID.randomUUID(),key=UUID.randomUUID();captainCommand(previous,crew,key,false,true);
        UUID roleKey=UUID.randomUUID();
        tx(()->{commands.saveRole(root.principal().accountId(),roleKey,previous,true,clock.instant());return null;});
        jdbc.update("UPDATE admin_role_command SET related_account_ids=NULL WHERE command_key=?",roleKey);
        var marker=new IllegalStateException("Injected retention audit failure");
        doAnswer(call->{call.callRealMethod();throw marker;}).when(audit).appendAuditMaintenance(any(),any(),isNull(),eq(AuditService.Event.AUDIT_CLEANUP),any());
        Throwable failure=assertThrows(RuntimeException.class,retention::runScheduled);while(failure.getCause()!=null)failure=failure.getCause();assertSame(marker,failure);
        assertEquals(1,count("CREW_CAPTAIN_CHANGED"));assertEquals(0,count("AUDIT_CLEANUP"));
        assertNull(jdbc.queryForObject("SELECT last_success_local_date FROM audit_cleanup_control",java.sql.Date.class));
        assertTrue(jdbc.queryForObject("SELECT ?=ANY(related_account_ids) FROM admin_role_command WHERE command_key=?",Boolean.class,previous,key));
        assertNull(jdbc.queryForObject("SELECT related_account_ids FROM admin_role_command WHERE command_key=?",java.sql.Array.class,roleKey));
        doCallRealMethod().when(audit).appendAuditMaintenance(any(),any(),isNull(),eq(AuditService.Event.AUDIT_CLEANUP),any());
        clock.advance(Duration.ofDays(1));assertTrue(retention.runScheduled());
    }
    @Test void concurrentInstancesProduceExactlyOneSuccessfulDailyCleanup() throws Exception {
        event(clock.instant().minus(Duration.ofDays(366)),"LOGIN_SUCCEEDED",null,UUID.randomUUID(),"ACCOUNT",null,"{}");
        var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> work=()->{barrier.await();return retention.runScheduled();};
            var first=executor.submit(work);var second=executor.submit(work);
            assertNotEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,count("AUDIT_CLEANUP"));
    }
    @Test void busyAdvisoryLockSkipsWithoutRecordingCleanup() throws Exception {
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var holder=executor.submit(()->tx(()->{
                jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)",Object.class,AuditRetentionStore.LOCK_KEY);held.countDown();
                try {assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException failure) {Thread.currentThread().interrupt();throw new IllegalStateException(failure);}return true;
            }));
            try {assertTrue(held.await(10,TimeUnit.SECONDS));assertFalse(retention.runScheduled());assertEquals(0,count("AUDIT_CLEANUP"));}
            finally {release.countDown();}
            assertTrue(holder.get(10,TimeUnit.SECONDS));
        }
    }
    @Test void exportAuditFailureIsBestEffortAndDisconnectDoesNotAuditSuccess() throws Exception {
        var marker=new IllegalStateException("Injected export audit failure");
        doAnswer(call->{call.callRealMethod();throw marker;}).when(audit).appendAuditMaintenance(any(),any(),any(),eq(AuditService.Event.AUDIT_EXPORT),any());
        assertTrue(export(all()).startsWith("\ufeff"));assertEquals(0,count("AUDIT_EXPORT"));
        doCallRealMethod().when(audit).appendAuditMaintenance(any(),any(),any(),eq(AuditService.Event.AUDIT_EXPORT),any());
        assertThrows(IOException.class,()->exports.stream(root.principal(),all(),new OutputStream(){public void write(int value)throws IOException{throw new IOException("Disconnected");}}));
        assertEquals(0,count("AUDIT_EXPORT"));
    }
    @Test void ordinaryUserAndRevokedSessionCannotExport() {
        var ordinary=account();var bytes=new ByteArrayOutputStream();
        assertEquals(403,assertThrows(AuthFailure.class,()->exports.stream(ordinary.principal(),all(),bytes)).status());
        auth.logout(ordinary.principal());assertEquals(401,assertThrows(AuthFailure.class,()->exports.stream(ordinary.principal(),all(),bytes)).status());
        assertEquals(0,bytes.size());assertEquals(0,count("AUDIT_EXPORT"));
    }
    @Test void onlyReviewedAuditV7IsAcceptedByParticipationGuard() {
        assertFalse(tx(()->facts.forAccount(root.principal().accountId())).historical());
        new TransactionTemplate(manager).executeWithoutResult(status->{
            jdbc.update("UPDATE flyway_schema_history SET script='V7__unknown.sql' WHERE version='7'");
            try {assertEquals(503,assertThrows(AuthFailure.class,()->facts.forAccount(root.principal().accountId())).status());}
            finally {status.setRollbackOnly();}
        });
    }
    @Test void migrationV6ToV7PreservesAuditAndAddsEmptyMetadata() {
        identityGuard();String upgrade=SCHEMA+"_upgrade";UUID id=UUID.randomUUID();
        try {
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(upgrade).defaultSchema(upgrade).target("6").load().migrate();
            jdbc.update("INSERT INTO "+upgrade+".audit_event(id,occurred_at,actor_kind,event_type,target_id) VALUES (?,?,'SYSTEM','LOGIN_SUCCEEDED',?)",id,Timestamp.from(clock.instant()),UUID.randomUUID());
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(upgrade).defaultSchema(upgrade).target("7").load().migrate();
            assertEquals("{}",jdbc.queryForObject("SELECT metadata::text FROM "+upgrade+".audit_event WHERE id=?",String.class,id));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM "+upgrade+".audit_cleanup_control",Integer.class));
        } finally {assertTrue(upgrade.matches("it_audit_retention_[0-9a-f]{32}_upgrade"));jdbc.execute("DROP SCHEMA IF EXISTS \""+upgrade+"\" CASCADE");}
    }
    static List<List<String>> csv(String value) {
        var result=new ArrayList<List<String>>();var row=new ArrayList<String>();var cell=new StringBuilder();boolean quoted=false;
        for(int i=value.startsWith("\ufeff")?1:0;i<value.length();i++) {
            char ch=value.charAt(i);
            if(ch=='"') {if(quoted && i+1<value.length() && value.charAt(i+1)=='"') {cell.append('"');i++;}else quoted=!quoted;}
            else if(!quoted && ch==';') {row.add(cell.toString());cell.setLength(0);}
            else if(!quoted && ch=='\r' && i+1<value.length() && value.charAt(i+1)=='\n') {row.add(cell.toString());result.add(row);row=new ArrayList<>();cell.setLength(0);i++;}
            else cell.append(ch);
        }
        assertFalse(quoted);return result;
    }
    @AfterAll void cleanupOwnSchema() {identityGuard();jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");}
}
