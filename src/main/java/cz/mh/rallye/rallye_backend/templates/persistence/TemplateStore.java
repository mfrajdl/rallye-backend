package cz.mh.rallye.rallye_backend.templates.persistence;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration;
import cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import cz.mh.rallye.rallye_backend.templates.application.ImageNames;
import cz.mh.rallye.rallye_backend.templates.application.TemplateFileContent;

@Repository
public class TemplateStore {
    public record Template(UUID id,String name,String description,long skipPenaltyPoints,String status,long version,Instant createdAt,Instant updatedAt,long defaultWrongCodePenaltyPoints) {
        public Template(UUID id,String name,String description,long skip,String status,long version,Instant createdAt,Instant updatedAt){this(id,name,description,skip,status,version,createdAt,updatedAt,0);}
    }
    public record Stage(UUID id,UUID templateId,int sequenceNo,String name,Long expectedDurationMs,Long maxPenaltyPoints,String instructionsMarkdown,StageConfiguration configuration) {
        public Stage(UUID id,UUID templateId,int sequenceNo,String name,Long expectedDurationMs,Long maxPenaltyPoints){this(id,templateId,sequenceNo,name,expectedDurationMs,maxPenaltyPoints,null,StageConfiguration.empty());}
        @Override public String toString(){return "Stage[id="+id+"]";}
    }
    private final JdbcTemplate jdbc;
    public TemplateStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static Template template(ResultSet r,int n)throws SQLException{return new Template(r.getObject("id",UUID.class),r.getString("name"),r.getString("description"),r.getLong("skip_penalty_points"),r.getString("status"),r.getLong("version"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),r.getLong("default_wrong_code_penalty_points"));}
    public static Stage stage(ResultSet r,int n)throws SQLException{
        var gps=new Gps(r.getBoolean("gps_enabled"),r.getObject("latitude",Double.class),r.getObject("longitude",Double.class),r.getObject("radius_m",Double.class),r.getObject("outside_penalty_points",Long.class),r.getObject("spoofing_penalty_points",Long.class));
        var code=new Code(r.getBoolean("code_enabled"),r.getString("code_value"),r.getObject("wrong_code_penalty_points",Long.class),r.getString("code_value")==null&&r.getString("code_hash")!=null);
        var time=new TimePenalty(r.getBoolean("time_penalty_enabled"),r.getObject("penalty_interval_ms",Long.class),r.getObject("points_per_interval",Long.class));
        var delayed=new Delayed(r.getBoolean("delayed_enabled"),r.getObject("minimum_duration_ms",Long.class),r.getObject("early_penalty_per_second",Long.class));
        return new Stage(r.getObject("id",UUID.class),r.getObject("race_template_id",UUID.class),r.getInt("sequence_no"),r.getString("name"),r.getObject("expected_duration_ms",Long.class),r.getObject("max_penalty_points",Long.class),r.getString("instructions_markdown"),new StageConfiguration(gps,code,time,delayed,r.getString("stage_type"),r.getObject("radar_penalty_points",Long.class)));
    }
    public Template lock(UUID id){var rows=jdbc.query("SELECT * FROM race_template WHERE id=? FOR UPDATE",TemplateStore::template,id);return rows.isEmpty()?null:rows.getFirst();}
    public List<Template> list(String query,String status,int page,int size){
        var sql=new StringBuilder("SELECT * FROM race_template WHERE true");var args=new ArrayList<Object>();
        if(query!=null && !query.isEmpty()){sql.append(" AND name ILIKE ? ESCAPE '!'");args.add("%"+query.replace("!","!!").replace("%","!%").replace("_","!_")+"%");}
        if(status!=null){sql.append(" AND status=?");args.add(status);}
        sql.append(" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?");args.add(size+1);args.add((long)page*size);
        return jdbc.query(sql.toString(),TemplateStore::template,args.toArray());
    }
    public void insert(UUID id,String name,String description,long skip,Instant now){insert(id,name,description,skip,0,now);}
    public void insert(UUID id,String name,String description,long skip,long wrong,Instant now){jdbc.update("INSERT INTO race_template(id,name,description,skip_penalty_points,default_wrong_code_penalty_points,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",id,name,description,skip,wrong,Timestamp.from(now),Timestamp.from(now));}
    public void update(UUID id,String name,String description,long skip,boolean substantial,Instant now){update(id,name,description,skip,null,substantial,now);}
    public void update(UUID id,String name,String description,long skip,Long wrong,boolean substantial,Instant now){jdbc.update("UPDATE race_template SET name=?,description=?,skip_penalty_points=?,default_wrong_code_penalty_points=coalesce(?,default_wrong_code_penalty_points),status=CASE WHEN ? THEN 'DRAFT' ELSE status END,version=version+1,updated_at=? WHERE id=?",name,description,skip,wrong,substantial,Timestamp.from(now),id);}
    public void touch(UUID id,Instant now){jdbc.update("UPDATE race_template SET status='DRAFT',version=version+1,updated_at=? WHERE id=?",Timestamp.from(now),id);}
    public void state(UUID id,String status,Instant now){jdbc.update("UPDATE race_template SET status=?,version=version+1,updated_at=? WHERE id=?",status,Timestamp.from(now),id);}
    public void delete(UUID id){jdbc.update("DELETE FROM race_template WHERE id=?",id);}
    /** Fail closed on unknown schemas; in the reviewed event schema protect actual source usage. */
    public void ensureDeletionSupported(){ensureDeletionSupported(null);}
    public void ensureDeletionSupported(UUID template){
        boolean known=Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT count(*) IN (22,23,24,25,26,27,28) AND bool_and(success AND type='SQL')
              AND (array_agg(version ORDER BY installed_rank)=ARRAY['1','2','3','4','5','6','7','8','9','10','11','12','13','14','15','16','17','18','19','20','21','22']::varchar[] OR (array_agg(version ORDER BY installed_rank)=ARRAY['1','2','3','4','5','6','7','8','9','10','11','12','13','14','15','16','17','18','19','20','21','22','23']::varchar[] AND count(*) FILTER(WHERE version='23' AND script='V23__race_simulations.sql')=1) OR (array_agg(version ORDER BY installed_rank)=ARRAY['1','2','3','4','5','6','7','8','9','10','11','12','13','14','15','16','17','18','19','20','21','22','23','24']::varchar[] AND count(*) FILTER(WHERE version='23' AND script='V23__race_simulations.sql')=1 AND count(*) FILTER(WHERE version='24' AND script='V24__persistent_simulation_management.sql')=1) OR (array_agg(version ORDER BY installed_rank)=ARRAY['1','2','3','4','5','6','7','8','9','10','11','12','13','14','15','16','17','18','19','20','21','22','23','24','25']::varchar[] AND count(*) FILTER(WHERE version='23' AND script='V23__race_simulations.sql')=1 AND count(*) FILTER(WHERE version='24' AND script='V24__persistent_simulation_management.sql')=1 AND count(*) FILTER(WHERE version='25' AND script='V25__simulation_race_lifecycle.sql')=1) OR (array_agg(version ORDER BY installed_rank)=ARRAY['1','2','3','4','5','6','7','8','9','10','11','12','13','14','15','16','17','18','19','20','21','22','23','24','25','26']::varchar[] AND count(*) FILTER(WHERE version='23' AND script='V23__race_simulations.sql')=1 AND count(*) FILTER(WHERE version='24' AND script='V24__persistent_simulation_management.sql')=1 AND count(*) FILTER(WHERE version='25' AND script='V25__simulation_race_lifecycle.sql')=1 AND count(*) FILTER(WHERE version='26' AND script='V26__crew_registrations.sql')=1) OR (array_agg(version ORDER BY installed_rank)=ARRAY['1','2','3','4','5','6','7','8','9','10','11','12','13','14','15','16','17','18','19','20','21','22','23','24','25','26','27']::varchar[] AND count(*) FILTER(WHERE version='23' AND script='V23__race_simulations.sql')=1 AND count(*) FILTER(WHERE version='24' AND script='V24__persistent_simulation_management.sql')=1 AND count(*) FILTER(WHERE version='25' AND script='V25__simulation_race_lifecycle.sql')=1 AND count(*) FILTER(WHERE version='26' AND script='V26__crew_registrations.sql')=1 AND count(*) FILTER(WHERE version='27' AND script='V27__race_event_lifecycle.sql')=1) OR (array_agg(version ORDER BY installed_rank)=ARRAY['1','2','3','4','5','6','7','8','9','10','11','12','13','14','15','16','17','18','19','20','21','22','23','24','25','26','27','28']::varchar[] AND count(*) FILTER(WHERE version='23' AND script='V23__race_simulations.sql')=1 AND count(*) FILTER(WHERE version='24' AND script='V24__persistent_simulation_management.sql')=1 AND count(*) FILTER(WHERE version='25' AND script='V25__simulation_race_lifecycle.sql')=1 AND count(*) FILTER(WHERE version='26' AND script='V26__crew_registrations.sql')=1 AND count(*) FILTER(WHERE version='27' AND script='V27__race_event_lifecycle.sql')=1 AND count(*) FILTER(WHERE version='28' AND script='V28__stage_completion_types.sql')=1))
              AND count(*) FILTER (WHERE version='22' AND script='V22__action_configuration.sql')=1
              AND count(*) FILTER (WHERE version='16' AND script='V16__race_templates.sql')=1
              AND count(*) FILTER (WHERE version='20' AND script='V20__template_file_library.sql')=1
              AND count(*) FILTER (WHERE version='19' AND script='V19__template_markdown_and_named_images.sql')=1
              AND count(*) FILTER (WHERE version='18' AND script='V18__template_html_and_images.sql')=1
              AND count(*) FILTER (WHERE version='17' AND script='V17__stage_configuration_and_readiness.sql')=1
            FROM flyway_schema_history WHERE type<>'SCHEMA'
            """,Boolean.class));
        boolean events=Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass('race_event') IS NOT NULL",Boolean.class));
        boolean eventSchema=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM flyway_schema_history WHERE version='21' AND script='V21__planned_race_events.sql' AND success AND type='SQL')",Boolean.class));
        if(!known || events!=eventSchema || (events && template==null))throw new AuthFailure(503,"TEMPLATE_USAGE_CHECK_UNAVAILABLE");
        if(events && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM race_event WHERE source_template_id=?)",Boolean.class,template)))throw new AuthFailure(409,"TEMPLATE_IN_USE");
    }
    public List<Stage> stages(UUID id){return jdbc.query("SELECT * FROM template_stage WHERE race_template_id=? ORDER BY sequence_no",TemplateStore::stage,id);}
    public void insertStage(UUID id,UUID template,int sequence,String name,Long expected,Long max){jdbc.update("INSERT INTO template_stage(id,race_template_id,sequence_no,name,expected_duration_ms,max_penalty_points) VALUES (?,?,?,?,?,?)",id,template,sequence,name,expected,max);}
    public void updateStage(UUID id,String name,Long expected,Long max){jdbc.update("UPDATE template_stage SET name=?,expected_duration_ms=?,max_penalty_points=? WHERE id=?",name,expected,max,id);}
    public void instructionsMarkdown(UUID id,String instructionsMarkdown){jdbc.update("UPDATE template_stage SET instructions_markdown=? WHERE id=?",instructionsMarkdown,id);}
    public void configureStage(UUID id,StageConfiguration config){
        var gps=config.gps();var code=config.code();var time=config.timePenalty();var delayed=config.delayed();
        jdbc.update("""
            UPDATE template_stage SET gps_enabled=?,latitude=?,longitude=?,radius_m=?,outside_penalty_points=?,spoofing_penalty_points=?,
              code_enabled=?,code_value=?,code_hash=CASE WHEN CAST(? AS text) IS NOT NULL THEN NULL ELSE code_hash END,wrong_code_penalty_points=?,time_penalty_enabled=?,penalty_interval_ms=?,points_per_interval=?,
              delayed_enabled=?,minimum_duration_ms=?,early_penalty_per_second=?,stage_type=?,radar_penalty_points=? WHERE id=?
            """,gps.enabled(),gps.latitude(),gps.longitude(),gps.radiusMeters(),gps.outsidePenaltyPoints(),gps.spoofingPenaltyPoints(),
            code.enabled(),code.value(),code.value(),code.wrongAttemptPenaltyPoints(),time.enabled(),time.intervalMs(),time.pointsPerInterval(),
            delayed.enabled(),delayed.minimumDurationMs(),delayed.pointsPerSecond(),config.type(),config.radarPenaltyPoints(),id);
    }
    public void deleteStage(UUID id){jdbc.update("DELETE FROM template_stage WHERE id=?",id);}
    public void order(UUID template,List<UUID> ids){for(int i=0;i<ids.size();i++)jdbc.update("UPDATE template_stage SET sequence_no=? WHERE id=? AND race_template_id=?",i+1,ids.get(i),template);}
 public record Image(UUID id,UUID templateId,String fileName,String nameKey,String contentType,int byteSize,Integer width,Integer height,Instant createdAt,boolean used,String markdownSource) {
  public Image(UUID id,UUID templateId,String fileName,String nameKey,String contentType,int byteSize,Integer width,Integer height,Instant createdAt,boolean used){this(id,templateId,fileName,nameKey,contentType,byteSize,width,height,createdAt,used,null);}
  public boolean isImage(){return contentType.startsWith("image/");}
  public boolean isMarkdown(){return contentType.equals("text/markdown");}
 }
 public List<Image> images(UUID template){return jdbc.query("""
  SELECT i.*,EXISTS(SELECT 1 FROM template_stage_image u WHERE u.image_id=i.id) AS used
  FROM template_image i WHERE race_template_id=? ORDER BY created_at,id
  """,(r,n)->new Image(r.getObject("id",UUID.class),r.getObject("race_template_id",UUID.class),r.getString("file_name"),r.getString("name_key"),r.getString("content_type"),
   r.getInt("byte_size"),r.getObject("width",Integer.class),r.getObject("height",Integer.class),r.getTimestamp("created_at").toInstant(),r.getBoolean("used"),r.getString("markdown_source")),template);}
 public void image(UUID id,UUID template,String name,cz.mh.rallye.rallye_backend.crews.application.PhotoFiles.Image image,Instant now){
  jdbc.update("INSERT INTO template_image(id,race_template_id,file_name,name_key,content_type,byte_size,width,height,created_at) VALUES (?,?,?,?,?,?,?,?,?)",id,template,name,ImageNames.key(name),image.type(),image.bytes().length,image.width(),image.height(),Timestamp.from(now));
 }
 public boolean imageReferenced(UUID id){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM template_image WHERE id=?)",Boolean.class,id));}
 public void deleteImage(UUID id){jdbc.update("DELETE FROM template_image WHERE id=?",id);}
 public void references(UUID template,UUID stage,Set<UUID> ids){
  jdbc.update("DELETE FROM template_stage_image WHERE stage_id=?",stage);
  for(UUID id:ids)jdbc.update("INSERT INTO template_stage_image(stage_id,image_id,race_template_id) VALUES (?,?,?)",stage,id,template);
 }
    /** Copies the complete current persisted stage model, including the existing write-only CODE hash. */
    public void copyStage(Stage source,UUID id,UUID template,int sequence,String markdown){
        // Copy the complete current row configuration inside PostgreSQL: the CODE hash is not a JDBC bind value.
        int copied=jdbc.update("""
            INSERT INTO template_stage(id,race_template_id,sequence_no,name,expected_duration_ms,max_penalty_points,instructions_markdown,
                gps_enabled,latitude,longitude,radius_m,outside_penalty_points,spoofing_penalty_points,
                code_enabled,code_hash,code_value,wrong_code_penalty_points,time_penalty_enabled,penalty_interval_ms,points_per_interval,
                delayed_enabled,minimum_duration_ms,early_penalty_per_second,stage_type,radar_penalty_points)
            SELECT ?,?,?,name,expected_duration_ms,max_penalty_points,?,
                gps_enabled,latitude,longitude,radius_m,outside_penalty_points,spoofing_penalty_points,
                code_enabled,code_hash,code_value,wrong_code_penalty_points,time_penalty_enabled,penalty_interval_ms,points_per_interval,
                delayed_enabled,minimum_duration_ms,early_penalty_per_second,stage_type,radar_penalty_points
            FROM template_stage WHERE id=?
            """,id,template,sequence,markdown,source.id());
        if(copied!=1)throw new AuthFailure(503,"TEMPLATE_COPY_INCONSISTENT");
    }
    public Set<UUID> usedImages(UUID stage){return new HashSet<>(jdbc.query("SELECT image_id FROM template_stage_image WHERE stage_id=?",(r,n)->r.getObject(1,UUID.class),stage));}
 public boolean imageNameReferenced(UUID template,String name){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM template_image WHERE race_template_id=? AND name_key=?)",Boolean.class,template,ImageNames.key(name)));}
 public void file(UUID id,UUID template,String name,TemplateFileContent.Validated file,Instant at){
  jdbc.update("INSERT INTO template_image(id,race_template_id,file_name,name_key,content_type,byte_size,width,height,created_at,markdown_source) VALUES (?,?,?,?,?,?,?,?,?,?)",id,template,name,ImageNames.key(name),file.contentType(),file.bytes().length,file.width(),file.height(),Timestamp.from(at),file.markdown());
 }
}
