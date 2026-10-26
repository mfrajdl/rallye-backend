package cz.mh.rallye.rallye_backend.templates.application;

import java.time.*;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.password.PasswordEncoder;
import cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration;
import cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore.Template;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore.Stage;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import cz.mh.rallye.rallye_backend.identity.application.AuthService.Principal;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import cz.mh.rallye.rallye_backend.audit.application.AuditService.Event;
import cz.mh.rallye.rallye_backend.shared.persistence.CommandStore;

@Service
@Transactional
public class TemplateService {
    public record CodeView(boolean enabled,boolean codeConfigured,Long wrongAttemptPenaltyPoints) {}
    public record TimePenaltyView(boolean enabled,Long intervalSeconds,Long pointsPerInterval) {}
    public record StageView(UUID id,int sequenceNo,String name,Long expectedDurationMs,Long maxPenaltyPoints,String instructionsHtml,
                            Gps gps,CodeView code,TimePenaltyView timePenalty,Delayed delayed) {}
    public record CodeChange(boolean enabled,String newCode,Long wrongAttemptPenaltyPoints) {
        @Override public String toString(){return "CodeChange[redacted]";}
    }
    public record ConfigurationChange(Gps gps,CodeChange code,TimePenalty timePenalty,Delayed delayed) {
        @Override public String toString(){return "ConfigurationChange[redacted]";}
    }
    public record View(UUID id,String name,String description,long skipPenaltyPoints,String status,long version,Instant createdAt,Instant updatedAt,List<StageView> stages) {}
    public record Summary(UUID id,String name,String description,long skipPenaltyPoints,String status,long version,Instant createdAt,Instant updatedAt) {}
    public record Page(List<Summary> items,int page,int size,boolean hasMore) {}
    public record Created(UUID id,UUID templateId,long version) {}
    private final TemplateStore store; private final AdminStore roles; private final AccountStore accounts;
    private final CommandStore commands; private final AuditService audit; private final Clock clock; private final PasswordEncoder passwords;
    private final TemplateImageFiles imageFiles;
    private final JsonMapper json=JsonMapper.builder().build();
    public TemplateService(TemplateStore store,AdminStore roles,AccountStore accounts,CommandStore commands,AuditService audit,Clock clock,PasswordEncoder passwords,TemplateImageFiles imageFiles){this.store=store;this.roles=roles;this.accounts=accounts;this.commands=commands;this.audit=audit;this.clock=clock;this.passwords=passwords;this.imageFiles=imageFiles;}
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
    void authorize(Principal actor){
        if(actor==null)throw AuthFailure.credentials();roles.lock();accounts.lockAccount(actor.accountId());
        if(!roles.activeVerified(actor.accountId()) || !accounts.isActiveSession(actor.sessionId(),actor.accountId(),now()))throw AuthFailure.credentials();
        if(!roles.administrator(actor.accountId()))throw new AuthFailure(403,"ADMIN_REQUIRED");
    }
    private static AuthFailure invalid(){return new AuthFailure(400,"INVALID_REQUEST");}
    private Template template(UUID id){var row=store.lock(id);if(row==null)throw new AuthFailure(404,"TEMPLATE_NOT_FOUND");return row;}
    private void version(Template t,long version){if(version<0)throw invalid();if(t.version()!=version)throw new AuthFailure(409,"TEMPLATE_VERSION_CONFLICT");}
    private static String name(String value,boolean required){if(value==null || value.isBlank()){if(required)throw invalid();return null;}if(value.codePointCount(0,value.length())>100)throw invalid();return value.strip();}
    private static String description(String value){if(value!=null && value.codePointCount(0,value.length())>2000)throw invalid();return value;}
    private static void stageValues(Long expected,Long max){if(expected!=null && expected<0 || max!=null && max<0)throw invalid();}
    private static StageView view(Stage s){
        var c=s.configuration();var time=c.timePenalty();
        return new StageView(s.id(),s.sequenceNo(),s.name(),s.expectedDurationMs(),s.maxPenaltyPoints(),s.instructionsHtml(),c.gps(),
                new CodeView(c.code().enabled(),c.code().hash()!=null,c.code().wrongAttemptPenaltyPoints()),
                new TimePenaltyView(time.enabled(),time.intervalMs()==null?null:time.intervalMs()/1000,time.pointsPerInterval()),c.delayed());
    }
    private View view(Template t){return new View(t.id(),t.name(),t.description(),t.skipPenaltyPoints(),t.status(),t.version(),t.createdAt(),t.updatedAt(),store.stages(t.id()).stream().map(TemplateService::view).toList());}
    private Stage stage(UUID template,UUID id){return store.stages(template).stream().filter(s->s.id().equals(id)).findFirst().orElseThrow(()->new AuthFailure(404,"TEMPLATE_STAGE_NOT_FOUND"));}
    private Created replay(Principal actor,UUID key,String op,String request){if(key==null)throw invalid();String result=commands.replay(actor.accountId(),key,op,request,now());return result==null?null:json.readValue(result,Created.class);}
    private void save(Principal actor,UUID key,String op,String request,Created result){commands.save(actor.accountId(),key,result.id(),op,request,json.writeValueAsString(result),now());}
    public Created create(Principal actor,UUID key,String rawName,String description,long skip){
        authorize(actor);String name=name(rawName,true);description=description(description);
        String request=json.writeValueAsString(Arrays.asList(name,description,skip));var old=replay(actor,key,"TEMPLATE_CREATE",request);if(old!=null)return old;
        UUID id=UUID.randomUUID();store.insert(id,name,description,skip,now());audit.template(actor.accountId(),Event.TEMPLATE_CREATED,id,false);
        var result=new Created(id,id,0);save(actor,key,"TEMPLATE_CREATE",request,result);return result;
    }
    public Page list(Principal actor,String query,String status,int page,int size){
        authorize(actor);if(page<0 || page>10000 || size<1 || size>100 || query!=null && query.codePointCount(0,query.length())>100 || status!=null && !Set.of("DRAFT","READY").contains(status))throw invalid();
        var rows=store.list(query==null?null:query.strip(),status,page,size);
        return new Page(rows.stream().limit(size).map(t->new Summary(t.id(),t.name(),t.description(),t.skipPenaltyPoints(),t.status(),t.version(),t.createdAt(),t.updatedAt())).toList(),page,size,rows.size()>size);
    }
    public View detail(Principal actor,UUID id){authorize(actor);return view(template(id));}
    public View update(Principal actor,UUID id,long expected,String rawName,String description,long skip){
        authorize(actor);var t=template(id);version(t,expected);String name=name(rawName,true);description=description(description);
        if(!Objects.equals(t.name(),name)||!Objects.equals(t.description(),description)||t.skipPenaltyPoints()!=skip){
            store.update(id,name,description,skip,t.skipPenaltyPoints()!=skip,now());audit.template(actor.accountId(),Event.TEMPLATE_UPDATED,id,false);
        }return view(template(id));
    }
    public void delete(Principal actor,UUID id,long expected){authorize(actor);var t=template(id);version(t,expected);store.ensureDeletionSupported();var images=store.images(id).stream().map(TemplateStore.Image::id).toList();store.delete(id);imageFiles.removeAfterCommit(images);audit.template(actor.accountId(),Event.TEMPLATE_DELETED,id,false);}
    public TemplateReadiness.Report readiness(Principal actor,UUID id){authorize(actor);var t=template(id);return TemplateReadiness.check(t,store.stages(id));}
    public View state(Principal actor,UUID id,long expected,String status){
        authorize(actor);var t=template(id);version(t,expected);if(status==null || !Set.of("DRAFT","READY").contains(status))throw invalid();
        if(status.equals("READY")){
            var report=TemplateReadiness.check(t,store.stages(id));
            if(!report.ready())throw new TemplateReadiness.NotReady(report);
        }
        if(!status.equals(t.status())){
            store.state(id,status,now());
            audit.template(actor.accountId(),status.equals("READY")?Event.TEMPLATE_READIED:Event.TEMPLATE_DRAFTED,id,false);
        }
        return view(template(id));
    }
    public Created createStage(Principal actor,UUID template,long expected,UUID key,String rawName,Long duration,Long max){
        authorize(actor);String name=name(rawName,false);stageValues(duration,max);
        String request=json.writeValueAsString(Arrays.asList(template,expected,name,duration,max));var old=replay(actor,key,"TEMPLATE_STAGE_CREATE",request);if(old!=null)return old;
        var t=template(template);version(t,expected);UUID id=UUID.randomUUID();store.insertStage(id,template,store.stages(template).size()+1,name,duration,max);store.touch(template,now());audit.template(actor.accountId(),Event.TEMPLATE_STAGE_CREATED,id,true);
        var result=new Created(id,template,Math.incrementExact(t.version()));save(actor,key,"TEMPLATE_STAGE_CREATE",request,result);return result;
    }
    public StageView stageDetail(Principal actor,UUID template,UUID id){authorize(actor);template(template);return view(stage(template,id));}
    public View updateStage(Principal actor,UUID template,UUID id,long expected,String rawName,Long duration,Long max){
        return updateStage(actor,template,id,expected,rawName,duration,max,false,null);
    }
    public View updateStage(Principal actor,UUID template,UUID id,long expected,String rawName,Long duration,Long max,boolean instructionsSpecified,String instructionsHtml){
        authorize(actor);var t=template(template);version(t,expected);var s=stage(template,id);String name=name(rawName,false);stageValues(duration,max);
        InstructionsHtml.Clean clean=instructionsSpecified?InstructionsHtml.sanitize(template,instructionsHtml):null;
        if(clean!=null){
            var existing=store.images(template).stream().map(TemplateStore.Image::id).collect(java.util.stream.Collectors.toSet());
            if(!existing.containsAll(clean.images()))throw new AuthFailure(400,"TEMPLATE_IMAGE_REFERENCE_INVALID");
            instructionsHtml=clean.html();
        }
        boolean baseChanged=!Objects.equals(s.name(),name)||!Objects.equals(s.expectedDurationMs(),duration)||!Objects.equals(s.maxPenaltyPoints(),max);
        boolean instructionsChanged=instructionsSpecified && !Objects.equals(s.instructionsHtml(),instructionsHtml);
        if(baseChanged || instructionsChanged){
            if(baseChanged)store.updateStage(id,name,duration,max);
            if(instructionsChanged){store.instructionsHtml(id,instructionsHtml);store.references(template,id,clean.images());}
            store.touch(template,now());audit.template(actor.accountId(),Event.TEMPLATE_STAGE_UPDATED,id,true);
        }
        return view(template(template));
    }
    public View configure(Principal actor,UUID template,UUID id,long expected,ConfigurationChange change){
        authorize(actor);var t=template(template);version(t,expected);var stage=stage(template,id);
        if(change==null || change.gps()==null || change.code()==null || change.timePenalty()==null || change.delayed()==null)throw invalid();
        // Validate every supplied numeric value even in a disabled rule, before expensive password hashing.
        var old=stage.configuration();String hash=old.code().hash();
        var proposed=new StageConfiguration(change.gps(),new Code(change.code().enabled(),hash,change.code().wrongAttemptPenaltyPoints()),change.timePenalty(),change.delayed());
        proposed.validate();
        if(change.code().newCode()!=null){
            String normalized=normalizeCode(change.code().newCode());
            if(hash==null || !passwords.matches(normalized,hash))hash=passwords.encode(normalized);
        }
        var config=new StageConfiguration(change.gps(),new Code(change.code().enabled(),hash,change.code().wrongAttemptPenaltyPoints()),change.timePenalty(),change.delayed());
        if(!config.equals(old)){
            store.configureStage(id,config);store.touch(template,now());audit.template(actor.accountId(),Event.TEMPLATE_STAGE_CONFIGURED,id,true);
        }
        return view(template(template));
    }
    public static String normalizeCode(String code){
        if(code==null)throw invalid();String normalized=code.strip().toUpperCase(Locale.ROOT);
        if(normalized.isEmpty() || normalized.codePointCount(0,normalized.length())>128)throw invalid();return normalized;
    }
    public View deleteStage(Principal actor,UUID template,UUID id,long expected){
        authorize(actor);var t=template(template);version(t,expected);stage(template,id);store.deleteStage(id);
        store.order(template,store.stages(template).stream().map(Stage::id).toList());store.touch(template,now());audit.template(actor.accountId(),Event.TEMPLATE_STAGE_DELETED,id,true);return view(template(template));
    }
    public View order(Principal actor,UUID template,long expected,List<UUID> ids){
        authorize(actor);var t=template(template);version(t,expected);var current=store.stages(template).stream().map(Stage::id).toList();
        if(ids==null||ids.stream().anyMatch(Objects::isNull)||ids.size()!=current.size()||new HashSet<>(ids).size()!=ids.size()||!new HashSet<>(ids).equals(new HashSet<>(current)))throw invalid();
        if(!ids.equals(current)){store.order(template,ids);store.touch(template,now());audit.template(actor.accountId(),Event.TEMPLATE_REORDERED,template,false);}return view(template(template));
    }
}
