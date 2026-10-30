package cz.mh.rallye.rallye_backend.races.domain;

import java.time.Instant;
import java.util.UUID;

/** A pure projection: observing a deadline never writes history or acquires a row lock. */
public final class EventLifecycle {
 private EventLifecycle(){}
 public static String effective(String persisted,Instant start,Instant end,Instant now){
  if(persisted.equals("ENDED")||end!=null&&!now.isBefore(end))return "ENDED";
  if(persisted.equals("PLANNED")&&start!=null&&!now.isBefore(start))return "RUNNING";
  return persisted;
 }
 public record Metadata(Instant startedAt,Instant lastEndedAt,UUID activePeriodId,long periodNumber,long closureRevision,Long currentClosureRevision,Instant resultsFinalizedAt){}
 public record View(Instant serverTime,String persistedStatus,boolean transitionsPending,Instant startedAt,Instant effectiveStartedAt,Instant lastEndedAt,UUID activePeriodId,long periodNumber,long closureRevision,Long currentClosureRevision,Instant resultsFinalizedAt,boolean canStart,boolean canEnd,boolean canResume,boolean canEditScheduledStartAt,boolean canEditEndAt,boolean canEditBasicDetails,String resumeBlockedReason){}
 public static View view(String status,Instant start,Instant end,Metadata m,Instant now){
  String effective=effective(status,start,end,now);boolean pending=!effective.equals(status),ending=pending&&effective.equals("ENDED"),finalized=m.resultsFinalizedAt()!=null;
  boolean planned=status.equals("PLANNED"),running=effective.equals("RUNNING"),ended=status.equals("ENDED");
  return new View(now,status,pending,m.startedAt(),m.startedAt()!=null?m.startedAt():pending&&running?start:null,m.lastEndedAt(),running?m.activePeriodId():null,m.periodNumber(),m.closureRevision(),ended?m.currentClosureRevision():null,m.resultsFinalizedAt(),!finalized&&planned&&!ending,!finalized&&!ended&&!ending,!finalized&&ended,!finalized&&planned&&!pending,!finalized&&!ended&&!pending,!finalized&&planned&&!pending,finalized?"RESULTS_FINALIZED":ending?"PENDING_TRANSITION":ended?null:"NOT_ENDED");
 }
}
