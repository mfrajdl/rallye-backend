package cz.mh.rallye.rallye_backend.races.persistence;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore;
import cz.mh.rallye.rallye_backend.templates.application.TemplateFileContent;
@Repository
public class EventStore {
 public record Event(UUID id,UUID sourceTemplateId,long sourceTemplateVersion,String name,String description,long skipPenaltyPoints,String status,Instant scheduledStartAt,Instant endAt,long version,Instant createdAt,Instant updatedAt){}
 public record Stage(TemplateStore.Stage data,UUID sourceTemplateStageId){}
 private final JdbcTemplate jdbc;
 public EventStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
 private static Instant instant(ResultSet r,String name)throws SQLException{var t=r.getTimestamp(name);return t==null?null:t.toInstant();}
 private static Event map(ResultSet r,int n)throws SQLException{return new Event(r.getObject("id",UUID.class),r.getObject("source_template_id",UUID.class),r.getLong("source_template_version"),r.getString("name"),r.getString("description"),r.getLong("skip_penalty_points"),r.getString("status"),instant(r,"scheduled_start_at"),instant(r,"end_at"),r.getLong("version"),instant(r,"created_at"),instant(r,"updated_at"));}
 private static Timestamp ts(Instant v){return v==null?null:Timestamp.from(v);}
 public Event lock(UUID id){var rows=jdbc.query("SELECT * FROM race_event WHERE id=? FOR UPDATE",EventStore::map,id);return rows.isEmpty()?null:rows.getFirst();}
 public List<Event> list(String query,int page,int size){return jdbc.query("SELECT * FROM race_event WHERE name ILIKE ? ESCAPE '!' ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",EventStore::map,"%"+query.replace("!","!!").replace("%","!%").replace("_","!_")+"%",size+1,(long)page*size);}
 public void insert(UUID id,TemplateStore.Template source,String name,String description,Instant start,Instant end,Instant now){jdbc.update("INSERT INTO race_event(id,source_template_id,source_template_version,name,description,skip_penalty_points,scheduled_start_at,end_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",id,source.id(),source.version(),name,description,source.skipPenaltyPoints(),ts(start),ts(end),ts(now),ts(now));}
 public void update(UUID id,String name,String description,Instant start,Instant end,Instant now){jdbc.update("UPDATE race_event SET name=?,description=?,scheduled_start_at=?,end_at=?,version=version+1,updated_at=? WHERE id=? AND status='PLANNED'",name,description,ts(start),ts(end),ts(now),id);}
 public List<Stage> stages(UUID event){return jdbc.query("SELECT s.*,race_event_id AS race_template_id FROM event_stage s WHERE race_event_id=? ORDER BY sequence_no",(r,n)->new Stage(TemplateStore.stage(r,n),r.getObject("source_template_stage_id",UUID.class)),event);}
 public List<TemplateStore.Image> files(UUID event){return jdbc.query("SELECT * FROM event_file WHERE race_event_id=? ORDER BY name_key,id",(r,n)->new TemplateStore.Image(r.getObject("id",UUID.class),event,r.getString("file_name"),r.getString("name_key"),r.getString("content_type"),r.getInt("byte_size"),r.getObject("width",Integer.class),r.getObject("height",Integer.class),r.getTimestamp("created_at").toInstant(),false,r.getString("markdown_source")),event);}
 public void file(UUID id,UUID event,String name,String key,TemplateFileContent.Validated file,Instant now){jdbc.update("INSERT INTO event_file(id,race_event_id,file_name,name_key,content_type,byte_size,width,height,created_at,markdown_source) VALUES (?,?,?,?,?,?,?,?,?,?)",id,event,name,key,file.contentType(),file.bytes().length,file.width(),file.height(),ts(now),file.markdown());}
 public boolean referenced(UUID event,String key){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM event_file WHERE race_event_id=? AND name_key=?)",Boolean.class,event,key));}
 public void copyStage(UUID source,UUID target,UUID event,String markdown){
  int copied=jdbc.update("""
   INSERT INTO event_stage(id,race_event_id,source_template_stage_id,sequence_no,name,expected_duration_ms,max_penalty_points,instructions_markdown,
    gps_enabled,latitude,longitude,radius_m,outside_penalty_points,spoofing_penalty_points,code_enabled,code_hash,wrong_code_penalty_points,
    time_penalty_enabled,penalty_interval_ms,points_per_interval,delayed_enabled,minimum_duration_ms,early_penalty_per_second)
   SELECT ?,?,?,sequence_no,name,expected_duration_ms,max_penalty_points,?,
    gps_enabled,latitude,longitude,radius_m,outside_penalty_points,spoofing_penalty_points,code_enabled,code_hash,wrong_code_penalty_points,
    time_penalty_enabled,penalty_interval_ms,points_per_interval,delayed_enabled,minimum_duration_ms,early_penalty_per_second
   FROM template_stage WHERE id=?
   """,target,event,source,markdown,source);
  if(copied!=1)throw new IllegalStateException("Source stage unavailable");
 }
}
