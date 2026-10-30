package cz.mh.rallye.rallye_backend.competition.domain;

import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration;

/** Deterministic race rules. No clock, persistence, transport or simulated-input dependencies. */
public final class RaceEvaluator {
 public static final int VERSION=1;
 public static class RuleViolation extends RuntimeException {private final String code;public RuleViolation(String code){super(code);this.code=code;}public String code(){return code;}}
 public record Stage(UUID id,UUID sourceStageId,int sequenceNo,String name,Long expectedDurationMs,Long maxPenaltyPoints,String instructionsMarkdown,StageConfiguration configuration){@Override public String toString(){return "Stage["+id+"]";}}
 public record Definition(long skipPenaltyPoints,long defaultWrongCodePenaltyPoints,List<Stage> stages){public Definition{stages=List.copyOf(stages);}@Override public String toString(){return "Definition[redacted]";}}
 public record Position(double latitude,double longitude,double accuracyMeters,boolean mockLocation){public Position{GpsMath.validate(latitude,longitude);if(!Double.isFinite(accuracyMeters)||accuracyMeters<0)throw new RuleViolation("INVALID_COORDINATES");}}
 public record Progress(UUID stageId,String status,Instant activatedAt,Instant completedAt,Instant endedAt,boolean codeSatisfied,boolean gpsSatisfied){}
 public record Penalty(UUID id,UUID actionId,UUID stageId,String cause,long points,boolean effective,UUID supersededBy,Instant virtualAt){}
 public record GpsResult(Double latitude,Double longitude,Double accuracyMeters,Double distanceMeters,String outcome){}
 public record Action(UUID actionId,long sequence,String type,UUID stageId,String outcome,long pointsDelta,Instant virtualAt,GpsResult gps){}
 public record State(Instant virtualStartAt,Instant virtualNow,Instant timeFloor,String status,Position position,List<Progress> stages,List<Penalty> penalties,Action lastAction){public State{stages=List.copyOf(stages);penalties=List.copyOf(penalties);}}
 public State start(Definition def,Instant at){if(def.stages().isEmpty())throw new RuleViolation("SIMULATION_TEMPLATE_NOT_READY");var progress=new ArrayList<Progress>();for(int i=0;i<def.stages().size();i++)progress.add(new Progress(def.stages().get(i).id(),i==0?"ACTIVE":"PENDING",i==0?at:null,null,null,false,false));return new State(at,at,at,"RUNNING",null,progress,List.of(),null);}
 public static Progress current(State s){return s.stages().stream().filter(p->p.status().equals("ACTIVE")).findFirst().orElse(null);}
 public static long total(State s){try{long n=0;for(var p:s.penalties())if(p.effective())n=Math.addExact(n,p.points());return n;}catch(ArithmeticException e){throw new RuleViolation("SIMULATION_POINTS_OVERFLOW");}}
 public static long stagePoints(State s,UUID stage){try{long n=0;for(var p:s.penalties())if(p.effective()&&p.stageId().equals(stage))n=Math.addExact(n,p.points());return n;}catch(ArithmeticException e){throw new RuleViolation("SIMULATION_POINTS_OVERFLOW");}}
 public static long add(long a,long b){try{return Math.addExact(a,b);}catch(ArithmeticException e){throw new RuleViolation("SIMULATION_POINTS_OVERFLOW");}}
 public static long delta(long a,long b){try{return Math.subtractExact(a,b);}catch(ArithmeticException e){throw new RuleViolation("SIMULATION_POINTS_OVERFLOW");}}
 private static long rate(long duration,long interval,long points){try{return Math.multiplyExact(duration/interval+(duration%interval==0?0:1),points);}catch(ArithmeticException e){throw new RuleViolation("SIMULATION_POINTS_OVERFLOW");}}
 public static long duration(Instant start,Instant end){try{long n=Duration.between(start,end).toMillis();if(n<0)throw new RuleViolation("SIMULATION_CLOCK_BEFORE_STATE");return n;}catch(ArithmeticException e){throw new RuleViolation("SIMULATION_TIME_OUT_OF_RANGE");}}
 private static UUID penaltyId(UUID action,String cause){return UUID.nameUUIDFromBytes((action+":"+cause).getBytes(StandardCharsets.UTF_8));}
 private static void penalty(List<Penalty> ps,UUID action,UUID stage,String cause,long points,Instant at){ps.add(new Penalty(penaltyId(action,cause),action,stage,cause,points,true,null,at));}
 public State evaluate(Definition def,State old,String type,UUID stageId,String submittedCode,UUID action,long sequence){return apply(def,old,type,stageId,submittedCode,action,sequence,null);}
 /** Replays previously verified facts, never requires storing a submitted CODE. */
 public State replay(Definition def,State old,Action fact){return apply(def,old,fact.type(),fact.stageId(),null,fact.actionId(),fact.sequence(),fact);}
 private State apply(Definition def,State old,String type,UUID stageId,String submittedCode,UUID action,long sequence,Action proof){
  if(type.equals("COMPLETE")){var found=old.stages().stream().filter(p->p.stageId().equals(stageId)).findFirst().orElseThrow(()->new RuleViolation("STAGE_NOT_CURRENT"));if(found.status().equals("COMPLETED"))return old;}
  if(!old.status().equals("RUNNING"))throw new RuleViolation("SIMULATION_NOT_RUNNING");
  var current=current(old);if(current==null||!current.stageId().equals(stageId))throw new RuleViolation("STAGE_NOT_CURRENT");
  if(old.virtualNow().isBefore(old.timeFloor()))throw new RuleViolation("SIMULATION_CLOCK_BEFORE_STATE");
  int index=old.stages().indexOf(current);var stage=def.stages().get(index);var cfg=stage.configuration();var ps=new ArrayList<>(old.penalties());var progress=new ArrayList<>(old.stages());boolean code=current.codeSatisfied(),gps=current.gpsSatisfied();String outcome;GpsResult gpsResult=null;boolean skipped=false;
  switch(type){
   case "CODE"->{if(!cfg.type().equals("STANDARD")||!cfg.code().enabled())throw new RuleViolation("CHECK_NOT_ENABLED");if(code)outcome="CHECK_ALREADY_SATISFIED";else{if(proof!=null){if(!Set.of("CODE_ACCEPTED","CODE_REJECTED").contains(proof.outcome()))throw new IllegalStateException("Invalid replay fact");code=proof.outcome().equals("CODE_ACCEPTED");}else{String normalized;try{normalized=StageConfiguration.normalizeCode(submittedCode);}catch(RuntimeException e){throw new RuleViolation("INVALID_CODE_INPUT");}code=cfg.code().matches(normalized);}outcome=code?"CODE_ACCEPTED":"CODE_REJECTED";if(!code)penalty(ps,action,stageId,"WRONG_CODE",cfg.code().wrongAttemptPenaltyPoints()==null?def.defaultWrongCodePenaltyPoints():cfg.code().wrongAttemptPenaltyPoints(),old.virtualNow());}}
   case "GPS"->{if(!cfg.type().equals("STANDARD")||!cfg.gps().enabled())throw new RuleViolation("CHECK_NOT_ENABLED");if(gps)outcome="CHECK_ALREADY_SATISFIED";else{var pos=old.position();Double distance=pos==null?null:GpsMath.distance(pos.latitude(),pos.longitude(),cfg.gps().latitude(),cfg.gps().longitude());if(pos==null)outcome="GPS_UNAVAILABLE";else if(pos.mockLocation()){outcome="GPS_MOCK_REJECTED";penalty(ps,action,stageId,"GPS_SPOOFING",cfg.gps().spoofingPenaltyPoints(),old.virtualNow());}else if(pos.accuracyMeters()>cfg.gps().radiusMeters())outcome="GPS_INACCURATE";else if(distance<=cfg.gps().radiusMeters()){gps=true;outcome="GPS_ACCEPTED";}else{outcome="GPS_OUTSIDE";penalty(ps,action,stageId,"GPS_OUTSIDE",cfg.gps().outsidePenaltyPoints(),old.virtualNow());}gpsResult=new GpsResult(pos==null?null:pos.latitude(),pos==null?null:pos.longitude(),pos==null?null:pos.accuracyMeters(),distance,outcome);}}
   case "SKIP"->{skipped=true;outcome="STAGE_SKIPPED";UUID replacement=penaltyId(action,"SKIP_REPLACEMENT");for(int i=0;i<ps.size();i++){var p=ps.get(i);if(p.effective()&&p.stageId().equals(stageId))ps.set(i,new Penalty(p.id(),p.actionId(),p.stageId(),p.cause(),p.points(),false,replacement,p.virtualAt()));}penalty(ps,action,stageId,"SKIP_REPLACEMENT",add(stage.maxPenaltyPoints()==null?0:stage.maxPenaltyPoints(),def.skipPenaltyPoints()),old.virtualNow());}
   case "RADAR"->{
    if(!cfg.type().equals("RADAR"))throw new RuleViolation("CHECK_NOT_ENABLED");
    var pos=old.position();Double distance=pos==null?null:GpsMath.distance(pos.latitude(),pos.longitude(),cfg.gps().latitude(),cfg.gps().longitude());
    if(pos==null)outcome="GPS_UNAVAILABLE";else if(pos.mockLocation())outcome="GPS_MOCK_REJECTED";
    else if(pos.accuracyMeters()>cfg.gps().radiusMeters())outcome="GPS_INACCURATE";
    else if(distance<=cfg.gps().radiusMeters()){gps=true;outcome="RADAR_ACCEPTED";}
    else{outcome="RADAR_OUTSIDE";penalty(ps,action,stageId,"RADAR_MEASUREMENT",cfg.radarPenaltyPoints(),old.virtualNow());}
    Double measured=Set.of("RADAR_ACCEPTED","RADAR_OUTSIDE").contains(outcome)?(double)Math.round(distance):null;
    gpsResult=new GpsResult(pos==null?null:pos.latitude(),pos==null?null:pos.longitude(),pos==null?null:pos.accuracyMeters(),measured,outcome);
   }
   case "COMPLETE"->{if(!cfg.type().equals("INFORMATION"))throw new RuleViolation("STAGE_REQUIREMENTS_NOT_MET");outcome="INFORMATION_COMPLETED";}
   default->throw new IllegalArgumentException("Unsupported domain command");
  }
  boolean completed=!skipped&&(cfg.type().equals("INFORMATION")?type.equals("COMPLETE"):(!cfg.code().enabled()||code)&&(!cfg.gps().enabled()||gps));
  if(completed&&!cfg.type().equals("INFORMATION")){long elapsed=duration(current.activatedAt(),old.virtualNow());if(cfg.timePenalty().enabled())penalty(ps,action,stageId,"TIME",rate(elapsed,cfg.timePenalty().intervalMs(),cfg.timePenalty().pointsPerInterval()),old.virtualNow());if(cfg.delayed().enabled())penalty(ps,action,stageId,"EARLY",rate(Math.max(0,cfg.delayed().minimumDurationMs()-elapsed),1000,cfg.delayed().pointsPerSecond()),old.virtualNow());}
  progress.set(index,new Progress(stageId,skipped?"SKIPPED":completed?"COMPLETED":"ACTIVE",current.activatedAt(),completed?old.virtualNow():null,completed||skipped?old.virtualNow():null,code,gps));
  String status="RUNNING";if(completed||skipped){if(index+1==progress.size())status="COMPLETED";else{var next=progress.get(index+1);progress.set(index+1,new Progress(next.stageId(),"ACTIVE",old.virtualNow(),null,null,false,false));}}
  var result=new State(old.virtualStartAt(),old.virtualNow(),outcome.equals("CHECK_ALREADY_SATISFIED")?old.timeFloor():old.virtualNow(),status,old.position(),progress,ps,null);
  return new State(result.virtualStartAt(),result.virtualNow(),result.timeFloor(),status,result.position(),progress,ps,new Action(action,sequence,type,stageId,outcome,delta(total(result),total(old)),old.virtualNow(),gpsResult));
 }
 /** New simulations wait for the organizer; legacy start() deliberately retains its semantics. */
 public State waiting(Definition def,Instant at){var s=start(def,at);var ps=new ArrayList<>(s.stages());var p=ps.getFirst();ps.set(0,new Progress(p.stageId(),"ACTIVE",null,null,null,false,false));return new State(at,at,at,"RUNNING",null,ps,List.of(),null);}
 public State startRace(State s,UUID action,long sequence){
  open(s);if(s.virtualNow().isBefore(s.timeFloor()))throw new RuleViolation("SIMULATION_CLOCK_BEFORE_STATE");
  var ps=new ArrayList<>(s.stages());var p=current(s);if(p==null)throw new RuleViolation("SIMULATION_NOT_RUNNING");
  ps.set(ps.indexOf(p),new Progress(p.stageId(),p.status(),s.virtualNow(),null,null,p.codeSatisfied(),p.gpsSatisfied()));
  return new State(s.virtualStartAt(),s.virtualNow(),s.virtualNow(),s.status(),s.position(),ps,s.penalties(),new Action(action,sequence,"RACE_START",null,"RACE_STARTED",0,s.virtualNow(),null));
 }
 public State endRace(Definition def,State s,UUID action,long sequence){
  if(s.virtualNow().isBefore(s.timeFloor()))throw new RuleViolation("SIMULATION_CLOCK_BEFORE_STATE");
  var ps=new ArrayList<>(s.penalties());var progress=new ArrayList<>(s.stages());
  for(int i=0;i<progress.size();i++){
   var p=progress.get(i);if(!Set.of("ACTIVE","PENDING").contains(p.status()))continue;
   var stage=def.stages().get(i);UUID replacement=UUID.nameUUIDFromBytes((action+":"+p.stageId()+":UNFINISHED").getBytes(StandardCharsets.UTF_8));
   for(int j=0;j<ps.size();j++){var old=ps.get(j);if(old.effective()&&old.stageId().equals(p.stageId()))ps.set(j,new Penalty(old.id(),old.actionId(),old.stageId(),old.cause(),old.points(),false,replacement,old.virtualAt()));}
   ps.add(new Penalty(replacement,action,p.stageId(),"UNFINISHED",add(stage.maxPenaltyPoints()==null?0:stage.maxPenaltyPoints(),def.skipPenaltyPoints()),true,null,s.virtualNow()));
   progress.set(i,new Progress(p.stageId(),"ENDED",p.activatedAt(),null,s.virtualNow(),p.codeSatisfied(),p.gpsSatisfied()));
  }
  var result=new State(s.virtualStartAt(),s.virtualNow(),s.virtualNow(),s.status().equals("COMPLETED")?"COMPLETED":"CLOSED",s.position(),progress,ps,null);
  return new State(result.virtualStartAt(),result.virtualNow(),result.timeFloor(),result.status(),result.position(),progress,ps,new Action(action,sequence,"RACE_END",null,"RACE_ENDED",delta(total(result),total(s)),s.virtualNow(),null));
 }
 public State shift(State s,long delta,UUID id,long seq){open(s);Instant at;try{at=s.virtualNow().plusMillis(delta);if(at.isBefore(s.virtualStartAt()))throw new RuleViolation("SIMULATION_TIME_OUT_OF_RANGE");duration(s.virtualStartAt(),at);}catch(DateTimeException|ArithmeticException e){throw new RuleViolation("SIMULATION_TIME_OUT_OF_RANGE");}if(at.equals(s.virtualNow()))return s;return control(s,at,s.position(),s.status(),id,seq,"CLOCK_SHIFT","CLOCK_SHIFTED");}
 public State position(State s,Position p,UUID id,long seq){open(s);if(Objects.equals(s.position(),p))return s;return control(s,s.virtualNow(),p,s.status(),id,seq,"POSITION","POSITION_SET");}
 public State close(State s,UUID id,long seq){if(s.status().equals("CLOSED"))return s;return control(s,s.virtualNow(),s.position(),"CLOSED",id,seq,"CLOSE","CLOSED");}
 private static void open(State s){if(s.status().equals("CLOSED"))throw new RuleViolation("SIMULATION_NOT_RUNNING");}
 private static State control(State s,Instant at,Position p,String status,UUID id,long seq,String type,String outcome){return new State(s.virtualStartAt(),at,s.timeFloor(),status,p,s.stages(),s.penalties(),new Action(id,seq,type,null,outcome,0,at,null));}
}
