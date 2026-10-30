package cz.mh.rallye.rallye_backend.races.application;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import cz.mh.rallye.rallye_backend.races.persistence.EventStore;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore;
import cz.mh.rallye.rallye_backend.templates.application.*;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import cz.mh.rallye.rallye_backend.identity.application.AuthService.Principal;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import cz.mh.rallye.rallye_backend.shared.persistence.CommandStore;
@Service
public class EventService {
 public record Input(String name,String description,Instant scheduledStartAt,Instant endAt){}
 public record Created(UUID id,long version){}
 public record View(UUID id,UUID sourceTemplateId,long sourceTemplateVersion,String name,String description,long skipPenaltyPoints,String status,Instant scheduledStartAt,Instant endAt,long version,Instant createdAt,Instant updatedAt,long defaultWrongCodePenaltyPoints,String sourceTemplateName,boolean sourceTemplateNameIsSnapshot,cz.mh.rallye.rallye_backend.races.domain.EventLifecycle.View lifecycle){}
 private View view(EventStore.Event e){return view(e,now());}
 private View view(EventStore.Event e,Instant at){return new View(e.id(),e.sourceTemplateId(),e.sourceTemplateVersion(),e.name(),e.description(),e.skipPenaltyPoints(),cz.mh.rallye.rallye_backend.races.domain.EventLifecycle.effective(e.status(),e.scheduledStartAt(),e.endAt(),at),e.scheduledStartAt(),e.endAt(),e.version(),e.createdAt(),e.updatedAt(),e.defaultWrongCodePenaltyPoints(),e.sourceTemplateName(),e.sourceTemplateNameIsSnapshot(),cz.mh.rallye.rallye_backend.races.domain.EventLifecycle.view(e.status(),e.scheduledStartAt(),e.endAt(),e.lifecycleMetadata(),at));}
 public record StageView(UUID id,UUID sourceTemplateStageId,int sequenceNo,String name,Long expectedDurationMs,Long maxPenaltyPoints,String instructionsMarkdown,String instructionsRenderedHtml,cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration.Gps gps,TemplateService.CodeView code,TemplateService.TimePenaltyView timePenalty,cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration.Delayed delayed){}
 public record InstructionsView(UUID raceEventId,UUID stageId,long version,String instructionsMarkdown,String instructionsRenderedHtml,List<TemplateFileReferences.Warning> missingReferences){}
 public record FileView(String fileName,String contentType,int byteSize,Integer width,Integer height,String url){}
 public record Content(String fileName,String contentType,byte[] bytes,boolean markdown,boolean pdf){}
 public record Page(List<View> items,int page,int size,boolean hasMore){}
 private final EventStore events;private final TemplateStore templates;private final TemplateImageFiles sourceFiles;private final EventFiles files;private final TemplateFileContent content;
 private final AdminStore roles;private final AccountStore accounts;private final CommandStore commands;private final AuditService audit;private final Clock clock;private final TransactionTemplate tx;
 private final JsonMapper json=JsonMapper.builder().build();
 public EventService(EventStore events,TemplateStore templates,TemplateImageFiles sourceFiles,EventFiles files,TemplateFileContent content,AdminStore roles,AccountStore accounts,CommandStore commands,AuditService audit,Clock clock,PlatformTransactionManager manager){if(sourceFiles.sameRoot(files.storage))throw new IllegalArgumentException("Event and template storage roots must be distinct");this.events=events;this.templates=templates;this.sourceFiles=sourceFiles;this.files=files;this.content=content;this.roles=roles;this.accounts=accounts;this.commands=commands;this.audit=audit;this.clock=clock;tx=new TransactionTemplate(manager);}
 private LifecycleService lifecycle;
 @org.springframework.beans.factory.annotation.Autowired public void lifecycle(LifecycleService lifecycle){this.lifecycle=lifecycle;}
 private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
 void authorize(Principal actor){
  if(actor==null)throw AuthFailure.credentials();roles.lock();accounts.lockAccount(actor.accountId());
  if(!roles.activeVerified(actor.accountId())||!accounts.isActiveSession(actor.sessionId(),actor.accountId(),now()))throw AuthFailure.credentials();
  if(!roles.administrator(actor.accountId()))throw new AuthFailure(403,"ADMIN_REQUIRED");
 }
 private EventStore.Event event(UUID id){var event=events.lock(id);if(event==null)throw new AuthFailure(404,"RACE_EVENT_NOT_FOUND");return event;}
 static Input validated(Input in){
  if(in==null||in.name()==null||in.name().isBlank()||in.name().codePointCount(0,in.name().length())>100||in.description()!=null&&in.description().codePointCount(0,in.description().length())>2000)throw invalid();
  Instant start=in.scheduledStartAt(),end=in.endAt();
  if(start!=null)start=start.truncatedTo(ChronoUnit.MILLIS);if(end!=null)end=end.truncatedTo(ChronoUnit.MILLIS);
  return new Input(in.name().strip(),in.description(),start,end);
 }
 private static AuthFailure invalid(){return new AuthFailure(400,"INVALID_REQUEST");}
 public Created create(Principal actor,UUID template,long sourceVersion,UUID key,Input raw){
  var in=validated(raw);if(sourceVersion<0||key==null)throw invalid();
  tx.executeWithoutResult(s->authorize(actor));
  // Order: source storage -> destination storage -> admin_control -> actor account -> template.
  return sourceFiles.files.exclusive(()->files.storage.files.exclusive(()->{
   UUID id=UUID.randomUUID();var written=new ArrayList<String>();
   try{return tx.execute(s->{
    authorize(actor);String request=json.writeValueAsString(Arrays.asList(template,sourceVersion,in.name(),in.description(),in.scheduledStartAt()==null?null:in.scheduledStartAt().toString(),in.endAt()==null?null:in.endAt().toString()));
    var old=commands.replay(actor.accountId(),key,"RACE_EVENT_CREATE",request,now());if(old!=null)return json.readValue(old,Created.class);
    var source=templates.lock(template);if(source==null)throw new AuthFailure(404,"TEMPLATE_NOT_FOUND");
    if(source.version()!=sourceVersion)throw new AuthFailure(409,"TEMPLATE_VERSION_CONFLICT");
    if(!source.status().equals("READY"))throw new AuthFailure(409,"TEMPLATE_NOT_READY");
    Instant at=now();validateTimes(in,null,at);
    var stages=templates.stages(template);var library=templates.images(template);
    if(!TemplateFileReferences.readiness(source,stages,library).ready())throw new AuthFailure(409,"TEMPLATE_NOT_READY");
    events.insert(id,source,in.name(),in.description(),in.scheduledStartAt(),in.endAt(),at);
    for(var file:library){
     byte[] bytes=sourceFiles.read(template,file.fileName());
     if(bytes.length!=file.byteSize()||!ImageNames.key(file.fileName()).equals(file.nameKey()))throw inconsistent();
     var verified=content.validate(file.fileName(),new ByteArrayInputStream(bytes),sourceFiles.files);
     if(!verified.contentType().equals(file.contentType())||!Objects.equals(verified.width(),file.width())||!Objects.equals(verified.height(),file.height())||!Objects.equals(verified.markdown(),file.markdownSource()))throw inconsistent();
     String markdown=file.markdownSource();if(file.isMarkdown()){markdown=InstructionsMarkdown.copyToEvent(template,id,markdown);bytes=markdown.getBytes(StandardCharsets.UTF_8);}
     var copy=new TemplateFileContent.Validated(bytes,file.contentType(),file.width(),file.height(),markdown);
     files.storage.write(id,file.fileName(),bytes);written.add(file.fileName());events.file(UUID.randomUUID(),id,file.fileName(),file.nameKey(),copy,at);
    }
    for(var stage:stages){String markdown=InstructionsMarkdown.copyToEvent(template,id,stage.instructionsMarkdown());InstructionsMarkdown.validate(markdown);events.copyStage(stage.id(),UUID.randomUUID(),id,markdown);}
    audit.race(actor.accountId(),AuditService.Event.RACE_EVENT_CREATED,id,template,sourceVersion);
    if(lifecycle!=null)lifecycle.reconcile(event(id),at,true,"API_RECONCILIATION");
    var result=new Created(id,lifecycle==null?0:event(id).version());commands.save(actor.accountId(),key,id,"RACE_EVENT_CREATE",request,json.writeValueAsString(result),at);return result;
   });}catch(RuntimeException failure){
    for(var name:written)try{if(!events.referenced(id,ImageNames.key(name)))files.storage.remove(id,name);}catch(RuntimeException unavailable){/* Uncertain commit: retain bytes until checked cleanup. */}
    throw failure;
   }
  }));
 }
 private static AuthFailure inconsistent(){return new AuthFailure(503,"RACE_EVENT_SNAPSHOT_UNAVAILABLE");}
 public View detail(Principal actor,UUID id){return read(s->{authorizeRead(actor);var e=readEvent(id);return view(e,now());});}
 public Page list(Principal actor,String query,int page,int size){if(page<0||size<1||size>100||query!=null&&query.length()>100)throw invalid();return read(s->{authorizeRead(actor);Instant at=now();var rows=events.list(query==null?"":query.strip(),page,size);return new Page(rows.subList(0,Math.min(rows.size(),size)).stream().map(e->view(e,at)).toList(),page,size,rows.size()>size);});}
 private <T> T read(java.util.function.Function<org.springframework.transaction.TransactionStatus,T> work){var read=new TransactionTemplate(tx.getTransactionManager());read.setReadOnly(true);read.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);return read.execute(work::apply);}
 private void authorizeRead(Principal actor){if(actor==null||!roles.activeVerified(actor.accountId())||!accounts.isActiveSession(actor.sessionId(),actor.accountId(),now()))throw AuthFailure.credentials();if(!roles.administrator(actor.accountId()))throw new AuthFailure(403,"ADMIN_REQUIRED");}
 private EventStore.Event readEvent(UUID id){var e=events.read(id);if(e==null)throw new AuthFailure(404,"RACE_EVENT_NOT_FOUND");return e;}
 private static void validateTimes(Input in,EventStore.Event before,Instant at){
  if(in.scheduledStartAt()!=null&&(before==null||!Objects.equals(before.scheduledStartAt(),in.scheduledStartAt()))&&in.scheduledStartAt().isBefore(at))throw new AuthFailure(400,"RACE_EVENT_SCHEDULE_INVALID");
  if(in.endAt()!=null&&(before==null||!Objects.equals(before.endAt(),in.endAt()))&&!in.endAt().isAfter(at))throw new AuthFailure(400,"RACE_EVENT_END_TIME_INVALID");
 }
 public View update(Principal actor,UUID id,long version,Input raw){var in=validated(raw);if(version<0)throw invalid();
  LifecycleService.Outcome<View> outcome=tx.execute(s->{authorize(actor);var locked=event(id);Instant at=now();var before=lifecycle==null?locked:lifecycle.reconcile(locked,at,true,"API_RECONCILIATION");
   var validation=LifecycleService.validate(()->{
    if(before.version()!=version)throw new AuthFailure(409,"RACE_EVENT_VERSION_CONFLICT");
    if(before.status().equals("ENDED"))throw new AuthFailure(409,"RACE_EVENT_ENDED");
    if(before.status().equals("RUNNING")){
     if(!Objects.equals(before.scheduledStartAt(),in.scheduledStartAt()))throw new AuthFailure(409,"RACE_EVENT_SCHEDULE_LOCKED");
     if(!before.name().equals(in.name())||!Objects.equals(before.description(),in.description()))throw new AuthFailure(409,"RACE_EVENT_DETAILS_LOCKED");
    }
    validateTimes(in,before,at);return before;
   });if(validation.failure()!=null)return new LifecycleService.Outcome<View>(null,validation.failure());
   boolean times=!Objects.equals(before.scheduledStartAt(),in.scheduledStartAt())||!Objects.equals(before.endAt(),in.endAt());
   if(times||!before.name().equals(in.name())||!Objects.equals(before.description(),in.description())){
    events.update(id,in.name(),in.description(),in.scheduledStartAt(),in.endAt(),at);
    if(times&&lifecycle!=null)lifecycle.timesUpdated(before,in.scheduledStartAt(),in.endAt(),at,actor.accountId());
    audit.race(actor.accountId(),AuditService.Event.RACE_EVENT_UPDATED,id,before.sourceTemplateId(),before.sourceTemplateVersion());
   }
   var after=event(id);if(lifecycle!=null)after=lifecycle.reconcile(after,at,true,"API_RECONCILIATION");
   return new LifecycleService.Outcome<>(view(after,at),null);
  });return outcome.get();
 }
 public InstructionsView updateInstructions(Principal actor,UUID id,UUID stageId,long version,String markdown){
  if(version<0)throw invalid();
  LifecycleService.Outcome<InstructionsView> outcome=tx.execute(s->{
   authorize(actor);var locked=event(id);Instant at=now();var parent=lifecycle==null?locked:lifecycle.reconcile(locked,at,true,"API_RECONCILIATION");
   var check=LifecycleService.validate(()->{
    if(parent.version()!=version)throw new AuthFailure(409,"RACE_EVENT_VERSION_CONFLICT");
    if(!parent.status().equals("PLANNED"))throw new AuthFailure(409,"RACE_EVENT_NOT_PLANNED");
    var stage=events.stages(id).stream().filter(row->row.data().id().equals(stageId)).findFirst().orElseThrow(()->new AuthFailure(404,"RACE_EVENT_STAGE_NOT_FOUND"));
    InstructionsMarkdown.validate(markdown);var parsed=InstructionsMarkdown.analyzeEvent(id,markdown);var library=events.files(id);TemplateFileReferences.validate(parsed,library);
    var names=TemplateFileReferences.names(library);var missing=new LinkedHashSet<TemplateFileReferences.Warning>();
    for(var ref:parsed.references().values())if(!names.containsKey(ref.key()))missing.add(new TemplateFileReferences.Warning("FILE_NOT_FOUND","STAGE",stageId,null,ref.fileName(),ref.image()?"IMAGE":"LINK"));
    return new InstructionsView(id,stageId,Objects.equals(stage.data().instructionsMarkdown(),markdown)?parent.version():parent.version()+1,markdown,InstructionsMarkdown.renderEvent(id,markdown,names),List.copyOf(missing));
   });if(check.failure()!=null)return check;
   var result=check.value();if(result.version()!=parent.version()){events.instructions(id,stageId,markdown);events.touch(id,at);audit.raceInstructions(actor.accountId(),id,stageId);}return check;
  });return outcome.get();
 }
 public void delete(Principal actor,UUID id,long version){
  if(version<0)throw invalid();
  files.storage.files.exclusive(()->{
   LifecycleService.Outcome<List<TemplateStore.Image>> outcome=tx.execute(s->{authorize(actor);var locked=event(id);Instant at=now();var row=lifecycle==null?locked:lifecycle.reconcile(locked,at,true,"API_RECONCILIATION");
    var check=LifecycleService.validate(()->{if(row.version()!=version)throw new AuthFailure(409,"RACE_EVENT_VERSION_CONFLICT");if(!row.status().equals("PLANNED"))throw new AuthFailure(409,"RACE_EVENT_NOT_PLANNED");events.ensureDeletionSupported();if(events.registrations(id)||events.lifecycleHistory(id))throw new AuthFailure(409,"RACE_EVENT_IN_USE");return events.files(id);});
    if(check.failure()!=null)return check;
    events.delete(id);audit.race(actor.accountId(),AuditService.Event.RACE_EVENT_DELETED,id,row.sourceTemplateId(),row.sourceTemplateVersion());return check;
   });
   for(var file:outcome.get())files.storage.remove(id,file.fileName());return null;
  });
 }
 public List<StageView> stages(Principal actor,UUID id){return read(s->{authorizeRead(actor);var parent=readEvent(id);var names=TemplateFileReferences.names(events.files(id));return events.stages(id).stream().map(row->stageView(row,names,parent.defaultWrongCodePenaltyPoints())).toList();});}
 public StageView stage(Principal actor,UUID event,UUID stage){return stages(actor,event).stream().filter(s->s.id().equals(stage)).findFirst().orElseThrow(()->new AuthFailure(404,"RACE_EVENT_STAGE_NOT_FOUND"));}
 private StageView stageView(EventStore.Stage row,Map<String,String> names,long global){var s=row.data();var c=s.configuration();return new StageView(s.id(),row.sourceTemplateStageId(),s.sequenceNo(),s.name(),s.expectedDurationMs(),s.maxPenaltyPoints()==null?0:s.maxPenaltyPoints(),s.instructionsMarkdown(),InstructionsMarkdown.renderEvent(s.templateId(),s.instructionsMarkdown(),names),c.gps(),TemplateService.codeView(c.code(),global),new TemplateService.TimePenaltyView(c.timePenalty().enabled(),c.timePenalty().intervalMs()==null?null:c.timePenalty().intervalMs()/1000,c.timePenalty().pointsPerInterval()),c.delayed());}
 public List<FileView> files(Principal actor,UUID id){return read(s->{authorizeRead(actor);readEvent(id);return events.files(id).stream().map(f->new FileView(f.fileName(),f.contentType(),f.byteSize(),f.width(),f.height(),ImageNames.eventUrl(id,f.fileName()))).toList();});}
 public Content file(Principal actor,UUID id,String name){String key=ImageNames.key(name);return files.storage.files.exclusive(()->read(s->{authorizeRead(actor);readEvent(id);var library=events.files(id);var file=library.stream().filter(f->f.nameKey().equals(key)).findFirst().orElseThrow(()->new AuthFailure(404,"RACE_EVENT_FILE_NOT_FOUND"));byte[] bytes=files.storage.read(id,file.fileName());if(bytes.length!=file.byteSize())throw inconsistent();if(file.isMarkdown()){if(!Arrays.equals(bytes,file.markdownSource().getBytes(StandardCharsets.UTF_8)))throw inconsistent();bytes=TemplateDocumentHtml.page(file.fileName(),InstructionsMarkdown.renderEvent(id,file.markdownSource(),TemplateFileReferences.names(library))).getBytes(StandardCharsets.UTF_8);}return new Content(file.fileName(),file.isMarkdown()?"text/html;charset=UTF-8":file.contentType(),bytes,file.isMarkdown(),file.contentType().equals("application/pdf"));}));}
}
