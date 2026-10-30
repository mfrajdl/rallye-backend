package cz.mh.rallye.rallye_backend.participation.application;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.application.AuthService.Principal;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import cz.mh.rallye.rallye_backend.crews.persistence.CrewStore;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import cz.mh.rallye.rallye_backend.shared.persistence.CommandStore;
import cz.mh.rallye.rallye_backend.participation.domain.RegistrationRules;
import cz.mh.rallye.rallye_backend.participation.persistence.RegistrationStore;
import cz.mh.rallye.rallye_backend.participation.persistence.RegistrationStore.*;

@Service
public class RegistrationService {
 public record EventSummary(UUID id,String name,String description,String status,Instant scheduledStartAt,Instant startedAt,Instant endAt){}
 public record Status(EventSummary event,long version,Instant serverTime,Instant registrationFrom,Instant registrationTo,String override,String effectiveState,String reason,boolean canRegister,boolean canManageSettings){}
 public record Created(UUID id,long version){}
 public record View(UUID id,UUID raceEventId,UUID crewId,String crewNameAtRegistration,String status,long version,Instant registeredAt,Instant updatedAt,String membersText,int childrenUnder15,int memberCount,int participants15Plus,Instant individualStartedAt,UUID historicalCaptainAccountId,boolean compositionLocked,boolean canEditMembers,String editBlockedReason){@Override public String toString(){return "RegistrationView["+id+"]";}}
 public record Defaults(String source,UUID sourceRegistrationId,String membersText,int childrenUnder15){@Override public String toString(){return "RegistrationDefaults[redacted]";}}
 public record Crew(UUID crewId,long crewVersion,String name,UUID captainAccountId,UUID registrationId,boolean canRegister,String blockedReason,Defaults defaults){}
 public record Page<T>(List<T> items,int page,int size,boolean hasMore){}
 private record Actor(UUID id,boolean admin,String name){}
 private final RegistrationStore registrations;private final CrewStore crews;private final AccountStore accounts;private final AdminStore roles;private final CommandStore commands;private final AuditService audit;private final Clock clock;private final TransactionTemplate tx;
 private static final JsonMapper JSON=JsonMapper.builder().build();
 public RegistrationService(RegistrationStore registrations,CrewStore crews,AccountStore accounts,AdminStore roles,CommandStore commands,AuditService audit,Clock clock,PlatformTransactionManager manager){this.registrations=registrations;this.crews=crews;this.accounts=accounts;this.roles=roles;this.commands=commands;this.audit=audit;this.clock=clock;tx=new TransactionTemplate(manager);}
 private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
 private Actor authorize(Principal p){
  if(p==null)throw AuthFailure.credentials();roles.lock();var account=accounts.lockAccount(p.accountId());
  if(account==null||!roles.activeVerified(p.accountId())||!accounts.isActiveSession(p.sessionId(),p.accountId(),now()))throw AuthFailure.credentials();
  return new Actor(p.accountId(),roles.administrator(p.accountId()),account.name());
 }
 private static AuthFailure error(int status,String code){return new AuthFailure(status,code);}
 private Event event(UUID id){var e=registrations.event(id);if(e==null)throw error(404,"RACE_EVENT_NOT_FOUND");return e;}
 private static boolean ended(Event e,Instant at){return RegistrationRules.ended(e.status(),e.endAt(),at);}
 private static Status status(Event e,Actor a,Instant at){var state=RegistrationRules.state(e.status(),e.startedAt(),e.endAt(),e.from(),e.to(),e.override(),at);return new Status(new EventSummary(e.id(),e.name(),e.description(),e.status(),e.scheduledStartAt(),e.startedAt(),e.endAt()),e.version(),at,e.from(),e.to(),e.override(),state.effectiveState(),state.reason(),state.open(),a.admin()&&!ended(e,at));}
 private static void version(long expected,long actual,String code){if(expected<0)throw error(400,"INVALID_REQUEST");if(expected!=actual)throw error(409,code);}
 private static void paging(int page,int size){if(page<0||page>10000||size<1||size>100)throw error(400,"INVALID_REQUEST");}
 public Status status(Principal p,UUID id){return tx.execute(s->{var a=authorize(p);return status(event(id),a,now());});}
 public Status settings(Principal p,UUID id,long version,Instant from,Instant to,String override){
  Instant f=from==null?null:from.truncatedTo(ChronoUnit.MILLIS),t=to==null?null:to.truncatedTo(ChronoUnit.MILLIS);RegistrationRules.settings(f,t,override);
  return tx.execute(s->{var a=authorize(p);if(!a.admin())throw error(403,"ADMIN_REQUIRED");var e=event(id);version(version,e.version(),"RACE_EVENT_VERSION_CONFLICT");Instant at=now();if(ended(e,at))throw error(409,"RACE_EVENT_ENDED");
   if(!Objects.equals(e.from(),f)||!Objects.equals(e.to(),t)||!e.override().equals(override)){
    registrations.settings(id,f,t,override,at);audit.registration(a.id(),AuditService.Event.RACE_REGISTRATION_SETTINGS_UPDATED,id,id,e.version()+1);e=event(id);
   }return status(e,a,at);
  });
 }
 public Page<Crew> crews(Principal p,UUID event,int page,int size){paging(page,size);return tx.execute(s->{var a=authorize(p);var e=event(event);boolean open=status(e,a,now()).canRegister();var rows=registrations.candidates(event,a.id(),page,size);
  return new Page<>(rows.stream().limit(size).map(c->{String reason=c.registration()!=null?"ALREADY_REGISTERED":!open?"REGISTRATION_CLOSED":null;var defaults=c.source()==null?new Defaults("CAPTAIN_PROFILE",null,a.name(),0):new Defaults("LAST_REGISTRATION",c.source(),c.members(),c.children());return new Crew(c.crew(),c.version(),c.name(),c.captain(),c.registration(),reason==null,reason,defaults);}).toList(),page,size,rows.size()>size);
 });}
 public Created create(Principal p,UUID event,UUID key,UUID crew,String text,int children){
  if(key==null||crew==null)throw error(400,"INVALID_REQUEST");var members=RegistrationRules.members(text,children);
  return tx.execute(s->{var a=authorize(p);var c=crews.find(crew);if(c==null||!a.id().equals(c.captainAccountId()))throw error(404,"CREW_NOT_FOUND");var e=event(event);Instant at=now();
   String request=JSON.writeValueAsString(List.of(event,crew,digest(members.text()),children));String old=commands.replay(a.id(),key,"CREW_REGISTRATION_CREATE",request,at);
   if(old!=null)return JSON.readValue(old,Created.class);
   if(!status(e,a,at).canRegister())throw error(409,"REGISTRATION_CLOSED");if(registrations.exists(event,crew))throw error(409,"CREW_ALREADY_REGISTERED");
   UUID id=UUID.randomUUID();registrations.insert(id,event,crew,c.name(),members.text(),children,at);audit.registration(a.id(),AuditService.Event.REGISTRATION_CREATED,id,event,0);
   var result=new Created(id,0);commands.save(a.id(),key,id,"CREW_REGISTRATION_CREATE",request,JSON.writeValueAsString(result),at);return result;
  });
 }
 private static String digest(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
 private static View view(Row r,Event e,Actor a,Instant at){boolean end=ended(e,at),started=r.startedAt()!=null,owner=a.id().equals(r.captain());int count=RegistrationRules.members(r.members(),r.children()).count();String reason=end?"EVENT_ENDED":started?"INDIVIDUAL_START_REACHED":!owner?"NOT_CAPTAIN":null;return new View(r.id(),r.event(),r.crew(),r.name(),r.status(),r.version(),r.registeredAt(),r.updatedAt(),r.members(),r.children(),count,count-r.children(),r.startedAt(),r.historicalCaptain(),end||started,reason==null,reason);}
 public Page<View> list(Principal p,UUID event,String scope,int page,int size){paging(page,size);if(scope==null||!Set.of("own","all").contains(scope))throw error(400,"INVALID_REQUEST");return tx.execute(s->{var a=authorize(p);boolean all=scope.equals("all");if(all&&!a.admin())throw error(403,"ADMIN_REQUIRED");var e=event(event);Instant at=now();var rows=registrations.list(event,a.id(),all,page,size);return new Page<>(rows.stream().limit(size).map(r->view(r,e,a,at)).toList(),page,size,rows.size()>size);});}
 private void accessible(UUID event,UUID id,Actor a,boolean edit){
  UUID crew=registrations.crew(event,id);if(crew==null)throw error(404,"REGISTRATION_NOT_FOUND");var c=crews.find(crew);
  if(c==null||!a.id().equals(c.captainAccountId())&&(edit||!a.admin()))throw error(404,"REGISTRATION_NOT_FOUND");
  // Crew lock is acquired before the parent/event and registration locks.
 }
 public View detail(Principal p,UUID event,UUID id){return tx.execute(s->{var a=authorize(p);accessible(event,id,a,false);var e=event(event);var r=registrations.lock(event,id);if(r==null)throw error(404,"REGISTRATION_NOT_FOUND");return view(r,e,a,now());});}
 public View members(Principal p,UUID event,UUID id,long version,String text,int children){var members=RegistrationRules.members(text,children);return tx.execute(s->{var a=authorize(p);accessible(event,id,a,true);var e=event(event);var r=registrations.lock(event,id);if(r==null)throw error(404,"REGISTRATION_NOT_FOUND");version(version,r.version(),"REGISTRATION_VERSION_CONFLICT");Instant at=now();if(ended(e,at))throw error(409,"RACE_EVENT_ENDED");if(r.startedAt()!=null)throw error(409,"REGISTRATION_COMPOSITION_LOCKED");
  if(!r.members().equals(members.text())||r.children()!=children){registrations.members(id,members.text(),children,at);audit.registration(a.id(),AuditService.Event.REGISTRATION_MEMBERS_UPDATED,id,event,r.version()+1);r=registrations.lock(event,id);}
  return view(r,e,a,at);
 });}
}
