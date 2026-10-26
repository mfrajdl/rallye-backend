package cz.mh.rallye.rallye_backend.identity;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import cz.mh.rallye.rallye_backend.crews.application.*;
import cz.mh.rallye.rallye_backend.crews.PhotoFilesTest;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import cz.mh.rallye.rallye_backend.audit.persistence.AuditStore;
import cz.mh.rallye.rallye_backend.shared.persistence.CommandStore;
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
class CrewsIT {
    static final String SCHEMA="it_crews_"+UUID.randomUUID().toString().replace("-","");
    static final Path PHOTOS=temporaryDirectory();
    static Path temporaryDirectory() {
        try { return Files.createTempDirectory("it_crew_photos_").toAbsolutePath(); }
        catch(IOException failure) {throw new UncheckedIOException(failure);}
    }
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry properties) {
        properties.add("spring.flyway.schemas",()->SCHEMA);properties.add("spring.flyway.default-schema",()->SCHEMA);
        properties.add("spring.datasource.hikari.connection-init-sql",()->"SET search_path TO "+SCHEMA);
        properties.add("rallye.crews.photo-directory",()->PHOTOS.toString());
    }
    @Autowired CrewService crews;
    @Autowired AuthService auth;
    @Autowired AdminService administration;
    @Autowired IdentityIT.TestMail mail;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager manager;
    @Autowired javax.sql.DataSource dataSource;
    // Spy below the MANDATORY service proxy: stubbing itself must not invoke a transactional method.
    @MockitoSpyBean AuditStore audit;
    @MockitoSpyBean CommandStore commands;
    AuthService.LoginSession root,owner,stranger;
    AuthService.LoginSession account() {
        String email=UUID.randomUUID()+"@example.test";
        auth.register(email,"test-password","Captain");auth.verifyEmail(mail.token(email,"EMAIL_VERIFICATION"));
        return auth.login(email,"test-password",true);
    }
    @BeforeAll void bootstrap() throws Exception {
        root=account();String sql=AdministrationIT.bootstrapBody(root.principal().accountId());
        new TransactionTemplate(manager).executeWithoutResult(status->jdbc.execute(sql));
    }
    @BeforeEach void fixtures() {owner=account();stranger=account();}
    CrewService.View create() {return crews.create(owner.principal(),UUID.randomUUID(),"Crew","RZ","Description",false,null);}
    long auditCount(UUID id) {return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE target_id=?",Long.class,id);}
    void injectedFailure(Runnable operation,RuntimeException marker) {
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),
                "No outer test transaction: inspect committed state after the service completes");
        Throwable failure=assertThrows(RuntimeException.class,operation::run);
        while(failure.getCause()!=null) failure=failure.getCause();
        assertSame(marker,failure,"Must fail due to the injected error, not an unrelated database problem");
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
    }
    @Test void crudAndDuplicateNamesAndPlatesAreAllowed() {
        var crew=create();var duplicate=create();assertNotEquals(crew.id(),duplicate.id());
        assertEquals(2,crews.list(owner.principal(),false,null,false,0,50).items().size());
        assertEquals(crew,crews.detail(owner.principal(),crew.id()));
        var updated=crews.update(owner.principal(),crew.id(),0,"Changed","RZ",null);
        assertEquals(1,updated.version());assertEquals(2,auditCount(crew.id()));
        crews.delete(owner.principal(),crew.id(),1);
        assertEquals(404,assertThrows(AuthFailure.class,()->crews.detail(owner.principal(),crew.id())).status());
        assertEquals(3,auditCount(crew.id()));
    }
    @Test void accessChecksCoverOwnerStrangerAdministratorAndOrphans() {
        var crew=create();
        assertEquals(404,assertThrows(AuthFailure.class,()->crews.detail(stranger.principal(),crew.id())).status());
        assertEquals(404,assertThrows(AuthFailure.class,()->crews.update(stranger.principal(),crew.id(),0,"x",null,null)).status());
        assertEquals(403,assertThrows(AuthFailure.class,()->crews.list(owner.principal(),true,null,false,0,50)).status());
        assertEquals(403,assertThrows(AuthFailure.class,()->crews.create(owner.principal(),UUID.randomUUID(),"x",null,null,true,null)).status());
        var orphan=crews.create(root.principal(),UUID.randomUUID(),"Orphan",null,null,true,null);
        assertNull(orphan.captainAccountId());
        assertTrue(crews.list(root.principal(),true,null,true,0,100).items().stream().anyMatch(c->c.id().equals(orphan.id())));
        assertEquals(crew.id(),crews.detail(root.principal(),crew.id()).id());
        assertEquals(root.principal().accountId(),crews.captain(root.principal(),orphan.id(),0,root.principal().accountId(),UUID.randomUUID()).captainAccountId());
    }
    @Test void listSortsCaseInsensitivelyByNameThenIdBeforePagingInBothScopes() {
        var created=new ArrayList<CrewService.View>();
        for(String name:List.of("Zebra","beta","Alpha","BETA","alpha","Alpha"))
            created.add(crews.create(owner.principal(),UUID.randomUUID(),name,null,null,false,null));
        var expected=created.stream().sorted(Comparator.comparing((CrewService.View c)->c.name().toLowerCase(Locale.ROOT))
                .thenComparing(c->c.id().toString())).map(CrewService.View::id).toList();
        var own=new ArrayList<UUID>();var filtered=new ArrayList<UUID>();
        for(int page=0;page<3;page++) {
            var mine=crews.list(owner.principal(),false,null,false,page,2);
            var all=crews.list(root.principal(),true,owner.principal().accountId(),false,page,2);
            assertEquals(page,mine.page());assertEquals(2,mine.size());assertEquals(page<2,mine.hasMore());
            assertEquals(page<2,all.hasMore());
            own.addAll(mine.items().stream().map(CrewService.ListItem::id).toList());
            filtered.addAll(all.items().stream().map(CrewService.ListItem::id).toList());
        }
        assertEquals(expected,own);assertEquals(expected,filtered);
        var unfiltered=new ArrayList<UUID>();
        for(int page=0;;page++) {
            var result=crews.list(root.principal(),true,null,false,page,100);
            unfiltered.addAll(result.items().stream().map(CrewService.ListItem::id).filter(expected::contains).toList());
            if(!result.hasMore())break;
        }
        assertEquals(expected,unfiltered);
    }
    @Test void listReadsCurrentCaptainNameWithoutChangingCrewOrDetail() {
        var first=create();var second=create();
        var before=crews.list(owner.principal(),false,null,false,0,50);
        assertEquals(2,before.items().size());
        assertTrue(before.items().stream().allMatch(c->"Captain".equals(c.captainDisplayName())));
        auth.changeDisplayName(owner.principal(),"Jana Nováková");
        for(var result:List.of(crews.list(owner.principal(),false,null,false,0,50),
                crews.list(root.principal(),true,owner.principal().accountId(),false,0,50))) {
            assertEquals(2,result.items().size());
            assertTrue(result.items().stream().allMatch(c->"Jana Nováková".equals(c.captainDisplayName())
                    &&owner.principal().accountId().equals(c.captainAccountId())&&c.version()==0));
        }
        assertEquals(first,crews.detail(owner.principal(),first.id()));
        assertEquals(second,crews.detail(owner.principal(),second.id()));
    }
    @Test void listPreservesOwnershipCaptainAndOrphanFilters() {
        var mine=create();
        var other=crews.create(stranger.principal(),UUID.randomUUID(),"Other",null,null,false,null);
        var orphan=crews.create(root.principal(),UUID.randomUUID(),"Orphan list",null,null,true,null);
        assertEquals(List.of(mine.id()),crews.list(owner.principal(),false,null,false,0,50).items().stream()
                .map(CrewService.ListItem::id).toList());
        assertEquals(List.of(other.id()),crews.list(root.principal(),true,stranger.principal().accountId(),false,0,50)
                .items().stream().map(CrewService.ListItem::id).toList());
        var orphans=crews.list(root.principal(),true,null,true,0,100).items();
        assertTrue(orphans.stream().anyMatch(c->c.id().equals(orphan.id())));
        assertTrue(orphans.stream().allMatch(c->c.captainAccountId()==null&&c.captainDisplayName()==null));
        assertEquals(403,assertThrows(AuthFailure.class,()->crews.list(owner.principal(),true,null,false,0,50)).status());
        assertEquals(400,assertThrows(AuthFailure.class,()->crews.list(owner.principal(),false,
                stranger.principal().accountId(),false,0,50)).status());
    }
    @Test void captainTargetsMustBeActiveVerifiedAndDifferentAndOldOwnerImmediatelyLosesAccess() {
        var crew=create();UUID key=UUID.randomUUID();
        assertEquals("CAPTAIN_UNCHANGED",assertThrows(AuthFailure.class,()->crews.captain(root.principal(),crew.id(),0,owner.principal().accountId(),key)).code());
        assertEquals("CAPTAIN_UNAVAILABLE",assertThrows(AuthFailure.class,()->crews.captain(root.principal(),crew.id(),0,UUID.randomUUID(),key)).code());
        UUID target=stranger.principal().accountId();
        jdbc.update("UPDATE user_account SET email_verified_at=NULL WHERE id=?",target);
        assertEquals("CAPTAIN_UNAVAILABLE",assertThrows(AuthFailure.class,()->crews.captain(root.principal(),crew.id(),0,target,key)).code());
        jdbc.update("UPDATE user_account SET email_verified_at=now(),status='DISABLED' WHERE id=?",target);
        assertEquals("CAPTAIN_UNAVAILABLE",assertThrows(AuthFailure.class,()->crews.captain(root.principal(),crew.id(),0,target,key)).code());
        jdbc.update("UPDATE user_account SET status='ACTIVE' WHERE id=?",target);
        var changed=crews.captain(root.principal(),crew.id(),0,target,key);
        assertEquals(changed,crews.captain(root.principal(),crew.id(),0,target,key));
        assertEquals(404,assertThrows(AuthFailure.class,()->crews.detail(owner.principal(),crew.id())).status());
        assertEquals(target,crews.detail(stranger.principal(),crew.id()).captainAccountId());
        assertEquals(2,auditCount(crew.id()));
        assertNull(crews.captain(root.principal(),crew.id(),1,null,UUID.randomUUID()).captainAccountId());
    }
    @Test void createReplayConflictExpiryAndCrossOperationNamespace() {
        UUID key=UUID.randomUUID();
        var first=crews.create(owner.principal(),key,"Crew",null,null,false,null);
        assertEquals(first,crews.create(owner.principal(),key,"Crew",null,null,false,null));
        assertEquals(1,auditCount(first.id()));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AuthFailure.class,
                ()->crews.create(owner.principal(),key,"Different",null,null,false,null)).code());
        UUID adminKey=UUID.randomUUID();
        administration.changeRole(root.principal(),stranger.principal().accountId(),true,adminKey);
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AuthFailure.class,
                ()->crews.create(root.principal(),adminKey,"x",null,null,false,null)).code());
        UUID crewKey=UUID.randomUUID();crews.create(root.principal(),crewKey,"x",null,null,false,null);
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AuthFailure.class,
                ()->administration.changeRole(root.principal(),stranger.principal().accountId(),false,crewKey)).code());
        jdbc.update("UPDATE admin_role_command SET created_at=?,expires_at=? WHERE actor_id=? AND command_key=?",
                java.sql.Timestamp.from(clock.instant().minusSeconds(86401)),java.sql.Timestamp.from(clock.instant()),owner.principal().accountId(),key);
        assertNotEquals(first.id(),crews.create(owner.principal(),key,"New",null,null,false,null).id());
    }
    @Test void concurrentCreateWithSameKeyHasOneResultAndAudit() throws Exception {
        UUID key=UUID.randomUUID();var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<CrewService.View> operation=()->{barrier.await(5,TimeUnit.SECONDS);return crews.create(owner.principal(),key,"Crew",null,null,false,null);};
            var a=executor.submit(operation);var b=executor.submit(operation);
            var first=a.get(15,TimeUnit.SECONDS);assertEquals(first,b.get(15,TimeUnit.SECONDS));assertEquals(1,auditCount(first.id()));
        }
    }
    @Test void concurrentEditsWithSameVersionCannotBothSucceed() throws Exception {
        var crew=create();var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> operation=()->{barrier.await(5,TimeUnit.SECONDS);try {crews.update(owner.principal(),crew.id(),0,"Changed",null,null);return true;}
                catch(AuthFailure failure){assertEquals("CREW_VERSION_CONFLICT",failure.code());return false;}};
            var a=executor.submit(operation);var b=executor.submit(operation);
            assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));assertEquals(2,auditCount(crew.id()));
        }
    }
    @Test void photoAndCaptainUseOneVersionAndFilesArePrivate() throws Exception {
        var crew=create();
        var uploaded=crews.upload(owner.principal(),crew.id(),0,new ByteArrayInputStream(PhotoFilesTest.image("png",10,10)));
        assertEquals(1,uploaded.version());assertTrue(uploaded.hasPhoto());
        assertEquals(404,assertThrows(AuthFailure.class,()->crews.photo(stranger.principal(),crew.id())).status());
        assertEquals("image/png",crews.photo(root.principal(),crew.id()).contentType());
        assertEquals("CREW_VERSION_CONFLICT",assertThrows(AuthFailure.class,
                ()->crews.captain(root.principal(),crew.id(),0,null,UUID.randomUUID())).code());
        var transferred=crews.captain(root.principal(),crew.id(),1,stranger.principal().accountId(),UUID.randomUUID());
        assertEquals(2,transferred.version());
        assertEquals(404,assertThrows(AuthFailure.class,()->crews.photo(owner.principal(),crew.id())).status());
        assertFalse(crews.deletePhoto(stranger.principal(),crew.id(),2).hasPhoto());
    }
    @Test void failedAuditRollsBackCreationAndDoesNotReserveKey() {
        UUID key=UUID.randomUUID();long count=jdbc.queryForObject("SELECT count(*) FROM crew",Long.class);
        long events=jdbc.queryForObject("SELECT count(*) FROM audit_event",Long.class);
        var marker=new IllegalStateException("Test audit failure");
        doAnswer(invocation->{invocation.callRealMethod();throw marker;}).when(audit).appendCrew(any(),any(),any(),eq(AuditService.Event.CREW_CREATED),any(),any(),any());
        try {injectedFailure(()->crews.create(owner.principal(),key,"x",null,null,false,null),marker);}
        finally {reset(audit);}
        assertEquals(count,jdbc.queryForObject("SELECT count(*) FROM crew",Long.class));
        assertEquals(events,jdbc.queryForObject("SELECT count(*) FROM audit_event",Long.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM admin_role_command WHERE command_key=?",Long.class,key));
        assertNotNull(crews.create(owner.principal(),key,"x",null,null,false,null));
    }
    @Test void failedCommandWriteRollsBackCrewAndAudit() {
        long count=jdbc.queryForObject("SELECT count(*) FROM crew",Long.class);
        long events=jdbc.queryForObject("SELECT count(*) FROM audit_event",Long.class);
        UUID key=UUID.randomUUID();var marker=new IllegalStateException("Test command failure");
        doAnswer(invocation->{invocation.callRealMethod();throw marker;}).when(commands).save(any(),eq(key),any(),any(),any(),any(),any());
        try {injectedFailure(()->crews.create(owner.principal(),key,"x",null,null,false,null),marker);}
        finally {reset(commands);}
        assertEquals(count,jdbc.queryForObject("SELECT count(*) FROM crew",Long.class));
        assertEquals(events,jdbc.queryForObject("SELECT count(*) FROM audit_event",Long.class));
        assertNotNull(crews.create(owner.principal(),key,"x",null,null,false,null));
    }
    @Test void failedPhotoAuditKeepsOriginalFileAndVersionAndRemovesNewFile() throws Exception {
        var crew=create();byte[] png=PhotoFilesTest.image("png",10,10);
        crews.upload(owner.principal(),crew.id(),0,new ByteArrayInputStream(png));
        byte[] before=crews.photo(owner.principal(),crew.id()).bytes();
        long events=auditCount(crew.id());
        long filesBefore;try(var files=Files.list(PHOTOS)){filesBefore=files.count();}
        var marker=new IllegalStateException("Test photo audit failure");
        byte[] replacement=PhotoFilesTest.image("jpeg",12,12);
        doAnswer(invocation->{invocation.callRealMethod();throw marker;}).when(audit).appendCrew(any(),any(),any(),eq(AuditService.Event.CREW_PHOTO_CHANGED),any(),any(),any());
        try {injectedFailure(()->crews.upload(owner.principal(),crew.id(),1,new ByteArrayInputStream(replacement)),marker);}
        finally {reset(audit);}
        assertEquals(1,crews.detail(owner.principal(),crew.id()).version());
        assertEquals(events,auditCount(crew.id()));
        assertArrayEquals(before,crews.photo(owner.principal(),crew.id()).bytes());
        try(var files=Files.list(PHOTOS)){assertEquals(filesBefore,files.count());}
    }
    @Test void failedCaptainAndDeleteAuditDoNotChangeCrewOrVersion() {
        var crew=create();
        UUID key=UUID.randomUUID();
        var marker=new IllegalStateException("Test audit failure");
        doAnswer(invocation->{invocation.callRealMethod();throw marker;}).when(audit).appendCrew(any(),any(),any(),any(),eq(crew.id()),any(),any());
        try {
            injectedFailure(()->crews.captain(root.principal(),crew.id(),0,null,key),marker);
            assertEquals(crew,crews.detail(owner.principal(),crew.id()));
            assertEquals(1,auditCount(crew.id()));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM admin_role_command WHERE command_key=?",Long.class,key));
            injectedFailure(()->crews.delete(owner.principal(),crew.id(),0),marker);
        } finally {reset(audit);}
        assertEquals(crew,crews.detail(owner.principal(),crew.id()));assertEquals(1,auditCount(crew.id()));
        assertNull(crews.captain(root.principal(),crew.id(),0,null,key).captainAccountId());
        assertEquals(2,auditCount(crew.id()));
    }
    @Test void cleanupPreservesReferencedFilesAndRequiresCurrentAdmin() throws Exception {
        var crew=create();crews.upload(owner.principal(),crew.id(),0,new ByteArrayInputStream(PhotoFilesTest.image("png",1,1)));
        UUID photo=jdbc.queryForObject("SELECT photo_id FROM crew WHERE id=?",UUID.class,crew.id());
        Path orphan=PHOTOS.resolve(UUID.randomUUID()+".photo");Files.write(orphan,new byte[]{1});
        FileTime old=FileTime.from(clock.instant().minus(Duration.ofHours(25)));
        Files.setLastModifiedTime(orphan,old);Files.setLastModifiedTime(PHOTOS.resolve(photo+".photo"),old);
        assertEquals(403,assertThrows(AuthFailure.class,()->crews.cleanup(owner.principal())).status());
        crews.cleanup(root.principal());assertFalse(Files.exists(orphan));assertTrue(Files.exists(PHOTOS.resolve(photo+".photo")));
    }
    @Test void upgradingV2PreservesExistingAdminKeysAndExpiry() {
        String schema=SCHEMA+"_upgrade";
        assertTrue(schema.matches("it_crews_[0-9a-f]{32}_upgrade"));
        UUID actor=UUID.randomUUID(),key=UUID.randomUUID(),target=UUID.randomUUID();
        var created=java.sql.Timestamp.from(clock.instant());var expires=java.sql.Timestamp.from(clock.instant().plusSeconds(86400));
        try {
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("2").load().migrate();
            jdbc.update("INSERT INTO "+schema+".admin_role_command(actor_id,command_key,target_id,grant_role,created_at,expires_at) VALUES (?,?,?,true,?,?)",
                    actor,key,target,created,expires);
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("3").load().migrate();
            var row=jdbc.queryForMap("SELECT * FROM "+schema+".admin_role_command WHERE actor_id=? AND command_key=?",actor,key);
            assertEquals(target,row.get("target_id"));assertEquals(true,row.get("grant_role"));
            assertEquals("ADMIN_ROLE",row.get("operation"));assertEquals(created,row.get("created_at"));assertEquals(expires,row.get("expires_at"));
        } finally {
            assertEquals("rallye_test",jdbc.queryForObject("SELECT current_database()",String.class));
            assertEquals("rallye_test",jdbc.queryForObject("SELECT current_user",String.class));
            jdbc.execute("DROP SCHEMA IF EXISTS \""+schema+"\" CASCADE");
        }
    }
    @AfterAll void cleanupOwnSchemaAndDirectory() throws Exception {
        assertTrue(SCHEMA.matches("it_crews_[0-9a-f]{32}"));
        assertEquals("rallye_test",jdbc.queryForObject("SELECT current_database()",String.class));
        assertEquals("rallye_test",jdbc.queryForObject("SELECT current_user",String.class));
        jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");
        assertTrue(PHOTOS.getFileName().toString().startsWith("it_crew_photos_"));
        try(var paths=Files.walk(PHOTOS)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);}
    }
}
