package cz.mh.rallye.rallye_backend.races.persistence;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import cz.mh.rallye.rallye_backend.races.domain.EventLifecycle.Metadata;

@Repository
public class LifecycleStore {
 public record Entry(UUID id,UUID raceEventId,long sequence,String kind,String type,String fromStatus,String toStatus,Instant effectiveAt,Instant processedAt,String trigger,String cause,String actorKind,UUID actorAccountId,long versionBefore,long versionAfter,Instant scheduledStartAtBefore,Instant scheduledStartAtAfter,Instant endAtBefore,Instant endAtAfter,UUID periodId,Long closureRevision,Long invalidatedClosureRevision){}
 public record Period(UUID id,UUID raceEventId,long number,Instant startedAt,Instant endedAt,String endBoundary,UUID startTransitionId,UUID endTransitionId,long startSequence,Long endSequence){}
 private final JdbcTemplate jdbc;
 public LifecycleStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
 static Timestamp ts(Instant i){return i==null?null:Timestamp.from(i);}
 static Instant at(ResultSet r,String n)throws SQLException{var t=r.getTimestamp(n);return t==null?null:t.toInstant();}
 public Metadata metadata(UUID id){try{return jdbc.queryForObject("SELECT * FROM race_event WHERE id=?",(r,n)->new Metadata(at(r,"started_at"),at(r,"last_ended_at"),r.getObject("active_period_id",UUID.class),r.getLong("period_number"),r.getLong("closure_revision"),r.getObject("current_closure_revision",Long.class),at(r,"results_finalized_at")),id);}catch(org.springframework.dao.DataAccessException failure){throw new cz.mh.rallye.rallye_backend.identity.application.AuthFailure(503,"RACE_EVENT_RESULTS_CHECK_UNAVAILABLE",failure);}}
 private static Entry entry(ResultSet r,int n)throws SQLException{return new Entry(r.getObject("id",UUID.class),r.getObject("race_event_id",UUID.class),r.getLong("sequence"),r.getString("kind"),r.getString("type"),r.getString("from_status"),r.getString("to_status"),at(r,"effective_at"),at(r,"processed_at"),r.getString("trigger"),r.getString("cause"),r.getString("actor_kind"),r.getObject("actor_account_id",UUID.class),r.getLong("version_before"),r.getLong("version_after"),at(r,"scheduled_start_at_before"),at(r,"scheduled_start_at_after"),at(r,"end_at_before"),at(r,"end_at_after"),r.getObject("period_id",UUID.class),r.getObject("closure_revision",Long.class),r.getObject("invalidated_closure_revision",Long.class));}
 public Entry append(UUID id,EventStore.Event e,String type,String next,Instant effective,Instant processed,String trigger,String cause,UUID actor,Instant startAfter,Instant endAfter,UUID period,Long closure,Long invalidated){
  long sequence=jdbc.queryForObject("UPDATE race_event SET lifecycle_order=lifecycle_order+1 WHERE id=? RETURNING lifecycle_order",Long.class,e.id());
  return jdbc.queryForObject("""
   INSERT INTO race_event_lifecycle_history(id,race_event_id,sequence,kind,type,from_status,to_status,effective_at,processed_at,"trigger",cause,actor_kind,actor_account_id,version_before,version_after,scheduled_start_at_before,scheduled_start_at_after,end_at_before,end_at_after,period_id,closure_revision,invalidated_closure_revision)
   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING *
   """,LifecycleStore::entry,id,e.id(),sequence,type.equals("TIMES_UPDATED")?"CONFIGURATION":"TRANSITION",type,e.status(),next,ts(effective),ts(processed),trigger,cause,actor==null?"SYSTEM":"USER",actor,e.version(),e.version()+1,ts(e.scheduledStartAt()),ts(startAfter),ts(e.endAt()),ts(endAfter),period,closure,invalidated);
 }
 public void start(EventStore.Event e,Metadata m,Entry h,Instant end){
  jdbc.update("INSERT INTO race_event_running_period(id,race_event_id,number,started_at,start_transition_id,start_sequence) VALUES (?,?,?,?,?,?)",h.periodId(),e.id(),m.periodNumber()+1,ts(h.effectiveAt()),h.id(),h.sequence());
  jdbc.update("UPDATE race_event SET status='RUNNING',started_at=coalesce(started_at,?),active_period_id=?,period_number=period_number+1,current_closure_revision=NULL,end_at=?,version=version+1,updated_at=? WHERE id=?",ts(h.effectiveAt()),h.periodId(),ts(end),ts(h.processedAt()),e.id());
 }
 public void end(EventStore.Event e,Metadata m,Entry h){
  if(m.activePeriodId()!=null)jdbc.update("UPDATE race_event_running_period SET ended_at=?,end_boundary=?,end_transition_id=?,end_sequence=? WHERE id=? AND ended_at IS NULL",ts(h.effectiveAt()),h.cause().equals("END_AT")?"INCLUSIVE":"SERVER_ORDER",h.id(),h.sequence(),m.activePeriodId());
  jdbc.update("UPDATE race_event SET status='ENDED',last_ended_at=?,active_period_id=NULL,closure_revision=closure_revision+1,current_closure_revision=closure_revision+1,version=version+1,updated_at=? WHERE id=?",ts(h.effectiveAt()),ts(h.processedAt()),e.id());
 }
 public List<UUID> due(Instant at,int limit){return jdbc.query("SELECT id FROM race_event WHERE status<>'ENDED' AND ((end_at IS NOT NULL AND end_at<=?) OR (status='PLANNED' AND scheduled_start_at IS NOT NULL AND scheduled_start_at<=?)) ORDER BY id LIMIT ?",(r,n)->r.getObject(1,UUID.class),ts(at),ts(at),limit);}
 public List<Entry> history(UUID id,int page,int size){return jdbc.query("SELECT * FROM race_event_lifecycle_history WHERE race_event_id=? ORDER BY sequence DESC,id DESC LIMIT ? OFFSET ?",LifecycleStore::entry,id,size+1,(long)page*size);}
 public List<Period> periods(UUID id,int page,int size){return jdbc.query("SELECT * FROM race_event_running_period WHERE race_event_id=? ORDER BY number DESC,id DESC LIMIT ? OFFSET ?",(r,n)->new Period(r.getObject("id",UUID.class),r.getObject("race_event_id",UUID.class),r.getLong("number"),at(r,"started_at"),at(r,"ended_at"),r.getString("end_boundary"),r.getObject("start_transition_id",UUID.class),r.getObject("end_transition_id",UUID.class),r.getLong("start_sequence"),r.getObject("end_sequence",Long.class)),id,size+1,(long)page*size);}
}
