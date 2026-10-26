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
    @DynamicPropertySource static void schema(DynamicPropertyRegistry p){p.add("rallye.templates.image-directory",()->IMAGE_DIR.toString());p.add("spring.flyway.schemas",()->SCHEMA);p.add("spring.flyway.default-schema",()->SCHEMA);p.add("spring.datasource.hikari.connection-init-sql",()->"SET search_path TO "+SCHEMA);}
    @Autowired cz.mh.rallye.rallye_backend.templates.application.TemplateImageService images;
    @Autowired cz.mh.rallye.rallye_backend.templates.application.TemplateDuplicationService copies;
    @Autowired cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore templateStore;
    @Autowired TemplateService service;@Autowired AuthService auth;@Autowired JdbcTemplate jdbc;
    @Autowired AccountStore accounts;@Autowired PlatformTransactionManager manager;@Autowired MutableClock clock;
    @Autowired PreRaceAccountParticipationFacts facts;@Autowired CommandCleanupStore cleanup;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @MockitoSpyBean AuditStore audit;@MockitoSpyBean CommandStore commands;
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
    @AfterAll void dropOnlyPrivateTestSchema()throws Exception{try(var files=java.nio.file.Files.list(IMAGE_DIR)){for(var file:files.toList())java.nio.file.Files.delete(file);}java.nio.file.Files.delete(IMAGE_DIR);assertTrue(SCHEMA.matches("it_templates_[0-9a-f]{32}"));assertEquals("rallye_test",jdbc.queryForObject("SELECT current_database()",String.class));assertEquals("rallye_test",jdbc.queryForObject("SELECT current_user",String.class));jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");}
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
        tx().executeWithoutResult(s->{int rank=jdbc.queryForObject("SELECT max(installed_rank)+1 FROM flyway_schema_history",Integer.class);jdbc.update("INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,installed_by,execution_time,success) VALUES (?,'19','Future','SQL','V19__future.sql',current_user,0,true)",rank);assertEquals(503,assertThrows(AuthFailure.class,()->service.delete(root.principal(),id,0)).status());s.setRollbackOnly();});
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
    Configured bareWithInstructions(){
        UUID id=create();UUID stage=service.createStage(root.principal(),id,0,UUID.randomUUID(),"Station",0L,0L).id();
        service.updateStage(root.principal(),id,stage,1,"Station",0L,0L,true,"Follow the plain text instructions.");return new Configured(id,stage);
    }
    TemplateService.ConfigurationChange config(boolean gps,boolean code,String newCode,long rate){
        return new TemplateService.ConfigurationChange(gps?new Gps(true,90.0,-180.0,0.5,rate,rate):new Gps(false,null,null,null,null,null),
            new TemplateService.CodeChange(code,newCode,code?rate:null),new TimePenalty(true,60000L,rate),new Delayed(true,0L,rate));
    }
    Configured configured(boolean gps,boolean code){var fixture=bareWithInstructions();service.configure(root.principal(),fixture.template(),fixture.stage(),2,config(gps,code,code?" synthetic-code ":null,-3));return fixture;}
    long version(Configured fixture){return service.detail(root.principal(),fixture.template()).version();}
    String storedHash(UUID stage){return jdbc.queryForObject("SELECT code_hash FROM template_stage WHERE id=?",String.class,stage);}
    @Test void incompleteReadinessIsReadOnlyAndReturnsFieldErrors(){
        UUID id=create();UUID stage=service.createStage(root.principal(),id,0,UUID.randomUUID(),null,null,null).id();
        var before=service.detail(root.principal(),id);long events=count("SELECT count(*) FROM audit_event");
        var report=service.readiness(root.principal(),id);assertFalse(report.ready());assertEquals(1,report.version());assertTrue(report.errors().stream().anyMatch(e->stage.equals(e.stageId()) && e.field().equals("instructionsHtml")));
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
        var f=configured(true,true);String hash=storedHash(f.stage());assertTrue(hash.startsWith("$argon2id$"));assertTrue(passwords.matches("SYNTHETIC-CODE",hash));
        String response=mvc.perform(get("/api/v1/race-templates/"+f.template()).header("Authorization","Bearer "+root.secret())).andExpect(status().isOk()).andExpect(jsonPath("$.stages[0].code.codeConfigured").value(true)).andExpect(jsonPath("$.stages[0].code.newCode").doesNotExist()).andExpect(jsonPath("$.stages[0].code.hash").doesNotExist()).andExpect(jsonPath("$.stages[0].statisticsVersion").doesNotExist()).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("synthetic-code"));assertFalse(response.contains("SYNTHETIC-CODE"));assertFalse(response.contains(hash));
        var before=service.detail(root.principal(),f.template());long events=count("SELECT count(*) FROM audit_event"),commandsBefore=count("SELECT count(*) FROM admin_role_command");
        assertEquals(before,service.configure(root.principal(),f.template(),f.stage(),3,config(true,true,null,-3)));
        assertEquals(before,service.configure(root.principal(),f.template(),f.stage(),3,config(true,true," SyNtHeTiC-CoDe ",-3)));
        assertTrue(Objects.equals(hash,storedHash(f.stage())),"Equivalent input must preserve the existing hash");assertEquals(events,count("SELECT count(*) FROM audit_event"));assertEquals(commandsBefore,count("SELECT count(*) FROM admin_role_command"));
        assertThrows(AuthFailure.class,()->service.configure(root.principal(),f.template(),f.stage(),3,config(true,true,"x".repeat(129),-3)));
        String ledger=String.join("",jdbc.queryForList("SELECT coalesce(request_data::text,'') || coalesce(result_data::text,'') FROM admin_role_command",String.class));
        String metadata=String.join("",jdbc.queryForList("SELECT metadata::text FROM audit_event",String.class));
        for(String value:List.of("synthetic-code","SYNTHETIC-CODE",hash,"x".repeat(129))){assertFalse(ledger.contains(value));assertFalse(metadata.contains(value));}
    }
    @Test void disabledCodeKeepsItsHashAndExplicitNullViaHttpPreservesIt()throws Exception{
        var f=configured(true,true);String hash=storedHash(f.stage());
        String body="""
            {"version":3,"gps":{"enabled":true,"latitude":90,"longitude":-180,"radiusMeters":0.5,"outsidePenaltyPoints":-3,"spoofingPenaltyPoints":-3},
             "code":{"enabled":true,"newCode":null,"wrongAttemptPenaltyPoints":-3},
             "timePenalty":{"enabled":true,"intervalSeconds":60,"pointsPerInterval":-3},"delayed":{"enabled":true,"minimumDurationMs":0,"pointsPerSecond":-3}}
            """;
        long commandsBefore=count("SELECT count(*) FROM admin_role_command");
        mvc.perform(put("/api/v1/race-templates/"+f.template()+"/stages/"+f.stage()+"/configuration").header("Authorization","Bearer "+root.secret()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3));
        service.configure(root.principal(),f.template(),f.stage(),3,config(true,false,null,-3));
        assertTrue(Objects.equals(hash,storedHash(f.stage())));assertTrue(service.detail(root.principal(),f.template()).stages().getFirst().code().codeConfigured());assertEquals(commandsBefore,count("SELECT count(*) FROM admin_role_command"));assertTrue(service.readiness(root.principal(),f.template()).ready());
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
        assertEquals(before,service.detail(root.principal(),f.template()));assertNull(storedHash(f.stage()));
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
    @Test void htmlInstructionsArePreservedClearedAndRequiredForReady()throws Exception{
        var f=configured(true,false);service.updateStage(root.principal(),f.template(),f.stage(),3,"Station",0L,0L,true,"Literal <b>text</b>.");
        var stage=service.stageDetail(root.principal(),f.template(),f.stage());assertEquals("Literal <b>text</b>.",stage.instructionsHtml());assertTrue(service.readiness(root.principal(),f.template()).ready());
        mvc.perform(get("/api/v1/race-templates/"+f.template()+"/stages/"+f.stage()).header("Authorization","Bearer "+root.secret())).andExpect(status().isOk()).andExpect(jsonPath("$.instructionsHtml").value("Literal <b>text</b>.")).andExpect(jsonPath("$.instructions").doesNotExist());
        service.updateStage(root.principal(),f.template(),f.stage(),4,"Station",0L,0L,true," ");assertFalse(service.readiness(root.principal(),f.template()).ready());
        service.updateStage(root.principal(),f.template(),f.stage(),5,"Station",0L,0L,true,null);assertNull(service.stageDetail(root.principal(),f.template(),f.stage()).instructionsHtml());
    }
    String publish(Configured f,long v){try{service.state(root.principal(),f.template(),v,"READY");return "OK";}catch(AuthFailure failure){return failure.code();}}
    @Test void publicationAndIncompleteEditRaceCannotLeaveInvalidReady()throws Exception{
        var f=configured(true,false);long v=version(f);var empty=StageConfiguration.empty();
        var results=race(()->publish(f,v),()->{try{service.configure(root.principal(),f.template(),f.stage(),v,new TemplateService.ConfigurationChange(new Gps(true,null,null,null,null,null),new TemplateService.CodeChange(false,null,null),empty.timePenalty(),empty.delayed()));return "OK";}catch(AuthFailure failure){return failure.code();}});
        assertEquals(1,results.stream().filter("OK"::equals).count());assertEquals(1,results.stream().filter("TEMPLATE_VERSION_CONFLICT"::equals).count());assertEquals(v+1,version(f));
        if(service.detail(root.principal(),f.template()).status().equals("READY"))assertTrue(service.readiness(root.principal(),f.template()).ready());
    }
    @Test void configurationAndPublicationAuditFailuresRollbackHashStateVersionAndAudit(){
        var f=configured(true,true);var before=service.detail(root.principal(),f.template());String hash=storedHash(f.stage());long events=count("SELECT count(*) FROM audit_event"),commandCount=count("SELECT count(*) FROM admin_role_command");
        failedAudit(Event.TEMPLATE_STAGE_CONFIGURED,f.stage(),()->service.configure(root.principal(),f.template(),f.stage(),3,config(true,true,"Replacement",9)));
        assertEquals(before,service.detail(root.principal(),f.template()));assertTrue(Objects.equals(hash,storedHash(f.stage())),"Code hash must roll back");
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
    UUID upload(UUID template)throws Exception{return images.upload(root.principal(),template,service.detail(root.principal(),template).version(),UUID.randomUUID(),png()).id();}
    String imageHtml(UUID template,UUID image){return "<img src=\""+cz.mh.rallye.rallye_backend.templates.application.InstructionsHtml.imageUrl(template,image)+"\">";}
    void html(Configured f,String html){service.updateStage(root.principal(),f.template(),f.stage(),version(f),"Station",0L,0L,true,html);}
    @Test void imageUploadReadsActualFormatsAndDoesNotDraftReadyTemplate()throws Exception{
        var f=configured(true,false);service.state(root.principal(),f.template(),version(f),"READY");long v=version(f);
        for(String format:List.of("png","jpeg")){
            var created=images.upload(root.principal(),f.template(),v,UUID.randomUUID(),new java.io.ByteArrayInputStream(cz.mh.rallye.rallye_backend.crews.PhotoFilesTest.image(format,12,8)));
            var content=images.content(root.principal(),f.template(),created.id());assertEquals("image/"+format,content.contentType());assertEquals(12,javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(content.bytes())).getWidth());
            mvc.perform(get(cz.mh.rallye.rallye_backend.templates.application.InstructionsHtml.imageUrl(f.template(),created.id())).header("Authorization","Bearer "+root.secret())).andExpect(status().isOk()).andExpect(header().string("X-Content-Type-Options","nosniff"));
        }
        assertEquals(v,version(f));assertEquals("READY",service.detail(root.principal(),f.template()).status());assertEquals(2,images.list(root.principal(),f.template()).size());
    }
    @Test void imageOnlyInstructionsAreReadyAndUsageCanBeSharedAndRemoved()throws Exception{
        var f=configured(true,false);UUID image=upload(f.template());String html=imageHtml(f.template(),image);html(f,html+html);
        assertEquals(1,count("SELECT count(*) FROM template_stage_image WHERE stage_id=?",f.stage()));assertTrue(service.readiness(root.principal(),f.template()).ready());
        UUID second=service.createStage(root.principal(),f.template(),version(f),UUID.randomUUID(),"Other",0L,0L).id();
        service.updateStage(root.principal(),f.template(),second,version(f),"Other",0L,0L,true,html);
        assertEquals(2,count("SELECT count(*) FROM template_stage_image WHERE image_id=?",image));
        assertEquals("TEMPLATE_IMAGE_IN_USE",assertThrows(AuthFailure.class,()->images.delete(root.principal(),f.template(),image,version(f))).code());
        html(f,"<p>No image</p>");assertEquals(1,count("SELECT count(*) FROM template_stage_image WHERE image_id=?",image));
        service.deleteStage(root.principal(),f.template(),second,version(f));assertEquals(0,count("SELECT count(*) FROM template_stage_image WHERE image_id=?",image));
        images.delete(root.principal(),f.template(),image,version(f));assertFalse(java.nio.file.Files.exists(IMAGE_DIR.resolve(image+".photo")));
    }
    @Test void htmlRejectsMissingAndForeignImageWithoutChangingInstructions()throws Exception{
        var f=configured(true,false);UUID other=create(),foreign=upload(other);var before=service.detail(root.principal(),f.template());
        for(String html:List.of(imageHtml(f.template(),UUID.randomUUID()),imageHtml(other,foreign),imageHtml(f.template(),foreign))){
            assertEquals("TEMPLATE_IMAGE_REFERENCE_INVALID",assertThrows(AuthFailure.class,()->html(f,html)).code());assertEquals(before,service.detail(root.principal(),f.template()));
        }
    }
    @Test void sanitizedNoOpKeepsReadyVersionTimestampAndAudit()throws Exception{
        var f=configured(true,false);html(f,"<p>Text</p>");service.state(root.principal(),f.template(),version(f),"READY");
        var before=service.detail(root.principal(),f.template());long events=count("SELECT count(*) FROM audit_event");
        html(f,"<p onclick='bad()'>Text</p>");assertEquals(before,service.detail(root.principal(),f.template()));assertEquals(events,count("SELECT count(*) FROM audit_event"));
        html(f,"<p>Changed</p>");assertEquals("DRAFT",service.detail(root.principal(),f.template()).status());assertEquals(before.version()+1,version(f));
    }
    @Test void htmlAuditFailureRollsBackHtmlReferencesVersionAndState()throws Exception{
        var f=configured(true,false);UUID image=upload(f.template());var before=service.detail(root.principal(),f.template());
        failedAudit(Event.TEMPLATE_STAGE_UPDATED,f.stage(),()->html(f,imageHtml(f.template(),image)));
        assertEquals(before,service.detail(root.principal(),f.template()));assertEquals(0,count("SELECT count(*) FROM template_stage_image WHERE stage_id=?",f.stage()));
    }
    @Test void uploadAuditFailureRollsBackMetadataCommandAndFile()throws Exception{
        UUID template=create(),key=UUID.randomUUID();byte[] bytes=cz.mh.rallye.rallye_backend.crews.PhotoFilesTest.image("png",2,2);
        long before;try(var files=java.nio.file.Files.list(IMAGE_DIR)){before=files.count();}
        // Initialize the lock file before measuring the failed upload's file effects.
        upload(template);try(var files=java.nio.file.Files.list(IMAGE_DIR)){before=files.count();}
        long rows=count("SELECT count(*) FROM template_image WHERE race_template_id=?",template);
        failedAudit(Event.TEMPLATE_IMAGE_CREATED,template,()->images.upload(root.principal(),template,0,key,new java.io.ByteArrayInputStream(bytes)));
        assertEquals(rows,count("SELECT count(*) FROM template_image WHERE race_template_id=?",template));assertEquals(0,count("SELECT count(*) FROM admin_role_command WHERE actor_id=? AND command_key=?",root.id(),key));
        try(var files=java.nio.file.Files.list(IMAGE_DIR)){assertEquals(before,files.count());}
    }
    @Test void imageDeleteAuditFailureKeepsMetadataAndReadableFile()throws Exception{
        UUID template=create(),image=upload(template);byte[] bytes=images.content(root.principal(),template,image).bytes();
        failedAudit(Event.TEMPLATE_IMAGE_DELETED,template,()->images.delete(root.principal(),template,image,0));
        assertArrayEquals(bytes,images.content(root.principal(),template,image).bytes());assertEquals(1,images.list(root.principal(),template).size());
    }
    @Test void imageUploadReplayAndConflictsUseExistingLedger()throws Exception{
        UUID template=create(),key=UUID.randomUUID();byte[] bytes=cz.mh.rallye.rallye_backend.crews.PhotoFilesTest.image("png",3,2);
        var first=images.upload(root.principal(),template,0,key,new java.io.ByteArrayInputStream(bytes));
        assertEquals(first,images.upload(root.principal(),template,0,key,new java.io.ByteArrayInputStream(bytes)));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AuthFailure.class,()->images.upload(root.principal(),template,1,key,new java.io.ByteArrayInputStream(bytes))).code());
        assertEquals(1,count("SELECT count(*) FROM template_image WHERE race_template_id=?",template));
    }
    @Test void concurrentUseAndDeletionCannotLeaveDanglingReference()throws Exception{
        var f=configured(true,false);UUID image=upload(f.template());long v=version(f);
        var results=race(()->{try{html(f,imageHtml(f.template(),image));return "USED";}catch(AuthFailure e){return e.code();}},
            ()->{try{images.delete(root.principal(),f.template(),image,v);return "DELETED";}catch(AuthFailure e){return e.code();}});
        assertFalse(results.contains("USED")&&results.contains("DELETED"));
        assertEquals(0,count("SELECT count(*) FROM template_stage_image u LEFT JOIN template_image i ON i.id=u.image_id WHERE i.id IS NULL"));
    }
    @Test void concurrentSameUploadKeyCreatesOnlyOneImage()throws Exception{
        UUID template=create(),key=UUID.randomUUID();byte[] bytes=cz.mh.rallye.rallye_backend.crews.PhotoFilesTest.image("png",3,2);
        var result=race(()->images.upload(root.principal(),template,0,key,new java.io.ByteArrayInputStream(bytes)).id().toString(),()->images.upload(root.principal(),template,0,key,new java.io.ByteArrayInputStream(bytes)).id().toString());
        assertEquals(result.getFirst(),result.getLast());assertEquals(1,images.list(root.principal(),template).size());assertEquals(1,count("SELECT count(*) FROM audit_event WHERE target_id=? AND event_type='TEMPLATE_IMAGE_CREATED'",template));
    }
    @Test void deletingTemplateCascadesImageMetadataReferencesAndFiles()throws Exception{
        var f=configured(true,false);UUID image=upload(f.template());html(f,imageHtml(f.template(),image));
        service.delete(root.principal(),f.template(),version(f));assertEquals(0,count("SELECT count(*) FROM template_image WHERE id=?",image));assertEquals(0,count("SELECT count(*) FROM template_stage_image WHERE image_id=?",image));assertFalse(java.nio.file.Files.exists(IMAGE_DIR.resolve(image+".photo")));
    }
    @Test void imageAccessRemainsAdminOnlyAndOldInstructionsFieldIsRejected()throws Exception{
        UUID template=create(),image=upload(template);var ordinary=actor();
        assertEquals(403,assertThrows(AuthFailure.class,()->images.list(ordinary.principal(),template)).status());
        assertEquals(403,assertThrows(AuthFailure.class,()->images.content(ordinary.principal(),template,image)).status());
        mvc.perform(put("/api/v1/race-templates/"+template+"/stages/"+UUID.randomUUID()).header("Authorization","Bearer "+root.secret()).contentType("application/json").content("{\"version\":0,\"instructions\":\"old\"}")).andExpect(status().isBadRequest());
    }
    @Test void v18PreservesSchemaGuardAndEnforcesImageTemplateForeignKeys()throws Exception{
        var f=configured(true,false);UUID other=create(),image=upload(other);
        assertThrows(DataAccessException.class,()->tx().executeWithoutResult(s->jdbc.update("INSERT INTO template_stage_image(stage_id,image_id,race_template_id) VALUES (?,?,?)",f.stage(),image,f.template())));
        assertEquals(1,count("SELECT count(*) FROM flyway_schema_history WHERE version='18' AND script='V18__template_html_and_images.sql' AND success"));
        tx().executeWithoutResult(s->{assertFalse(facts.forAccount(root.id()).historical());jdbc.update("UPDATE flyway_schema_history SET script='V18__unknown.sql' WHERE version='18'");assertEquals(503,assertThrows(AuthFailure.class,()->facts.forAccount(root.id())).status());assertEquals(503,assertThrows(AuthFailure.class,()->service.delete(root.principal(),f.template(),version(f))).status());s.setRollbackOnly();});
    }
    @Test void upgradeV17ToV18KeepsStageConfigurationAndCreatesEmptyImageRelations(){
        String upgrade=SCHEMA+"_v18";assertTrue(upgrade.matches("it_templates_[0-9a-f]{32}_v18"));
        assertEquals("rallye_test",jdbc.queryForObject("SELECT current_database()",String.class));assertEquals("rallye_test",jdbc.queryForObject("SELECT current_user",String.class));
        UUID template=UUID.randomUUID(),stage=UUID.randomUUID();
        try{
            org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas(upgrade).defaultSchema(upgrade).target("17").load().migrate();
            jdbc.update("INSERT INTO "+upgrade+".race_template(id,name,skip_penalty_points,created_at,updated_at) VALUES (?,'Upgrade',-3,now(),now())",template);
            jdbc.update("INSERT INTO "+upgrade+".template_stage(id,race_template_id,sequence_no,name,instructions,expected_duration_ms,max_penalty_points) VALUES (?,?,1,'Station',NULL,120000,0)",stage,template);
            org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas(upgrade).defaultSchema(upgrade).target("18").load().migrate();
            assertEquals(120000L,jdbc.queryForObject("SELECT expected_duration_ms FROM "+upgrade+".template_stage WHERE id=?",Long.class,stage));
            assertNull(jdbc.queryForObject("SELECT instructions_html FROM "+upgrade+".template_stage WHERE id=?",String.class,stage));
            assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM "+upgrade+".template_image",Long.class));
            assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM "+upgrade+".template_stage_image",Long.class));
        }finally{jdbc.execute("DROP SCHEMA \""+upgrade+"\" CASCADE");}
    }
    record CopyFixture(Configured first,UUID second,UUID used,UUID unused) {}
    CopyFixture copyFixture()throws Exception{
        var f=configured(true,true);UUID used=upload(f.template());
        UUID unused=images.upload(root.principal(),f.template(),version(f),UUID.randomUUID(),new java.io.ByteArrayInputStream(cz.mh.rallye.rallye_backend.crews.PhotoFilesTest.image("jpeg",3,4))).id();
        html(f,"<h2>Task</h2>"+imageHtml(f.template(),used)+imageHtml(f.template(),used));
        UUID second=service.createStage(root.principal(),f.template(),version(f),UUID.randomUUID(),"Second",1000L,10L).id();
        service.updateStage(root.principal(),f.template(),second,version(f),"Second",1000L,10L,true,imageHtml(f.template(),used));
        service.configure(root.principal(),f.template(),second,version(f),config(false,true,"second-code",0));
        return new CopyFixture(f,second,used,unused);
    }
    void equalStageConfiguration(UUID original,UUID copy){
        var first=new HashMap<>(jdbc.queryForMap("SELECT * FROM template_stage WHERE id=?",original));
        var second=new HashMap<>(jdbc.queryForMap("SELECT * FROM template_stage WHERE id=?",copy));
        for(String identity:List.of("id","race_template_id","sequence_no","instructions_html")){first.remove(identity);second.remove(identity);}
        assertTrue(first.equals(second),"Every current stage configuration column must match; sensitive values are not printed");
    }
    List<Long> duplicationCounts(){return List.of("race_template","template_stage","template_image","template_stage_image","audit_event","admin_role_command").stream().map(table->count("SELECT count(*) FROM "+table)).toList();}
    Set<String> imageFiles()throws Exception{try(var files=java.nio.file.Files.list(IMAGE_DIR)){return files.map(file->file.getFileName().toString()).filter(name->!name.equals(".storage.lock")).collect(java.util.stream.Collectors.toSet());}}
    void failedCopyAudit(UUID source,boolean stage,Runnable operation){
        var injected=new IllegalStateException("Injected copy audit failure");
        doAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());call.callRealMethod();throw injected;})
            .when(audit).appendTemplateCopy(any(),any(),eq(root.id()),any(),eq(source),any(),eq(stage));
        try{Throwable failure=assertThrows(RuntimeException.class,operation::run);while(failure.getCause()!=null)failure=failure.getCause();assertSame(injected,failure);}
        finally{doCallRealMethod().when(audit).appendTemplateCopy(any(),any(),eq(root.id()),any(),eq(source),any(),eq(stage));}
    }
    @ParameterizedTest @ValueSource(strings={"DRAFT","READY"})
    void duplicatesCompleteTemplateAndAllImagesIndependently(String status)throws Exception{
        var data=copyFixture();var f=data.first();if(status.equals("READY"))service.state(root.principal(),f.template(),version(f),"READY");
        var original=service.detail(root.principal(),f.template());var created=copies.duplicate(root.principal(),f.template(),UUID.randomUUID());
        var copy=service.detail(root.principal(),created.id());assertEquals(0,copy.version());assertEquals("DRAFT",copy.status());assertEquals(original.name()+" (kopie)",copy.name());assertEquals(original.description(),copy.description());assertEquals(original.skipPenaltyPoints(),copy.skipPenaltyPoints());assertEquals(2,copy.stages().size());assertEquals(original,service.detail(root.principal(),f.template()));
        var copiedImages=images.list(root.principal(),copy.id());assertEquals(2,copiedImages.size());assertEquals(1,copiedImages.stream().filter(i->i.used()).count());
        var used=copiedImages.stream().filter(i->i.used()).findFirst().orElseThrow();var unused=copiedImages.stream().filter(i->!i.used()).findFirst().orElseThrow();
        assertNotEquals(data.used(),used.id());assertNotEquals(data.unused(),unused.id());
        assertArrayEquals(images.content(root.principal(),f.template(),data.used()).bytes(),images.content(root.principal(),copy.id(),used.id()).bytes());
        assertArrayEquals(images.content(root.principal(),f.template(),data.unused()).bytes(),images.content(root.principal(),copy.id(),unused.id()).bytes());
        for(int i=0;i<2;i++){
            var old=original.stages().get(i);var next=copy.stages().get(i);assertNotEquals(old.id(),next.id());assertEquals(i+1,next.sequenceNo());equalStageConfiguration(old.id(),next.id());
            assertFalse(next.instructionsHtml().contains(f.template().toString()));assertTrue(next.instructionsHtml().contains(used.url()));
            assertEquals(Set.of(used.id()),templateStore.usedImages(next.id()));
        }
        assertTrue(passwords.matches("SYNTHETIC-CODE",storedHash(copy.stages().getFirst().id())));
        assertEquals(2,count("SELECT count(*) FROM template_stage_image WHERE image_id=?",used.id()));
        long events=count("SELECT count(*) FROM audit_event WHERE target_id=? AND event_type='TEMPLATE_DUPLICATED' AND metadata->>'sourceId'=?",copy.id(),f.template().toString());assertEquals(1,events);
        String ledger=jdbc.queryForObject("SELECT result_data::text FROM admin_role_command WHERE target_id=?",String.class,copy.id());assertFalse(ledger.contains("code"));
        String auditJson=jdbc.queryForObject("SELECT metadata::text FROM audit_event WHERE target_id=?",String.class,copy.id());assertFalse(auditJson.contains("hash"));
        service.updateStage(root.principal(),copy.id(),copy.stages().getFirst().id(),0,"Edited",0L,0L,true,"<p>Independent</p>");
        assertEquals(original,service.detail(root.principal(),f.template()));
    }
    @Test void duplicateIncompleteEmptyDraftAndLongUnicodeName(){
        UUID original=service.create(root.principal(),UUID.randomUUID(),"🚗".repeat(100),"Description",0).id();
        var copy=copies.duplicate(root.principal(),original,UUID.randomUUID());assertEquals(100,service.detail(root.principal(),copy.id()).name().codePointCount(0,service.detail(root.principal(),copy.id()).name().length()));assertTrue(service.detail(root.principal(),copy.id()).stages().isEmpty());
        service.createStage(root.principal(),original,0,UUID.randomUUID(),null,null,null);var incomplete=copies.duplicate(root.principal(),original,UUID.randomUUID());assertNull(service.detail(root.principal(),incomplete.id()).stages().getFirst().name());assertFalse(service.readiness(root.principal(),incomplete.id()).ready());
    }
    @Test void stageCopyIsImmediateCompleteSharedAndInvalidatesReady()throws Exception{
        var data=copyFixture();var f=data.first();service.state(root.principal(),f.template(),version(f),"READY");long v=version(f);var files=imageFiles();
        var created=copies.duplicateStage(root.principal(),f.template(),f.stage(),v,UUID.randomUUID());var result=service.detail(root.principal(),f.template());
        assertEquals(List.of(f.stage(),created.id(),data.second()),result.stages().stream().map(TemplateService.StageView::id).toList());assertEquals(List.of(1,2,3),result.stages().stream().map(TemplateService.StageView::sequenceNo).toList());assertEquals(v+1,result.version());assertEquals("DRAFT",result.status());
        equalStageConfiguration(f.stage(),created.id());assertEquals(result.stages().getFirst().instructionsHtml(),result.stages().get(1).instructionsHtml());assertEquals(Set.of(data.used()),templateStore.usedImages(created.id()));assertEquals(files,imageFiles());assertTrue(passwords.matches("SYNTHETIC-CODE",storedHash(created.id())));
        assertEquals("TEMPLATE_IMAGE_IN_USE",assertThrows(AuthFailure.class,()->images.delete(root.principal(),f.template(),data.used(),v+1)).code());
        assertEquals(1,count("SELECT count(*) FROM audit_event WHERE target_id=? AND event_type='TEMPLATE_STAGE_DUPLICATED' AND metadata->>'sourceId'=?",created.id(),f.stage().toString()));
    }
    @Test void duplicatingLastStageOfDraftKeepsContiguousOrder(){
        var f=configured(false,true);long v=version(f);var first=copies.duplicateStage(root.principal(),f.template(),f.stage(),v,UUID.randomUUID());var second=copies.duplicateStage(root.principal(),f.template(),first.id(),v+1,UUID.randomUUID());
        assertEquals(List.of(f.stage(),first.id(),second.id()),service.detail(root.principal(),f.template()).stages().stream().map(TemplateService.StageView::id).toList());assertEquals("DRAFT",service.detail(root.principal(),f.template()).status());
    }
    @Test void duplicateReplayConflictAndCrossOperationNamespace(){
        var f=configured(false,true);UUID fullKey=UUID.randomUUID(),stageKey=UUID.randomUUID();
        var full=copies.duplicate(root.principal(),f.template(),fullKey);assertEquals(full,copies.duplicate(root.principal(),f.template(),fullKey));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AuthFailure.class,()->copies.duplicate(root.principal(),create(),fullKey)).code());
        long v=version(f);var stage=copies.duplicateStage(root.principal(),f.template(),f.stage(),v,stageKey);
        assertEquals(stage,copies.duplicateStage(root.principal(),f.template(),f.stage(),v,stageKey));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AuthFailure.class,()->copies.duplicateStage(root.principal(),f.template(),f.stage(),v+1,stageKey)).code());
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AuthFailure.class,()->copies.duplicateStage(root.principal(),f.template(),f.stage(),v,fullKey)).code());
        assertEquals("TEMPLATE_VERSION_CONFLICT",assertThrows(AuthFailure.class,()->copies.duplicateStage(root.principal(),f.template(),f.stage(),v,UUID.randomUUID())).code());
        service.delete(root.principal(),f.template(),version(f));
        assertEquals(full,copies.duplicate(root.principal(),f.template(),fullKey));
        assertEquals(stage,copies.duplicateStage(root.principal(),f.template(),f.stage(),v,stageKey));
    }
    @Test void fullCopyAuditFailureRollsBackAllRowsAndNewFiles()throws Exception{
        var data=copyFixture();var f=data.first();var counts=duplicationCounts();var files=imageFiles();
        failedCopyAudit(f.template(),false,()->copies.duplicate(root.principal(),f.template(),UUID.randomUUID()));assertEquals(counts,duplicationCounts());assertEquals(files,imageFiles());
    }
    @Test void stageCopyAuditFailureRollsBackOrderVersionUsageAndLedger()throws Exception{
        var data=copyFixture();var f=data.first();var before=service.detail(root.principal(),f.template());var counts=duplicationCounts();var files=imageFiles();
        failedCopyAudit(f.stage(),true,()->copies.duplicateStage(root.principal(),f.template(),f.stage(),before.version(),UUID.randomUUID()));assertEquals(before,service.detail(root.principal(),f.template()));assertEquals(counts,duplicationCounts());assertEquals(files,imageFiles());
    }
    @Test void fullCopyCommandFailureAfterInsertRollsBackAllDataAndFiles()throws Exception{
        var data=copyFixture();var counts=duplicationCounts();var files=imageFiles();UUID key=UUID.randomUUID();var injected=new IllegalStateException("Injected copy ledger failure");
        doAnswer(call->{call.callRealMethod();assertEquals(1,count("SELECT count(*) FROM admin_role_command WHERE actor_id=? AND command_key=?",root.id(),key));throw injected;}).when(commands).save(eq(root.id()),eq(key),any(),eq("TEMPLATE_DUPLICATE"),anyString(),anyString(),any());
        try{Throwable failure=assertThrows(RuntimeException.class,()->copies.duplicate(root.principal(),data.first().template(),key));while(failure.getCause()!=null)failure=failure.getCause();assertSame(injected,failure);assertEquals(counts,duplicationCounts());assertEquals(files,imageFiles());}
        finally{doCallRealMethod().when(commands).save(eq(root.id()),eq(key),any(),eq("TEMPLATE_DUPLICATE"),anyString(),anyString(),any());}
    }
    @Test void missingSourceImageRollsBackEarlierFileCopies()throws Exception{
        var data=copyFixture();var ordered=templateStore.images(data.first().template());UUID missing=ordered.getLast().id();var path=IMAGE_DIR.resolve(missing+".photo");byte[] backup=java.nio.file.Files.readAllBytes(path);java.nio.file.Files.delete(path);
        try{var counts=duplicationCounts();var files=imageFiles();assertEquals(503,assertThrows(AuthFailure.class,()->copies.duplicate(root.principal(),data.first().template(),UUID.randomUUID())).status());assertEquals(counts,duplicationCounts());assertEquals(files,imageFiles());}
        finally{java.nio.file.Files.write(path,backup);}
    }
    @Test void concurrentSameKeyFullCopiesCreateExactlyOneSnapshot()throws Exception{
        var f=copyFixture().first();UUID key=UUID.randomUUID();long before=count("SELECT count(*) FROM race_template");
        var result=race(()->copies.duplicate(root.principal(),f.template(),key).id().toString(),()->copies.duplicate(root.principal(),f.template(),key).id().toString());
        assertEquals(result.getFirst(),result.getLast());assertEquals(before+1,count("SELECT count(*) FROM race_template"));assertEquals(1,count("SELECT count(*) FROM audit_event WHERE target_id=? AND event_type='TEMPLATE_DUPLICATED'",UUID.fromString(result.getFirst())));
    }
    @Test void concurrentStageCopiesUseVersionAndIdempotenceWithoutGaps()throws Exception{
        var f=configured(false,true);long v=version(f);UUID key=UUID.randomUUID();
        var replay=race(()->copies.duplicateStage(root.principal(),f.template(),f.stage(),v,key).id().toString(),()->copies.duplicateStage(root.principal(),f.template(),f.stage(),v,key).id().toString());assertEquals(replay.getFirst(),replay.getLast());assertEquals(v+1,version(f));
        Callable<String> copy=()->{try{copies.duplicateStage(root.principal(),f.template(),f.stage(),v+1,UUID.randomUUID());return "OK";}catch(AuthFailure failure){return failure.code();}};
        var conflict=race(copy,copy);assertEquals(1,conflict.stream().filter("OK"::equals).count());assertEquals(1,conflict.stream().filter("TEMPLATE_VERSION_CONFLICT"::equals).count());assertEquals(List.of(1,2,3),service.detail(root.principal(),f.template()).stages().stream().map(TemplateService.StageView::sequenceNo).toList());
    }
    @Test void copiesCannotBypassCurrentAdminOrRevealSecretsInHttp()throws Exception{
        var f=configured(true,true);var ordinary=actor();
        assertEquals(403,assertThrows(AuthFailure.class,()->copies.duplicate(ordinary.principal(),f.template(),UUID.randomUUID())).status());
        assertEquals(403,assertThrows(AuthFailure.class,()->copies.duplicateStage(ordinary.principal(),f.template(),f.stage(),version(f),UUID.randomUUID())).status());
        var response=mvc.perform(post("/api/v1/race-templates/"+f.template()+"/duplicate").header("Authorization","Bearer "+root.secret()).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isCreated()).andReturn();
        var id=tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.getResponse().getContentAsString()).get("id").asString();
        var detail=mvc.perform(get("/api/v1/race-templates/"+id).header("Authorization","Bearer "+root.secret())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(detail.contains(storedHash(f.stage())));assertFalse(detail.contains("newCode"));assertFalse(detail.contains("synthetic-code"));
        assertEquals("TEMPLATE_NOT_FOUND",assertThrows(AuthFailure.class,()->copies.duplicate(root.principal(),UUID.randomUUID(),UUID.randomUUID())).code());
        assertEquals("TEMPLATE_STAGE_NOT_FOUND",assertThrows(AuthFailure.class,()->copies.duplicateStage(root.principal(),f.template(),UUID.randomUUID(),version(f),UUID.randomUUID())).code());
    }
}
