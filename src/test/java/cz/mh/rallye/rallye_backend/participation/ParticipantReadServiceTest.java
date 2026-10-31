package cz.mh.rallye.rallye_backend.participation;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.participation.application.*;
import cz.mh.rallye.rallye_backend.participation.persistence.CrewStartStore;
import cz.mh.rallye.rallye_backend.participation.persistence.CrewStartStore.*;
import cz.mh.rallye.rallye_backend.races.application.EventFiles;
import cz.mh.rallye.rallye_backend.races.persistence.EventStore;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore;
import cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration;
import cz.mh.rallye.rallye_backend.templates.application.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ParticipantReadServiceTest {
 @TempDir Path directory;
 final CrewStartStore store=mock(CrewStartStore.class);final EventStore events=mock(EventStore.class);final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
 final UUID reg=UUID.randomUUID(),event=UUID.randomUUID(),run=UUID.randomUUID(),stage=UUID.randomUUID(),future=UUID.randomUUID(),device=UUID.randomUUID();final Instant now=Instant.parse("2026-10-31T12:00:00Z");
 final CrewStartSecrets secrets=new CrewStartSecrets(new SecretTokens());final String token=new SecretTokens().create();ParticipantReadService service;EventFiles files;
 Registration registration(){return new Registration(reg,event,run,1,2,now);}
 Device device(String state,long generation){return new Device(device,reg,generation,secrets.deviceHash(token),state,now,null,UUID.randomUUID(),null,null,null);}
 Run run(String status){return new Run(run,reg,event,1,status,1,now,status.equals("COMPLETED")?now:null,null,0);}
 Progress progress(UUID id,int sequence,String status){return new Progress(run,event,id,sequence,status,status.equals("PENDING")?null:now,null,null,false,false);}
 TemplateStore.Stage stage(UUID id,int order,String markdown,StageConfiguration config){return new TemplateStore.Stage(id,event,order,order==1?"Public":"FUTURE SECRET",null,999L,markdown,config);}
 EventStore.Event event(String status,Instant start,Instant end){return new EventStore.Event(event,UUID.randomUUID(),0,"Event",null,17,status,start,end,0,now,now);}
 StageConfiguration hidden(String type){return new StageConfiguration(new StageConfiguration.Gps(!type.equals("INFORMATION"),49.123456,16.123456,42.0,13L,14L),new StageConfiguration.Code(type.equals("STANDARD"),"SECRETANSWER",25L),new StageConfiguration.TimePenalty(type.equals("STANDARD"),60000L,35L),new StageConfiguration.Delayed(type.equals("STANDARD"),120000L,45L),type,type.equals("RADAR")?55L:null);}
 @BeforeEach void setup(){
  files=new EventFiles(directory.toString());service=new ParticipantReadService(new RaceDeviceAccess(store,events,secrets),store,events,files,Clock.fixed(now,ZoneOffset.UTC),manager);
  when(manager.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());when(store.activeDevice(any())).thenAnswer(i->i.getArgument(0).equals(secrets.deviceHash(token))?device("ACTIVE",1):null);
  when(store.registration(eq(reg),anyBoolean())).thenReturn(registration());when(store.run(eq(reg),eq(run),anyBoolean())).thenReturn(run("RUNNING"));when(store.device(reg,device,true)).thenReturn(device("ACTIVE",1));
  when(events.lock(event)).thenReturn(event("RUNNING",null,now.plusSeconds(3600)));when(events.read(event)).thenReturn(event("RUNNING",null,now.plusSeconds(3600)));
  when(store.progress(run)).thenReturn(List.of(progress(stage,1,"ACTIVE"),progress(future,2,"PENDING")));when(events.files(event)).thenReturn(List.of());
  when(events.stages(event)).thenReturn(List.of(new EventStore.Stage(stage(stage,1,"# Public",hidden("STANDARD")),UUID.randomUUID()),new EventStore.Stage(stage(future,2,"FUTURE INSTRUCTIONS",hidden("RADAR")),UUID.randomUUID())));
 }
 String failure(Runnable work){return assertThrows(AuthFailure.class,work::run).code();}
 TemplateStore.Image file(String name,String markdown){byte[] bytes=(markdown==null?"bytes":markdown).getBytes(StandardCharsets.UTF_8);files.storage.write(event,name,bytes);return new TemplateStore.Image(UUID.randomUUID(),event,name,ImageNames.key(name),markdown==null?"application/pdf":"text/markdown",bytes.length,null,null,now,false,markdown);}
 @Test void whitelistNeverSerializesCodesCoordinatesRatesOrFutureData(){for(String type:List.of("STANDARD","INFORMATION","RADAR")){
  when(events.stages(event)).thenReturn(List.of(new EventStore.Stage(stage(stage,1,"# Public",hidden(type)),UUID.randomUUID()),new EventStore.Stage(stage(future,2,"FUTURE INSTRUCTIONS",hidden("RADAR")),UUID.randomUUID())));
  var result=service.current(token,reg);String json=JsonMapper.builder().build().writeValueAsString(result);assertEquals(type,result.currentStage().type());for(String secret:List.of("SECRETANSWER","49.123456","16.123456","outsidePenaltyPoints","wrongAttemptPenaltyPoints","radarPenaltyPoints","minimumDurationMs","FUTURE",future.toString()))assertFalse(json.contains(secret),secret);
  assertEquals(Set.of("id","sequenceNo","name","type","activatedAt","instructionsMarkdown","instructionsRenderedHtml","requiredChecks","satisfiedChecks","files"),JsonMapper.builder().build().readTree(json).get("currentStage").propertyNames());
 }}
 @Test void currentUsesSnapshotAndCarriesExactSignedInt64(){when(store.adjustments(reg)).thenReturn(List.of(new Adjustment(UUID.randomUUID(),reg,UUID.randomUUID(),Long.MIN_VALUE,now,"private reason")));var view=service.current(token,reg);assertEquals(Long.toString(Long.MIN_VALUE),view.totalPenaltyPoints());assertEquals(now,view.individualStartedAt());assertEquals(0,view.version());assertEquals(List.of("CODE","GPS"),view.currentStage().requiredChecks());verify(events).stages(event);verify(store,never()).touchRun(any(),any(),anyLong());}
 @Test void unknownOtherRegistrationAndMalformedTokenAreIndistinguishable(){assertEquals("RACE_DEVICE_INVALID",failure(()->service.current(new SecretTokens().create(),reg)));assertEquals("RACE_DEVICE_INVALID",failure(()->service.current(token,UUID.randomUUID())));assertEquals("RACE_DEVICE_INVALID",failure(()->service.current("bad",reg)));verify(events,never()).stages(any());}
 @Test void lockedDeviceIsRecheckedAfterHintAndRejectsRevokedOrReplaced(){when(store.device(reg,device,true)).thenReturn(device("REVOKED",1));assertEquals("RACE_DEVICE_INVALID",failure(()->service.current(token,reg)));when(store.device(reg,device,true)).thenReturn(device("ACTIVE",2));assertEquals("RACE_DEVICE_INVALID",failure(()->service.current(token,reg)));verify(events,never()).stages(any());}
 @Test void filesAreReachableTransitivelyWithCyclesAndNoWholeLibraryLeak(){var a=file("a.md","[b](b.md)");var b=file("b.md","[a](a.md) [pdf](ok.pdf)");var pdf=file("ok.pdf",null);var hidden=file("hidden.pdf",null);when(events.files(event)).thenReturn(List.of(a,b,pdf,hidden));when(events.stages(event)).thenReturn(List.of(new EventStore.Stage(stage(stage,1,"[a](a.md)",hidden("STANDARD")),UUID.randomUUID()),new EventStore.Stage(stage(future,2,"[hidden](hidden.pdf)",hidden("RADAR")),UUID.randomUUID())));
  assertEquals(List.of("a.md","b.md","ok.pdf"),service.current(token,reg).currentStage().files().stream().map(ParticipantReadService.FileView::fileName).toList());assertTrue(service.file(token,reg,"ok.pdf").pdf());assertEquals("RACE_FILE_NOT_FOUND",failure(()->service.file(token,reg,"hidden.pdf")));assertEquals("RACE_FILE_NOT_FOUND",failure(()->service.file(token,reg,"missing.pdf")));assertEquals("RACE_FILE_NOT_FOUND",failure(()->service.file(token,reg,"../ok.pdf")));assertEquals("RACE_FILE_NOT_FOUND",failure(()->service.file(token,reg,pdf.id().toString())));
 }
 @Test void markdownUsesParticipantUrlsAndRetainsRelativeSources(){String text="[relative](guide.md) [explicit]("+ImageNames.eventUrl(event,"guide.md")+"#part)\n\n[x][ref]\n\n[ref]: "+ImageNames.eventUrl(event,"guide.md");var guide=file("guide.md","# Part\n[relative](guide.md)");when(events.files(event)).thenReturn(List.of(guide));when(events.stages(event)).thenReturn(List.of(new EventStore.Stage(stage(stage,1,text,hidden("STANDARD")),UUID.randomUUID()),new EventStore.Stage(stage(future,2,null,hidden("RADAR")),UUID.randomUUID())));var view=service.current(token,reg);assertTrue(view.currentStage().instructionsMarkdown().contains("[relative](guide.md)"));assertFalse(view.currentStage().instructionsMarkdown().contains("/race-events/"));assertFalse(view.currentStage().instructionsRenderedHtml().contains("/race-events/"));assertTrue(view.currentStage().instructionsRenderedHtml().contains("/race-participations/"+reg+"/files/guide.md#part"));var content=service.file(token,reg,"guide.md");assertTrue(content.markdown());String html=new String(content.bytes(),StandardCharsets.UTF_8);assertTrue(html.contains("/race-participations/"+reg+"/files/guide.md"));assertFalse(html.contains("/race-events/"));}
 @Test void completedRunHasNoCurrentStageButPreviouslyVisitedFilesRemain(){var pdf=file("past.pdf",null);when(events.files(event)).thenReturn(List.of(pdf));when(events.stages(event)).thenReturn(List.of(new EventStore.Stage(stage(stage,1,"[past](past.pdf)",hidden("STANDARD")),UUID.randomUUID())));when(store.progress(run)).thenReturn(List.of(progress(stage,1,"COMPLETED")));when(store.run(eq(reg),eq(run),anyBoolean())).thenReturn(run("COMPLETED"));assertNull(service.current(token,reg).currentStage());assertTrue(service.file(token,reg,"past.pdf").pdf());}
 @Test void lifecycleReadIsEffectiveAndDoesNotReconcileOrChangeProgress(){for(String state:List.of("PLANNED","RUNNING","ENDED")){when(events.read(event)).thenReturn(event(state,null,now.plusSeconds(1)));assertEquals(state,service.current(token,reg).eventStatus());}when(events.read(event)).thenReturn(event("RUNNING",null,now));assertEquals("ENDED",service.current(token,reg).eventStatus());when(events.read(event)).thenReturn(event("RUNNING",null,now.plusSeconds(1)));assertEquals("RUNNING",service.current(token,reg).eventStatus());verify(store,never()).updateProgress(any(),any());}
 @Test void missingPhysicalFileAndModifiedDocumentFailClosed(){var doc=file("guide.md","original");when(events.files(event)).thenReturn(List.of(doc));when(events.stages(event)).thenReturn(List.of(new EventStore.Stage(stage(stage,1,"[doc](guide.md)",hidden("STANDARD")),UUID.randomUUID()),new EventStore.Stage(stage(future,2,null,hidden("RADAR")),UUID.randomUUID())));files.storage.remove(event,"guide.md");assertEquals("PHOTO_STORAGE_UNAVAILABLE",failure(()->service.file(token,reg,"guide.md")));files.storage.write(event,"guide.md","tampered".getBytes(StandardCharsets.UTF_8));assertEquals("RACE_STATE_UNAVAILABLE",failure(()->service.file(token,reg,"guide.md")));}
 @Test void eventRegistrationRunDeviceLocksPrecedeReadingSnapshot(){service.current(token,reg);var order=inOrder(events,store);order.verify(events).lock(event);order.verify(store).registration(reg,true);order.verify(store).run(reg,run,true);order.verify(store).device(reg,device,true);order.verify(events).stages(event);}
 @Test void unsupportedQuizFailsClosedRatherThanReturningItsDefinition(){when(events.stages(event)).thenReturn(List.of(new EventStore.Stage(stage(stage,1,"Quiz",hidden("QUIZ")),UUID.randomUUID()),new EventStore.Stage(stage(future,2,null,hidden("RADAR")),UUID.randomUUID())));assertEquals("RACE_STATE_UNAVAILABLE",failure(()->service.current(token,reg)));}
}
