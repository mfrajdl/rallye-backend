package cz.mh.rallye.rallye_backend.participation.application;
import java.util.UUID;
import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.*;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import cz.mh.rallye.rallye_backend.races.persistence.EventStore;
import cz.mh.rallye.rallye_backend.participation.persistence.CrewStartStore;
/** Internal authorization only; caller's transaction retains event/registration/run/device locks through its effect. */
@Service
public class RaceDeviceAccess {
 public record Authorized(UUID registrationId,UUID runId,UUID deviceId,long deviceGeneration,long activationGeneration,long runVersion){}
 private final CrewStartStore store;private final EventStore events;private final CrewStartSecrets secrets;
 public RaceDeviceAccess(CrewStartStore store,EventStore events,CrewStartSecrets secrets){this.store=store;this.events=events;this.secrets=secrets;}
 private static AuthFailure invalid(){return new AuthFailure(401,"RACE_DEVICE_INVALID");}
 @Transactional(propagation=Propagation.MANDATORY)
 public Authorized require(String token,UUID registration,UUID run,long activation,long generation){
  return authorize(token,registration,run,activation,generation);
 }
 /** Read the current boundary under the same locks; readers do not supply potentially stale generations. */
 @Transactional(propagation=Propagation.MANDATORY)
 public Authorized current(String token,UUID registration){return authorize(token,registration,null,null,null);}
 private Authorized authorize(String token,UUID registration,UUID run,Long activation,Long generation){
  CrewStartSecrets.DeviceHash hash;try{hash=secrets.deviceHash(token);}catch(AuthFailure failure){throw invalid();}
  var hint=store.activeDevice(hash);if(hint==null||!hint.registrationId().equals(registration))throw invalid();var first=store.registration(registration,false);if(first==null||events.lock(first.eventId())==null)throw invalid();
  var r=store.registration(registration,true);if(r==null)throw invalid();var current=r.currentRunId()==null?null:store.run(registration,r.currentRunId(),true);var d=store.device(registration,hint.id(),true);
  if(d==null||!d.state().equals("ACTIVE")||!d.hash().equals(hash)||d.generation()!=r.deviceGeneration()||generation!=null&&d.generation()!=generation)throw invalid();
  if(current==null||run!=null&&!current.id().equals(run))throw new AuthFailure(409,"RACE_RUN_NOT_CURRENT");if(activation!=null&&current.activationGeneration()!=activation)throw new AuthFailure(409,"RACE_ACTIVATION_STALE");return new Authorized(registration,current.id(),d.id(),d.generation(),current.activationGeneration(),current.version());
 }
}
