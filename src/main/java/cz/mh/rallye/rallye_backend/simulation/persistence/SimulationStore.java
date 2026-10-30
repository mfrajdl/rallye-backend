package cz.mh.rallye.rallye_backend.simulation.persistence;
import java.time.*;import java.sql.*;import java.util.*;import org.springframework.stereotype.Repository;import org.springframework.jdbc.core.JdbcTemplate;import tools.jackson.databind.json.JsonMapper;
import cz.mh.rallye.rallye_backend.competition.domain.RaceEvaluator.*;import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore.Image;import cz.mh.rallye.rallye_backend.templates.application.TemplateFileContent;
@Repository
public class SimulationStore {
 public static final JsonMapper JSON=JsonMapper.builder().build();
 public record Race(String status,Instant startedAt,Instant endedAt){
  public static Race waiting(){return new Race("NOT_STARTED",null,null);}
  public static Race legacy(State s){return new Race("RUNNING",s.virtualStartAt(),null);}
 }
 public record Session(UUID id,UUID owner,UUID templateId,long templateVersion,String templateName,Definition definition,int format,int engine,UUID runId,long version,State state,Instant createdAt,Instant expiresAt,Race race){
  public Session(UUID id,UUID owner,UUID templateId,long templateVersion,String templateName,Definition definition,int format,int engine,UUID runId,long version,State state,Instant createdAt,Instant expiresAt){this(id,owner,templateId,templateVersion,templateName,definition,format,engine,runId,version,state,createdAt,expiresAt,Race.legacy(state));}
 @Override public String toString(){return "Simulation["+id+"]";}}
 public record Run(UUID id,UUID parent,Long through,State initial,Race race){public Run(UUID id,UUID parent,Long through,State initial){this(id,parent,through,initial,Race.legacy(initial));}}
 /** Explicit whitelist of confirmed simulator inputs; never HTTP headers or authentication context. */
 public record ConfirmedInput(String version,UUID runId,UUID stageId,String code,String deltaMs,String mode,Double latitude,Double longitude,Double accuracyMeters,Boolean mockLocation,String throughSequence){
  @Override public String toString(){return "ConfirmedSimulationInput[redacted]";}
 }
 public record Facts(String operation,Action action,Instant clock,Position position,ConfirmedInput input,Instant previousClock,Race race){
  public Facts(String operation,Action action,Instant clock,Position position,ConfirmedInput input,Instant previousClock){this(operation,action,clock,position,input,previousClock,null);}
  public Facts(String operation,Action action,Instant clock,Position position){this(operation,action,clock,position,null,null);}
  @Override public String toString(){return "SimulationFacts[operation="+operation+"]";}
 }
 public record Effect(String beforeHash,String afterHash){}
 public record Command(UUID id,UUID runId,long sequence,String operation,String fingerprint,Instant expiresAt,String response,Facts facts,Effect effect){}
 private final JdbcTemplate jdbc;public SimulationStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
 private static Timestamp ts(Instant i){return i==null?null:Timestamp.from(i);}private static Instant instant(ResultSet r,String k)throws SQLException{return r.getTimestamp(k).toInstant();}
 public static String json(Object v){return JSON.writeValueAsString(v);}public static <T>T read(String text,Class<T> type){return JSON.readValue(text,type);}
 private static Session map(ResultSet r,int n)throws SQLException{if(r.getInt("snapshot_format_version")!=1||r.getInt("evaluator_version")!=1)throw new cz.mh.rallye.rallye_backend.identity.application.AuthFailure(503,"SIMULATION_ENGINE_VERSION_UNSUPPORTED");return new Session(r.getObject("id",UUID.class),r.getObject("owner_account_id",UUID.class),r.getObject("source_template_id",UUID.class),r.getLong("source_template_version"),r.getString("source_template_name"),read(r.getString("snapshot"),Definition.class),r.getInt("snapshot_format_version"),r.getInt("evaluator_version"),r.getObject("current_run_id",UUID.class),r.getLong("version"),read(r.getString("projection"),State.class),instant(r,"created_at"),null,read(r.getString("race"),Race.class));}
 public Session lock(UUID id){var rows=jdbc.query("SELECT * FROM race_simulation WHERE id=? FOR UPDATE",SimulationStore::map,id);return rows.isEmpty()?null:rows.getFirst();}
 public void insert(UUID id,UUID owner,UUID template,long version,String name,Definition def,UUID run,State initial,Instant at,Instant expires){jdbc.update("INSERT INTO race_simulation(id,owner_account_id,source_template_id,source_template_version,source_template_name,snapshot,evaluator_version,current_run_id,status,virtual_start_at,virtual_now,projection,created_at,last_activity_at,expires_at) VALUES (?,?,?,?,?,?::jsonb,1,?,?,?,?,?::jsonb,?,?,?)",id,owner,template,version,name,json(def),run,initial.status(),ts(initial.virtualStartAt()),ts(initial.virtualNow()),json(initial),ts(at),ts(at),ts(expires));}
 public void update(UUID id,UUID run,long version,State state,Instant at,Instant expires){jdbc.update("UPDATE race_simulation SET current_run_id=?,version=?,status=?,virtual_start_at=?,virtual_now=?,projection=?::jsonb,last_activity_at=?,expires_at=? WHERE id=?",run,version,state.status(),ts(state.virtualStartAt()),ts(state.virtualNow()),json(state),ts(at),ts(expires),id);}
 public void run(UUID id,UUID simulation,UUID parent,Long through,State initial){jdbc.update("INSERT INTO simulation_run(id,simulation_id,number,parent_run_id,restored_through_sequence,started_virtual_at,initial_state) SELECT ?,?,coalesce(max(number),0)+1,?,?,?,?::jsonb FROM simulation_run WHERE simulation_id=?",id,simulation,parent,through,ts(initial.virtualStartAt()),json(initial),simulation);}
 public void race(UUID id,Race race){jdbc.update("UPDATE race_simulation SET race=?::jsonb WHERE id=?",json(race),id);}
 public void runRace(UUID simulation,UUID run,Race race){jdbc.update("UPDATE simulation_run SET initial_race=?::jsonb WHERE simulation_id=? AND id=?",json(race),simulation,run);}
 public Run firstRun(UUID simulation){var ids=jdbc.query("SELECT id FROM simulation_run WHERE simulation_id=? ORDER BY number LIMIT 1",(r,n)->r.getObject(1,UUID.class),simulation);if(ids.isEmpty())throw new IllegalStateException("Missing initial simulation run");return run(simulation,ids.getFirst());}
 public Run run(UUID simulation,UUID id){var rows=jdbc.query("SELECT * FROM simulation_run WHERE simulation_id=? AND id=?",(r,n)->new Run(r.getObject("id",UUID.class),r.getObject("parent_run_id",UUID.class),r.getObject("restored_through_sequence",Long.class),read(r.getString("initial_state"),State.class),read(r.getString("initial_race"),Race.class)),simulation,id);return rows.isEmpty()?null:rows.getFirst();}
 public long nextSequence(UUID simulation){return jdbc.queryForObject("SELECT coalesce(max(sequence),0)+1 FROM simulation_command WHERE simulation_id=?",Long.class,simulation);}
 private static Command command(ResultSet r,int n)throws SQLException{return new Command(r.getObject("id",UUID.class),r.getObject("run_id",UUID.class),r.getLong("sequence"),r.getString("operation"),r.getString("request_fingerprint"),instant(r,"replay_expires_at"),r.getString("response"),read(r.getString("verified_facts"),Facts.class),read(r.getString("effects"),Effect.class));}
 public Command replay(UUID simulation,UUID actor,UUID key){var rows=jdbc.query("SELECT * FROM simulation_command WHERE simulation_id=? AND actor_id=? AND command_key=?",SimulationStore::command,simulation,actor,key);return rows.isEmpty()?null:rows.getFirst();}
 public List<Command> commands(UUID simulation,UUID run,long through){return jdbc.query("SELECT * FROM simulation_command WHERE simulation_id=? AND run_id=? AND sequence<=? ORDER BY sequence",SimulationStore::command,simulation,run,through);}

 public record RadarRow(UUID id,UUID sourceRunId,long sequence,long measurementNumber,Facts facts,Instant recordedAt){}
 /** Bounded projection of successful measurement facts; no projection replay or target configuration. */
 public List<RadarRow> radarMeasurements(UUID simulation,Map<UUID,Long> visibleRuns,UUID stage,long after,int size){return jdbc.query("""
  WITH visible AS (SELECT key::uuid AS run_id,value::bigint AS cutoff FROM jsonb_each_text(?::jsonb)),
  measurements AS (
   SELECT c.id,c.run_id,c.sequence,c.verified_facts,c.recorded_at,
          row_number() OVER (ORDER BY c.sequence) AS measurement_number
   FROM simulation_command c JOIN visible v ON v.run_id=c.run_id AND c.sequence<=v.cutoff
   WHERE c.simulation_id=? AND c.operation='RADAR'
     AND c.verified_facts #>> '{action,stageId}'=?
     AND c.verified_facts #>> '{action,outcome}' IN ('RADAR_OUTSIDE','RADAR_ACCEPTED')
  ) SELECT * FROM measurements WHERE sequence>? ORDER BY sequence LIMIT ?
  """,(r,n)->new RadarRow(r.getObject("id",UUID.class),r.getObject("run_id",UUID.class),r.getLong("sequence"),r.getLong("measurement_number"),read(r.getString("verified_facts"),Facts.class),instant(r,"recorded_at")),json(visibleRuns),simulation,stage.toString(),after,size+1);}
 public void command(UUID id,UUID simulation,UUID run,UUID actor,UUID key,long sequence,String operation,long version,String fingerprint,Facts facts,Effect effect,String response,Instant at){jdbc.update("INSERT INTO simulation_command(id,simulation_id,run_id,actor_id,command_key,sequence,operation,expected_version,request_fingerprint,verified_facts,effects,response,observed_virtual_at,recorded_at,replay_expires_at) VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?,?)",id,simulation,run,actor,key,sequence,operation,version,fingerprint,json(facts),json(effect),response,ts(facts.clock()),ts(at),ts(at.plusSeconds(86400)));}
 public void file(UUID id,UUID sim,String name,String key,TemplateFileContent.Validated v,Instant at){jdbc.update("INSERT INTO simulation_file(id,simulation_id,file_name,name_key,content_type,byte_size,width,height,markdown_source,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",id,sim,name,key,v.contentType(),v.bytes().length,v.width(),v.height(),v.markdown(),ts(at));}
 public List<Image> files(UUID sim){return jdbc.query("SELECT * FROM simulation_file WHERE simulation_id=? ORDER BY name_key",(r,n)->new Image(r.getObject("id",UUID.class),sim,r.getString("file_name"),r.getString("name_key"),r.getString("content_type"),r.getInt("byte_size"),r.getObject("width",Integer.class),r.getObject("height",Integer.class),instant(r,"created_at"),false,r.getString("markdown_source")),sim);}
 public boolean referenced(UUID sim,String name){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM simulation_file WHERE simulation_id=? AND name_key=?)",Boolean.class,sim,cz.mh.rallye.rallye_backend.templates.application.ImageNames.key(name)));}
 public void delete(UUID id){
  jdbc.update("DELETE FROM race_simulation WHERE id=?",id);
  jdbc.update("DELETE FROM admin_role_command WHERE target_id=? AND operation='SIMULATION_CREATE'",id);
 }
 public record StageLabel(UUID id,String name,int sequenceNo){}
 public record Listed(UUID id,UUID templateId,long templateVersion,String templateName,Instant createdAt,Instant lastActivityAt,UUID runId,long version,String status,StageLabel stage){}
 public List<Listed> list(UUID owner,String status,int page,int size){return jdbc.query("""
  SELECT id,source_template_id,source_template_version,source_template_name,created_at,last_activity_at,current_run_id,version,status,
    snapshot_format_version,evaluator_version,
    CASE WHEN status='RUNNING' THEN
     (SELECT jsonb_build_object('id',d->'id','name',d->'name','sequenceNo',d->'sequenceNo')
      FROM jsonb_array_elements(snapshot->'stages') d
      WHERE d->>'id'=(SELECT p->>'stageId' FROM jsonb_array_elements(projection->'stages') p WHERE p->>'status'='ACTIVE' LIMIT 1) LIMIT 1)
    END AS current_stage
  FROM race_simulation WHERE owner_account_id=? AND (CAST(? AS text) IS NULL OR status=?)
  ORDER BY last_activity_at DESC,id DESC LIMIT ? OFFSET ?
  """,(r,n)->{
   if(r.getInt("snapshot_format_version")!=1||r.getInt("evaluator_version")!=1)throw new cz.mh.rallye.rallye_backend.identity.application.AuthFailure(503,"SIMULATION_ENGINE_VERSION_UNSUPPORTED");
   String stage=r.getString("current_stage");return new Listed(r.getObject("id",UUID.class),r.getObject("source_template_id",UUID.class),r.getLong("source_template_version"),r.getString("source_template_name"),instant(r,"created_at"),instant(r,"last_activity_at"),r.getObject("current_run_id",UUID.class),r.getLong("version"),r.getString("status"),stage==null?null:read(stage,StageLabel.class));
  },owner,status,status,size+1,(long)page*size);}
 public void expireReplay(Instant at){jdbc.update("UPDATE simulation_command SET request_fingerprint=NULL,response=NULL WHERE replay_expires_at<=? AND response IS NOT NULL",ts(at));}
}
