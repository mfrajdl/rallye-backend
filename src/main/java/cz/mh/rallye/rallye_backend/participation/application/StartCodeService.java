package cz.mh.rallye.rallye_backend.participation.application;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.application.AuthService.Principal;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import cz.mh.rallye.rallye_backend.crews.persistence.CrewStore;
import cz.mh.rallye.rallye_backend.races.application.LifecycleService;
import cz.mh.rallye.rallye_backend.races.domain.EventLifecycle;
import cz.mh.rallye.rallye_backend.races.persistence.EventStore;
import cz.mh.rallye.rallye_backend.participation.persistence.*;
import cz.mh.rallye.rallye_backend.participation.persistence.CrewStartStore.*;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import cz.mh.rallye.rallye_backend.shared.persistence.CommandStore;

/** ADMIN issuance only. No redemption, device authorization or race runtime. */
@Service
public class StartCodeService {
 public record CodeResult(UUID id,String kind,String state,long generation,String code,String pointsAdjustment,Instant issuedAt){@Override public String toString(){return "CodeResult["+id+",redacted]";}}
 public record IssuedCodePair(UUID registrationId,long registrationVersion,List<CodeResult> codes){@Override public String toString(){return "IssuedCodePair["+registrationId+",redacted]";}}
 public record IssuedCode(UUID registrationId,long registrationVersion,CodeResult code){@Override public String toString(){return "IssuedCode["+registrationId+",redacted]";}}
 public record CodeView(UUID id,UUID registrationId,String kind,String state,long generation,String pointsAdjustment,Instant issuedAt,Instant usedAt,UUID usedDeviceId){}
 public record CodeDetail(UUID id,UUID registrationId,String kind,String state,long generation,String pointsAdjustment,Instant issuedAt,Instant usedAt,UUID usedDeviceId,String code){@Override public String toString(){return "CodeDetail["+id+",redacted]";}}
 public record Page(List<CodeView> items,int page,int size,boolean hasMore){}
 private final CrewStartStore store;private final RegistrationStore registrations;private final CrewStore crews;private final AccountStore accounts;private final AdminStore roles;private final CommandStore ledger;private final AuditService audit;private final CrewStartSecrets secrets;private final SecretTokens hashes;private final EventStore events;private final LifecycleService lifecycle;private final Clock clock;private final TransactionTemplate tx;
 private static final JsonMapper JSON=JsonMapper.builder().build();
 public StartCodeService(CrewStartStore store,RegistrationStore registrations,CrewStore crews,AccountStore accounts,AdminStore roles,CommandStore ledger,AuditService audit,CrewStartSecrets secrets,SecretTokens hashes,EventStore events,LifecycleService lifecycle,Clock clock,PlatformTransactionManager manager){this.store=store;this.registrations=registrations;this.crews=crews;this.accounts=accounts;this.roles=roles;this.ledger=ledger;this.audit=audit;this.secrets=secrets;this.hashes=hashes;this.events=events;this.lifecycle=lifecycle;this.clock=clock;this.tx=new TransactionTemplate(manager);}
 private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
 private static AuthFailure error(int status,String code){return new AuthFailure(status,code);}
 private void authorize(Principal p,boolean lock){if(p==null)throw AuthFailure.credentials();if(lock){roles.lock();accounts.lockAccount(p.accountId());}if(!roles.activeVerified(p.accountId())||!accounts.isActiveSession(p.sessionId(),p.accountId(),now()))throw AuthFailure.credentials();if(!roles.administrator(p.accountId()))throw error(403,"ADMIN_REQUIRED");}
 private <T>T read(java.util.function.Supplier<T> action){var r=new TransactionTemplate(tx.getTransactionManager());r.setReadOnly(true);r.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return r.execute(s->action.get());}
 private void parent(UUID event,UUID registration){if(registrations.event(event,false)==null)throw error(404,"RACE_EVENT_NOT_FOUND");if(registrations.row(event,registration,false)==null)throw error(404,"REGISTRATION_NOT_FOUND");}
 private Code requiredCode(UUID registration,UUID id){Code c=store.code(registration,id,false);if(c==null)throw error(404,"START_CODE_NOT_FOUND");return c;}
 private static String visible(Code c){return c.state().equals("AVAILABLE")?c.value():null;}
 private static CodeResult result(Code c){return new CodeResult(c.id(),c.kind(),c.state(),c.generation(),visible(c),Long.toString(c.points()),c.issuedAt());}
 private static CodeView view(Code c){return new CodeView(c.id(),c.registrationId(),c.kind(),c.state(),c.generation(),Long.toString(c.points()),c.issuedAt(),c.usedAt(),c.usedDeviceId());}
 public Page list(Principal p,UUID event,UUID registration,int page,int size){if(page<0||page>10000||size<1||size>100)throw error(400,"INVALID_REQUEST");return read(()->{authorize(p,false);parent(event,registration);var rows=store.codes(registration,page,size);return new Page(rows.stream().limit(size).map(StartCodeService::view).toList(),page,size,rows.size()>size);});}
 public CodeDetail detail(Principal p,UUID event,UUID registration,UUID code){return read(()->{authorize(p,false);parent(event,registration);Code c=requiredCode(registration,code);return new CodeDetail(c.id(),registration,c.kind(),c.state(),c.generation(),Long.toString(c.points()),c.issuedAt(),c.usedAt(),c.usedDeviceId(),visible(c));});}
 public IssuedCodePair originals(Principal p,UUID event,UUID registration,UUID key,long version){return (IssuedCodePair)issue(p,event,registration,key,version,0,null,false);}
 public IssuedCode replacement(Principal p,UUID event,UUID registration,UUID key,long version,long points,String reason){return (IssuedCode)issue(p,event,registration,key,version,points,reason,true);}
 private Object receipt(Command c){long version=JSON.readTree(c.resultJson()).get("registrationVersion").longValue();if(c.operation().equals("ORIGINAL_ISSUE"))return new IssuedCodePair(c.registrationId(),version,List.of(result(requiredCode(c.registrationId(),c.codeId())),result(requiredCode(c.registrationId(),c.secondCodeId()))));return new IssuedCode(c.registrationId(),version,result(requiredCode(c.registrationId(),c.codeId())));}
 private Code generate(UUID registration,String kind,long generation,long points,String reason,UUID actor,Instant at){for(int attempt=0;attempt<20;attempt++){String value=secrets.newCode();if(!store.codeValueExists(registration,value))return new Code(UUID.randomUUID(),registration,kind,generation,"AVAILABLE",value,at,actor,null,null,points,reason,0);}throw error(503,"RACE_STATE_UNAVAILABLE");}
 private Object issue(Principal p,UUID event,UUID registration,UUID key,long version,long points,String rawReason,boolean replacement){
  if(key==null||version<0||rawReason!=null&&(rawReason.indexOf(0)>=0||rawReason.codePointCount(0,rawReason.length())>2000))throw error(400,"INVALID_REQUEST");String reason=rawReason==null||rawReason.isBlank()?null:rawReason.strip();
  try { LifecycleService.Outcome<Object> outcome=tx.execute(s->{authorize(p,true);
   UUID crew=registrations.crew(event,registration);if(crew!=null)crews.find(crew);var e=events.lock(event);if(e==null)throw error(404,"RACE_EVENT_NOT_FOUND");var reg=store.registration(registration,true);if(crew==null||reg==null||!event.equals(reg.eventId()))throw error(404,"REGISTRATION_NOT_FOUND");Instant at=now();
   String operation=replacement?"REPLACEMENT_ISSUE":"ORIGINAL_ISSUE", shared="RACE_"+operation;
   String request=JSON.writeValueAsString(Arrays.asList(event,registration,version,Long.toString(points),reason==null?null:hashes.hash(reason)));
   String fingerprint=hashes.hash(request);String replay=ledger.replay(p.accountId(),key,shared,request,at);Command old=store.command(p.accountId(),key);
   if(old!=null){if(!old.registrationId().equals(registration)||!old.operation().equals(operation)||old.fingerprintVersion()!=1||!old.fingerprint().equals(fingerprint))throw error(409,"IDEMPOTENCY_CONFLICT");if(replay!=null&&!JSON.readTree(replay).get("commandId").asString().equals(old.id().toString()))throw error(503,"RACE_STATE_UNAVAILABLE");return new LifecycleService.Outcome<>(receipt(old),null);}
   if(replay!=null)throw error(503,"RACE_STATE_UNAVAILABLE");
   var current=lifecycle.reconcile(e,at,true,"API_RECONCILIATION");
   var check=LifecycleService.validate(()->{if(reg.version()!=version)throw error(409,"REGISTRATION_VERSION_CONFLICT");String state=EventLifecycle.effective(current.status(),current.scheduledStartAt(),current.endAt(),at);if(state.equals("ENDED"))throw error(409,"RACE_ENDED");
    if(replacement){if(!state.equals("RUNNING"))throw error(409,"RACE_NOT_STARTED");if(reg.currentRunId()==null)throw error(409,"PARTICIPATION_NOT_STARTED");var run=store.run(registration,reg.currentRunId(),true);if(run==null)throw error(503,"RACE_STATE_UNAVAILABLE");if(run.status().equals("COMPLETED"))throw error(409,"RUN_ALREADY_COMPLETED");}
    else{if(reg.startedAt()!=null)throw error(409,"PARTICIPATION_ALREADY_STARTED");if(store.nextCodeGeneration(registration)!=1)throw error(409,"ORIGINAL_CODES_ALREADY_ISSUED");}return true;});
   if(check.failure()!=null)return new LifecycleService.Outcome<Object>(null,check.failure());
   long next=store.nextCodeGeneration(registration);Code first=generate(registration,replacement?"REPLACEMENT":"ORIGINAL",next,points,reason,p.accountId(),at);store.insertCode(first);Code second=null;
   if(!replacement){second=generate(registration,"ORIGINAL",2,0,null,p.accountId(),at);store.insertCode(second);}
   if(!store.touchRegistration(registration,version,at))throw error(409,"REGISTRATION_VERSION_CONFLICT");long updated=Math.addExact(version,1);UUID command=UUID.randomUUID();String metadata=JSON.writeValueAsString(Map.of("registrationVersion",updated));
   var evidence=new Command(command,registration,p.accountId(),key,operation,1,fingerprint,first.id(),second==null?null:second.id(),null,at,201,metadata);store.insertCommand(evidence);
   ledger.save(p.accountId(),key,registration,shared,request,JSON.writeValueAsString(Map.of("commandId",command)),at);
   audit.startCodes(p.accountId(),replacement?AuditService.Event.REPLACEMENT_CODE_ISSUED:AuditService.Event.START_CODES_ISSUED,registration,event,command,second==null?List.of(first.id()):List.of(first.id(),second.id()),updated,points);
   return new LifecycleService.Outcome<>(receipt(evidence),null);
  });return outcome.get();
  }catch(org.springframework.dao.DataAccessException|IllegalStateException|ArithmeticException failure){throw new AuthFailure(503,"RACE_STATE_UNAVAILABLE",failure);}
 }
}
