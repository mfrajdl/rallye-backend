package cz.mh.rallye.rallye_backend.simulation;
import java.util.*;import java.time.*;import java.nio.file.Path;import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import org.springframework.transaction.*;import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;
import cz.mh.rallye.rallye_backend.simulation.application.*;import cz.mh.rallye.rallye_backend.simulation.persistence.SimulationStore;import static cz.mh.rallye.rallye_backend.simulation.persistence.SimulationStore.*;import static cz.mh.rallye.rallye_backend.competition.domain.RaceEvaluator.*;import cz.mh.rallye.rallye_backend.competition.domain.*;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore;import cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration;import cz.mh.rallye.rallye_backend.templates.application.*;import cz.mh.rallye.rallye_backend.races.application.EventFiles;import cz.mh.rallye.rallye_backend.identity.application.*;import cz.mh.rallye.rallye_backend.identity.persistence.*;import cz.mh.rallye.rallye_backend.audit.application.AuditService;import cz.mh.rallye.rallye_backend.shared.persistence.CommandStore;
class SimulationServiceTest {
 @TempDir Path dir;final SimulationStore store=mock(SimulationStore.class);final TemplateStore templates=mock(TemplateStore.class);final AdminStore roles=mock(AdminStore.class);final AccountStore accounts=mock(AccountStore.class);final CommandStore ledger=mock(CommandStore.class);final AuditService audit=mock(AuditService.class);final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);final Instant now=Instant.parse("2026-10-29T10:00:00Z");final UUID id=UUID.randomUUID(),run=UUID.randomUUID(),stage=UUID.randomUUID();final AuthService.Principal actor=new AuthService.Principal(UUID.randomUUID(),UUID.randomUUID(),false);SimulationService service;Definition def;AtomicReference<Session> session;Map<UUID,Command> commands;Map<UUID,Run> runs;
 @BeforeEach void setup(){def=new Definition(2,3,List.of(new Stage(stage,stage,1,"Station",10L,10L,"# Text",new StageConfiguration(new StageConfiguration.Gps(false,null,null,null,null,null),new StageConfiguration.Code(true,"PRIVATE-CODE",null),new StageConfiguration.TimePenalty(false,null,null),new StageConfiguration.Delayed(false,null,null)))));State initial=new RaceEvaluator().start(def,now);session=new AtomicReference<>(new Session(id,actor.accountId(),UUID.randomUUID(),2,"Source",def,1,1,run,0,initial,now,now.plusSeconds(86400)));commands=new HashMap<>();runs=new HashMap<>();runs.put(run,new Run(run,null,null,initial));service=new SimulationService(store,templates,new TemplateImageFiles(dir.resolve("templates").toString()),new SimulationFiles(dir.resolve("simulations").toString()),new EventFiles(dir.resolve("events").toString()),new TemplateFileContent(102400,10485760),roles,accounts,ledger,audit,Clock.fixed(now,ZoneOffset.UTC),manager);when(manager.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());when(roles.activeVerified(actor.accountId())).thenReturn(true);when(roles.administrator(actor.accountId())).thenReturn(true);when(accounts.isActiveSession(eq(actor.sessionId()),eq(actor.accountId()),any())).thenReturn(true);when(store.lock(id)).thenAnswer(i->session.get());when(store.nextSequence(id)).thenAnswer(i->commands.size()+1L);when(store.replay(eq(id),eq(actor.accountId()),any())).thenAnswer(i->commands.get(i.getArgument(2)));when(store.run(eq(id),any())).thenAnswer(i->runs.get(i.getArgument(1)));when(store.commands(eq(id),any(),anyLong())).thenAnswer(i->commands.values().stream().filter(c->c.runId().equals(i.getArgument(1))&&c.sequence()<=(Long)i.getArgument(2)).sorted(Comparator.comparingLong(Command::sequence)).toList());doAnswer(i->{var s=session.get();session.set(new Session(id,s.owner(),s.templateId(),2,"Source",def,1,1,i.getArgument(1),i.getArgument(2),i.getArgument(3),now,i.getArgument(5),s.race()));return null;}).when(store).update(eq(id),any(),anyLong(),any(),any(),any());doAnswer(i->{UUID key=i.getArgument(4);commands.put(key,new Command(i.getArgument(0),i.getArgument(2),i.getArgument(5),i.getArgument(6),i.getArgument(8),now.plusSeconds(86400),i.getArgument(11),i.getArgument(9),i.getArgument(10)));return null;}).when(store).command(any(),eq(id),any(),eq(actor.accountId()),any(),anyLong(),anyString(),anyLong(),anyString(),any(),any(),anyString(),any());doAnswer(i->{runs.put(i.getArgument(0),new Run(i.getArgument(0),i.getArgument(2),i.getArgument(3),i.getArgument(4)));return null;}).when(store).run(any(),eq(id),any(),any(),any());}
 SimulationService.Input input(long v,UUID r,String code){return new SimulationService.Input(v,r,stage,code,null,null,null,null,null,null,null);}
 @BeforeEach void lifecycleStore(){
  doAnswer(i->{var s=session.get();session.set(new Session(s.id(),s.owner(),s.templateId(),s.templateVersion(),s.templateName(),s.definition(),s.format(),s.engine(),s.runId(),s.version(),s.state(),s.createdAt(),s.expiresAt(),i.getArgument(1)));return null;}).when(store).race(eq(id),any());
  doAnswer(i->{UUID r=i.getArgument(1);var old=runs.get(r);runs.put(r,new Run(old.id(),old.parent(),old.through(),old.initial(),i.getArgument(2)));return null;}).when(store).runRace(eq(id),any(),any());
 }
 @Test void mutationReplayConflictAndSafeProjection(){UUID key=UUID.randomUUID();var first=service.mutate(actor,id,key,"CODE",input(0,run,"wrong"));assertEquals("3",first.dashboard().totalPenaltyPoints());assertEquals(first,service.mutate(actor,id,key,"CODE",input(0,run," WRONG ")));assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AuthFailure.class,()->service.mutate(actor,id,key,"CODE",input(9,run,"wrong"))).code());assertFalse(json(first).contains("PRIVATE-CODE"));assertFalse(json(commands.values()).contains("PRIVATE-CODE"));assertFalse(json(commands.values()).contains("\"WRONG\""));assertEquals(def,read(json(def),Definition.class));assertEquals(session.get().state(),read(json(session.get().state()),State.class));}
 @Test void restoreReplaysFactsAndForksWithoutDoubleCounting(){service.mutate(actor,id,UUID.randomUUID(),"CODE",input(0,run,"wrong"));service.mutate(actor,id,UUID.randomUUID(),"SKIP",input(1,run,null));assertEquals(12,total(session.get().state()));var restore=new SimulationService.Input(2,run,null,null,null,null,null,null,null,null,1L);var result=service.mutate(actor,id,UUID.randomUUID(),"RESTORE",restore);assertNotEquals(run,result.runId());assertEquals("3",result.dashboard().totalPenaltyPoints());assertEquals("RUNNING",result.status());assertEquals(2,service.history(actor,id,run,0,50).items().size());var done=service.mutate(actor,id,UUID.randomUUID(),"CODE",input(3,result.runId(),"PRIVATE-CODE"));assertEquals("COMPLETED",done.status());assertEquals("3",done.dashboard().totalPenaltyPoints());}
 @Test void staleRunOwnerVersionAndAdminFailClosed(){when(roles.administrator(actor.accountId())).thenReturn(false);assertEquals(403,assertThrows(AuthFailure.class,()->service.get(actor,id)).status());when(roles.administrator(actor.accountId())).thenReturn(true);var s=session.get();session.set(new Session(s.id(),UUID.randomUUID(),s.templateId(),2,s.templateName(),def,1,1,run,0,s.state(),now,s.expiresAt()));assertEquals(404,assertThrows(AuthFailure.class,()->service.get(actor,id)).status());session.set(s);assertEquals("SIMULATION_VERSION_CONFLICT",assertThrows(AuthFailure.class,()->service.mutate(actor,id,UUID.randomUUID(),"CODE",input(1,run,"wrong"))).code());assertEquals("SIMULATION_RUN_CONFLICT",assertThrows(AuthFailure.class,()->service.mutate(actor,id,UUID.randomUUID(),"CODE",input(0,UUID.randomUUID(),"wrong"))).code());assertTrue(commands.isEmpty());}
 @Test void lifecycleAuditFailureRollsBackAndExpiredSessionCannotReplay(){doThrow(new IllegalStateException("Injected")).when(audit).simulation(any(),eq(AuditService.Event.SIMULATION_CLOSED),any());assertThrows(IllegalStateException.class,()->service.mutate(actor,id,UUID.randomUUID(),"CLOSE",input(0,run,null)));verify(manager).rollback(any());assertTrue(commands.isEmpty());}
 @Test void legacyExpiryDoesNotPreventReadingSimulation(){var s=session.get();session.set(new Session(s.id(),s.owner(),s.templateId(),2,s.templateName(),def,1,1,run,0,s.state(),now.minusSeconds(90000),now));assertNull(service.get(actor,id).expiresAt());}
 @Test void completedAcknowledgementKeepsStateButRecordsRequestedStage(){var done=service.mutate(actor,id,UUID.randomUUID(),"CODE",input(0,run,"PRIVATE-CODE"));assertEquals("COMPLETED",done.status());var receipt=service.mutate(actor,id,UUID.randomUUID(),"COMPLETE",input(1,run,null));assertEquals(done,receipt);var history=service.history(actor,id,run,0,50);assertEquals(2,history.items().size());assertEquals(stage,history.items().getLast().stageId());assertEquals("STAGE_ALREADY_COMPLETED",history.items().getLast().outcome());assertEquals("0",history.items().getLast().pointsDelta());}
 @Test void exactCodeInputsSurviveCanonicalReplayAndRestore(){
  UUID key=UUID.randomUUID();String raw="  wRoNg\t";
  var first=service.mutate(actor,id,key,"CODE",input(0,run,raw));
  assertEquals(first,service.mutate(actor,id,key,"CODE",input(0,run,"WRONG")));
  var item=service.history(actor,id,run,0,50).items().getFirst();assertEquals(raw,item.input().code());
  assertEquals("0",item.input().version());assertEquals(stage,item.input().stageId());assertEquals("CODE_REJECTED",item.outcome());assertEquals("3",item.pointsDelta());
  assertEquals(now,item.clockChange().before());assertEquals(now,item.clockChange().after());assertEquals(1,commands.size());
  var done=service.mutate(actor,id,UUID.randomUUID(),"CODE",input(1,run," private-code "));
  assertEquals(" private-code ",service.history(actor,id,run,0,50).items().getLast().input().code());
  assertEquals("COMPLETED",done.status());
  var restored=service.mutate(actor,id,UUID.randomUUID(),"RESTORE",new SimulationService.Input(2,run,null,null,null,null,null,null,null,null,1L));
  var history=service.history(actor,id,restored.runId(),0,50);assertEquals(raw,history.items().getFirst().input().code());
  assertEquals("1",history.items().getLast().input().throughSequence());assertEquals("3",restored.dashboard().totalPenaltyPoints());
 }
 @Test void legacyFactsDeserializeAndRestoreWithoutInventingInputs(){
  service.mutate(actor,id,UUID.randomUUID(),"CODE",input(0,run,"wrong"));
  commands.replaceAll((key,c)->{
   String legacy=json(new Facts(c.facts().operation(),c.facts().action(),c.facts().clock(),c.facts().position()));
   var node=SimulationStore.JSON.readTree(legacy);((tools.jackson.databind.node.ObjectNode)node).remove("input");((tools.jackson.databind.node.ObjectNode)node).remove("previousClock");
   return new Command(c.id(),c.runId(),c.sequence(),c.operation(),c.fingerprint(),c.expiresAt(),c.response(),read(node.toString(),Facts.class),c.effect());
  });
  var old=service.history(actor,id,run,0,50).items().getFirst();assertNull(old.input());assertNull(old.clockChange());assertEquals("3",old.pointsDelta());
  var restored=service.mutate(actor,id,UUID.randomUUID(),"RESTORE",new SimulationService.Input(1,run,null,null,null,null,null,null,null,null,1L));
  assertEquals("3",restored.dashboard().totalPenaltyPoints());
 }
 @Test void historyKeepsBothClockEndpointsAndExactInt64Delta(){
  long delta=9007199254740993L;
  var shifted=service.mutate(actor,id,UUID.randomUUID(),"CLOCK_SHIFT",new SimulationService.Input(0,run,null,null,delta,null,null,null,null,null,null));
  var first=service.history(actor,id,run,0,50).items().getFirst();assertEquals(Long.toString(delta),first.input().deltaMs());
  assertEquals(now,first.clockChange().before());assertEquals(now.plusMillis(delta),first.clockChange().after());
  service.mutate(actor,id,UUID.randomUUID(),"CLOCK_SHIFT",new SimulationService.Input(shifted.version(),run,null,null,-delta,null,null,null,null,null,null));
  var last=service.history(actor,id,run,0,50).items().getLast();assertEquals(now,last.clockChange().after());assertEquals(Long.toString(-delta),last.input().deltaMs());
 }

 @Test void listIsScopedValidatedAndDoesNotExposeSnapshots(){
  var row=new Listed(id,UUID.randomUUID(),2,"Source",now,now,run,3,"RUNNING",new StageLabel(stage,"Station",1));
  when(store.list(actor.accountId(),"RUNNING",0,1)).thenReturn(List.of(row,row));
  var page=service.list(actor,"RUNNING",0,1);assertTrue(page.hasMore());assertEquals(1,page.items().size());assertEquals(stage,page.items().getFirst().currentStage().id());assertFalse(json(page).contains("PRIVATE-CODE"));
  assertEquals(400,assertThrows(AuthFailure.class,()->service.list(actor,"DRAFT",0,1)).status());
  assertEquals(400,assertThrows(AuthFailure.class,()->service.list(actor,null,-1,1)).status());assertEquals(400,assertThrows(AuthFailure.class,()->service.list(actor,null,0,101)).status());
 }

 void waiting(){var old=session.get();var state=new RaceEvaluator().waiting(def,now);session.set(new Session(id,old.owner(),old.templateId(),2,"Source",def,1,1,run,0,state,now,null,Race.waiting()));runs.put(run,new Run(run,null,null,state,Race.waiting()));}
 SimulationService.Input control(SimulationView.View v){return new SimulationService.Input(v.version(),v.runId(),null,null,null,null,null,null,null,null,null);}
 @Test void explicitStartBlocksGameplayAndCountsTimeFromActualStart(){
  waiting();var initial=service.get(actor,id);assertEquals("NOT_STARTED",initial.race().status());assertFalse(initial.clock().businessActionsAllowed());assertNull(initial.currentStage().activatedAt());
  assertEquals("SIMULATION_RACE_NOT_STARTED",assertThrows(AuthFailure.class,()->service.mutate(actor,id,UUID.randomUUID(),"CODE",input(0,run,"wrong"))).code());assertTrue(commands.isEmpty());
  var shifted=service.mutate(actor,id,UUID.randomUUID(),"CLOCK_SHIFT",new SimulationService.Input(0,run,null,null,60000L,null,null,null,null,null,null));
  UUID key=UUID.randomUUID();var started=service.mutate(actor,id,key,"RACE_START",control(shifted));assertEquals(shifted.clock().virtualNow(),started.race().startedAt());assertEquals(started.race().startedAt(),started.currentStage().activatedAt());assertEquals(0,started.clock().activeStageElapsedMs());
  assertEquals(started,service.mutate(actor,id,key,"RACE_START",control(shifted)));
  assertEquals("SIMULATION_RACE_ALREADY_STARTED",assertThrows(AuthFailure.class,()->service.mutate(actor,id,UUID.randomUUID(),"RACE_START",control(started))).code());
  var ended=service.mutate(actor,id,UUID.randomUUID(),"RACE_END",control(started));assertEquals(started.race().startedAt(),ended.race().endedAt());assertEquals("ENDED",ended.race().status());assertEquals("12",ended.dashboard().totalPenaltyPoints());assertEquals(ended.race(),ended.dashboard().race());
  assertEquals("SIMULATION_RACE_ALREADY_ENDED",assertThrows(AuthFailure.class,()->service.mutate(actor,id,UUID.randomUUID(),"RACE_END",control(ended))).code());
  assertEquals("SIMULATION_RACE_ENDED",assertThrows(AuthFailure.class,()->service.mutate(actor,id,UUID.randomUUID(),"COMPLETE",input(ended.version(),run,null))).code());
 }
 @Test void endBeforeStartRestoreAndRestartKeepIndependentRaceLifecycle(){
  waiting();var initial=service.get(actor,id);var end=service.mutate(actor,id,UUID.randomUUID(),"RACE_END",control(initial));assertNull(end.race().startedAt());assertEquals(now,end.race().endedAt());assertEquals("CLOSED",end.status());assertEquals("12",end.dashboard().totalPenaltyPoints());
  var history=service.history(actor,id,run,0,50);assertEquals("RACE_END",history.items().getFirst().type());assertEquals(end.race(),history.items().getFirst().race());assertNull(history.items().getFirst().input().stageId());
  var restore=service.mutate(actor,id,UUID.randomUUID(),"RESTORE",new SimulationService.Input(end.version(),end.runId(),null,null,null,null,null,null,null,null,0L));assertEquals("NOT_STARTED",restore.race().status());assertEquals("0",restore.dashboard().totalPenaltyPoints());
  var start=service.mutate(actor,id,UUID.randomUUID(),"RACE_START",control(restore));
  var restart=service.mutate(actor,id,UUID.randomUUID(),"RESTART",control(start));assertEquals("NOT_STARTED",restart.race().status());assertNull(restart.race().startedAt());assertNull(restart.currentStage().activatedAt());
 }
 @Test void completionAndOrganizerEndAreDifferentAndRestoreReplaysStart(){
  waiting();var start=service.mutate(actor,id,UUID.randomUUID(),"RACE_START",control(service.get(actor,id)));var completed=service.mutate(actor,id,UUID.randomUUID(),"CODE",input(start.version(),run,"PRIVATE-CODE"));assertEquals("COMPLETED",completed.status());assertEquals("RUNNING",completed.race().status());
  var ended=service.mutate(actor,id,UUID.randomUUID(),"RACE_END",control(completed));assertEquals("COMPLETED",ended.status());assertEquals("ENDED",ended.race().status());assertEquals(completed.dashboard().totalPenaltyPoints(),ended.dashboard().totalPenaltyPoints());
  var restored=service.mutate(actor,id,UUID.randomUUID(),"RESTORE",new SimulationService.Input(ended.version(),run,null,null,null,null,null,null,null,null,1L));assertEquals("RUNNING",restored.race().status());assertEquals(now,restored.race().startedAt());assertNull(restored.race().endedAt());
  verify(audit).simulation(actor.accountId(),AuditService.Event.SIMULATION_RACE_STARTED,id);verify(audit).simulation(actor.accountId(),AuditService.Event.SIMULATION_RACE_ENDED,id);
 }
 @Test void organizerCommandsCannotMoveBehindTimeFloorOrBypassOwner(){
  waiting();var start=service.mutate(actor,id,UUID.randomUUID(),"RACE_START",control(service.get(actor,id)));var shifted=service.mutate(actor,id,UUID.randomUUID(),"CLOCK_SHIFT",new SimulationService.Input(start.version(),run,null,null,10000L,null,null,null,null,null,null));var wrong=service.mutate(actor,id,UUID.randomUUID(),"CODE",input(shifted.version(),run,"wrong"));
  var past=service.mutate(actor,id,UUID.randomUUID(),"CLOCK_SHIFT",new SimulationService.Input(wrong.version(),run,null,null,-1L,null,null,null,null,null,null));assertEquals("SIMULATION_CLOCK_BEFORE_STATE",assertThrows(AuthFailure.class,()->service.mutate(actor,id,UUID.randomUUID(),"RACE_END",control(past))).code());
  var old=session.get();session.set(new Session(old.id(),UUID.randomUUID(),old.templateId(),old.templateVersion(),old.templateName(),def,1,1,run,old.version(),old.state(),now,null,old.race()));assertEquals(404,assertThrows(AuthFailure.class,()->service.mutate(actor,id,UUID.randomUUID(),"RACE_END",control(past))).status());
 }

 @Test void oldReplayResponseWithoutRaceIsStillUsableAndCannotChangeCurrentRace(){
  UUID key=UUID.randomUUID();var old=service.mutate(actor,id,key,"CODE",input(0,run,"wrong"));
  var c=commands.get(key);var response=(tools.jackson.databind.node.ObjectNode)SimulationStore.JSON.readTree(c.response());response.remove("race");((tools.jackson.databind.node.ObjectNode)response.get("dashboard")).remove("race");
  commands.put(key,new Command(c.id(),c.runId(),c.sequence(),c.operation(),c.fingerprint(),c.expiresAt(),response.toString(),c.facts(),c.effect()));
  var ended=service.mutate(actor,id,UUID.randomUUID(),"RACE_END",control(old));var replay=service.mutate(actor,id,key,"CODE",input(0,run,"wrong"));assertEquals("RUNNING",replay.race().status());assertEquals(now,replay.race().startedAt());assertEquals(replay.race(),replay.dashboard().race());assertEquals(ended,service.get(actor,id));assertEquals(2,commands.size());
 }

 private void radarFixture(){var e=StageConfiguration.empty();var cfg=new StageConfiguration(new StageConfiguration.Gps(true,50d,14d,100d,null,null),e.code(),e.timePenalty(),e.delayed(),"RADAR",7L);def=new Definition(2,3,List.of(new Stage(stage,stage,1,"Radar",0L,10L,"Text",cfg)));var old=session.get();session.set(new Session(old.id(),old.owner(),old.templateId(),2,"Source",def,1,1,run,0,new RaceEvaluator().start(def,now),now,old.expiresAt()));}
 @Test void radarHistoryProjectsOnlyMeasurementDataAndSeparateMeasurementPenalty(){
  radarFixture();UUID a=UUID.randomUUID(),b=UUID.randomUUID();var outside=new Action(a,2,"RADAR",stage,"RADAR_OUTSIDE",7,now,new GpsResult(51d,15d,0d,123d,"RADAR_OUTSIDE"));var inside=new Action(b,4,"RADAR",stage,"RADAR_ACCEPTED",99,now.plusSeconds(1),new GpsResult(50.0001,14.0001,0d,13d,"RADAR_ACCEPTED"));
  when(store.radarMeasurements(eq(id),anyMap(),eq(stage),eq(0L),eq(1))).thenReturn(List.of(new RadarRow(a,run,2,1,new Facts("RADAR",outside,now,null),now),new RadarRow(b,run,4,2,new Facts("RADAR",inside,now,null),now)));
  var page=service.radarHistory(actor,id,run,stage,0,1);assertTrue(page.hasMore());assertEquals(2,page.nextAfterSequence());assertEquals("7",page.items().getFirst().penaltyPoints());assertEquals(51d,page.items().getFirst().latitude());assertEquals(123,page.items().getFirst().distanceMeters());assertFalse(page.items().getFirst().completedStage());
  when(store.radarMeasurements(eq(id),anyMap(),eq(stage),eq(2L),eq(1))).thenReturn(List.of(new RadarRow(b,run,4,2,new Facts("RADAR",inside,now,null),now)));
  var last=service.radarHistory(actor,id,run,stage,2,1);assertEquals("0",last.items().getFirst().penaltyPoints());assertTrue(last.items().getFirst().completedStage());assertFalse(last.hasMore());assertFalse(json(last).contains("radiusMeters"));verifyNoInteractions(audit,ledger);
 }
 @Test void radarHistoryRespectsBranchCutoffAndValidatesAccess(){
  radarFixture();UUID child=UUID.randomUUID();runs.put(child,new Run(child,run,5L,session.get().state()));when(store.radarMeasurements(eq(id),anyMap(),eq(stage),eq(0L),eq(50))).thenReturn(List.of());
  assertTrue(service.radarHistory(actor,id,child,stage,0,50).items().isEmpty());verify(store).radarMeasurements(id,Map.of(child,Long.MAX_VALUE,run,5L),stage,0,50);
  assertEquals(400,assertThrows(AuthFailure.class,()->service.radarHistory(actor,id,run,stage,-1,50)).status());assertEquals(400,assertThrows(AuthFailure.class,()->service.radarHistory(actor,id,run,stage,0,101)).status());assertEquals("SIMULATION_RESTORE_INVALID",assertThrows(AuthFailure.class,()->service.radarHistory(actor,id,UUID.randomUUID(),stage,0,50)).code());assertEquals("SIMULATION_STAGE_NOT_FOUND",assertThrows(AuthFailure.class,()->service.radarHistory(actor,id,run,UUID.randomUUID(),0,50)).code());
  when(roles.administrator(actor.accountId())).thenReturn(false);assertEquals(403,assertThrows(AuthFailure.class,()->service.radarHistory(actor,id,run,stage,0,50)).status());
 }
}
