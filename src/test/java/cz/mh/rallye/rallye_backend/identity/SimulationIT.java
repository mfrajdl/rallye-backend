package cz.mh.rallye.rallye_backend.identity;
import java.io.*;import java.nio.file.*;import java.sql.Timestamp;import java.time.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;import org.springframework.beans.factory.annotation.Autowired;import org.springframework.boot.test.context.SpringBootTest;import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;import org.springframework.context.annotation.Import;import org.springframework.test.annotation.DirtiesContext;import org.springframework.test.context.*;import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.transaction.PlatformTransactionManager;import org.springframework.transaction.support.TransactionTemplate;import org.springframework.test.web.servlet.MockMvc;
import cz.mh.rallye.rallye_backend.support.TestDatabaseInitializer;import cz.mh.rallye.rallye_backend.simulation.application.*;import cz.mh.rallye.rallye_backend.simulation.persistence.SimulationStore;import cz.mh.rallye.rallye_backend.templates.application.*;import cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration.*;import cz.mh.rallye.rallye_backend.identity.application.*;import cz.mh.rallye.rallye_backend.identity.persistence.*;import cz.mh.rallye.rallye_backend.audit.persistence.AuditStore;import cz.mh.rallye.rallye_backend.audit.application.AuditService.Event;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("it") @ContextConfiguration(initializers=TestDatabaseInitializer.class) @Import(IdentityIT.Fixtures.class) @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS) @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimulationIT {
 static final String SCHEMA="it_simulation_"+UUID.randomUUID().toString().replace("-","");static final Path DIR=directory();static Path directory(){try{return Files.createTempDirectory("it_simulation_");}catch(IOException e){throw new UncheckedIOException(e);}}
 @DynamicPropertySource static void properties(DynamicPropertyRegistry p){p.add("spring.flyway.schemas",()->SCHEMA);p.add("spring.flyway.default-schema",()->SCHEMA);p.add("spring.datasource.hikari.connection-init-sql",()->"SET search_path TO "+SCHEMA);p.add("rallye.templates.image-directory",()->DIR.resolve("templates").toString());p.add("rallye.races.file-directory",()->DIR.resolve("events").toString());p.add("rallye.simulation.file-directory",()->DIR.resolve("simulations").toString());}
 @Autowired org.springframework.beans.factory.config.AutowireCapableBeanFactory beanFactory;@Autowired SimulationService service;@Autowired TemplateService templates;@Autowired TemplateFileService templateFiles;@Autowired JdbcTemplate jdbc;@Autowired AccountStore accounts;@Autowired MutableClock clock;@Autowired PlatformTransactionManager manager;@Autowired MockMvc mvc;@Autowired AccountCancellationService cancellation;@Autowired AccountCancellationStore cancellationStore;@Autowired PreRaceAccountParticipationFacts facts;
 @MockitoSpyBean AuditStore audit;@MockitoSpyBean SimulationStore store;
 @Autowired cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore sourceStore;
 @Autowired cz.mh.rallye.rallye_backend.audit.application.AuditService fixtureAudit;
 @Autowired TemplateImageFiles sourceFiles;@Autowired TemplateFileContent sourceFormats;
 record Actor(UUID id,String token,AuthService.Principal principal){} Actor admin;Instant testStart;
 TransactionTemplate tx(){return new TransactionTemplate(manager);}
 Actor actor(boolean role){return tx().execute(s->{UUID id=UUID.randomUUID();String email=id+"@example.test",token=new SecretTokens().create();Timestamp at=Timestamp.from(clock.instant());jdbc.update("INSERT INTO user_account(id,email,normalized_email,display_name,status,email_verified_at,created_at,updated_at) VALUES (?,?,?,'Simulator','ACTIVE',?,?,?)",id,email,email,at,at,at);jdbc.update("INSERT INTO user_identity(id,account_id,provider,issuer,subject,password_hash,created_at) VALUES (?,?,'LOCAL','local',?,'synthetic-test-hash',?)",UUID.randomUUID(),id,id.toString(),at);UUID session=accounts.insertSession(id,new SecretTokens().hash(token),true,clock.instant(),clock.instant().plusSeconds(2592000));if(role)jdbc.update("INSERT INTO user_account_role(account_id,role,granted_at) VALUES (?,'ADMIN',?)",id,at);return new Actor(id,token,new AuthService.Principal(id,session,true));});}
 @BeforeAll void bootstrap()throws Exception{admin=actor(false);String sql=AdministrationIT.bootstrapBody(admin.id());tx().executeWithoutResult(s->jdbc.execute(sql));}
 @BeforeEach void time(){testStart=clock.instant();}@AfterEach void resetTime(){clock.advance(Duration.between(clock.instant(),testStart));}
 @AfterAll void cleanup()throws Exception{assertTrue(SCHEMA.matches("it_simulation_[0-9a-f]{32}"));assertEquals("rallye_test",jdbc.queryForObject("SELECT current_database()",String.class));assertEquals("rallye_test",jdbc.queryForObject("SELECT current_user",String.class));jdbc.execute("DROP SCHEMA "+SCHEMA+" CASCADE");assertTrue(DIR.getFileName().toString().startsWith("it_simulation_"));try(var all=Files.walk(DIR)){for(var p:all.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
 record Fixture(UUID template,UUID stage){}
 /** Build only the source template here; simulator creation/snapshotting, journal, replay and
  * mutations below always use the real service in their own committed transactions.
  * Preserve source versions 3/4 and real audit; omit only source-fixture idempotency receipts.
  */
 Fixture source(boolean ready)throws Exception{
  UUID template=UUID.randomUUID(),stage=UUID.randomUUID();
  String instructions="# Route\n\n![Map]("+ImageNames.url(template,"map.png")+") [Guide]("+ImageNames.fileUrl(template,"guide.md")+")";
  var configuration=new cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration(
    new Gps(true,50d,14d,100d,4L,5L),new Code(true,TemplateService.normalizeCode("PRIVATE-ANSWER"),3L,false),
    new TimePenalty(true,60000L,1L),new cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration.Delayed(true,1000L,2L));
  configuration.validate();InstructionsMarkdown.analyze(template,instructions);
  byte[] png=cz.mh.rallye.rallye_backend.crews.PhotoFilesTest.image("png",2,2);
  byte[] guide=("# Guide\n\n![Map]("+ImageNames.url(template,"map.png")+")").getBytes(java.nio.charset.StandardCharsets.UTF_8);
  var contents=new LinkedHashMap<String,TemplateFileContent.Validated>();
  contents.put("map.png",sourceFormats.validate("map.png",new ByteArrayInputStream(png),sourceFiles.files));
  contents.put("guide.md",sourceFormats.validate("guide.md",new ByteArrayInputStream(guide),sourceFiles.files));
  return sourceFiles.files.exclusive(()->{
   try{return tx().execute(s->{
    // Preserve filesystem -> ADMIN/account -> template lock ordering and production authorization.
    templates.list(admin.principal(),null,null,0,1);var at=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    sourceStore.insert(template,"Simulator source",null,2,at);fixtureAudit.template(admin.id(),Event.TEMPLATE_CREATED,template,false);
    sourceStore.insertStage(stage,template,1,"Station",60000L,10L);sourceStore.touch(template,at);
    fixtureAudit.template(admin.id(),Event.TEMPLATE_STAGE_CREATED,stage,true);
    sourceStore.instructionsMarkdown(stage,instructions);sourceStore.touch(template,at);
    fixtureAudit.template(admin.id(),Event.TEMPLATE_STAGE_UPDATED,stage,true);
    sourceStore.configureStage(stage,configuration);sourceStore.touch(template,at);
    fixtureAudit.template(admin.id(),Event.TEMPLATE_STAGE_CONFIGURED,stage,true);
    contents.forEach((name,content)->{
     sourceFiles.write(template,name,content.bytes());sourceStore.file(UUID.randomUUID(),template,name,content,at);
     fixtureAudit.template(admin.id(),Event.TEMPLATE_FILE_CREATED,template,false);
    });
    var library=sourceStore.images(template);
    for(var content:contents.values())if(content.markdown()!=null)
     TemplateFileReferences.validate(InstructionsMarkdown.analyzeDocument(template,content.markdown()),library);
    sourceStore.references(template,stage,TemplateFileReferences.imageIds(template,instructions,library));
    if(ready)templates.state(admin.principal(),template,3,"READY");
    return new Fixture(template,stage);
   });}catch(RuntimeException failure){
    // An uncertain commit must never unlink referenced content. Own guarded class cleanup remains a fallback.
    for(String name:contents.keySet())try{if(!sourceStore.imageNameReferenced(template,name))sourceFiles.remove(template,name);}
    catch(RuntimeException unavailable){/* Only this class's private directory is removed by cleanup(). */}
    throw failure;
   }
  });
 }
 @Test void sourceFixturesKeepDraftReadyVersionsAuditAndIndependentFiles()throws Exception{
  var draft=source(false);var ready=source(true);assertNotEquals(draft.template(),ready.template());assertNotEquals(draft.stage(),ready.stage());
  var untouched=templates.detail(admin.principal(),ready.template());
  for(var f:List.of(draft,ready)){
   boolean published=f.equals(ready);var view=templates.detail(admin.principal(),f.template());
   assertEquals(published?"READY":"DRAFT",view.status());assertEquals(published?4:3,view.version());
   assertTrue(templates.readiness(admin.principal(),f.template()).ready());
   assertEquals(2,templateFiles.list(admin.principal(),f.template()).items().size());
   assertEquals(published?7:6,count("SELECT count(*) FROM audit_event WHERE target_id IN (?,?)",f.template(),f.stage()));
   assertEquals(1,count("SELECT count(*) FROM template_stage_image WHERE stage_id=? AND race_template_id=?",f.stage(),f.template()));
   assertTrue(Files.isRegularFile(DIR.resolve("templates/race-templates/"+f.template()+"/images/map.png")));
  }
  templates.updateStage(admin.principal(),draft.template(),draft.stage(),3,"Changed",60000L,10L,true,"Other");
  templateFiles.delete(admin.principal(),draft.template(),"guide.md",4);
  assertEquals(untouched,templates.detail(admin.principal(),ready.template()));
  assertTrue(Files.isRegularFile(DIR.resolve("templates/race-templates/"+ready.template()+"/images/guide.md")));
 }
 // Fixture callers need only the source version; simulator authorization remains in the tested service.
 long version(UUID template){return jdbc.queryForObject("SELECT version FROM race_template WHERE id=?",Long.class,template);}long count(String sql,Object...args){return jdbc.queryForObject(sql,Long.class,args);}
 // Existing journal/replay tests exercise legacy implicitly-started runs, including version zero.
 // New lifecycle tests below use service.create directly and the real explicit start.
 SimulationView.View legacyFixture(SimulationView.View v){return tx().execute(t->{
  var row=store.lock(v.id());var state=new cz.mh.rallye.rallye_backend.competition.domain.RaceEvaluator().start(row.definition(),row.state().virtualStartAt());
  var race=SimulationStore.Race.legacy(state);jdbc.update("UPDATE race_simulation SET projection=?::jsonb,race=?::jsonb WHERE id=?",SimulationStore.json(state),SimulationStore.json(race),v.id());
  jdbc.update("UPDATE simulation_run SET initial_state=?::jsonb,initial_race=?::jsonb WHERE simulation_id=? AND id=?",SimulationStore.json(state),SimulationStore.json(race),v.id(),v.runId());
  return service.get(admin.principal(),v.id());
 });}
 SimulationView.View create(Fixture f){return legacyFixture(service.create(admin.principal(),f.template(),version(f.template()),UUID.randomUUID(),clock.instant()));}
 SimulationService.Input input(SimulationView.View v,String code){return new SimulationService.Input(v.version(),v.runId(),v.currentStage()==null?null:v.currentStage().id(),code,null,null,null,null,null,null,null);}
 SimulationView.View command(SimulationView.View v,String op,String code){return service.mutate(admin.principal(),v.id(),UUID.randomUUID(),op,input(v,code));}
 SimulationView.View shift(SimulationView.View v,long delta){return service.mutate(admin.principal(),v.id(),UUID.randomUUID(),"CLOCK_SHIFT",new SimulationService.Input(v.version(),v.runId(),null,null,delta,null,null,null,null,null,null));}
 SimulationView.View target(SimulationView.View v){return service.mutate(admin.principal(),v.id(),UUID.randomUUID(),"POSITION",new SimulationService.Input(v.version(),v.runId(),v.currentStage().id(),null,null,"TARGET",null,null,null,null,null));}
 List<String> race(Callable<String> first,Callable<String> second)throws Exception{var barrier=new CyclicBarrier(2);try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return first.call();});var b=pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return second.call();});return List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));}}
 @Test void draftAndReadySnapshotsAreIndependentAndSafe()throws Exception{for(boolean ready:new boolean[]{false,true}){var f=source(ready);var before=templates.detail(admin.principal(),f.template());long events=count("SELECT count(*) FROM race_event"),crews=count("SELECT count(*) FROM crew");var sim=create(f);assertEquals(before,templates.detail(admin.principal(),f.template()));assertEquals(2,service.files(admin.principal(),sim.id()).size());assertFalse(SimulationStore.json(sim).contains("PRIVATE-ANSWER"));assertTrue(sim.currentStage().instructionsMarkdown().contains("/admin/race-simulations/"+sim.id()));assertFalse(sim.currentStage().instructionsMarkdown().contains(f.template().toString()));assertTrue(new String(service.file(admin.principal(),sim.id(),"guide.md").bytes(),java.nio.charset.StandardCharsets.UTF_8).contains("/admin/race-simulations/"+sim.id()));templates.updateStage(admin.principal(),f.template(),f.stage(),version(f.template()),"Changed",0L,0L,true,"Other");templates.delete(admin.principal(),f.template(),version(f.template()));assertEquals(sim,service.get(admin.principal(),sim.id()));assertEquals(events,count("SELECT count(*) FROM race_event"));assertEquals(crews,count("SELECT count(*) FROM crew"));}}
 @Test void incompleteSourceIsRejectedWithoutArtifacts(){UUID template=templates.create(admin.principal(),UUID.randomUUID(),"Incomplete",null,0).id();var failure=assertThrows(SimulationService.NotReady.class,()->service.create(admin.principal(),template,0,UUID.randomUUID(),null));assertFalse(failure.report().errors().isEmpty());assertEquals(0,count("SELECT count(*) FROM race_simulation WHERE source_template_id=?",template));}
 @Test void commandReplayAndWrongCodeHistoryPreserveConfirmedCode()throws Exception{var sim=create(source(false));UUID key=UUID.randomUUID();var input=input(sim,"SUBMITTED-NO");var first=service.mutate(admin.principal(),sim.id(),key,"CODE",input);assertEquals("3",first.dashboard().totalPenaltyPoints());assertEquals(first,service.mutate(admin.principal(),sim.id(),key,"CODE",input));assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AuthFailure.class,()->service.mutate(admin.principal(),sim.id(),key,"CODE",input(first,"OTHER"))).code());String record=jdbc.queryForObject("SELECT row_to_json(c)::text FROM simulation_command c WHERE simulation_id=?",String.class,sim.id());assertTrue(record.contains("SUBMITTED-NO"));assertFalse(record.contains("PRIVATE-ANSWER"));assertFalse(record.contains(admin.token()));assertEquals("SUBMITTED-NO",service.history(admin.principal(),sim.id(),sim.runId(),0,50).items().getFirst().input().code());assertEquals(1,service.history(admin.principal(),sim.id(),sim.runId(),0,50).items().size());}
 @Test void clockBackFloorRestoreAndSkipHaveExactScores()throws Exception{var sim=create(source(false));sim=shift(sim,60001);sim=command(sim,"CODE","NO");var point=sim;sim=shift(sim,-1000);assertTrue(sim.clock().clockBeforeState());var past=sim;assertEquals("SIMULATION_CLOCK_BEFORE_STATE",assertThrows(AuthFailure.class,()->command(past,"CODE","PRIVATE-ANSWER")).code());sim=service.mutate(admin.principal(),sim.id(),UUID.randomUUID(),"RESTORE",new SimulationService.Input(sim.version(),sim.runId(),null,null,null,null,null,null,null,null,point.dashboard().lastAction().sequence()));assertEquals("3",sim.dashboard().totalPenaltyPoints());assertNotEquals(point.runId(),sim.runId());sim=command(sim,"SKIP",null);assertEquals("12",sim.dashboard().totalPenaltyPoints());assertEquals("9",sim.dashboard().lastAction().pointsDelta());assertEquals("COMPLETED",sim.status());assertFalse(sim.dashboard().recentPenalties().getFirst().effective());var archived=service.history(admin.principal(),sim.id(),sim.runId(),0,50);assertTrue(archived.items().size()>=3);}
 @Test void andTimeDelayedAndRestartKeepOldRunReadonly()throws Exception{var sim=create(source(true));sim=command(sim,"CODE","PRIVATE-ANSWER");assertEquals("RUNNING",sim.status());sim=command(sim,"CODE","NO");assertEquals("0",sim.dashboard().totalPenaltyPoints());sim=target(sim);sim=command(sim,"GPS",null);assertEquals("COMPLETED",sim.status());assertEquals("2",sim.dashboard().totalPenaltyPoints());UUID old=sim.runId();sim=command(sim,"RESTART",null);assertNotEquals(old,sim.runId());assertEquals("0",sim.dashboard().totalPenaltyPoints());assertTrue(sim.currentStage().satisfiedChecks().isEmpty());var restarted=sim;assertEquals("SIMULATION_RUN_CONFLICT",assertThrows(AuthFailure.class,()->service.mutate(admin.principal(),restarted.id(),UUID.randomUUID(),"CODE",new SimulationService.Input(restarted.version(),old,restarted.currentStage().id(),"PRIVATE-ANSWER",null,null,null,null,null,null,null))).code());assertFalse(service.history(admin.principal(),sim.id(),old,0,50).items().isEmpty());}
 @Test void concurrentSameKeyHasOneEffectAndDifferentKeysConflict()throws Exception{var sim=create(source(false));UUID key=UUID.randomUUID();Callable<String> same=()->SimulationStore.json(service.mutate(admin.principal(),sim.id(),key,"CODE",input(sim,"NO")));var answers=race(same,same);assertEquals(answers.getFirst(),answers.getLast());assertEquals(1,count("SELECT count(*) FROM simulation_command WHERE simulation_id=?",sim.id()));var latest=service.get(admin.principal(),sim.id());Callable<String> next=()->{try{service.mutate(admin.principal(),sim.id(),UUID.randomUUID(),"CODE",input(latest,"NO"));return "OK";}catch(AuthFailure e){return e.code();}};var results=race(next,next);assertEquals(1,results.stream().filter("OK"::equals).count());assertEquals(1,results.stream().filter("SIMULATION_VERSION_CONFLICT"::equals).count());}
 @Test void journalFailureRollsBackProjectionPointsAndVersion()throws Exception{var sim=create(source(false));var fail=new AtomicBoolean(true);doAnswer(i->{i.callRealMethod();if(fail.get())throw new IllegalStateException("Injected command persistence failure");return null;}).when(store).command(any(),eq(sim.id()),any(),any(),any(),anyLong(),any(),anyLong(),any(),any(),any(),any(),any());assertThrows(RuntimeException.class,()->command(sim,"CODE","NO"));assertEquals(sim,service.get(admin.principal(),sim.id()));assertEquals(0,count("SELECT count(*) FROM simulation_command WHERE simulation_id=?",sim.id()));fail.set(false);assertEquals("3",command(sim,"CODE","NO").dashboard().totalPenaltyPoints());}
 @Test void auditFailureRollsBackRestartAndInitialCreation()throws Exception{var f=source(false);var sim=create(f);var fail=new AtomicBoolean(true);doAnswer(i->{i.callRealMethod();if(fail.get())throw new IllegalStateException("Injected simulation audit failure");return null;}).when(audit).appendSimulation(any(),any(),any(),eq(Event.SIMULATION_RESTARTED),eq(sim.id()));assertThrows(RuntimeException.class,()->command(sim,"RESTART",null));assertEquals(sim,service.get(admin.principal(),sim.id()));assertEquals(1,count("SELECT count(*) FROM simulation_run WHERE simulation_id=?",sim.id()));fail.set(false);assertNotEquals(sim.runId(),command(sim,"RESTART",null).runId());}
 @Test void ownerIsolationIncludesFilesAndHistoryAndRoleRemoval()throws Exception{var sim=create(source(false));var other=actor(true);assertEquals(404,assertThrows(AuthFailure.class,()->service.get(other.principal(),sim.id())).status());assertEquals(404,assertThrows(AuthFailure.class,()->service.file(other.principal(),sim.id(),"map.png")).status());assertEquals(404,assertThrows(AuthFailure.class,()->service.history(other.principal(),sim.id(),sim.runId(),0,50)).status());var own=service.create(other.principal(),sim.source().templateId(),sim.source().templateVersion(),UUID.randomUUID(),null);jdbc.update("DELETE FROM user_account_role WHERE account_id=?",other.id());assertEquals(403,assertThrows(AuthFailure.class,()->service.get(other.principal(),own.id())).status());}
 @Test void physicalCancellationAndAnonymizationRemoveSimulations()throws Exception{var f=source(false);var deleted=actor(true);var a=service.create(deleted.principal(),f.template(),version(f.template()),UUID.randomUUID(),null);assertFalse(Boolean.TRUE.equals(tx().execute(s->facts.forAccount(deleted.id()).historical())));cancellation.administrator(admin.principal(),deleted.id(),UUID.randomUUID());assertEquals(0,count("SELECT count(*) FROM race_simulation WHERE id=?",a.id()));assertEquals(0,count("SELECT count(*) FROM simulation_run WHERE simulation_id=?",a.id()));var anonymous=actor(true);var b=service.create(anonymous.principal(),f.template(),version(f.template()),UUID.randomUUID(),null);tx().executeWithoutResult(s->{cancellationStore.deleteIdentities(anonymous.id());cancellationStore.deleteSessions(anonymous.id());cancellationStore.deleteTokens(anonymous.id());cancellationStore.deleteRoles(anonymous.id());cancellationStore.anonymize(anonymous.id(),clock.instant());});assertEquals(0,count("SELECT count(*) FROM race_simulation WHERE id=?",b.id()));}
 @Test void simulationsSurviveInactivityAndNewSessionWithoutLosingState()throws Exception{
  var sim=create(source(false));sim=shift(sim,60001);sim=command(sim,"CODE","wrong");var before=sim;
  assertNull(sim.expiresAt());clock.advance(Duration.ofDays(365));
  admin=tx().execute(t->{var tokens=new SecretTokens();String secret=tokens.create();UUID session=accounts.insertSession(admin.id(),tokens.hash(secret),true,clock.instant(),clock.instant().plus(Duration.ofDays(30)));return new Actor(admin.id(),secret,new AuthService.Principal(admin.id(),session,true));});
  service.cleanup();var reloaded=beanFactory.createBean(SimulationService.class);
  assertEquals(before,reloaded.get(admin.principal(),sim.id()));assertEquals(2,reloaded.history(admin.principal(),sim.id(),sim.runId(),0,50).items().size());
  assertEquals("6",command(before,"CODE","wrong").dashboard().totalPenaltyPoints());
 }

 @Test void foreignRunFkAndHttpStateContract()throws Exception{var f=source(false);var a=create(f);var b=create(f);assertThrows(RuntimeException.class,()->tx().executeWithoutResult(s->jdbc.update("UPDATE race_simulation SET current_run_id=? WHERE id=?",b.runId(),a.id())));assertEquals(a,service.get(admin.principal(),a.id()));mvc.perform(get("/api/v1/admin/race-simulations/"+a.id()).header("Authorization","Bearer "+admin.token())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.dashboard.totalPenaltyPoints").value("0")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE-ANSWER"))));}
 @Test void restoreWorksAfterIndependentServiceReload()throws Exception{var sim=create(source(false));sim=command(sim,"CODE","NO");var checkpoint=sim;sim=command(sim,"SKIP",null);var stored=store.lock(sim.id());assertEquals(12,cz.mh.rallye.rallye_backend.competition.domain.RaceEvaluator.total(stored.state()));var reloaded=beanFactory.createBean(SimulationService.class);assertEquals(sim,reloaded.get(admin.principal(),sim.id()));var restored=reloaded.mutate(admin.principal(),sim.id(),UUID.randomUUID(),"RESTORE",new SimulationService.Input(sim.version(),sim.runId(),null,null,null,null,null,null,null,null,checkpoint.dashboard().lastAction().sequence()));assertEquals("3",restored.dashboard().totalPenaltyPoints());assertEquals(restored,service.get(admin.principal(),sim.id()));}
 @Test void reviewedV23AndUnknownFutureAreHandledExplicitly(){assertFalse(Boolean.TRUE.equals(tx().execute(s->facts.forAccount(admin.id()).historical())));int rank=jdbc.queryForObject("SELECT max(installed_rank)+1 FROM flyway_schema_history",Integer.class);jdbc.update("INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,installed_by,execution_time,success) VALUES (?,'28','Unknown','SQL','V28__unknown.sql',current_user,0,true)",rank);try{assertEquals(503,assertThrows(AuthFailure.class,()->tx().execute(s->facts.forAccount(admin.id()))).status());}finally{jdbc.update("DELETE FROM flyway_schema_history WHERE installed_rank=?",rank);}}

 long physicalFiles()throws IOException{Path root=DIR.resolve("simulations");if(!Files.exists(root))return 0;try(var paths=Files.walk(root)){return paths.filter(Files::isRegularFile).filter(p->!p.getFileName().toString().equals(".storage.lock")).count();}}
 @Test void creationAuditFailureRollsBackSnapshotLedgerAndFilesAndCanRetry()throws Exception{var f=source(false);UUID key=UUID.randomUUID();long before=physicalFiles();var fail=new AtomicBoolean(true);doAnswer(i->{i.callRealMethod();if(fail.get())throw new IllegalStateException("Injected create audit failure");return null;}).when(audit).appendSimulation(any(),any(),any(),eq(Event.SIMULATION_CREATED),any());assertThrows(RuntimeException.class,()->service.create(admin.principal(),f.template(),version(f.template()),key,null));assertEquals(0,count("SELECT count(*) FROM race_simulation WHERE source_template_id=?",f.template()));assertEquals(0,count("SELECT count(*) FROM admin_role_command WHERE command_key=?",key));assertEquals(before,physicalFiles());fail.set(false);var created=service.create(admin.principal(),f.template(),version(f.template()),key,null);assertEquals(created,service.create(admin.principal(),f.template(),version(f.template()),key,null));}
 @Test void concurrentSourceEditCannotMixSnapshots()throws Exception{var f=source(true);long v=version(f.template());var results=race(()->{try{return service.create(admin.principal(),f.template(),v,UUID.randomUUID(),null).id().toString();}catch(AuthFailure e){return e.code();}},()->{templates.updateStage(admin.principal(),f.template(),f.stage(),v,"Changed",60000L,10L,true,"# Changed");return "UPDATED";});assertEquals("UPDATED",results.getLast());if(results.getFirst().equals("TEMPLATE_VERSION_CONFLICT"))assertEquals(0,count("SELECT count(*) FROM race_simulation WHERE source_template_id=?",f.template()));else assertTrue(service.get(admin.principal(),UUID.fromString(results.getFirst())).currentStage().instructionsMarkdown().contains("# Route"));}
 @Test void cancellationAndAttemptCannotLeaveUsableSimulation()throws Exception{var f=source(false);var owner=actor(true);var pending=service.create(owner.principal(),f.template(),version(f.template()),UUID.randomUUID(),null);var sim=service.mutate(owner.principal(),pending.id(),UUID.randomUUID(),"RACE_START",organizerInput(pending));var results=race(()->{try{service.mutate(owner.principal(),sim.id(),UUID.randomUUID(),"CODE",input(sim,"NO"));return "ATTEMPTED";}catch(AuthFailure e){return e.code();}},()->{cancellation.administrator(admin.principal(),owner.id(),UUID.randomUUID());return "CANCELLED";});assertEquals("CANCELLED",results.getLast());assertTrue(Set.of("ATTEMPTED","INVALID_CREDENTIALS","UNAUTHENTICATED","SIMULATION_NOT_FOUND").contains(results.getFirst()));assertEquals(0,count("SELECT count(*) FROM race_simulation WHERE id=?",sim.id()));assertEquals(0,count("SELECT count(*) FROM simulation_command WHERE simulation_id=?",sim.id()));}
 @Test void upgradeV22ToV23PreservesExistingSourceData(){String schema=SCHEMA+"_upgrade";assertTrue(schema.matches("it_simulation_[0-9a-f]{32}_upgrade"));UUID source=UUID.randomUUID();try{org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).target("22").load().migrate();jdbc.update("INSERT INTO "+schema+".race_template(id,name,status,skip_penalty_points,created_at,updated_at) VALUES (?,'Preserved','DRAFT',-2,now(),now())",source);org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).target("23").load().migrate();assertEquals("Preserved",jdbc.queryForObject("SELECT name FROM "+schema+".race_template WHERE id=?",String.class,source));assertEquals(-2,count("SELECT skip_penalty_points FROM "+schema+".race_template WHERE id=?",source));assertEquals(0,count("SELECT count(*) FROM "+schema+".race_simulation"));assertEquals(4,count("SELECT count(*) FROM information_schema.tables WHERE table_schema=? AND table_name IN ('race_simulation','simulation_run','simulation_command','simulation_file')",schema));}finally{jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}}
 @Test void expiredReplayKeepsFactsButCannotApplyTheSameKeyAgain()throws Exception{var sim=create(source(false));UUID key=UUID.randomUUID();var original=input(sim,"NO");sim=service.mutate(admin.principal(),sim.id(),key,"CODE",original);clock.advance(Duration.ofHours(23));sim=command(sim,"CODE","NO");clock.advance(Duration.ofHours(2));var active=sim;assertEquals("IDEMPOTENCY_KEY_EXPIRED",assertThrows(AuthFailure.class,()->service.mutate(admin.principal(),active.id(),key,"CODE",original)).code());service.cleanup();assertEquals("6",service.get(admin.principal(),sim.id()).dashboard().totalPenaltyPoints());assertNull(jdbc.queryForObject("SELECT request_fingerprint FROM simulation_command WHERE simulation_id=? AND command_key=?",String.class,sim.id(),key));assertEquals(2,service.history(admin.principal(),sim.id(),sim.runId(),0,50).items().size());}
 @Test void deleteAuditFailurePreservesSnapshotAndFilesUntilExplicitRetry()throws Exception{
  var sim=create(source(false));Path image=DIR.resolve("simulations/race-simulations/"+sim.id()+"/images/map.png");var fail=new AtomicBoolean(true);
  doAnswer(i->{i.callRealMethod();if(fail.get())throw new IllegalStateException("Injected delete audit failure");return null;}).when(audit).appendSimulation(any(),any(),any(),eq(Event.SIMULATION_DELETED),eq(sim.id()));
  assertThrows(RuntimeException.class,()->service.delete(admin.principal(),sim.id(),sim.version()));assertEquals(sim,service.get(admin.principal(),sim.id()));assertTrue(Files.exists(image));
  assertEquals(0,count("SELECT count(*) FROM audit_event WHERE target_id=? AND event_type='SIMULATION_DELETED'",sim.id()));
  fail.set(false);service.delete(admin.principal(),sim.id(),sim.version());assertFalse(Files.exists(image));
  assertEquals(1,count("SELECT count(*) FROM audit_event WHERE target_id=? AND event_type='SIMULATION_DELETED'",sim.id()));
 }


 @Test void confirmedCodesPersistExactlyAcrossReloadAndIdempotentRetry()throws Exception{
  var sim=create(source(false));UUID key=UUID.randomUUID();String wrong="  wrong MiXeD\t";
  var first=service.mutate(admin.principal(),sim.id(),key,"CODE",input(sim,wrong));
  assertEquals(first,service.mutate(admin.principal(),sim.id(),key,"CODE",input(sim,"WRONG MIXED")));
  var next=command(first,"CODE"," private-answer ");
  var reloaded=beanFactory.createBean(SimulationService.class);var page=reloaded.history(admin.principal(),sim.id(),sim.runId(),0,50);
  assertEquals(2,page.items().size());assertEquals(wrong,page.items().getFirst().input().code());assertEquals(" private-answer ",page.items().getLast().input().code());
  assertEquals("CODE_REJECTED",page.items().getFirst().outcome());assertEquals("CODE_ACCEPTED",page.items().getLast().outcome());
  assertEquals("3",page.items().getFirst().pointsDelta());assertEquals(next.currentStage().id(),page.items().getFirst().stageId());
  var stale=first;assertThrows(AuthFailure.class,()->command(stale,"CODE","rejected HTTP request"));
  assertEquals(2,reloaded.history(admin.principal(),sim.id(),sim.runId(),0,50).items().size());
  String facts=jdbc.queryForObject("SELECT verified_facts::text FROM simulation_command WHERE simulation_id=? AND sequence=1",String.class,sim.id());
  assertFalse(facts.contains(admin.token()));assertFalse(facts.contains("Authorization"));assertFalse(facts.contains("password"));
 }
 @Test void positionInputsAndGpsChecksKeepDistinctOrderedEvents()throws Exception{
  var sim=create(source(false));double lat=50.00123456789,lon=14.00123456789,accuracy=2.123456789;
  for(String mode:List.of("INPUT","OUTSIDE","UNAVAILABLE","TARGET")){
   sim=service.mutate(admin.principal(),sim.id(),UUID.randomUUID(),"POSITION",new SimulationService.Input(sim.version(),sim.runId(),sim.currentStage().id(),null,null,mode,mode.equals("INPUT")?lat:null,mode.equals("INPUT")?lon:null,mode.equals("INPUT")?accuracy:null,mode.equals("INPUT")?false:null,null));
   sim=command(sim,"GPS",null);
  }
  var page=service.history(admin.principal(),sim.id(),sim.runId(),0,50);assertEquals(8,page.items().size());
  for(int i=0;i<8;i++)assertEquals(i+1,page.items().get(i).sequence());
  assertEquals(lat,page.items().getFirst().input().latitude());assertEquals(lon,page.items().getFirst().input().longitude());assertEquals(accuracy,page.items().getFirst().position().accuracyMeters());
  for(int i=0;i<4;i++){assertEquals("POSITION",page.items().get(i*2).type());assertEquals(List.of("INPUT","OUTSIDE","UNAVAILABLE","TARGET").get(i),page.items().get(i*2).input().mode());assertEquals("GPS",page.items().get(i*2+1).type());}
  assertEquals("GPS_OUTSIDE",page.items().get(1).gps().outcome());assertNotNull(page.items().get(1).gps().distanceMeters());assertEquals("4",page.items().get(1).pointsDelta());
  assertNull(page.items().get(4).position());assertEquals("GPS_UNAVAILABLE",page.items().get(5).outcome());assertNull(page.items().get(5).gps().distanceMeters());assertEquals("GPS_ACCEPTED",page.items().getLast().outcome());
 }
 @Test void historyControlInputsSurviveBranchRestoreAndLegacyFacts()throws Exception{
  var sim=create(source(false));Instant start=sim.clock().virtualNow();sim=shift(sim,1000);sim=shift(sim,-500);
  var before=sim;sim=command(sim,"CODE","wrong");sim=command(sim,"SKIP",null);
  var oldRun=sim.runId();var page=service.history(admin.principal(),sim.id(),oldRun,0,50);
  assertEquals(start,page.items().getFirst().clockChange().before());assertEquals(start.plusMillis(1000),page.items().getFirst().clockChange().after());
  assertEquals("-500",page.items().get(1).input().deltaMs());assertEquals("SKIP",page.items().getLast().type());assertNotNull(page.items().getLast().input().stageId());
  // Emulate genuinely old JSON without changing the replay facts, effects or stored projection.
  jdbc.update("UPDATE simulation_command SET verified_facts=verified_facts-'input'-'previousClock' WHERE simulation_id=? AND sequence=1",sim.id());
  var legacy=service.history(admin.principal(),sim.id(),oldRun,0,50).items().getFirst();assertNull(legacy.input());assertNull(legacy.clockChange());
  sim=service.mutate(admin.principal(),sim.id(),UUID.randomUUID(),"RESTORE",new SimulationService.Input(sim.version(),sim.runId(),null,null,null,null,null,null,null,null,2L));
  assertEquals(before.dashboard().totalPenaltyPoints(),sim.dashboard().totalPenaltyPoints());
  var restored=service.history(admin.principal(),sim.id(),sim.runId(),0,50);assertEquals("2",restored.items().getLast().input().throughSequence());assertEquals(oldRun,restored.items().getLast().input().runId());
  sim=command(sim,"RESTART",null);var restart=service.history(admin.principal(),sim.id(),sim.runId(),0,50).items().getLast();assertEquals("RESTART",restart.type());assertNotNull(restart.input().runId());
  assertEquals(page.items().size(),service.history(admin.principal(),sim.id(),oldRun,0,50).items().size());
 }

 @Test void ownListHasStableActivityPagingAndFiltersWithoutOtherOwners()throws Exception{
  // Other tests keep their simulations and may temporarily advance the shared clock.
  // Use a dedicated owner so this list contains only this test's deterministic fixtures.
  var originalAdmin=admin;admin=actor(true);
  try{
  var source=source(false);var first=create(source);var second=create(source);var stranger=actor(true);var foreign=service.create(stranger.principal(),source.template(),version(source.template()),UUID.randomUUID(),null);
  var page=service.list(admin.principal(),null,0,100);assertEquals(2,page.items().size());assertFalse(page.hasMore());assertTrue(page.items().stream().noneMatch(i->i.id().equals(foreign.id())));
  assertEquals(page.items().getFirst().lastActivityAt(),page.items().getLast().lastActivityAt());
  assertEquals(java.util.stream.Stream.of(first.id(),second.id()).sorted(Comparator.comparing(UUID::toString).reversed()).toList(),page.items().stream().filter(i->i.id().equals(first.id())||i.id().equals(second.id())).map(i->i.id()).toList());
  clock.advance(Duration.ofSeconds(1));var changed=command(first,"CODE","wrong");
  assertEquals(first.id(),service.list(admin.principal(),null,0,1).items().getFirst().id());assertTrue(service.list(admin.principal(),null,0,1).hasMore());
  var all=new ArrayList<UUID>();for(int n=0;;n++){var next=service.list(admin.principal(),null,n,1);next.items().forEach(i->all.add(i.id()));if(!next.hasMore())break;}
  assertEquals(List.of(first.id(),second.id()),all);assertEquals(all.size(),new HashSet<>(all).size());
  var summary=service.list(admin.principal(),"RUNNING",0,100).items().stream().filter(i->i.id().equals(first.id())).findFirst().orElseThrow();assertEquals(changed.runId(),summary.runId());assertEquals(changed.currentStage().id(),summary.currentStage().id());assertEquals(clock.instant(),summary.lastActivityAt());
  var completed=command(changed,"SKIP",null);var done=service.list(admin.principal(),"COMPLETED",0,100).items().stream().filter(i->i.id().equals(first.id())).findFirst().orElseThrow();assertNull(done.currentStage());assertEquals(completed.status(),done.status());
  assertEquals(List.of(second.id()),service.list(admin.principal(),"RUNNING",0,100).items().stream().map(i->i.id()).toList());
  assertEquals(List.of(first.id()),service.list(admin.principal(),"COMPLETED",0,100).items().stream().map(i->i.id()).toList());
  assertEquals(404,assertThrows(AuthFailure.class,()->service.delete(stranger.principal(),first.id(),completed.version())).status());
  }finally{admin=originalAdmin;}
 }
 @Test void explicitDeletionRemovesOnlyOwnSnapshotHistoryAndCreationLedger()throws Exception{
  var f=source(false);UUID key=UUID.randomUUID();var sim=service.create(admin.principal(),f.template(),version(f.template()),key,null);sim=legacyFixture(sim);sim=command(sim,"CODE","wrong");var other=create(f);
  long crews=count("SELECT count(*) FROM crew"),events=count("SELECT count(*) FROM race_event");
  var finalSim=sim;assertEquals(409,assertThrows(AuthFailure.class,()->service.delete(admin.principal(),finalSim.id(),0)).status());
  service.delete(admin.principal(),sim.id(),sim.version());
  for(String table:List.of("simulation_run","simulation_command","simulation_file"))assertEquals(0,count("SELECT count(*) FROM "+table+" WHERE simulation_id=?",sim.id()));
  assertEquals(0,count("SELECT count(*) FROM admin_role_command WHERE command_key=?",key));assertNotNull(service.get(admin.principal(),other.id()));
  assertNotNull(templates.detail(admin.principal(),f.template()));assertTrue(Files.isRegularFile(DIR.resolve("templates/race-templates/"+f.template()+"/images/map.png")));
  assertEquals(crews,count("SELECT count(*) FROM crew"));assertEquals(events,count("SELECT count(*) FROM race_event"));
  assertEquals(404,assertThrows(AuthFailure.class,()->service.delete(admin.principal(),finalSim.id(),finalSim.version())).status());assertEquals(404,assertThrows(AuthFailure.class,()->service.delete(admin.principal(),UUID.randomUUID(),0)).status());
 }
 @Test void deleteRacingMutationCannotLeaveUsableOrDanglingSimulation()throws Exception{
  var sim=create(source(false));var outcomes=race(()->{try{command(sim,"CODE","wrong");return "UPDATED";}catch(AuthFailure e){return e.code();}},()->{try{service.delete(admin.principal(),sim.id(),0);return "DELETED";}catch(AuthFailure e){return e.code();}});
  if(outcomes.contains("DELETED")){assertEquals(0,count("SELECT count(*) FROM race_simulation WHERE id=?",sim.id()));assertTrue(outcomes.contains("SIMULATION_NOT_FOUND"));assertEquals(0,count("SELECT count(*) FROM simulation_command WHERE simulation_id=?",sim.id()));}
  else{assertTrue(outcomes.contains("SIMULATION_VERSION_CONFLICT"));assertEquals("3",service.get(admin.principal(),sim.id()).dashboard().totalPenaltyPoints());}
 }
 @Test void upgradeV23ToV24KeepsSimulationAndRunButClearsLegacyExpiry(){
  String schema=SCHEMA+"_v24";assertTrue(schema.matches("it_simulation_[0-9a-f]{32}_v24"));UUID owner=UUID.randomUUID(),sim=UUID.randomUUID(),run=UUID.randomUUID();
  try{
   org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).target("23").load().migrate();
   tx().executeWithoutResult(t->{jdbc.update("INSERT INTO "+schema+".user_account(id,email,normalized_email,display_name,status,created_at,updated_at) VALUES (?,?,?,'Fixture','PENDING_EMAIL',now(),now())",owner,owner+"@example.test",owner+"@example.test");
    jdbc.update("INSERT INTO "+schema+".race_simulation(id,owner_account_id,source_template_id,source_template_version,source_template_name,snapshot,evaluator_version,current_run_id,status,virtual_start_at,virtual_now,projection,created_at,last_activity_at,expires_at) VALUES (?,?,?,0,'Preserved','{}',1,?,'RUNNING',now(),now(),'{}',now(),now(),now()+interval '24 hours')",sim,owner,UUID.randomUUID(),run);
    jdbc.update("INSERT INTO "+schema+".simulation_run(id,simulation_id,number,started_virtual_at,initial_state) VALUES (?,?,1,now(),'{}')",run,sim);});
   var before=jdbc.queryForMap("SELECT id,snapshot,projection,last_activity_at,current_run_id FROM "+schema+".race_simulation WHERE id=?",sim);
   org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).target("24").load().migrate();
   assertEquals(before,jdbc.queryForMap("SELECT id,snapshot,projection,last_activity_at,current_run_id FROM "+schema+".race_simulation WHERE id=?",sim));assertNull(jdbc.queryForObject("SELECT expires_at FROM "+schema+".race_simulation WHERE id=?",java.sql.Timestamp.class,sim));assertEquals(1,count("SELECT count(*) FROM "+schema+".simulation_run WHERE id=?",run));
  }finally{jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
 }

 SimulationService.Input organizerInput(SimulationView.View v){return new SimulationService.Input(v.version(),v.runId(),null,null,null,null,null,null,null,null,null);}
 SimulationView.View fresh(Fixture f){return service.create(admin.principal(),f.template(),version(f.template()),UUID.randomUUID(),null);}
 SimulationView.View organizer(SimulationView.View v,String operation){return service.mutate(admin.principal(),v.id(),UUID.randomUUID(),operation,organizerInput(v));}
 @Test void newRunWaitsForStartThenEndSameInstantPersistsAndRejectsReplayWithNewKey()throws Exception{
  var sim=fresh(source(false));assertEquals("NOT_STARTED",sim.race().status());assertNull(sim.currentStage().activatedAt());assertFalse(sim.clock().businessActionsAllowed());
  assertEquals("SIMULATION_RACE_NOT_STARTED",assertThrows(AuthFailure.class,()->command(sim,"CODE","wrong")).code());assertEquals(0,count("SELECT count(*) FROM simulation_command WHERE simulation_id=?",sim.id()));
  UUID key=UUID.randomUUID();var start=service.mutate(admin.principal(),sim.id(),key,"RACE_START",organizerInput(sim));assertEquals(start,service.mutate(admin.principal(),sim.id(),key,"RACE_START",organizerInput(sim)));
  assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AuthFailure.class,()->service.mutate(admin.principal(),sim.id(),key,"RACE_END",organizerInput(sim))).code());
  assertEquals("SIMULATION_RACE_ALREADY_STARTED",assertThrows(AuthFailure.class,()->organizer(start,"RACE_START")).code());
  var ended=organizer(start,"RACE_END");assertEquals(start.race().startedAt(),ended.race().endedAt());assertEquals("12",ended.dashboard().totalPenaltyPoints());assertEquals(ended,beanFactory.createBean(SimulationService.class).get(admin.principal(),sim.id()));
  assertEquals("SIMULATION_RACE_ALREADY_ENDED",assertThrows(AuthFailure.class,()->organizer(ended,"RACE_END")).code());assertEquals("SIMULATION_RACE_ENDED",assertThrows(AuthFailure.class,()->command(ended,"GPS",null)).code());
  var history=service.history(admin.principal(),ended.id(),ended.runId(),0,50);assertEquals(List.of("RACE_START","RACE_END"),history.items().stream().map(SimulationService.HistoryItem::type).toList());assertEquals(ended.race(),history.items().getLast().race());
  assertEquals(2,count("SELECT count(*) FROM audit_event WHERE target_id=? AND event_type IN ('SIMULATION_RACE_STARTED','SIMULATION_RACE_ENDED')",sim.id()));
 }
 @Test void endBeforeStartPenalizesPendingStagesWithoutClaimingCompletion()throws Exception{
  var sim=fresh(source(false));var ended=organizer(sim,"RACE_END");assertNull(ended.race().startedAt());assertEquals("ENDED",ended.race().status());assertEquals("CLOSED",ended.status());assertEquals("12",ended.dashboard().totalPenaltyPoints());
  var progress=store.lock(sim.id()).state().stages().getFirst();assertEquals("ENDED",progress.status());assertNull(progress.completedAt());assertNull(progress.activatedAt());assertEquals(ended.race().endedAt(),progress.endedAt());
 }
 @Test void endReplacesWrongCodePenaltyAndRestoreReinstatesRaceLifecycle()throws Exception{
  var sim=fresh(source(false));var started=organizer(sim,"RACE_START");var wrong=command(started,"CODE","wrong");var ended=organizer(wrong,"RACE_END");assertEquals("12",ended.dashboard().totalPenaltyPoints());assertEquals("9",ended.dashboard().lastAction().pointsDelta());
  assertFalse(store.lock(sim.id()).state().penalties().getFirst().effective());
  var restore=service.mutate(admin.principal(),sim.id(),UUID.randomUUID(),"RESTORE",new SimulationService.Input(ended.version(),ended.runId(),null,null,null,null,null,null,null,null,2L));assertEquals("RUNNING",restore.race().status());assertEquals("3",restore.dashboard().totalPenaltyPoints());assertNull(restore.race().endedAt());
  var restarted=organizer(restore,"RESTART");assertEquals("NOT_STARTED",restarted.race().status());assertNull(restarted.race().startedAt());assertEquals("0",restarted.dashboard().totalPenaltyPoints());
  var initial=service.mutate(admin.principal(),sim.id(),UUID.randomUUID(),"RESTORE",new SimulationService.Input(restarted.version(),restarted.runId(),null,null,null,null,null,null,null,null,0L));assertEquals("NOT_STARTED",initial.race().status());
 }
 @Test void completedCrewCanStillReceiveOrganizerEndWithoutNewPenalty()throws Exception{
  var sim=organizer(fresh(source(false)),"RACE_START");sim=command(sim,"CODE","PRIVATE-ANSWER");sim=target(sim);sim=command(sim,"GPS",null);assertEquals("COMPLETED",sim.status());assertEquals("RUNNING",sim.race().status());
  var progress=store.lock(sim.id()).state().stages();var ended=organizer(sim,"RACE_END");assertEquals("COMPLETED",ended.status());assertEquals("ENDED",ended.race().status());assertEquals(sim.dashboard().totalPenaltyPoints(),ended.dashboard().totalPenaltyPoints());assertEquals(progress,store.lock(sim.id()).state().stages());
 }
 @Test void lifecycleAuditFailureRollsBackRaceProjectionVersionHistoryAndAllowsRetry()throws Exception{
  var initial=fresh(source(false));for(var op:List.of("RACE_START","RACE_END")){
   var event=op.equals("RACE_START")?Event.SIMULATION_RACE_STARTED:Event.SIMULATION_RACE_ENDED;UUID key=UUID.randomUUID();
   doAnswer(i->{i.callRealMethod();throw new IllegalStateException("Injected lifecycle audit failure");}).when(audit).appendSimulation(any(),any(),any(),eq(event),eq(initial.id()));
   try{assertThrows(RuntimeException.class,()->service.mutate(admin.principal(),initial.id(),key,op,organizerInput(initial)));}finally{org.mockito.Mockito.reset(audit);}
   assertEquals(initial,service.get(admin.principal(),initial.id()));assertEquals(0,count("SELECT count(*) FROM simulation_command WHERE simulation_id=?",initial.id()));assertEquals(0,count("SELECT count(*) FROM audit_event WHERE target_id=? AND event_type=?",initial.id(),event.name()));
  }
  assertEquals("RUNNING",organizer(initial,"RACE_START").race().status());
 }
 @Test void concurrentStartsAndEndsHaveOnlyOneEffectAndRespectOwner()throws Exception{
  var initial=fresh(source(false));var other=actor(true);assertEquals(404,assertThrows(AuthFailure.class,()->service.mutate(other.principal(),initial.id(),UUID.randomUUID(),"RACE_START",organizerInput(initial))).status());
  Callable<String> start=()->{try{organizer(initial,"RACE_START");return "OK";}catch(AuthFailure e){return e.code();}};var starts=race(start,start);assertEquals(1,starts.stream().filter("OK"::equals).count());assertTrue(starts.contains("SIMULATION_VERSION_CONFLICT"));
  var running=service.get(admin.principal(),initial.id());UUID key=UUID.randomUUID();Callable<String> end=()->SimulationStore.json(service.mutate(admin.principal(),running.id(),key,"RACE_END",organizerInput(running)));var ends=race(end,end);assertEquals(ends.getFirst(),ends.getLast());assertEquals(2,count("SELECT count(*) FROM simulation_command WHERE simulation_id=?",initial.id()));assertEquals(1,count("SELECT count(*) FROM audit_event WHERE target_id=? AND event_type='SIMULATION_RACE_ENDED'",initial.id()));
 }
 @Test void migrationV24ToV25KeepsLegacyJournalHashesAndInfersStart()throws Exception{
  String schema=SCHEMA+"_v25";assertTrue(schema.matches("it_simulation_[0-9a-f]{32}_v25"));var sim=create(source(false));var wrong=command(sim,"CODE","wrong");
  try{
   org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).target("24").load().migrate();
   tx().executeWithoutResult(t->{
    jdbc.update("INSERT INTO "+schema+".user_account SELECT * FROM user_account WHERE id=?",admin.id());jdbc.update("INSERT INTO "+schema+".user_identity SELECT * FROM user_identity WHERE account_id=?",admin.id());
    String columns="id,owner_account_id,source_template_id,source_template_version,source_template_name,snapshot,snapshot_format_version,evaluator_version,current_run_id,version,status,virtual_start_at,virtual_now,projection,created_at,last_activity_at,expires_at";
    jdbc.update("INSERT INTO "+schema+".race_simulation ("+columns+") SELECT "+columns+" FROM race_simulation WHERE id=?",sim.id());
    columns="id,simulation_id,number,parent_run_id,restored_through_sequence,started_virtual_at,initial_state";
    jdbc.update("INSERT INTO "+schema+".simulation_run ("+columns+") SELECT "+columns+" FROM simulation_run WHERE simulation_id=?",sim.id());
    jdbc.update("INSERT INTO "+schema+".simulation_command SELECT * FROM simulation_command WHERE simulation_id=?",sim.id());
    jdbc.update("UPDATE "+schema+".simulation_command SET verified_facts=verified_facts-'race',response=response-'race' WHERE simulation_id=?",sim.id());
   });
   var before=jdbc.queryForMap("SELECT projection,snapshot FROM "+schema+".race_simulation WHERE id=?",sim.id());var effects=jdbc.queryForList("SELECT effects FROM "+schema+".simulation_command WHERE simulation_id=?",sim.id());
   org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).target("25").load().migrate();
   assertEquals(before,jdbc.queryForMap("SELECT projection,snapshot FROM "+schema+".race_simulation WHERE id=?",sim.id()));assertEquals(effects,jdbc.queryForList("SELECT effects FROM "+schema+".simulation_command WHERE simulation_id=?",sim.id()));assertEquals("RUNNING",jdbc.queryForObject("SELECT race->>'status' FROM "+schema+".race_simulation WHERE id=?",String.class,sim.id()));
   assertEquals(sim.clock().virtualStartAt(),Instant.parse(jdbc.queryForObject("SELECT race->>'startedAt' FROM "+schema+".race_simulation WHERE id=?",String.class,sim.id())));
   assertEquals(1,count("SELECT count(*) FROM "+schema+".simulation_command WHERE simulation_id=?",sim.id()));
   try(var connection=jdbc.getDataSource().getConnection()){
    String originalSchema=connection.getSchema();try{connection.setSchema(schema);var legacyStore=new SimulationStore(new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true)));
    var loaded=legacyStore.lock(sim.id());assertEquals("RUNNING",loaded.race().status());assertEquals(wrong.dashboard().totalPenaltyPoints(),Long.toString(cz.mh.rallye.rallye_backend.competition.domain.RaceEvaluator.total(loaded.state())));
    var run=legacyStore.run(sim.id(),sim.runId());var commands=legacyStore.commands(sim.id(),sim.runId(),Long.MAX_VALUE);assertNull(commands.getFirst().facts().race());
    var replay=new cz.mh.rallye.rallye_backend.competition.domain.RaceEvaluator().replay(loaded.definition(),run.initial(),commands.getFirst().facts().action());assertEquals(loaded.state(),replay);assertEquals(commands.getFirst().effect().afterHash(),SimulationService.hash(SimulationStore.json(replay)));
    }finally{connection.setSchema(originalSchema);}
   }

  }finally{jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
 }
}
