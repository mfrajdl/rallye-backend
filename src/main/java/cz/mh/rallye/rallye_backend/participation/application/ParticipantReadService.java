package cz.mh.rallye.rallye_backend.participation.application;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import cz.mh.rallye.rallye_backend.participation.persistence.CrewStartStore;
import cz.mh.rallye.rallye_backend.races.application.EventFiles;
import cz.mh.rallye.rallye_backend.races.application.EventService.Content;
import cz.mh.rallye.rallye_backend.races.domain.EventLifecycle;
import cz.mh.rallye.rallye_backend.races.persistence.EventStore;
import cz.mh.rallye.rallye_backend.templates.application.*;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore;

/** Explicit participant projection, never an administrative snapshot or a gameplay evaluator. */
@Service
public class ParticipantReadService {
 public record FileView(String fileName,String contentType,int byteSize,String url){}
 public record StageView(UUID id,int sequenceNo,String name,String type,Instant activatedAt,String instructionsMarkdown,
                         String instructionsRenderedHtml,List<String> requiredChecks,List<String> satisfiedChecks,List<FileView> files){}
 public record View(UUID registrationId,UUID runId,long activationGeneration,long deviceGeneration,long version,String status,
                    Instant individualStartedAt,String eventStatus,Instant endAt,StageView currentStage,String totalPenaltyPoints){}
 private final RaceDeviceAccess access;private final CrewStartStore store;private final EventStore events;private final EventFiles files;private final Clock clock;private final TransactionTemplate tx;
 public ParticipantReadService(RaceDeviceAccess access,CrewStartStore store,EventStore events,EventFiles files,Clock clock,PlatformTransactionManager manager){this.access=access;this.store=store;this.events=events;this.files=files;this.clock=clock;tx=new TransactionTemplate(manager);}
 private record Snapshot(CrewStartStore.Run run,EventStore.Event event,List<CrewStartStore.Progress> progress,Map<UUID,TemplateStore.Stage> stages){}
 private Snapshot snapshot(String token,UUID registration){
  var authorized=access.current(token,registration);var r=store.registration(registration,false);var run=store.run(registration,authorized.runId(),false);var event=events.read(r.eventId());
  if(run==null||event==null)throw unavailable();var stages=new HashMap<UUID,TemplateStore.Stage>();for(var stage:events.stages(event.id()))stages.put(stage.data().id(),stage.data());
  var progress=store.progress(run.id());for(var p:progress)if(!stages.containsKey(p.stageId()))throw unavailable();
  return new Snapshot(run,event,progress,stages);
 }
 public View current(String token,UUID registration){return transaction(()->{
  var s=snapshot(token,registration);var active=s.progress().stream().filter(p->p.status().equals("ACTIVE")).toList();if(active.size()>1)throw unavailable();StageView stage=null;
  if(!s.run().status().equals("COMPLETED")&&!active.isEmpty()){
   var p=active.getFirst();var data=s.stages().get(p.stageId());var library=events.files(s.event().id());var reachable=reachable(s.event().id(),List.of(data),library);var names=TemplateFileReferences.names(library);var required=new ArrayList<String>();var satisfied=new ArrayList<String>();
   // TIMED/DELAYED are STANDARD modifiers, not independent persisted stage types. QUIZ has no supported model.
   var config=data.configuration();if(!Set.of("STANDARD","INFORMATION","RADAR").contains(config.type()))throw unavailable();
   if(config.code().enabled()){required.add("CODE");if(p.codeSatisfied())satisfied.add("CODE");}if(config.gps().enabled()){required.add("GPS");if(p.gpsSatisfied())satisfied.add("GPS");}
   stage=new StageView(data.id(),p.sequence(),data.name(),config.type(),p.activatedAt(),InstructionsMarkdown.participantSource(s.event().id(),registration,data.instructionsMarkdown()),InstructionsMarkdown.renderParticipant(s.event().id(),registration,data.instructionsMarkdown(),names),List.copyOf(required),List.copyOf(satisfied),library.stream().filter(f->reachable.contains(f.nameKey())).map(f->new FileView(f.fileName(),f.contentType(),f.byteSize(),url(registration,f.fileName()))).toList());
  }
  // V29 currently persists only takeover adjustments; no gameplay penalty ledger exists yet.
  BigInteger total=BigInteger.ZERO;for(var a:store.adjustments(registration))total=total.add(BigInteger.valueOf(a.points()));
  return new View(registration,s.run().id(),s.run().activationGeneration(),store.registration(registration,false).deviceGeneration(),s.run().version(),s.run().status(),s.run().startedAt(),EventLifecycle.effective(s.event().status(),s.event().scheduledStartAt(),s.event().endAt(),clock.instant()),s.event().endAt(),stage,Long.toString(total.longValueExact()));
 });}
 public Content file(String token,UUID registration,String name){
  // Same storage-before-DB order as EventService. Materialize bytes while authorization locks are held.
  return files.storage.files.exclusive(()->transaction(()->{
   var s=snapshot(token,registration);String key;try{key=ImageNames.key(name);}catch(AuthFailure invalid){throw missing();}
   var library=events.files(s.event().id());var visible=s.progress().stream().filter(p->p.status().equals("ACTIVE")||p.status().equals("COMPLETED")||p.status().equals("SKIPPED")&&p.activatedAt()!=null).map(p->s.stages().get(p.stageId())).toList();
   var allowed=reachable(s.event().id(),visible,library);var file=library.stream().filter(f->f.nameKey().equals(key)&&allowed.contains(key)).findFirst().orElseThrow(ParticipantReadService::missing);
   if(!Set.of("image/png","image/jpeg","text/markdown","application/pdf").contains(file.contentType()))throw unavailable();
   byte[] bytes=files.storage.read(s.event().id(),file.fileName());if(bytes.length!=file.byteSize())throw unavailable();
   if(file.isMarkdown()){if(!Arrays.equals(bytes,file.markdownSource().getBytes(StandardCharsets.UTF_8)))throw unavailable();bytes=TemplateDocumentHtml.page(file.fileName(),InstructionsMarkdown.renderParticipant(s.event().id(),registration,file.markdownSource(),TemplateFileReferences.names(library))).getBytes(StandardCharsets.UTF_8);}
   return new Content(file.fileName(),file.isMarkdown()?"text/html;charset=UTF-8":file.contentType(),bytes,file.isMarkdown(),file.contentType().equals("application/pdf"));
  }));
 }
 static Set<String> reachable(UUID event,List<TemplateStore.Stage> stages,List<TemplateStore.Image> library){
  var known=new HashMap<String,TemplateStore.Image>();library.forEach(f->known.put(f.nameKey(),f));var queue=new ArrayDeque<String>();var seen=new HashSet<String>();
  for(var stage:stages)InstructionsMarkdown.analyzeEvent(event,stage.instructionsMarkdown()).references().values().forEach(r->queue.add(r.key()));
  while(!queue.isEmpty()){String key=queue.remove();if(!seen.add(key))continue;var file=known.get(key);if(file!=null&&file.isMarkdown())InstructionsMarkdown.analyzeEvent(event,file.markdownSource()).references().values().forEach(r->queue.add(r.key()));}
  return Set.copyOf(seen);
 }
 private <T>T transaction(java.util.function.Supplier<T> work){try{return tx.execute(s->work.get());}catch(AuthFailure failure){throw failure;}catch(org.springframework.dao.DataAccessException|IllegalStateException|ArithmeticException failure){throw unavailable();}}
 private static String url(UUID registration,String name){return "/api/v1/race-participations/"+registration+"/files/"+java.net.URLEncoder.encode(name,StandardCharsets.UTF_8).replace("+","%20");}
 private static AuthFailure unavailable(){return new AuthFailure(503,"RACE_STATE_UNAVAILABLE");}
 private static AuthFailure missing(){return new AuthFailure(404,"RACE_FILE_NOT_FOUND");}
}
