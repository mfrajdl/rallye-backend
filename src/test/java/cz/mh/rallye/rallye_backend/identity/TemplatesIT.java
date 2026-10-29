package cz.mh.rallye.rallye_backend.identity;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import cz.mh.rallye.rallye_backend.templates.application.TemplateReadiness;
import cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration;
import cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import cz.mh.rallye.rallye_backend.support.TestDatabaseInitializer;
import cz.mh.rallye.rallye_backend.templates.application.TemplateService;
import cz.mh.rallye.rallye_backend.audit.application.AuditService.Event;
import cz.mh.rallye.rallye_backend.audit.persistence.AuditStore;
import cz.mh.rallye.rallye_backend.shared.persistence.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@ActiveProfiles("it")
@ContextConfiguration(initializers=TestDatabaseInitializer.class)
@Import(IdentityIT.Fixtures.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemplatesIT {
    static final java.nio.file.Path IMAGE_DIR=imageDirectory();
    static java.nio.file.Path imageDirectory(){try{return java.nio.file.Files.createTempDirectory("it_template_images_");}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
    static final String SCHEMA="it_templates_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void schema(DynamicPropertyRegistry p){p.add("rallye.simulation.file-directory",()->IMAGE_DIR.resolve("simulations").toString());p.add("rallye.templates.image-directory",()->IMAGE_DIR.toString());p.add("spring.flyway.schemas",()->SCHEMA);p.add("spring.flyway.default-schema",()->SCHEMA);p.add("spring.datasource.hikari.connection-init-sql",()->"SET search_path TO "+SCHEMA);}
    @Autowired cz.mh.rallye.rallye_backend.templates.application.TemplateImageService images;
    @Autowired cz.mh.rallye.rallye_backend.templates.application.TemplateDuplicationService copies;
    @Autowired cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore templateStore;
    @Autowired cz.mh.rallye.rallye_backend.templates.application.TemplateFileService files;
    @Autowired cz.mh.rallye.rallye_backend.templates.application.TemplateTransferService transfer;
    @Autowired cz.mh.rallye.rallye_backend.simulation.application.SimulationService simulator;
    @Autowired TemplateService service;@Autowired AuthService auth;@Autowired JdbcTemplate jdbc;
    @Autowired AccountStore accounts;@Autowired PlatformTransactionManager manager;@Autowired MutableClock clock;
    @Autowired PreRaceAccountParticipationFacts facts;@Autowired CommandCleanupStore cleanup;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @MockitoSpyBean AuditStore audit;@MockitoSpyBean CommandStore commands;
    @Autowired cz.mh.rallye.rallye_backend.audit.application.AuditService fixtureAudit;
    record Actor(UUID id,String secret,AuthService.Principal principal,String email) {}
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    Actor root;
    TransactionTemplate tx(){return new TransactionTemplate(manager);}
    Actor actor(){
        UUID id=UUID.randomUUID();String email=id+"@example.test",secret=new SecretTokens().create();
        return tx().execute(status->{
            jdbc.queryForObject("SELECT id FROM admin_control WHERE id=1 FOR UPDATE",Integer.class);var at=Timestamp.from(clock.instant());
            jdbc.update("INSERT INTO user_account(id,email,normalized_email,display_name,status,created_at,updated_at,email_verified_at) VALUES (?,?,?,'Test','ACTIVE',?,?,?)",id,email,email,at,at,at);
            jdbc.update("INSERT INTO user_identity(id,account_id,provider,issuer,subject,password_hash,created_at) VALUES (?,?,'LOCAL','local',?,'test-only-hash',?)",UUID.randomUUID(),id,id.toString(),at);
            UUID session=accounts.insertSession(id,new SecretTokens().hash(secret),false,clock.instant(),clock.instant().plusSeconds(86400));
            return new Actor(id,secret,new AuthService.Principal(id,session,false),email);
        });
    }
    @BeforeAll void prepare()throws Exception{root=actor();String sql=AdministrationIT.bootstrapBody(root.id());tx().executeWithoutResult(s->jdbc.execute(sql));}
    @AfterAll void dropOnlyPrivateTestSchema()throws Exception{assertTrue(IMAGE_DIR.getFileName().toString().startsWith("it_template_images_"));try(var files=java.nio.file.Files.walk(IMAGE_DIR)){for(var file:files.sorted(java.util.Comparator.reverseOrder()).filter(path->!path.equals(IMAGE_DIR)).toList())java.nio.file.Files.delete(file);}java.nio.file.Files.delete(IMAGE_DIR);assertTrue(SCHEMA.matches("it_templates_[0-9a-f]{32}"));assertEquals("rallye_test",jdbc.queryForObject("SELECT current_database()",String.class));assertEquals("rallye_test",jdbc.queryForObject("SELECT current_user",String.class));jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");}
    UUID create(){return service.create(root.principal(),UUID.randomUUID(),"Template-"+UUID.randomUUID(),null,-3).id();}
    long count(String sql,Object... args){return jdbc.queryForObject(sql,Long.class,args);}
    @Test void crudAndOrderStayContiguousWithSingleAggregateVersion(){
        UUID id=create();assertEquals("DRAFT",service.detail(root.principal(),id).status());
        var first=service.createStage(root.principal(),id,0,UUID.randomUUID(),null,null,null);
        var second=service.createStage(root.principal(),id,1,UUID.randomUUID(),"Second",0L,0L);
        assertEquals(1,first.version());assertEquals(2,second.version());
        service.updateStage(root.principal(),id,first.id(),2,"First",120000L,10L);
        var moved=service.order(root.principal(),id,3,List.of(second.id(),first.id()));assertEquals(4,moved.version());assertEquals(List.of(second.id(),first.id()),moved.stages().stream().map(TemplateService.StageView::id).toList());
        var deleted=service.deleteStage(root.principal(),id,second.id(),4);assertEquals(5,deleted.version());assertEquals(1,deleted.stages().getFirst().sequenceNo());
        service.delete(root.principal(),id,5);assertEquals(0,count("SELECT count(*) FROM template_stage WHERE race_template_id=?",id));assertEquals("TEMPLATE_NOT_FOUND",assertThrows(AuthFailure.class,()->service.detail(root.principal(),id)).code());
    }
    @Test void readyNoOpsAndMetadataPreserveStateButSubstantialChangesInvalidate(){
        UUID id=create();UUID stage=service.createStage(root.principal(),id,0,UUID.randomUUID(),null,null,0L).id();
        jdbc.update("UPDATE race_template SET status='READY' WHERE id=?",id); // Simulate a future 4b-ready fixture, never expose a publish API in 4a.
        var before=service.detail(root.principal(),id);long audits=count("SELECT count(*) FROM audit_event WHERE target_id IN (?,?)",id,stage);
        assertEquals(before,service.update(root.principal(),id,1," "+before.name()+" ",null,-3));
        assertEquals(before,service.updateStage(root.principal(),id,stage,1,null,null,0L));
        assertEquals(before,service.order(root.principal(),id,1,List.of(stage)));assertEquals(audits,count("SELECT count(*) FROM audit_event WHERE target_id IN (?,?)",id,stage));
        var renamed=service.update(root.principal(),id,1,"Renamed","Changed description",-3);assertEquals("READY",renamed.status());assertEquals(2,renamed.version());
        var changed=service.update(root.principal(),id,2,"Renamed","Changed description",Long.MIN_VALUE);assertEquals("DRAFT",changed.status());assertEquals(Long.MIN_VALUE,changed.skipPenaltyPoints());
        jdbc.update("UPDATE race_template SET status='READY' WHERE id=?",id);
        assertEquals("DRAFT",service.updateStage(root.principal(),id,stage,3,"Station",null,0L).status());
    }
    @Test void addingDeletingAndReorderingInvalidateReadyButManualDraftCannotPublish(){
        UUID id=create();var a=service.createStage(root.principal(),id,0,UUID.randomUUID(),null,null,null);
        jdbc.update("UPDATE race_template SET status='READY' WHERE id=?",id);var b=service.createStage(root.principal(),id,1,UUID.randomUUID(),null,null,null);assertEquals("DRAFT",service.detail(root.principal(),id).status());
        jdbc.update("UPDATE race_template SET status='READY' WHERE id=?",id);assertEquals("DRAFT",service.order(root.principal(),id,2,List.of(b.id(),a.id())).status());
        jdbc.update("UPDATE race_template SET status='READY' WHERE id=?",id);assertEquals("DRAFT",service.deleteStage(root.principal(),id,b.id(),3).status());
        jdbc.update("UPDATE race_template SET status='READY' WHERE id=?",id);assertEquals("DRAFT",service.state(root.principal(),id,4,"DRAFT").status());
        assertThrows(cz.mh.rallye.rallye_backend.templates.application.TemplateReadiness.NotReady.class,()->service.state(root.principal(),id,5,"READY"));
    }
    @Test void replayAndChangedParametersUseSharedIdempotencyNamespace(){
        UUID key=UUID.randomUUID();var created=service.create(root.principal(),key,"Replay",null,-5);assertEquals(created,service.create(root.principal(),key,"Replay",null,-5));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AuthFailure.class,()->service.create(root.principal(),key,"Other",null,-5)).code());
        UUID stageKey=UUID.randomUUID();var stage=service.createStage(root.principal(),created.id(),0,stageKey,null,null,0L);
        assertEquals(stage,service.createStage(root.principal(),created.id(),0,stageKey,null,null,0L));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AuthFailure.class,()->service.createStage(root.principal(),created.id(),1,stageKey,null,null,0L)).code());
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AuthFailure.class,()->service.createStage(root.principal(),created.id(),1,key,null,null,0L)).code());
        assertEquals(1,count("SELECT count(*) FROM audit_event WHERE target_id=? AND event_type='TEMPLATE_STAGE_CREATED'",stage.id()));
    }
    List<String> race(Callable<String> a,Callable<String> b)throws Exception{var barrier=new CyclicBarrier(2);try(var pool=Executors.newFixedThreadPool(2)){var first=pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return a.call();});var second=pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return b.call();});return List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS));}}
    String update(UUID id,String name){try{service.update(root.principal(),id,0,name,null,-3);return "OK";}catch(AuthFailure failure){return failure.code();}}
    @Test void concurrentVersionWritersHaveOneWinner()throws Exception{UUID id=create();var results=race(()->update(id,"A"),()->update(id,"B"));assertEquals(1,results.stream().filter("OK"::equals).count());assertEquals(1,results.stream().filter("TEMPLATE_VERSION_CONFLICT"::equals).count());assertEquals(1,service.detail(root.principal(),id).version());}
    @Test void concurrentSameKeyCreatesOneTemplateAndOneAudit()throws Exception{UUID key=UUID.randomUUID();var ids=race(()->service.create(root.principal(),key,"Race",null,0).id().toString(),()->service.create(root.principal(),key,"Race",null,0).id().toString());assertEquals(ids.getFirst(),ids.getLast());assertEquals(1,count("SELECT count(*) FROM audit_event WHERE target_id=? AND event_type='TEMPLATE_CREATED'",UUID.fromString(ids.getFirst())));}
    @Test void concurrentAppendsCannotCreateGaps()throws Exception{UUID id=create();Callable<String> add=()->{try{service.createStage(root.principal(),id,0,UUID.randomUUID(),null,null,null);return "OK";}catch(AuthFailure f){return f.code();}};var results=race(add,add);assertEquals(1,results.stream().filter("OK"::equals).count());assertEquals(1,service.detail(root.principal(),id).stages().size());assertEquals(1,service.detail(root.principal(),id).stages().getFirst().sequenceNo());}
    void failedAudit(Event event,UUID target,Runnable operation){
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        long before=count("SELECT count(*) FROM audit_event WHERE actor_id=? AND event_type=? AND target_id=?",root.id(),event.name(),target);
        // Fixture creation may already have audited this same event/target. Clear only Mockito's
        // call history, not the committed audit rows or existing stubbing.
        clearInvocations(audit);
        var injected=new IllegalStateException("Simulated template audit failure");
        doAnswer(call->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            call.callRealMethod();
            assertEquals(before+1,count("SELECT count(*) FROM audit_event WHERE actor_id=? AND event_type=? AND target_id=?",root.id(),event.name(),target));
            throw injected;
        }).when(audit).appendTemplate(any(),any(),eq(root.id()),eq(event),eq(target),anyBoolean());
        try{
            var failure=assertThrows(DataAccessException.class,operation::run);
            assertSame(injected,failure.getMostSpecificCause());
            verify(audit,times(1)).appendTemplate(any(),any(),eq(root.id()),eq(event),eq(target),anyBoolean());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(before,count("SELECT count(*) FROM audit_event WHERE actor_id=? AND event_type=? AND target_id=?",root.id(),event.name(),target));
        }finally{reset(audit);}
    }
    @Test void auditFailureRollsBackTemplateUpdateReorderAndDeletions(){
        UUID id=create();var a=service.createStage(root.principal(),id,0,UUID.randomUUID(),"A",null,null);var b=service.createStage(root.principal(),id,1,UUID.randomUUID(),"B",null,null);var before=service.detail(root.principal(),id);long auditCount=count("SELECT count(*) FROM audit_event");
        failedAudit(Event.TEMPLATE_UPDATED,id,()->service.update(root.principal(),id,2,"Changed",null,5));assertEquals(before,service.detail(root.principal(),id));
        failedAudit(Event.TEMPLATE_REORDERED,id,()->service.order(root.principal(),id,2,List.of(b.id(),a.id())));assertEquals(before,service.detail(root.principal(),id));
        failedAudit(Event.TEMPLATE_STAGE_UPDATED,a.id(),()->service.updateStage(root.principal(),id,a.id(),2,"Changed",1L,2L));assertEquals(before,service.detail(root.principal(),id));
        failedAudit(Event.TEMPLATE_STAGE_DELETED,a.id(),()->service.deleteStage(root.principal(),id,a.id(),2));assertEquals(before,service.detail(root.principal(),id));
        failedAudit(Event.TEMPLATE_DELETED,id,()->service.delete(root.principal(),id,2));assertEquals(before,service.detail(root.principal(),id));assertEquals(auditCount,count("SELECT count(*) FROM audit_event"));
    }
    @Test void failedCreationDoesNotReserveIdempotencyKey(){
        UUID key=UUID.randomUUID();var injected=new IllegalStateException("Simulated audit failure");
        long templates=count("SELECT count(*) FROM race_template"),events=count("SELECT count(*) FROM audit_event");
        doAnswer(call->{call.callRealMethod();throw injected;}).when(audit).appendTemplate(any(),any(),eq(root.id()),eq(Event.TEMPLATE_CREATED),any(),eq(false));
        try{assertSame(injected,assertThrows(DataAccessException.class,()->service.create(root.principal(),key,"Failed",null,0)).getMostSpecificCause());}finally{reset(audit);}
        assertEquals(templates,count("SELECT count(*) FROM race_template"));assertEquals(events,count("SELECT count(*) FROM audit_event"));assertEquals(0,count("SELECT count(*) FROM admin_role_command WHERE actor_id=? AND command_key=?",root.id(),key));
        assertNotNull(service.create(root.principal(),key,"Failed",null,0));
    }
    @Test void idempotencyFailureRollsBackStageVersionAndAudit(){
        UUID id=create(),key=UUID.randomUUID();var before=service.detail(root.principal(),id);long events=count("SELECT count(*) FROM audit_event");var injected=new IllegalStateException("Simulated command failure");
        doAnswer(call->{call.callRealMethod();throw injected;}).when(commands).save(eq(root.id()),eq(key),any(),eq("TEMPLATE_STAGE_CREATE"),anyString(),anyString(),any());
        try{assertSame(injected,assertThrows(DataAccessException.class,()->service.createStage(root.principal(),id,0,key,null,null,null)).getMostSpecificCause());}finally{reset(commands);}
        assertEquals(before,service.detail(root.principal(),id));assertEquals(events,count("SELECT count(*) FROM audit_event"));assertEquals(0,count("SELECT count(*) FROM admin_role_command WHERE command_key=?",key));assertNotNull(service.createStage(root.principal(),id,0,key,null,null,null));
    }
    void sqlState(String state,Runnable operation){var error=assertThrows(RuntimeException.class,operation::run);Throwable cause=error;while(cause!=null && !(cause instanceof SQLException))cause=cause.getCause();assertNotNull(cause,"Must fail due to the intended database invariant");assertEquals(state,((SQLException)cause).getSQLState());}
    @Test void databaseRejectsGapsDuplicatesNegativeMaximumAndNegativeDuration(){
        UUID id=create();sqlState("23514",()->tx().executeWithoutResult(s->jdbc.update("INSERT INTO template_stage(id,race_template_id,sequence_no) VALUES (?,?,2)",UUID.randomUUID(),id)));
        var a=service.createStage(root.principal(),id,0,UUID.randomUUID(),null,null,0L);
        sqlState("23505",()->tx().executeWithoutResult(s->jdbc.update("INSERT INTO template_stage(id,race_template_id,sequence_no) VALUES (?,?,1),(?,?,3)",UUID.randomUUID(),id,UUID.randomUUID(),id)));
        sqlState("23514",()->jdbc.update("UPDATE template_stage SET max_penalty_points=-1 WHERE id=?",a.id()));sqlState("23514",()->jdbc.update("UPDATE template_stage SET expected_duration_ms=-1 WHERE id=?",a.id()));
        assertEquals(1,service.detail(root.principal(),id).stages().size());
    }
    @Test void invalidOrdersAndForeignStageNeverMutate(){UUID id=create(),other=create();var a=service.createStage(root.principal(),id,0,UUID.randomUUID(),null,null,null);var foreign=service.createStage(root.principal(),other,0,UUID.randomUUID(),null,null,null);var before=service.detail(root.principal(),id);for(var order:List.of(List.<UUID>of(),List.of(a.id(),a.id()),List.of(foreign.id())))assertEquals(400,assertThrows(AuthFailure.class,()->service.order(root.principal(),id,1,order)).status());assertEquals(404,assertThrows(AuthFailure.class,()->service.deleteStage(root.principal(),id,foreign.id(),1)).status());assertEquals(before,service.detail(root.principal(),id));}
    @Test void listingSearchAndPagingAreLiteralAndCaseInsensitive(){String marker="Search-"+UUID.randomUUID();for(int i=0;i<3;i++)service.create(root.principal(),UUID.randomUUID(),marker+"%!_"+i,null,0);var page=service.list(root.principal(),marker.toUpperCase()+"%!_","DRAFT",0,2);assertEquals(2,page.items().size());assertTrue(page.hasMore());var last=service.list(root.principal(),marker+"%!_",null,1,2);assertEquals(1,last.items().size());assertFalse(last.hasMore());assertTrue(page.items().stream().noneMatch(a->a.id().equals(last.items().getFirst().id())));assertTrue(service.list(root.principal(),marker,"READY",0,50).items().isEmpty());}
    @Test void readAndWriteRequireCurrentAdminAndActiveSession()throws Exception{
        var other=actor();UUID id=create();mvc.perform(get("/api/v1/race-templates/"+id)).andExpect(status().isUnauthorized());mvc.perform(get("/api/v1/race-templates/"+id).header("Authorization","Bearer "+other.secret())).andExpect(status().isForbidden());
        assertEquals(403,assertThrows(AuthFailure.class,()->service.create(other.principal(),UUID.randomUUID(),"Other",null,0)).status());
        tx().executeWithoutResult(s->jdbc.update("INSERT INTO user_account_role(account_id,role,granted_at) VALUES (?,'ADMIN',now())",other.id()));assertNotNull(service.detail(other.principal(),id));
        tx().executeWithoutResult(s->jdbc.update("DELETE FROM user_account_role WHERE account_id=?",other.id()));assertEquals(403,assertThrows(AuthFailure.class,()->service.detail(other.principal(),id)).status());
        auth.logout(other.principal());assertEquals(401,assertThrows(AuthFailure.class,()->service.detail(other.principal(),id)).status());
        mvc.perform(get("/api/v1/race-templates/"+id).header("Authorization","Bearer "+root.secret())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.stages").isArray());
    }
    @Test void commandPrivacyCleanupAndAuditRetentionUnderstandNewFormats(){
        var other=actor();tx().executeWithoutResult(s->jdbc.update("INSERT INTO user_account_role(account_id,role,granted_at) VALUES (?,'ADMIN',now())",other.id()));
        UUID retained=UUID.randomUUID();service.create(root.principal(),retained,"Retained",null,0);
        UUID key=UUID.randomUUID();var created=service.create(other.principal(),key,"Privacy",other.email(),-1);service.createStage(other.principal(),created.id(),0,UUID.randomUUID(),null,null,null);
        tx().executeWithoutResult(s->cleanup.prepareForAuditDeletion());
        int removed=tx().execute(s->cleanup.deleteRelated(other.id(),other.email()));assertEquals(2,removed);
        assertEquals(0,count("SELECT count(*) FROM admin_role_command WHERE actor_id=?",other.id()));assertNotNull(service.detail(root.principal(),created.id()));
        assertEquals(1,count("SELECT count(*) FROM admin_role_command WHERE actor_id=? AND command_key=?",root.id(),retained));
    }
    @Test void v16IsExplicitlyAcceptedAndWrongNameOrFutureVersionFailsClosed(){
        assertEquals(1,count("SELECT count(*) FROM flyway_schema_history WHERE version='16' AND script='V16__race_templates.sql' AND success"));
        tx().executeWithoutResult(s->assertFalse(facts.forAccount(root.id()).historical()));
        UUID id=create();
        tx().executeWithoutResult(s->{jdbc.update("UPDATE flyway_schema_history SET script='V16__unexpected.sql' WHERE version='16'");assertEquals(503,assertThrows(AuthFailure.class,()->facts.forAccount(root.id())).status());s.setRollbackOnly();});
        tx().executeWithoutResult(s->{int rank=jdbc.queryForObject("SELECT max(installed_rank)+1 FROM flyway_schema_history",Integer.class);jdbc.update("INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,installed_by,execution_time,success) VALUES (?,'23','Future','SQL','V23__future.sql',current_user,0,true)",rank);assertEquals(503,assertThrows(AuthFailure.class,()->service.delete(root.principal(),id,0)).status());s.setRollbackOnly();});
        assertNotNull(service.detail(root.principal(),id));
    }
    @Test void upgradeFromV15PreservesExistingAccountsAndCreatesEmptyTemplateModel(){
        String upgrade=SCHEMA+"_upgrade";
        assertEquals("rallye_test",jdbc.queryForObject("SELECT current_database()",String.class));
        assertEquals("rallye_test",jdbc.queryForObject("SELECT current_user",String.class));
        assertTrue(upgrade.matches("it_templates_[0-9a-f]{32}_upgrade"));
        try {
            org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas(upgrade).defaultSchema(upgrade).target("15").load().migrate();
            UUID account=UUID.randomUUID();
            jdbc.update("INSERT INTO "+upgrade+".user_account(id,email,normalized_email,display_name,status,created_at,updated_at) VALUES (?,?,?,'Pending','PENDING_EMAIL',now(),now())",account,account+"@example.test",account+"@example.test");
            org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas(upgrade).defaultSchema(upgrade).target("16").load().migrate();
            assertEquals(1,count("SELECT count(*) FROM "+upgrade+".user_account WHERE id=?",account));
            assertEquals(0,count("SELECT count(*) FROM "+upgrade+".race_template"));
            assertEquals(0,count("SELECT count(*) FROM "+upgrade+".template_stage"));
        } finally {jdbc.execute("DROP SCHEMA IF EXISTS "+upgrade+" CASCADE");}
    }

    record Configured(UUID template,UUID stage) {}
    /** Only the unconfigured starting fixture is shortened. Configuration, publication, Markdown
     * edits and uploads remain real service operations because this suite explicitly tests them.
     * Keep version 2 and all three audit events; only the fixture stage-create receipt is omitted.
     */
    Configured bareWithInstructions(){
        return tx().execute(s->{
            UUID id=create(),stage=UUID.randomUUID();var at=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            String text="Follow the plain text instructions.";
            cz.mh.rallye.rallye_backend.templates.application.InstructionsMarkdown.analyze(id,text);
            templateStore.insertStage(stage,id,1,"Station",0L,0L);templateStore.touch(id,at);
            fixtureAudit.template(root.id(),Event.TEMPLATE_STAGE_CREATED,stage,true);
            templateStore.instructionsMarkdown(stage,text);templateStore.references(id,stage,Set.of());templateStore.touch(id,at);
            fixtureAudit.template(root.id(),Event.TEMPLATE_STAGE_UPDATED,stage,true);
            return new Configured(id,stage);
        });
    }
    TemplateService.ConfigurationChange config(boolean gps,boolean code,String newCode,long rate){
        return new TemplateService.ConfigurationChange(gps?new Gps(true,90.0,-180.0,0.5,rate,rate):new Gps(false,null,null,null,null,null),
            new TemplateService.CodeChange(code,newCode,code?rate:null),new TimePenalty(true,60000L,rate),new Delayed(true,0L,rate));
    }
    Configured configured(boolean gps,boolean code){var fixture=bareWithInstructions();service.configure(root.principal(),fixture.template(),fixture.stage(),2,config(gps,code,code?" synthetic-code ":null,-3));return fixture;}
    // An internal fixture only needs the scalar version, not an authorized/rendered aggregate view.
    long version(UUID template){return jdbc.queryForObject("SELECT version FROM race_template WHERE id=?",Long.class,template);}
    long version(Configured fixture){return version(fixture.template());}
    @Test void startingFixturesKeepVersionsAuditAndIndependentRows(){
        var first=bareWithInstructions();var second=bareWithInstructions();
        assertNotEquals(first.template(),second.template());assertNotEquals(first.stage(),second.stage());
        var untouched=service.detail(root.principal(),second.template());
        for(var f:List.of(first,second)){
            var view=service.detail(root.principal(),f.template());assertEquals(2,view.version());assertEquals("DRAFT",view.status());
            assertEquals("Follow the plain text instructions.",view.stages().getFirst().instructionsMarkdown());
            assertEquals(3,count("SELECT count(*) FROM audit_event WHERE target_id IN (?,?)",f.template(),f.stage()));
            assertEquals(1,count("SELECT count(*) FROM audit_event WHERE target_id=? AND event_type='TEMPLATE_STAGE_UPDATED'",f.stage()));
        }
        service.updateStage(root.principal(),first.template(),first.stage(),2,"Changed",0L,0L,true,"Other instructions");
        assertEquals(untouched,service.detail(root.principal(),second.template()));
    }
    String storedCode(UUID stage){return jdbc.queryForObject("SELECT code_value FROM template_stage WHERE id=?",String.class,stage);}
    @Test void incompleteReadinessIsReadOnlyAndReturnsFieldErrors(){
        UUID id=create();UUID stage=service.createStage(root.principal(),id,0,UUID.randomUUID(),null,null,null).id();
        var before=service.detail(root.principal(),id);long events=count("SELECT count(*) FROM audit_event");
        var report=service.readiness(root.principal(),id);assertFalse(report.ready());assertEquals(1,report.version());assertTrue(report.errors().stream().anyMatch(e->stage.equals(e.stageId()) && e.field().equals("instructionsMarkdown")));
        var rejected=assertThrows(TemplateReadiness.NotReady.class,()->service.state(root.principal(),id,1,"READY"));assertEquals(report,rejected.report());
        assertEquals(before,service.detail(root.principal(),id));assertEquals(events,count("SELECT count(*) FROM audit_event"));
    }
    @ParameterizedTest @CsvSource({"true,false","false,true","true,true"})
    void gpsCodeAndBothChecksCanBecomeReady(boolean gps,boolean code){
        var f=configured(gps,code);assertTrue(service.readiness(root.principal(),f.template()).ready());
        var view=service.state(root.principal(),f.template(),3,"READY");assertEquals("READY",view.status());assertEquals(4,view.version());
        assertEquals(gps,view.stages().getFirst().gps().enabled());assertEquals(code,view.stages().getFirst().code().enabled());assertEquals(1,count("SELECT count(*) FROM audit_event WHERE target_id=? AND event_type='TEMPLATE_READIED'",f.template()));
    }
    @Test void andRuleRejectsEitherIncompleteActiveCheckDespiteTheOtherBeingComplete(){
        var f=bareWithInstructions();var empty=StageConfiguration.empty();
        service.configure(root.principal(),f.template(),f.stage(),2,new TemplateService.ConfigurationChange(new Gps(true,0.0,0.0,1.0,0L,0L),new TemplateService.CodeChange(true,null,null),empty.timePenalty(),empty.delayed()));
        var codeMissing=service.readiness(root.principal(),f.template());assertFalse(codeMissing.ready());assertTrue(codeMissing.errors().stream().anyMatch(e->e.field().equals("code.codeConfigured")));
        assertThrows(TemplateReadiness.NotReady.class,()->service.state(root.principal(),f.template(),3,"READY"));
        service.configure(root.principal(),f.template(),f.stage(),3,new TemplateService.ConfigurationChange(new Gps(true,null,null,null,null,null),new TemplateService.CodeChange(true,"Code",0L),empty.timePenalty(),empty.delayed()));
        var gpsMissing=service.readiness(root.principal(),f.template());assertFalse(gpsMissing.ready());assertTrue(gpsMissing.errors().stream().anyMatch(e->e.field().equals("gps.latitude")));
        assertThrows(TemplateReadiness.NotReady.class,()->service.state(root.principal(),f.template(),4,"READY"));assertEquals("DRAFT",service.detail(root.principal(),f.template()).status());
    }
    @Test void codeHashIsNormalizedPreservedAndNeverExposedOrPersistedInCommands()throws Exception{
        var f=configured(true,true);String hash=storedCode(f.stage());assertEquals("SYNTHETIC-CODE",hash);
        String response=mvc.perform(get("/api/v1/race-templates/"+f.template()).header("Authorization","Bearer "+root.secret())).andExpect(status().isOk()).andExpect(jsonPath("$.stages[0].code.codeConfigured").value(true)).andExpect(jsonPath("$.stages[0].code.newCode").doesNotExist()).andExpect(jsonPath("$.stages[0].code.hash").doesNotExist()).andExpect(jsonPath("$.stages[0].statisticsVersion").doesNotExist()).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("synthetic-code"));assertTrue(response.contains("SYNTHETIC-CODE"));assertTrue(response.contains(hash));
        var before=service.detail(root.principal(),f.template());long events=count("SELECT count(*) FROM audit_event"),commandsBefore=count("SELECT count(*) FROM admin_role_command");
        assertEquals(before,service.configure(root.principal(),f.template(),f.stage(),3,config(true,true,null,-3)));
        assertEquals(before,service.configure(root.principal(),f.template(),f.stage(),3,config(true,true," SyNtHeTiC-CoDe ",-3)));
        assertTrue(Objects.equals(hash,storedCode(f.stage())),"Equivalent input must preserve the existing hash");assertEquals(events,count("SELECT count(*) FROM audit_event"));assertEquals(commandsBefore,count("SELECT count(*) FROM admin_role_command"));
        assertThrows(AuthFailure.class,()->service.configure(root.principal(),f.template(),f.stage(),3,config(true,true,"x".repeat(129),-3)));
        String ledger=String.join("",jdbc.queryForList("SELECT coalesce(request_data::text,'') || coalesce(result_data::text,'') FROM admin_role_command",String.class));
        String metadata=String.join("",jdbc.queryForList("SELECT metadata::text FROM audit_event",String.class));
        for(String value:List.of("synthetic-code","SYNTHETIC-CODE",hash,"x".repeat(129))){assertFalse(ledger.contains(value));assertFalse(metadata.contains(value));}
    }
    @Test void disabledCodeKeepsItsHashAndExplicitNullViaHttpPreservesIt()throws Exception{
        var f=configured(true,true);String hash=storedCode(f.stage());
        String body="""
            {"version":3,"gps":{"enabled":true,"latitude":90,"longitude":-180,"radiusMeters":0.5,"outsidePenaltyPoints":-3,"spoofingPenaltyPoints":-3},
             "code":{"enabled":true,"newCode":null,"wrongAttemptPenaltyPoints":-3},
             "timePenalty":{"enabled":true,"intervalSeconds":60,"pointsPerInterval":-3},"delayed":{"enabled":true,"minimumDurationMs":0,"pointsPerSecond":-3}}
            """;
        long commandsBefore=count("SELECT count(*) FROM admin_role_command");
        mvc.perform(put("/api/v1/race-templates/"+f.template()+"/stages/"+f.stage()+"/configuration").header("Authorization","Bearer "+root.secret()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3));
        service.configure(root.principal(),f.template(),f.stage(),3,config(true,false,null,-3));
        assertTrue(Objects.equals(hash,storedCode(f.stage())));assertTrue(service.detail(root.principal(),f.template()).stages().getFirst().code().codeConfigured());assertEquals(commandsBefore,count("SELECT count(*) FROM admin_role_command"));assertTrue(service.readiness(root.principal(),f.template()).ready());
    }
    @ParameterizedTest @ValueSource(longs={Long.MIN_VALUE,0,Long.MAX_VALUE})
    void allSignedRatesAndNonnegativeMaximumPersist(long rate){
        var f=bareWithInstructions();service.configure(root.principal(),f.template(),f.stage(),2,config(true,true,"Code",rate));var stage=service.stageDetail(root.principal(),f.template(),f.stage());
        assertEquals(rate,stage.gps().outsidePenaltyPoints());assertEquals(rate,stage.gps().spoofingPenaltyPoints());assertEquals(rate,stage.code().wrongAttemptPenaltyPoints());assertEquals(rate,stage.timePenalty().pointsPerInterval());assertEquals(rate,stage.delayed().pointsPerSecond());assertEquals(0,stage.maxPenaltyPoints());
        assertEquals(400,assertThrows(AuthFailure.class,()->service.updateStage(root.principal(),f.template(),f.stage(),3,"Station",0L,-1L)).status());assertTrue(service.readiness(root.principal(),f.template()).ready());
    }
    @Test void filledInvalidDraftFieldsRollbackEvenIfRuleDisabled(){
        var f=bareWithInstructions();var before=service.detail(root.principal(),f.template());var empty=StageConfiguration.empty();
        for(double latitude:new double[]{-90.001,90.001,Double.NaN,Double.POSITIVE_INFINITY})assertThrows(AuthFailure.class,()->service.configure(root.principal(),f.template(),f.stage(),2,new TemplateService.ConfigurationChange(new Gps(false,latitude,null,null,null,null),new TemplateService.CodeChange(false,null,null),empty.timePenalty(),empty.delayed())));
        assertThrows(AuthFailure.class,()->service.configure(root.principal(),f.template(),f.stage(),2,new TemplateService.ConfigurationChange(empty.gps(),new TemplateService.CodeChange(false,null,null),new TimePenalty(false,1L,0L),empty.delayed())));
        assertThrows(AuthFailure.class,()->service.configure(root.principal(),f.template(),f.stage(),2,new TemplateService.ConfigurationChange(empty.gps(),new TemplateService.CodeChange(false,null,null),empty.timePenalty(),new Delayed(false,-1L,null))));
        for(String code:List.of("", " ","x".repeat(129)))assertThrows(AuthFailure.class,()->service.configure(root.principal(),f.template(),f.stage(),2,config(false,true,code,0)));
        assertEquals(before,service.detail(root.principal(),f.template()));assertNull(storedCode(f.stage()));
    }
    @Test void wholeSecondsConversionHasCheckedOverflowAndDbDivisibility()throws Exception{
        var f=bareWithInstructions();long seconds=Long.MAX_VALUE/1000;
        String body="""
            {"version":2,"gps":{"enabled":true,"latitude":0,"longitude":0,"radiusMeters":1,"outsidePenaltyPoints":0,"spoofingPenaltyPoints":0},
             "code":{"enabled":false},"timePenalty":{"enabled":true,"intervalSeconds":%s,"pointsPerInterval":-1},"delayed":{"enabled":false}}
            """;
        String path="/api/v1/race-templates/"+f.template()+"/stages/"+f.stage()+"/configuration";
        for(String interval:List.of("0","-1","1.5",Long.toString(seconds+1)))mvc.perform(put(path).header("Authorization","Bearer "+root.secret()).contentType("application/json").content(body.formatted(interval))).andExpect(status().isBadRequest());
        assertEquals(2,version(f));mvc.perform(put(path).header("Authorization","Bearer "+root.secret()).contentType("application/json").content(body.formatted(seconds))).andExpect(status().isOk());
        assertEquals(Math.multiplyExact(seconds,1000),jdbc.queryForObject("SELECT penalty_interval_ms FROM template_stage WHERE id=?",Long.class,f.stage()));assertEquals(seconds,service.stageDetail(root.principal(),f.template(),f.stage()).timePenalty().intervalSeconds());
        sqlState("23514",()->jdbc.update("UPDATE template_stage SET penalty_interval_ms=1001 WHERE id=?",f.stage()));
    }
    @Test void readyNoOpsPreserveTimestampAndAuditWhileConfigurationChangesInvalidate(){
        var f=configured(true,true);var ready=service.state(root.principal(),f.template(),3,"READY");var before=jdbc.queryForMap("SELECT status,version,updated_at FROM race_template WHERE id=?",f.template());long events=count("SELECT count(*) FROM audit_event");
        assertEquals(ready,service.state(root.principal(),f.template(),4,"READY"));assertEquals(ready,service.configure(root.principal(),f.template(),f.stage(),4,config(true,true," SYNTHETIC-CODE ",-3)));assertEquals(ready,service.updateStage(root.principal(),f.template(),f.stage(),4,"Station",0L,0L));
        assertEquals(before,jdbc.queryForMap("SELECT status,version,updated_at FROM race_template WHERE id=?",f.template()));assertEquals(events,count("SELECT count(*) FROM audit_event"));
        var renamed=service.update(root.principal(),f.template(),4,"Renamed","Description",-3);assertEquals("READY",renamed.status());
        var change=config(true,true,null,-3);var gps=new Gps(true,90.0,-180.0,2.0,-3L,-3L);
        assertEquals("DRAFT",service.configure(root.principal(),f.template(),f.stage(),5,new TemplateService.ConfigurationChange(gps,change.code(),change.timePenalty(),change.delayed())).status());
    }
    @Test void markdownInstructionsArePreservedClearedAndRequiredForReady()throws Exception{
        var f=configured(true,false);service.updateStage(root.principal(),f.template(),f.stage(),3,"Station",0L,0L,true,"Literal <b>text</b>.");
        var stage=service.stageDetail(root.principal(),f.template(),f.stage());assertEquals("Literal <b>text</b>.",stage.instructionsMarkdown());assertTrue(service.readiness(root.principal(),f.template()).ready());
        mvc.perform(get("/api/v1/race-templates/"+f.template()+"/stages/"+f.stage()).header("Authorization","Bearer "+root.secret())).andExpect(status().isOk()).andExpect(jsonPath("$.instructionsMarkdown").value("Literal <b>text</b>.")).andExpect(jsonPath("$.instructions").doesNotExist());
        service.updateStage(root.principal(),f.template(),f.stage(),4,"Station",0L,0L,true," ");assertFalse(service.readiness(root.principal(),f.template()).ready());
        service.updateStage(root.principal(),f.template(),f.stage(),5,"Station",0L,0L,true,null);assertNull(service.stageDetail(root.principal(),f.template(),f.stage()).instructionsMarkdown());
    }
    String publish(Configured f,long v){try{service.state(root.principal(),f.template(),v,"READY");return "OK";}catch(AuthFailure failure){return failure.code();}}
    @Test void publicationAndIncompleteEditRaceCannotLeaveInvalidReady()throws Exception{
        var f=configured(true,false);long v=version(f);var empty=StageConfiguration.empty();
        var results=race(()->publish(f,v),()->{try{service.configure(root.principal(),f.template(),f.stage(),v,new TemplateService.ConfigurationChange(new Gps(true,null,null,null,null,null),new TemplateService.CodeChange(false,null,null),empty.timePenalty(),empty.delayed()));return "OK";}catch(AuthFailure failure){return failure.code();}});
        assertEquals(1,results.stream().filter("OK"::equals).count());assertEquals(1,results.stream().filter("TEMPLATE_VERSION_CONFLICT"::equals).count());assertEquals(v+1,version(f));
        if(service.detail(root.principal(),f.template()).status().equals("READY"))assertTrue(service.readiness(root.principal(),f.template()).ready());
    }
    @Test void configurationAndPublicationAuditFailuresRollbackHashStateVersionAndAudit(){
        var f=configured(true,true);var before=service.detail(root.principal(),f.template());String hash=storedCode(f.stage());long events=count("SELECT count(*) FROM audit_event"),commandCount=count("SELECT count(*) FROM admin_role_command");
        failedAudit(Event.TEMPLATE_STAGE_CONFIGURED,f.stage(),()->service.configure(root.principal(),f.template(),f.stage(),3,config(true,true,"Replacement",9)));
        assertEquals(before,service.detail(root.principal(),f.template()));assertTrue(Objects.equals(hash,storedCode(f.stage())),"Code hash must roll back");
        failedAudit(Event.TEMPLATE_READIED,f.template(),()->service.state(root.principal(),f.template(),3,"READY"));assertEquals(before,service.detail(root.principal(),f.template()));
        assertEquals(events,count("SELECT count(*) FROM audit_event"));assertEquals(commandCount,count("SELECT count(*) FROM admin_role_command"));
    }
    @Test void v17DropsStatisticalVersionAndKeepsExactGuards(){
        assertEquals(0,count("SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name='template_stage' AND column_name='statistics_version'",SCHEMA));
        assertEquals(1,count("SELECT count(*) FROM flyway_schema_history WHERE version='17' AND script='V17__stage_configuration_and_readiness.sql' AND success"));
        tx().executeWithoutResult(s->assertFalse(facts.forAccount(root.id()).historical()));
        tx().executeWithoutResult(s->{jdbc.update("UPDATE flyway_schema_history SET script='V17__unexpected.sql' WHERE version='17'");assertEquals(503,assertThrows(AuthFailure.class,()->facts.forAccount(root.id())).status());s.setRollbackOnly();});
    }
    @Test void upgradeV16ToV17PreservesConfigurationAndAuditsInvalidatedReady(){
        String upgrade=SCHEMA+"_v17";assertTrue(upgrade.matches("it_templates_[0-9a-f]{32}_v17"));assertEquals("rallye_test",jdbc.queryForObject("SELECT current_database()",String.class));assertEquals("rallye_test",jdbc.queryForObject("SELECT current_user",String.class));
        UUID id=UUID.randomUUID(),stage=UUID.randomUUID();String hash=passwords.encode("SYNTHETIC");
        try{
            org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas(upgrade).defaultSchema(upgrade).target("16").load().migrate();
            jdbc.update("INSERT INTO "+upgrade+".race_template(id,name,skip_penalty_points,status,version,created_at,updated_at) VALUES (?,'Before',-3,'READY',4,now(),now())",id);
            jdbc.update("INSERT INTO "+upgrade+".template_stage(id,race_template_id,sequence_no,name,instructions_html,statistics_version,code_enabled,code_hash,wrong_code_penalty_points) VALUES (?,?,1,'Station','Text',7,true,?,-5)",stage,id,hash);
            org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas(upgrade).defaultSchema(upgrade).target("17").load().migrate();
            assertEquals("DRAFT",jdbc.queryForObject("SELECT status FROM "+upgrade+".race_template WHERE id=?",String.class,id));assertEquals(5,count("SELECT version FROM "+upgrade+".race_template WHERE id=?",id));
            assertEquals("Text",jdbc.queryForObject("SELECT instructions FROM "+upgrade+".template_stage WHERE id=?",String.class,stage));assertTrue(Objects.equals(hash,jdbc.queryForObject("SELECT code_hash FROM "+upgrade+".template_stage WHERE id=?",String.class,stage)));
            assertEquals(0,count("SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name='template_stage' AND column_name='statistics_version'",upgrade));assertEquals(1,count("SELECT count(*) FROM "+upgrade+".audit_event WHERE event_type='TEMPLATE_DRAFTED' AND actor_kind='SYSTEM' AND target_id=?",id));
        }finally{jdbc.execute("DROP SCHEMA IF EXISTS "+upgrade+" CASCADE");}
    }

    java.io.InputStream png()throws Exception{return new java.io.ByteArrayInputStream(cz.mh.rallye.rallye_backend.crews.PhotoFilesTest.image("png",12,8));}
    String upload(UUID template,String name)throws Exception{return images.upload(root.principal(),template,version(template),UUID.randomUUID(),name,png()).fileName();}
    void markdown(Configured f,String source){service.updateStage(root.principal(),f.template(),f.stage(),version(f),"Station",0L,0L,true,source);}
    UUID imageId(UUID template,String name){return jdbc.queryForObject("SELECT id FROM template_image WHERE race_template_id=? AND name_key=?",UUID.class,template,cz.mh.rallye.rallye_backend.templates.application.ImageNames.key(name));}
    java.nio.file.Path imagePath(UUID template,String name){return IMAGE_DIR.resolve("race-templates").resolve(template.toString()).resolve("images").resolve(name);}
    @Test void sourceMarkdownIsStoredExactlyAndReadOnlyHtmlIsSafe()throws Exception{
        var f=configured(true,false);String source="# Nadpis  \r\n\r\n**Text** & <script>bad()</script>\r\n";markdown(f,source);
        var stage=service.stageDetail(root.principal(),f.template(),f.stage());assertEquals(source,stage.instructionsMarkdown());assertTrue(stage.instructionsRenderedHtml().contains("<strong>Text</strong>"));assertFalse(stage.instructionsRenderedHtml().contains("<script>"));
        assertEquals(source,jdbc.queryForObject("SELECT instructions_markdown FROM template_stage WHERE id=?",String.class,f.stage()));
        mvc.perform(get("/api/v1/race-templates/"+f.template()+"/stages/"+f.stage()).header("Authorization","Bearer "+root.secret())).andExpect(status().isOk()).andExpect(jsonPath("$.instructionsMarkdown").value(source)).andExpect(jsonPath("$.instructionsRenderedHtml").exists()).andExpect(jsonPath("$.instructionsHtml").doesNotExist());
    }
    @Test void originalNameStorageAndCaseUnicodeCollisionNeverOverwriteExistingFile()throws Exception{
        UUID template=create();String name=upload(template,"Česká mapa.png");assertTrue(java.nio.file.Files.exists(imagePath(template,name)));byte[] original=images.content(root.principal(),template,name).bytes();
        for(String conflict:List.of("ČESKÁ MAPA.PNG","C\u030ceska\u0301 mapa.png"))assertEquals("TEMPLATE_IMAGE_NAME_CONFLICT",assertThrows(AuthFailure.class,()->images.upload(root.principal(),template,0,UUID.randomUUID(),conflict,png())).code());
        assertArrayEquals(original,images.content(root.principal(),template,name).bytes());assertArrayEquals(original,images.content(root.principal(),template,"ČESKÁ MAPA.PNG").bytes());assertEquals(1,images.list(root.principal(),template).size());
        UUID other=create();upload(other,name);assertEquals(1,images.list(root.principal(),other).size());
    }
    @Test void relativeAndExplicitReferencesShareUsageAndBlockDeletion()throws Exception{
        var f=configured(true,false);String name=upload(f.template(),"mapa trasy.png");String url=cz.mh.rallye.rallye_backend.templates.application.ImageNames.url(f.template(),name);
        markdown(f,"![A](mapa%20trasy.png) ![B]("+url+")");assertEquals(Set.of(imageId(f.template(),name)),templateStore.usedImages(f.stage()));
        assertTrue(service.stageDetail(root.principal(),f.template(),f.stage()).instructionsRenderedHtml().contains(url));assertTrue(service.readiness(root.principal(),f.template()).ready());
        assertEquals("TEMPLATE_IMAGE_IN_USE",assertThrows(AuthFailure.class,()->images.delete(root.principal(),f.template(),name,version(f))).code());
        markdown(f,"Text without image");images.delete(root.principal(),f.template(),name,version(f));assertFalse(java.nio.file.Files.exists(imagePath(f.template(),name)));
    }
    @Test void rejectsForeignMissingAndUnsafeReferencesWithoutChangingState()throws Exception{
        var f=configured(true,false);UUID other=create();upload(other,"foreign.png");var before=service.detail(root.principal(),f.template());
        for(String source:List.of("![x](https://evil.test/a.png)","![x](data:x)","![x]("+cz.mh.rallye.rallye_backend.templates.application.ImageNames.url(other,"foreign.png")+")")){
            assertThrows(AuthFailure.class,()->markdown(f,source));assertEquals(before,service.detail(root.principal(),f.template()));
        }
    }
    @Test void markdownNoOpAndReadyTransitionsKeepExistingRules(){
        var f=configured(true,false);String source="**Task**";markdown(f,source);service.state(root.principal(),f.template(),version(f),"READY");var before=service.detail(root.principal(),f.template());long events=count("SELECT count(*) FROM audit_event");
        markdown(f,source);assertEquals(before,service.detail(root.principal(),f.template()));assertEquals(events,count("SELECT count(*) FROM audit_event"));markdown(f,"**Changed**");assertEquals("DRAFT",service.detail(root.principal(),f.template()).status());markdown(f,"# ");assertFalse(service.readiness(root.principal(),f.template()).ready());
    }
    @Test void markdownAuditFailureRollsBackSourceReferencesAndVersion()throws Exception{
        var f=configured(true,false);upload(f.template(),"most.png");var before=service.detail(root.principal(),f.template());
        failedAudit(Event.TEMPLATE_STAGE_UPDATED,f.stage(),()->markdown(f,"![Most](most.png)"));assertEquals(before,service.detail(root.principal(),f.template()));assertTrue(templateStore.usedImages(f.stage()).isEmpty());
    }
    @Test void uploadReplayAndNameConflictPreserveFileAndAudit()throws Exception{
        UUID template=create(),key=UUID.randomUUID();var first=images.upload(root.principal(),template,0,key,"most.png",png());
        assertEquals(first,images.upload(root.principal(),template,0,key,"most.png",png()));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AuthFailure.class,()->images.upload(root.principal(),template,0,key,"other.png",png())).code());assertEquals(1,count("SELECT count(*) FROM audit_event WHERE target_id=? AND event_type='TEMPLATE_IMAGE_CREATED'",template));
        String body=mvc.perform(get("/api/v1/race-templates/"+template+"/images").header("Authorization","Bearer "+root.secret())).andExpect(status().isOk()).andExpect(jsonPath("$[0].fileName").value("most.png")).andExpect(jsonPath("$[0].id").doesNotExist()).andReturn().getResponse().getContentAsString();assertFalse(body.contains(imageId(template,"most.png").toString()));
    }
    @Test void uploadAndDeleteAuditFailuresKeepDatabaseAndFilesystemConsistent()throws Exception{
        UUID template=create();upload(template,"keep.png");byte[] bytes=cz.mh.rallye.rallye_backend.crews.PhotoFilesTest.image("png",2,2);UUID key=UUID.randomUUID();
        failedAudit(Event.TEMPLATE_IMAGE_CREATED,template,()->images.upload(root.principal(),template,0,key,"failed.png",new java.io.ByteArrayInputStream(bytes)));
        assertFalse(java.nio.file.Files.exists(imagePath(template,"failed.png")));assertEquals(1,images.list(root.principal(),template).size());assertEquals(0,count("SELECT count(*) FROM admin_role_command WHERE command_key=?",key));
        failedAudit(Event.TEMPLATE_IMAGE_DELETED,template,()->images.delete(root.principal(),template,"keep.png",0));assertTrue(java.nio.file.Files.exists(imagePath(template,"keep.png")));assertEquals(1,images.list(root.principal(),template).size());
    }
    @Test void concurrentSameNameUploadsHaveExactlyOneWinner()throws Exception{
        UUID template=create();Callable<String> upload=()->{try{images.upload(root.principal(),template,0,UUID.randomUUID(),"Most.png",png());return "OK";}catch(AuthFailure e){return e.code();}};
        var result=race(upload,upload);assertEquals(1,result.stream().filter("OK"::equals).count());assertEquals(1,result.stream().filter("TEMPLATE_IMAGE_NAME_CONFLICT"::equals).count());assertEquals(1,images.list(root.principal(),template).size());assertTrue(images.content(root.principal(),template,"most.PNG").bytes().length>0);
    }
    @Test void concurrentUseAndDeleteNeverLeaveDanglingReferences()throws Exception{
        var f=configured(true,false);upload(f.template(),"most.png");long v=version(f);
        var result=race(()->{service.updateStage(root.principal(),f.template(),f.stage(),v,"Station",0L,0L,true,"![Most](most.png)");return "USED";},()->{try{images.delete(root.principal(),f.template(),"most.png",v);return "DELETED";}catch(AuthFailure e){return e.code();}});
        assertEquals("USED",result.getFirst());
        assertTrue(Set.of("DELETED","TEMPLATE_VERSION_CONFLICT").contains(result.getLast()),result.toString());
        assertUseAndDeleteOutcome(f,v,result.getLast().equals("DELETED"));
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void useAndDeleteSequentialOrdersMatchForwardReferenceContract(boolean deleteFirst)throws Exception{
        var f=configured(true,false);upload(f.template(),"most.png");long v=version(f);
        if(deleteFirst)images.delete(root.principal(),f.template(),"most.png",v);
        service.updateStage(root.principal(),f.template(),f.stage(),v,"Station",0L,0L,true,"![Most](most.png)");
        if(!deleteFirst)assertEquals("TEMPLATE_VERSION_CONFLICT",assertThrows(AuthFailure.class,()->images.delete(root.principal(),f.template(),"most.png",v)).code());
        assertUseAndDeleteOutcome(f,v,deleteFirst);
    }
    void assertUseAndDeleteOutcome(Configured f,long initialVersion,boolean deleted)throws Exception{
        var detail=service.detail(root.principal(),f.template());
        assertEquals(initialVersion+1,detail.version());assertEquals("DRAFT",detail.status());
        var stage=service.stageDetail(root.principal(),f.template(),f.stage());
        assertEquals("![Most](most.png)",stage.instructionsMarkdown());
        assertEquals(deleted?0:1,count("SELECT count(*) FROM template_image WHERE race_template_id=?",f.template()));
        assertEquals(!deleted,java.nio.file.Files.exists(imagePath(f.template(),"most.png")));
        assertEquals(deleted?0:1,count("SELECT count(*) FROM template_stage_image WHERE stage_id=?",f.stage()));
        assertEquals(0,count("SELECT count(*) FROM template_stage_image u LEFT JOIN template_image i ON i.id=u.image_id WHERE i.id IS NULL"));
        var readiness=service.readiness(root.principal(),f.template());
        if(deleted){
            assertEquals(1,stage.missingReferences().size());var warning=stage.missingReferences().getFirst();
            assertEquals("FILE_NOT_FOUND",warning.code());assertEquals("most.png",warning.targetFileName());
            assertEquals(f.stage(),warning.sourceStageId());assertEquals("IMAGE",warning.referenceType());
            assertFalse(readiness.ready());assertTrue(readiness.errors().stream().anyMatch(e->e.code().equals("MISSING_FILE")));
            assertThrows(cz.mh.rallye.rallye_backend.templates.application.TemplateReadiness.NotReady.class,()->service.state(root.principal(),f.template(),detail.version(),"READY"));
        }else{
            assertTrue(stage.missingReferences().isEmpty());assertTrue(readiness.ready());
            assertTrue(images.content(root.principal(),f.template(),"most.png").bytes().length>0);
            assertEquals("TEMPLATE_IMAGE_IN_USE",assertThrows(AuthFailure.class,()->images.delete(root.principal(),f.template(),"most.png",detail.version())).code());
        }
        assertEquals(detail,service.detail(root.principal(),f.template()));
    }
    @ParameterizedTest @ValueSource(strings={"DRAFT","READY"}) void duplicationKeepsNamesAndRelativeSourceAndRewritesOnlyExplicitImages(String status)throws Exception{
        var f=configured(true,true);upload(f.template(),"most.png");upload(f.template(),"unused.png");String source="# Task\n\n![relative](most.png)\n\n![explicit]("+cz.mh.rallye.rallye_backend.templates.application.ImageNames.url(f.template(),"most.png")+")\n";markdown(f,source);
        if(status.equals("READY"))service.state(root.principal(),f.template(),version(f),"READY");var before=service.detail(root.principal(),f.template());var copy=copies.duplicate(root.principal(),f.template(),UUID.randomUUID());var detail=service.detail(root.principal(),copy.id());assertEquals("DRAFT",detail.status());assertEquals(0,detail.version());assertEquals(before,service.detail(root.principal(),f.template()));assertEquals(2,images.list(root.principal(),copy.id()).size());
        var next=detail.stages().getFirst();assertNotEquals(f.stage(),next.id());assertTrue(next.instructionsMarkdown().contains("![relative](most.png)"));assertFalse(next.instructionsMarkdown().contains(f.template().toString()));assertTrue(next.instructionsRenderedHtml().contains(copy.id().toString()));assertArrayEquals(images.content(root.principal(),f.template(),"most.png").bytes(),images.content(root.principal(),copy.id(),"most.png").bytes());assertEquals("SYNTHETIC-CODE",storedCode(next.id()));
        images.delete(root.principal(),copy.id(),"unused.png",0);assertTrue(java.nio.file.Files.exists(imagePath(f.template(),"unused.png")));
    }
    @Test void stageCopySharesNamedImagesAndPreservesSourceExactly()throws Exception{
        var f=configured(true,true);upload(f.template(),"most.png");String source="![Most](most.png)\n";markdown(f,source);long v=version(f);var duplicate=copies.duplicateStage(root.principal(),f.template(),f.stage(),v,UUID.randomUUID());
        assertEquals(source,service.stageDetail(root.principal(),f.template(),duplicate.id()).instructionsMarkdown());assertEquals(2,count("SELECT count(*) FROM template_stage_image WHERE image_id=?",imageId(f.template(),"most.png")));assertEquals(v+1,version(f));assertEquals(1,images.list(root.principal(),f.template()).size());
    }
    @Test void deletingTemplateRemovesNamedFilesAndReferences()throws Exception{
        var f=configured(true,false);upload(f.template(),"mapa.png");markdown(f,"![x](mapa.png)");service.delete(root.principal(),f.template(),version(f));assertFalse(java.nio.file.Files.exists(imagePath(f.template(),"mapa.png")));assertEquals(0,count("SELECT count(*) FROM template_image WHERE race_template_id=?",f.template()));
    }
    @Test void invalidNamesAndOrdinaryUsersCannotUploadOrRead()throws Exception{
        UUID template=create();var ordinary=actor();assertEquals(403,assertThrows(AuthFailure.class,()->images.content(ordinary.principal(),template,"mapa.png")).status());
        for(String name:List.of("../x.png","C:\\x.png","a/b.png","CON.png","a\nb.png"))assertEquals("INVALID_IMAGE_NAME",assertThrows(AuthFailure.class,()->images.upload(root.principal(),template,0,UUID.randomUUID(),name,png())).code());
        mvc.perform(post("/api/v1/race-templates/"+template+"/images").header("Authorization","Bearer "+ordinary.secret())).andExpect(status().isForbidden());
    }
    @Test void v19GuardAndNameUniquenessAreEnforced()throws Exception{
        UUID template=create();upload(template,"Most.png");assertThrows(DataAccessException.class,()->jdbc.update("INSERT INTO template_image(id,race_template_id,content_type,byte_size,width,height,created_at,file_name,name_key) SELECT gen_random_uuid(),race_template_id,content_type,byte_size,width,height,created_at,'most.PNG',name_key FROM template_image WHERE race_template_id=?",template));
        tx().executeWithoutResult(s->{assertFalse(facts.forAccount(root.id()).historical());jdbc.update("UPDATE flyway_schema_history SET script='V19__unknown.sql' WHERE version='19'");assertEquals(503,assertThrows(AuthFailure.class,()->facts.forAccount(root.id())).status());s.setRollbackOnly();});
    }

    @Test void duplicateAuditFailureRollsBackMetadataLedgerAndNamedFiles()throws Exception{
        var f=configured(true,true);upload(f.template(),"most.png");markdown(f,"![Most](most.png)");var before=service.detail(root.principal(),f.template());long templates=count("SELECT count(*) FROM race_template"),ledger=count("SELECT count(*) FROM admin_role_command"),events=count("SELECT count(*) FROM audit_event");
        Set<String> files;try(var paths=java.nio.file.Files.walk(IMAGE_DIR)){files=paths.map(IMAGE_DIR::relativize).map(Object::toString).collect(java.util.stream.Collectors.toSet());}
        var failure=new IllegalStateException("Injected copy audit failure");clearInvocations(audit);
        doAnswer(invocation->{invocation.callRealMethod();throw failure;}).when(audit).appendTemplateCopy(any(),any(),any(),any(),any(),any(),anyBoolean());
        try{RuntimeException thrown=assertThrows(RuntimeException.class,()->copies.duplicate(root.principal(),f.template(),UUID.randomUUID()));while(thrown.getCause() instanceof RuntimeException cause)thrown=cause;assertSame(failure,thrown);}finally{reset(audit);}
        assertEquals(before,service.detail(root.principal(),f.template()));assertEquals(templates,count("SELECT count(*) FROM race_template"));assertEquals(ledger,count("SELECT count(*) FROM admin_role_command"));assertEquals(events,count("SELECT count(*) FROM audit_event"));
        try(var paths=java.nio.file.Files.walk(IMAGE_DIR)){assertEquals(files,paths.map(IMAGE_DIR::relativize).map(Object::toString).collect(java.util.stream.Collectors.toSet()));}
    }
    @Test void upgradeV18ToV19DiscardsOnlyDisposableInstructionAndImageContent()throws Exception{
        String upgrade=SCHEMA+"_v19";assertTrue(upgrade.matches("it_templates_[0-9a-f]{32}_v19"));assertEquals("rallye_test",jdbc.queryForObject("SELECT current_database()",String.class));assertEquals("rallye_test",jdbc.queryForObject("SELECT current_user",String.class));
        UUID template=UUID.randomUUID(),stage=UUID.randomUUID(),image=UUID.randomUUID();
        try{
            org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas(upgrade).defaultSchema(upgrade).target("18").load().migrate();
            jdbc.update("INSERT INTO "+upgrade+".race_template(id,name,skip_penalty_points,status,version,created_at,updated_at) VALUES (?,'Keep name',-3,'READY',4,now(),now())",template);
            jdbc.update("INSERT INTO "+upgrade+".template_stage(id,race_template_id,sequence_no,name,instructions_html,max_penalty_points) VALUES (?,?,1,'Keep stage','<p>Disposable</p>',7)",stage,template);
            jdbc.update("INSERT INTO "+upgrade+".template_image VALUES (?,?,'image/png',10,1,1,now())",image,template);
            jdbc.update("INSERT INTO "+upgrade+".template_stage_image VALUES (?,?,?)",stage,image,template);
            org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas(upgrade).defaultSchema(upgrade).target("19").load().migrate();
            assertNull(jdbc.queryForObject("SELECT instructions_markdown FROM "+upgrade+".template_stage WHERE id=?",String.class,stage));assertEquals(7,count("SELECT max_penalty_points FROM "+upgrade+".template_stage WHERE id=?",stage));
            assertEquals("DRAFT",jdbc.queryForObject("SELECT status FROM "+upgrade+".race_template WHERE id=?",String.class,template));assertEquals(5,count("SELECT version FROM "+upgrade+".race_template WHERE id=?",template));assertEquals(0,count("SELECT count(*) FROM "+upgrade+".template_image"));assertEquals(0,count("SELECT count(*) FROM "+upgrade+".template_stage_image"));
            assertEquals(1,count("SELECT count(*) FROM "+upgrade+".audit_event WHERE target_id=? AND event_type='TEMPLATE_DRAFTED'",template));
        }finally{jdbc.execute("DROP SCHEMA IF EXISTS "+upgrade+" CASCADE");}
    }

    cz.mh.rallye.rallye_backend.templates.application.TemplateFileService.Uploaded file(UUID template,String name,String source){return file(template,name,source.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    cz.mh.rallye.rallye_backend.templates.application.TemplateFileService.Uploaded file(UUID template,String name,byte[] bytes){return files.upload(root.principal(),template,version(template),UUID.randomUUID(),name,new java.io.ByteArrayInputStream(bytes));}
    @Test void fileLibraryCyclesMissingTargetsAndReadyGuard()throws Exception{
        var f=configured(true,false);file(f.template(),"a.md","# A\n[B](b.md) ![Mapa](mapa.png)");var report=files.list(root.principal(),f.template());assertEquals(2,report.missingReferences().size());assertFalse(service.readiness(root.principal(),f.template()).ready());assertEquals("TEMPLATE_NOT_READY",assertThrows(TemplateReadiness.NotReady.class,()->service.state(root.principal(),f.template(),version(f),"READY")).getMessage());
        file(f.template(),"b.md","[A](a.md)");upload(f.template(),"mapa.png");assertTrue(files.list(root.principal(),f.template()).missingReferences().isEmpty());assertTrue(service.readiness(root.principal(),f.template()).ready());
        assertEquals("TEMPLATE_FILE_IN_USE",assertThrows(AuthFailure.class,()->files.delete(root.principal(),f.template(),"a.md",version(f))).code());assertEquals("TEMPLATE_IMAGE_IN_USE",assertThrows(AuthFailure.class,()->images.delete(root.principal(),f.template(),"mapa.png",version(f))).code());
        assertTrue(new String(files.content(root.principal(),f.template(),"a.md").bytes(),java.nio.charset.StandardCharsets.UTF_8).contains("/files/b.md"));
    }
    @Test void newDocumentMissingTargetInvalidatesReadyExactlyOnce(){
        var f=configured(true,false);service.state(root.principal(),f.template(),version(f),"READY");long v=version(f);UUID key=UUID.randomUUID();String source="[Later](later.pdf)";
        var first=files.upload(root.principal(),f.template(),v,key,"guide.md",new java.io.ByteArrayInputStream(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));assertEquals(v+1,first.version());assertEquals("DRAFT",first.status());assertEquals(1,first.missingReferences().size());
        assertEquals(first,files.upload(root.principal(),f.template(),v,key,"guide.md",new java.io.ByteArrayInputStream(source.getBytes(java.nio.charset.StandardCharsets.UTF_8))));assertEquals(v+1,version(f));
    }
    @Test void forwardStageLinkBecomesValidAfterUploadAndPreventsDeletion()throws Exception{
        var f=configured(true,false);markdown(f,"[Rules](pravidla.pdf)");assertEquals(1,service.stageDetail(root.principal(),f.template(),f.stage()).missingReferences().size());file(f.template(),"pravidla.pdf",cz.mh.rallye.rallye_backend.templates.TemplateFilesTest.pdf());assertTrue(service.stageDetail(root.principal(),f.template(),f.stage()).missingReferences().isEmpty());assertEquals("TEMPLATE_FILE_IN_USE",assertThrows(AuthFailure.class,()->files.delete(root.principal(),f.template(),"pravidla.pdf",version(f))).code());
        mvc.perform(get(cz.mh.rallye.rallye_backend.templates.application.ImageNames.fileUrl(f.template(),"pravidla.pdf")).header("Authorization","Bearer "+root.secret())).andExpect(status().isOk()).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.startsWith("attachment;")));
    }
    @Test void aliasesShareOneNamespaceButImagesDoNotExposeDocuments()throws Exception{
        UUID template=create();upload(template,"mapa.png");assertEquals(1,files.list(root.principal(),template).items().size());assertEquals("TEMPLATE_FILE_NAME_CONFLICT",assertThrows(AuthFailure.class,()->file(template,"MAPA.PNG",cz.mh.rallye.rallye_backend.crews.PhotoFilesTest.image("png",12,8))).code());file(template,"pokyny.md","Text");assertEquals(1,images.list(root.principal(),template).size());assertEquals("TEMPLATE_IMAGE_NOT_FOUND",assertThrows(AuthFailure.class,()->images.content(root.principal(),template,"pokyny.md")).code());assertEquals("TEMPLATE_IMAGE_NOT_FOUND",assertThrows(AuthFailure.class,()->images.delete(root.principal(),template,"pokyny.md",0)).code());
    }
    @Test void documentsAndPdfCopyWithExplicitAndRelativeReferences()throws Exception{
        var f=configured(true,false);upload(f.template(),"mapa.png");file(f.template(),"rules.pdf",cz.mh.rallye.rallye_backend.templates.TemplateFilesTest.pdf());file(f.template(),"guide.md","[Rules]("+cz.mh.rallye.rallye_backend.templates.application.ImageNames.fileUrl(f.template(),"rules.pdf")+") ![Map](mapa.png)");markdown(f,"[Guide](guide.md)");
        var copy=copies.duplicate(root.principal(),f.template(),UUID.randomUUID());assertEquals(3,files.list(root.principal(),copy.id()).items().size());assertTrue(files.list(root.principal(),copy.id()).missingReferences().isEmpty());String html=new String(files.content(root.principal(),copy.id(),"guide.md").bytes(),java.nio.charset.StandardCharsets.UTF_8);assertTrue(html.contains(copy.id().toString()));assertFalse(html.contains(f.template().toString()));assertArrayEquals(files.content(root.principal(),f.template(),"rules.pdf").bytes(),files.content(root.principal(),copy.id(),"rules.pdf").bytes());
    }
    @Test void fileUploadAndDeleteRollbackAuditMetadataSourcesAndBytes(){
        UUID template=create();long countBefore=count("SELECT count(*) FROM template_image WHERE race_template_id=?",template);UUID key=UUID.randomUUID();
        failedAudit(Event.TEMPLATE_FILE_CREATED,template,()->files.upload(root.principal(),template,0,key,"rollback.md",new java.io.ByteArrayInputStream("[Later](later.md)".getBytes(java.nio.charset.StandardCharsets.UTF_8))));assertEquals(countBefore,count("SELECT count(*) FROM template_image WHERE race_template_id=?",template));assertFalse(java.nio.file.Files.exists(imagePath(template,"rollback.md")));assertEquals(0,count("SELECT count(*) FROM admin_role_command WHERE command_key=?",key));
        file(template,"keep.md","Text");failedAudit(Event.TEMPLATE_FILE_DELETED,template,()->files.delete(root.principal(),template,"keep.md",0));assertTrue(java.nio.file.Files.exists(imagePath(template,"keep.md")));assertEquals(1,files.list(root.principal(),template).items().size());
    }
    @Test void concurrentForwardUploadAndDeleteCannotLoseReferencedTarget()throws Exception{
        UUID template=create();file(template,"target.md","Text");var results=race(()->{try{file(template,"source.md","[Target](target.md)");return "UPLOADED";}catch(AuthFailure e){return e.code();}},()->{try{files.delete(root.principal(),template,"target.md",0);return "DELETED";}catch(AuthFailure e){return e.code();}});
        var report=files.list(root.principal(),template);if(results.contains("DELETED"))assertEquals(1,report.missingReferences().size());else assertTrue(report.missingReferences().isEmpty());
    }
    @Test void v20RejectsInconsistentTypesAndUnknownMigrationNames()throws Exception{
        UUID template=create();file(template,"a.md","Text");sqlState("23514",()->jdbc.update("UPDATE template_image SET width=1 WHERE race_template_id=?",template));sqlState("23514",()->jdbc.update("UPDATE template_image SET markdown_source=NULL WHERE race_template_id=?",template));
        tx().executeWithoutResult(s->{assertFalse(facts.forAccount(root.id()).historical());jdbc.update("UPDATE flyway_schema_history SET script='V20__unknown.sql' WHERE version='20'");assertEquals(503,assertThrows(AuthFailure.class,()->facts.forAccount(root.id())).status());s.setRollbackOnly();});
    }

    @Test void upgradeV19ToV20PreservesImagesInstructionsAndIdsAndRequiresNewReadyValidation(){
        String upgrade=SCHEMA+"_v20";assertTrue(upgrade.matches("it_templates_[0-9a-f]{32}_v20"));assertEquals("rallye_test",jdbc.queryForObject("SELECT current_database()",String.class));assertEquals("rallye_test",jdbc.queryForObject("SELECT current_user",String.class));UUID template=UUID.randomUUID(),stage=UUID.randomUUID(),image=UUID.randomUUID();
        try{
            org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas(upgrade).defaultSchema(upgrade).target("19").load().migrate();
            jdbc.update("INSERT INTO "+upgrade+".race_template(id,name,skip_penalty_points,status,version,created_at,updated_at) VALUES (?,'Keep',-7,'READY',4,now(),now())",template);
            jdbc.update("INSERT INTO "+upgrade+".template_stage(id,race_template_id,sequence_no,name,instructions_markdown,max_penalty_points) VALUES (?,?,1,'Keep stage','![Map](mapa.png) [Future](future.md)',7)",stage,template);
            jdbc.update("INSERT INTO "+upgrade+".template_image(id,race_template_id,content_type,byte_size,width,height,created_at,file_name,name_key) VALUES (?,?,'image/png',10,2,3,now(),'mapa.png','MAPA.PNG')",image,template);
            jdbc.update("INSERT INTO "+upgrade+".template_stage_image VALUES (?,?,?)",stage,image,template);
            org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas(upgrade).defaultSchema(upgrade).target("20").load().migrate();
            assertEquals("![Map](mapa.png) [Future](future.md)",jdbc.queryForObject("SELECT instructions_markdown FROM "+upgrade+".template_stage WHERE id=?",String.class,stage));assertEquals("mapa.png",jdbc.queryForObject("SELECT file_name FROM "+upgrade+".template_image WHERE id=?",String.class,image));assertEquals(1,count("SELECT count(*) FROM "+upgrade+".template_stage_image WHERE stage_id=? AND image_id=?",stage,image));assertEquals(3,count("SELECT height FROM "+upgrade+".template_image WHERE id=?",image));assertEquals(-7,count("SELECT skip_penalty_points FROM "+upgrade+".race_template WHERE id=?",template));assertEquals("DRAFT",jdbc.queryForObject("SELECT status FROM "+upgrade+".race_template WHERE id=?",String.class,template));assertEquals(5,count("SELECT version FROM "+upgrade+".race_template WHERE id=?",template));assertEquals(1,count("SELECT count(*) FROM "+upgrade+".audit_event WHERE target_id=? AND event_type='TEMPLATE_DRAFTED'",template));
        }finally{jdbc.execute("DROP SCHEMA IF EXISTS "+upgrade+" CASCADE");}
    }

    @Test void concurrentSameFileKeyCreatesOneDocumentAndAudit()throws Exception{
        UUID template=create(),key=UUID.randomUUID();Callable<String> upload=()->files.upload(root.principal(),template,0,key,"same.md",new java.io.ByteArrayInputStream("Text".getBytes(java.nio.charset.StandardCharsets.UTF_8))).url();var results=race(upload,upload);assertEquals(results.getFirst(),results.getLast());assertEquals(1,count("SELECT count(*) FROM template_image WHERE race_template_id=?",template));assertEquals(1,count("SELECT count(*) FROM admin_role_command WHERE command_key=?",key));assertEquals(1,count("SELECT count(*) FROM audit_event WHERE target_id=? AND event_type='TEMPLATE_FILE_CREATED'",template));
    }
    @Test void deletingTemplateRemovesWholeLibraryButNotItsCopy()throws Exception{
        UUID template=create();file(template,"guide.md","[Self](guide.md) [PDF](rules.pdf)");file(template,"rules.pdf",cz.mh.rallye.rallye_backend.templates.TemplateFilesTest.pdf());var copy=copies.duplicate(root.principal(),template,UUID.randomUUID());service.delete(root.principal(),template,0);assertFalse(java.nio.file.Files.exists(imagePath(template,"guide.md")));assertFalse(java.nio.file.Files.exists(imagePath(template,"rules.pdf")));assertEquals(2,files.list(root.principal(),copy.id()).items().size());assertTrue(java.nio.file.Files.exists(imagePath(copy.id(),"guide.md")));
    }

    java.io.InputStream archiveInput(byte[] bytes){return new java.io.ByteArrayInputStream(bytes);}
    cz.mh.rallye.rallye_backend.templates.application.TemplateArchive.Definition portableDefinition(UUID id){
        var t=templateStore.lock(id);return new cz.mh.rallye.rallye_backend.templates.application.TemplateArchive.Definition(t.name(),t.description(),t.skipPenaltyPoints(),t.defaultWrongCodePenaltyPoints(),templateStore.stages(id).stream().map(st->new cz.mh.rallye.rallye_backend.templates.application.TemplateArchive.StageData(st.sequenceNo(),st.name(),st.expectedDurationMs(),st.maxPenaltyPoints(),cz.mh.rallye.rallye_backend.templates.application.InstructionsMarkdown.portable(id,st.instructionsMarkdown()),st.configuration())).toList());
    }
    @Test void archiveRoundTripKeepsAllRulesOrderReachableFilesAndNewIds()throws Exception{
        var f=configured(true,true);upload(f.template(),"mapa.png");file(f.template(),"rules.pdf",cz.mh.rallye.rallye_backend.templates.TemplateFilesTest.pdf());
        file(f.template(),"a.md","[B](b.md) [PDF](rules.pdf)");file(f.template(),"b.md","[A]("+cz.mh.rallye.rallye_backend.templates.application.ImageNames.fileUrl(f.template(),"a.md")+")");file(f.template(),"unused.md","Unused");
        markdown(f,"![Map]("+cz.mh.rallye.rallye_backend.templates.application.ImageNames.url(f.template(),"mapa.png")+") [A](a.md) [A again](a.md)");
        UUID second=service.createStage(root.principal(),f.template(),version(f),UUID.randomUUID(),"Second",1000L,9L).id();service.configure(root.principal(),f.template(),second,version(f),config(false,true,"SECOND",-4));
        service.updateStage(root.principal(),f.template(),second,version(f),"Second",1000L,9L,true,"Second text");
        var original=service.detail(root.principal(),f.template());var expected=portableDefinition(f.template());var zip=transfer.export(root.principal(),f.template());var bundle=cz.mh.rallye.rallye_backend.templates.application.TemplateArchive.decode(zip);
        assertEquals(expected,bundle.template());assertEquals(Set.of("a.md","b.md","mapa.png","rules.pdf"),bundle.files().keySet());assertFalse(cz.mh.rallye.rallye_backend.templates.application.TemplateArchive.JSON.writeValueAsString(bundle.template()).contains(f.template().toString()));
        long before=count("SELECT count(*) FROM race_template");var preview=transfer.preview(root.principal(),archiveInput(zip));assertEquals(2,preview.stageCount());assertEquals(4,preview.fileCount());assertEquals(before,count("SELECT count(*) FROM race_template"));
        var imported=transfer.importTemplate(root.principal(),UUID.randomUUID(),archiveInput(zip));assertNotEquals(f.template(),imported.id());assertEquals("DRAFT",service.detail(root.principal(),imported.id()).status());assertEquals(expected,portableDefinition(imported.id()));assertEquals(original,service.detail(root.principal(),f.template()));
        var oldIds=templateStore.stages(f.template()).stream().map(st->st.id()).collect(java.util.stream.Collectors.toSet());assertTrue(templateStore.stages(imported.id()).stream().noneMatch(st->oldIds.contains(st.id())));
        for(var entry:bundle.files().entrySet())assertArrayEquals(entry.getValue(),java.nio.file.Files.readAllBytes(imagePath(imported.id(),entry.getKey())));
        assertTrue(files.list(root.principal(),imported.id()).missingReferences().isEmpty());assertTrue(service.readiness(root.principal(),imported.id()).ready());
        var sim=simulator.create(root.principal(),imported.id(),0,UUID.randomUUID(),null);assertEquals(imported.id(),sim.source().templateId());assertEquals("NOT_STARTED",sim.race().status());
    }
    @Test void archiveImportDoesNotDependOnSourceOrSourceAdminAndCanBeRepeated()throws Exception{
        var f=configured(false,true);var zip=transfer.export(root.principal(),f.template());var expected=portableDefinition(f.template());service.delete(root.principal(),f.template(),version(f));
        UUID key=UUID.randomUUID();var first=transfer.importTemplate(root.principal(),key,archiveInput(zip));assertEquals(first,transfer.importTemplate(root.principal(),key,archiveInput(zip)));var second=transfer.importTemplate(root.principal(),UUID.randomUUID(),archiveInput(zip));assertNotEquals(first.id(),second.id());assertEquals(expected,portableDefinition(first.id()));assertEquals(expected,portableDefinition(second.id()));
        assertEquals(1,count("SELECT count(*) FROM audit_event WHERE target_id=? AND event_type='TEMPLATE_IMPORTED' AND actor_id=?",first.id(),root.id()));
        var another=configured(false,true);byte[] different=transfer.export(root.principal(),another.template());assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AuthFailure.class,()->transfer.importTemplate(root.principal(),key,archiveInput(different))).code());
        String request=jdbc.queryForObject("SELECT request_data::text FROM admin_role_command WHERE command_key=?",String.class,key);assertFalse(request.contains("SYNTHETIC-CODE"));
    }
    @Test void archiveAuditFailureRollsBackTemplateStagesFilesAndLedger()throws Exception{
        var f=configured(false,true);file(f.template(),"guide.md","Text");markdown(f,"[Guide](guide.md)");var zip=transfer.export(root.principal(),f.template());long templates=count("SELECT count(*) FROM race_template"),stages=count("SELECT count(*) FROM template_stage"),fileCount=count("SELECT count(*) FROM template_image");UUID key=UUID.randomUUID();var created=new java.util.concurrent.atomic.AtomicReference<UUID>();
        doAnswer(invocation->{created.set(invocation.getArgument(4));invocation.callRealMethod();throw new IllegalStateException("Injected import audit failure");}).when(audit).appendTemplate(any(),any(),any(),eq(Event.TEMPLATE_IMPORTED),any(),eq(false));
        try{assertThrows(RuntimeException.class,()->transfer.importTemplate(root.principal(),key,archiveInput(zip)));}finally{org.mockito.Mockito.reset(audit);}
        assertEquals(templates,count("SELECT count(*) FROM race_template"));assertEquals(stages,count("SELECT count(*) FROM template_stage"));assertEquals(fileCount,count("SELECT count(*) FROM template_image"));assertEquals(0,count("SELECT count(*) FROM admin_role_command WHERE command_key=?",key));assertFalse(java.nio.file.Files.exists(imagePath(created.get(),"guide.md")));assertEquals(0,count("SELECT count(*) FROM audit_event WHERE target_id=? AND event_type='TEMPLATE_IMPORTED'",created.get()));
        assertNotNull(transfer.importTemplate(root.principal(),key,archiveInput(zip)));
    }
    @Test void concurrentArchiveImportsWithSameKeyCreateExactlyOneTemplate()throws Exception{
        var f=configured(false,true);byte[] zip=transfer.export(root.principal(),f.template());UUID key=UUID.randomUUID();Callable<String> call=()->transfer.importTemplate(root.principal(),key,archiveInput(zip)).id().toString();var results=race(call,call);assertEquals(results.getFirst(),results.getLast());UUID id=UUID.fromString(results.getFirst());assertEquals(1,count("SELECT count(*) FROM audit_event WHERE target_id=? AND event_type='TEMPLATE_IMPORTED'",id));
    }
    @Test void archiveSourceEditAndExportHaveOneConsistentDefinition()throws Exception{
        var f=configured(false,true);var before=portableDefinition(f.template());long version=version(f);var result=race(()->{byte[] bytes=transfer.export(root.principal(),f.template());return cz.mh.rallye.rallye_backend.templates.application.TemplateArchive.JSON.writeValueAsString(cz.mh.rallye.rallye_backend.templates.application.TemplateArchive.decode(bytes).template());},()->{service.updateStage(root.principal(),f.template(),f.stage(),version,"Edited",0L,0L,true,"Changed");return "UPDATED";});
        String old=cz.mh.rallye.rallye_backend.templates.application.TemplateArchive.JSON.writeValueAsString(before),next=cz.mh.rallye.rallye_backend.templates.application.TemplateArchive.JSON.writeValueAsString(portableDefinition(f.template()));assertTrue(Set.of(old,next).contains(result.getFirst()));assertEquals("UPDATED",result.getLast());
    }
    @Test void archiveExportMissingFileFailsAndNonAdminCannotPreview()throws Exception{
        var f=configured(false,true);markdown(f,"[Missing](missing.md)");assertEquals("TEMPLATE_EXPORT_FILE_MISSING",assertThrows(AuthFailure.class,()->transfer.export(root.principal(),f.template())).code());
        Actor user=actor();assertEquals(403,assertThrows(AuthFailure.class,()->transfer.preview(user.principal(),archiveInput(new byte[]{1}))).status());
    }
}
