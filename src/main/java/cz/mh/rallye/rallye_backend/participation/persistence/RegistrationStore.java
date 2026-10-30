package cz.mh.rallye.rallye_backend.participation.persistence;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RegistrationStore {
 public record Event(UUID id,String name,String description,String status,Instant scheduledStartAt,Instant startedAt,Instant endAt,long version,Instant from,Instant to,String override){}
 public record Row(UUID id,UUID event,UUID crew,String name,String status,long version,Instant registeredAt,Instant updatedAt,String members,int children,Instant startedAt,UUID historicalCaptain,UUID captain){@Override public String toString(){return "Registration["+id+"]";}}
 public record Candidate(UUID crew,long version,String name,UUID captain,UUID registration,UUID source,String members,Integer children){@Override public String toString(){return "RegistrationCandidate["+crew+"]";}}
 private final JdbcTemplate jdbc;
 public RegistrationStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
 private static Instant instant(ResultSet r,String field)throws SQLException{var t=r.getTimestamp(field);return t==null?null:t.toInstant();}
 private static Timestamp ts(Instant value){return value==null?null:Timestamp.from(value);}
 public Event event(UUID id){return event(id,true);}
 public Event event(UUID id,boolean lock){var rows=jdbc.query("SELECT * FROM race_event WHERE id=?"+(lock?" FOR UPDATE":""),(r,n)->new Event(r.getObject("id",UUID.class),r.getString("name"),r.getString("description"),r.getString("status"),instant(r,"scheduled_start_at"),instant(r,"started_at"),instant(r,"end_at"),r.getLong("version"),instant(r,"registration_from"),instant(r,"registration_to"),r.getString("registration_override")),id);return rows.isEmpty()?null:rows.getFirst();}
 public void settings(UUID id,Instant from,Instant to,String override,Instant now){jdbc.update("UPDATE race_event SET registration_from=?,registration_to=?,registration_override=?,version=version+1,updated_at=? WHERE id=?",ts(from),ts(to),override,ts(now),id);}
 private static Row row(ResultSet r,int n)throws SQLException{return new Row(r.getObject("id",UUID.class),r.getObject("race_event_id",UUID.class),r.getObject("crew_id",UUID.class),r.getString("crew_name_at_registration"),r.getString("status"),r.getLong("version"),instant(r,"registered_at"),instant(r,"updated_at"),r.getString("members_text"),r.getInt("children_under_15"),instant(r,"individual_started_at"),r.getObject("historical_captain_account_id",UUID.class),r.getObject("captain_account_id",UUID.class));}
 public UUID crew(UUID event,UUID id){var rows=jdbc.query("SELECT crew_id FROM crew_registration WHERE race_event_id=? AND id=?",(r,n)->r.getObject(1,UUID.class),event,id);return rows.isEmpty()?null:rows.getFirst();}
 public Row lock(UUID event,UUID id){return row(event,id,true);}
 public Row row(UUID event,UUID id,boolean lock){var rows=jdbc.query("SELECT r.*,c.captain_account_id FROM crew_registration r JOIN crew c ON c.id=r.crew_id WHERE r.race_event_id=? AND r.id=?"+(lock?" FOR UPDATE OF r":""),RegistrationStore::row,event,id);return rows.isEmpty()?null:rows.getFirst();}
 public boolean exists(UUID event,UUID crew){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM crew_registration WHERE race_event_id=? AND crew_id=?)",Boolean.class,event,crew));}
 public void insert(UUID id,UUID event,UUID crew,String name,String members,int children,Instant at){jdbc.update("INSERT INTO crew_registration(id,race_event_id,crew_id,crew_name_at_registration,members_text,children_under_15,registered_at,updated_at) VALUES (?,?,?,?,?,?,?,?)",id,event,crew,name,members,children,ts(at),ts(at));}
 public void members(UUID id,String members,int children,Instant now){jdbc.update("UPDATE crew_registration SET members_text=?,children_under_15=?,version=version+1,updated_at=? WHERE id=?",members,children,ts(now),id);}
 public List<Row> list(UUID event,UUID actor,boolean all,int page,int size){return jdbc.query("SELECT r.*,c.captain_account_id FROM crew_registration r JOIN crew c ON c.id=r.crew_id WHERE r.race_event_id=? AND (? OR c.captain_account_id=?) ORDER BY r.registered_at DESC,r.id DESC LIMIT ? OFFSET ?",RegistrationStore::row,event,all,actor,size+1,(long)page*size);}
 /** LATERAL prefill lookup uses an indexed crew history in one query, never one query per crew. */
 public List<Candidate> candidates(UUID event,UUID actor,int page,int size){return jdbc.query("""
  SELECT c.id,c.version,c.name,c.captain_account_id,r.id AS registration_id,
         previous.id AS source_id,previous.members_text,previous.children_under_15
  FROM crew c LEFT JOIN crew_registration r ON r.crew_id=c.id AND r.race_event_id=?
  LEFT JOIN LATERAL (SELECT id,members_text,children_under_15 FROM crew_registration
    WHERE crew_id=c.id ORDER BY registered_at DESC,id DESC LIMIT 1) previous ON true
  WHERE c.captain_account_id=? ORDER BY lower(c.name),c.id LIMIT ? OFFSET ?
  """,(r,n)->new Candidate(r.getObject("id",UUID.class),r.getLong("version"),r.getString("name"),r.getObject("captain_account_id",UUID.class),r.getObject("registration_id",UUID.class),r.getObject("source_id",UUID.class),r.getString("members_text"),r.getObject("children_under_15",Integer.class)),event,actor,size+1,(long)page*size);}
 public long activeCount(UUID actor,Instant decisionAt){return jdbc.queryForObject("""
  WITH decision AS (SELECT CAST(? AS timestamptz) AS at)
  SELECT count(*)::bigint FROM crew_registration r JOIN crew c ON c.id=r.crew_id
  JOIN race_event e ON e.id=r.race_event_id CROSS JOIN decision d
  WHERE c.captain_account_id=? AND r.status='REGISTERED' AND e.status IN ('PLANNED','RUNNING')
    AND (e.end_at IS NULL OR e.end_at>d.at)
  """,Long.class,ts(decisionAt),actor);}
 public record OwnRegistration(UUID id,UUID raceEventId,String raceEventName,UUID crewId,String crewName,Instant scheduledStartAt,Instant endAt,String persistedStatus,long version){}
 /** One bounded joined query; ownership and ordering are applied before pagination. */
 public List<OwnRegistration> own(UUID actor,Instant at,int page,int size){return jdbc.query("""
  WITH decision AS (SELECT CAST(? AS timestamptz) AS at), owned AS (
   SELECT r.id,r.race_event_id,e.name AS event_name,r.crew_id,c.name AS crew_name,
          e.scheduled_start_at,e.end_at,e.status,r.version,
          CASE WHEN e.status='ENDED' OR e.end_at<=d.at THEN 'ENDED'
               WHEN e.status='PLANNED' AND e.scheduled_start_at<=d.at THEN 'RUNNING'
               ELSE e.status END AS effective_status
   FROM crew_registration r JOIN crew c ON c.id=r.crew_id
   JOIN race_event e ON e.id=r.race_event_id CROSS JOIN decision d
   WHERE c.captain_account_id=?
  ) SELECT * FROM owned
  ORDER BY CASE effective_status WHEN 'PLANNED' THEN 0 WHEN 'RUNNING' THEN 1 ELSE 2 END,
    CASE WHEN effective_status='PLANNED' THEN scheduled_start_at END ASC NULLS LAST,
    CASE WHEN effective_status='ENDED' THEN end_at END DESC NULLS LAST,
    id ASC
  LIMIT ? OFFSET ?
  """,(r,n)->new OwnRegistration(r.getObject("id",UUID.class),r.getObject("race_event_id",UUID.class),r.getString("event_name"),r.getObject("crew_id",UUID.class),r.getString("crew_name"),instant(r,"scheduled_start_at"),instant(r,"end_at"),r.getString("status"),r.getLong("version")),ts(at),actor,size+1,(long)page*size);}

}
