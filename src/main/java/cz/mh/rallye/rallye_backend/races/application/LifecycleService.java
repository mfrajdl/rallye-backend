package cz.mh.rallye.rallye_backend.races.application;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import cz.mh.rallye.rallye_backend.races.domain.EventLifecycle;
import cz.mh.rallye.rallye_backend.races.persistence.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.application.AuthService.Principal;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import cz.mh.rallye.rallye_backend.shared.persistence.CommandStore;

@Service
public class LifecycleService {
 public record Receipt(UUID raceEventId,long version,LifecycleStore.Entry transition){}
 public record Page<T>(List<T> items,int page,int size,boolean hasMore){}
 /** Only explicitly captured business validation failures are returned after committing reconciliation. */
 public record Outcome<T>(T value,AuthFailure failure){public T get(){if(failure!=null)throw failure;return value;}}
 public static <T> Outcome<T> validate(Supplier<T> validation){try{return new Outcome<>(validation.get(),null);}catch(AuthFailure failure){if(failure.status()>=500)throw failure;return new Outcome<>(null,failure);}}
 private final EventStore events;private final LifecycleStore store;private final AdminStore roles;private final AccountStore accounts;private final CommandStore commands;private final AuditService audit;private final Clock clock;private final TransactionTemplate tx;
 private static final JsonMapper JSON=JsonMapper.builder().build();
 public LifecycleService(EventStore events,LifecycleStore store,AdminStore roles,AccountStore accounts,CommandStore commands,AuditService audit,Clock clock,PlatformTransactionManager manager){this.events=events;this.store=store;this.roles=roles;this.accounts=accounts;this.commands=commands;this.audit=audit;this.clock=clock;tx=new TransactionTemplate(manager);}
 private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
 private void authorize(Principal p,boolean lock){if(p==null)throw AuthFailure.credentials();if(lock){roles.lock();accounts.lockAccount(p.accountId());}if(!roles.activeVerified(p.accountId())||!accounts.isActiveSession(p.sessionId(),p.accountId(),now()))throw AuthFailure.credentials();if(!roles.administrator(p.accountId()))throw failure(403,"ADMIN_REQUIRED");}
 private static AuthFailure failure(int status,String code){return new AuthFailure(status,code);}
 private EventStore.Event required(UUID id,boolean lock){var e=lock?events.lock(id):events.read(id);if(e==null)throw failure(404,"RACE_EVENT_NOT_FOUND");return e;}
 public EventLifecycle.View view(EventStore.Event e,Instant at){return EventLifecycle.view(e.status(),e.scheduledStartAt(),e.endAt(),e.lifecycleMetadata(),at);}
 /** Caller already holds admin_control, relevant accounts/crew, then the event row. */
 @Transactional(propagation=Propagation.MANDATORY)
 public EventStore.Event reconcile(EventStore.Event e,Instant at,boolean includeStart,String trigger){
  if(e.status().equals("ENDED"))return e;
  if(e.endAt()!=null&&!at.isBefore(e.endAt())){transition(e,"ENDED",e.endAt(),at,trigger,"END_AT",null,e.endAt());return required(e.id(),true);}
  if(includeStart&&e.status().equals("PLANNED")&&e.scheduledStartAt()!=null&&!at.isBefore(e.scheduledStartAt())){transition(e,"STARTED",e.scheduledStartAt(),at,trigger,"SCHEDULED_START",null,e.endAt());return required(e.id(),true);}
  return e;
 }
 private LifecycleStore.Entry transition(EventStore.Event e,String type,Instant effective,Instant at,String trigger,String cause,UUID actor,Instant end){
  var m=store.metadata(e.id());boolean ending=type.equals("ENDED");UUID period=ending?m.activePeriodId():UUID.randomUUID();
  var h=store.append(UUID.randomUUID(),e,type,ending?"ENDED":"RUNNING",effective,at,trigger,cause,actor,e.scheduledStartAt(),end,period,ending?m.closureRevision()+1:null,type.equals("RESUMED")?m.currentClosureRevision():null);
  if(ending)store.end(e,m,h);else store.start(e,m,h,end);
  audit.raceLifecycle(actor,AuditService.Event.valueOf("RACE_EVENT_"+type),e.id(),h.id(),h.versionAfter());return h;
 }
 @Transactional(propagation=Propagation.MANDATORY)
 public void timesUpdated(EventStore.Event before,Instant start,Instant end,Instant at,UUID actor){store.append(UUID.randomUUID(),before,"TIMES_UPDATED",before.status(),at,at,"ADMIN","SETTINGS_UPDATE",actor,start,end,null,null,null);}
 public Receipt command(Principal p,UUID id,UUID key,long version,String type,Instant rawEnd){
  if(key==null||version<0||!Set.of("STARTED","ENDED","RESUMED").contains(type))throw failure(400,"INVALID_REQUEST");
  Instant end=rawEnd==null?null:rawEnd.truncatedTo(ChronoUnit.MILLIS);
  Outcome<Receipt> result=tx.execute(s->{authorize(p,true);var before=required(id,true);Instant at=now();String request=JSON.writeValueAsString(Arrays.asList(id,version,end==null?null:end.toString()));String operation="RACE_EVENT_"+type;
   String old=commands.replay(p.accountId(),key,operation,request,at);if(old!=null)return new Outcome<>(JSON.readValue(old,Receipt.class),null);
   var e=reconcile(before,at,type.equals("RESUMED"),"API_RECONCILIATION");
   var check=validate(()->{if(e.version()!=version)throw failure(409,"RACE_EVENT_VERSION_CONFLICT");
    if(type.equals("STARTED")&&!e.status().equals("PLANNED"))throw failure(409,e.status().equals("ENDED")?"RACE_EVENT_ALREADY_ENDED":"RACE_EVENT_ALREADY_STARTED");
    if(type.equals("ENDED")&&e.status().equals("ENDED"))throw failure(409,"RACE_EVENT_ALREADY_ENDED");
    if(type.equals("RESUMED")){if(!e.status().equals("ENDED"))throw failure(409,"RACE_EVENT_NOT_ENDED");if(store.metadata(id).resultsFinalizedAt()!=null)throw failure(409,"RACE_EVENT_RESULTS_FINALIZED");if(end!=null&&!end.isAfter(at))throw failure(400,"RACE_EVENT_END_TIME_INVALID");}return e;});
   if(check.failure()!=null)return new Outcome<Receipt>(null,check.failure());
   var h=transition(e,type,at,at,"ADMIN","MANUAL",p.accountId(),type.equals("RESUMED")?end:e.endAt());var receipt=new Receipt(id,h.versionAfter(),h);
   commands.save(p.accountId(),key,id,operation,request,JSON.writeValueAsString(receipt),at);return new Outcome<>(receipt,null);
  });return result.get();
 }
 private static void paging(int page,int size){if(page<0||page>10000||size<1||size>100)throw failure(400,"INVALID_REQUEST");}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Page<LifecycleStore.Entry> history(Principal p,UUID id,int page,int size){paging(page,size);authorize(p,false);required(id,false);var rows=store.history(id,page,size);return new Page<>(rows.stream().limit(size).toList(),page,size,rows.size()>size);}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Page<LifecycleStore.Period> periods(Principal p,UUID id,int page,int size){paging(page,size);authorize(p,false);required(id,false);var rows=store.periods(id,page,size);return new Page<>(rows.stream().limit(size).toList(),page,size,rows.size()>size);}
 public void processDue(){for(UUID id:store.due(now(),100))try{tx.executeWithoutResult(s->{roles.lock();var e=events.lock(id);if(e!=null)reconcile(e,now(),true,"SCHEDULER");});}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(LifecycleService.class).error("Race lifecycle reconciliation failed: {}",failure.getClass().getSimpleName());}}
}
