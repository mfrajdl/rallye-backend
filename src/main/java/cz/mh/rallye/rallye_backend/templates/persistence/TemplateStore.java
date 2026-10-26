package cz.mh.rallye.rallye_backend.templates.persistence;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration;
import cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;

@Repository
public class TemplateStore {
    public record Template(UUID id,String name,String description,long skipPenaltyPoints,String status,long version,Instant createdAt,Instant updatedAt) {}
    public record Stage(UUID id,UUID templateId,int sequenceNo,String name,Long expectedDurationMs,Long maxPenaltyPoints,String instructionsHtml,StageConfiguration configuration) {
        public Stage(UUID id,UUID templateId,int sequenceNo,String name,Long expectedDurationMs,Long maxPenaltyPoints){this(id,templateId,sequenceNo,name,expectedDurationMs,maxPenaltyPoints,null,StageConfiguration.empty());}
        @Override public String toString(){return "Stage[id="+id+"]";}
    }
    private final JdbcTemplate jdbc;
    public TemplateStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static Template template(ResultSet r,int n)throws SQLException{return new Template(r.getObject("id",UUID.class),r.getString("name"),r.getString("description"),r.getLong("skip_penalty_points"),r.getString("status"),r.getLong("version"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());}
    private static Stage stage(ResultSet r,int n)throws SQLException{
        var gps=new Gps(r.getBoolean("gps_enabled"),r.getObject("latitude",Double.class),r.getObject("longitude",Double.class),r.getObject("radius_m",Double.class),r.getObject("outside_penalty_points",Long.class),r.getObject("spoofing_penalty_points",Long.class));
        var code=new Code(r.getBoolean("code_enabled"),r.getString("code_hash"),r.getObject("wrong_code_penalty_points",Long.class));
        var time=new TimePenalty(r.getBoolean("time_penalty_enabled"),r.getObject("penalty_interval_ms",Long.class),r.getObject("points_per_interval",Long.class));
        var delayed=new Delayed(r.getBoolean("delayed_enabled"),r.getObject("minimum_duration_ms",Long.class),r.getObject("early_penalty_per_second",Long.class));
        return new Stage(r.getObject("id",UUID.class),r.getObject("race_template_id",UUID.class),r.getInt("sequence_no"),r.getString("name"),r.getObject("expected_duration_ms",Long.class),r.getObject("max_penalty_points",Long.class),r.getString("instructions_html"),new StageConfiguration(gps,code,time,delayed));
    }
    public Template lock(UUID id){var rows=jdbc.query("SELECT * FROM race_template WHERE id=? FOR UPDATE",TemplateStore::template,id);return rows.isEmpty()?null:rows.getFirst();}
    public List<Template> list(String query,String status,int page,int size){
        var sql=new StringBuilder("SELECT * FROM race_template WHERE true");var args=new ArrayList<Object>();
        if(query!=null && !query.isEmpty()){sql.append(" AND name ILIKE ? ESCAPE '!'");args.add("%"+query.replace("!","!!").replace("%","!%").replace("_","!_")+"%");}
        if(status!=null){sql.append(" AND status=?");args.add(status);}
        sql.append(" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?");args.add(size+1);args.add((long)page*size);
        return jdbc.query(sql.toString(),TemplateStore::template,args.toArray());
    }
    public void insert(UUID id,String name,String description,long skip,Instant now){jdbc.update("INSERT INTO race_template(id,name,description,skip_penalty_points,created_at,updated_at) VALUES (?,?,?,?,?,?)",id,name,description,skip,Timestamp.from(now),Timestamp.from(now));}
    public void update(UUID id,String name,String description,long skip,boolean substantial,Instant now){jdbc.update("UPDATE race_template SET name=?,description=?,skip_penalty_points=?,status=CASE WHEN ? THEN 'DRAFT' ELSE status END,version=version+1,updated_at=? WHERE id=?",name,description,skip,substantial,Timestamp.from(now),id);}
    public void touch(UUID id,Instant now){jdbc.update("UPDATE race_template SET status='DRAFT',version=version+1,updated_at=? WHERE id=?",Timestamp.from(now),id);}
    public void state(UUID id,String status,Instant now){jdbc.update("UPDATE race_template SET status=?,version=version+1,updated_at=? WHERE id=?",status,Timestamp.from(now),id);}
    public void delete(UUID id){jdbc.update("DELETE FROM race_template WHERE id=?",id);}
    /** Before event support, deletion is safe only in the explicitly reviewed pre-event schema. */
    public void ensureDeletionSupported(){
        boolean known=Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT count(*)=18 AND bool_and(success AND type='SQL')
              AND array_agg(version ORDER BY installed_rank)=ARRAY['1','2','3','4','5','6','7','8','9','10','11','12','13','14','15','16','17','18']::varchar[]
              AND count(*) FILTER (WHERE version='16' AND script='V16__race_templates.sql')=1
              AND count(*) FILTER (WHERE version='18' AND script='V18__template_html_and_images.sql')=1
              AND count(*) FILTER (WHERE version='17' AND script='V17__stage_configuration_and_readiness.sql')=1
            FROM flyway_schema_history WHERE type<>'SCHEMA'
            """,Boolean.class));
        boolean events=Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass('race_event') IS NOT NULL",Boolean.class));
        if(!known || events)throw new AuthFailure(503,"TEMPLATE_USAGE_CHECK_UNAVAILABLE");
    }
    public List<Stage> stages(UUID id){return jdbc.query("SELECT * FROM template_stage WHERE race_template_id=? ORDER BY sequence_no",TemplateStore::stage,id);}
    public void insertStage(UUID id,UUID template,int sequence,String name,Long expected,Long max){jdbc.update("INSERT INTO template_stage(id,race_template_id,sequence_no,name,expected_duration_ms,max_penalty_points) VALUES (?,?,?,?,?,?)",id,template,sequence,name,expected,max);}
    public void updateStage(UUID id,String name,Long expected,Long max){jdbc.update("UPDATE template_stage SET name=?,expected_duration_ms=?,max_penalty_points=? WHERE id=?",name,expected,max,id);}
    public void instructionsHtml(UUID id,String instructionsHtml){jdbc.update("UPDATE template_stage SET instructions_html=? WHERE id=?",instructionsHtml,id);}
    public void configureStage(UUID id,StageConfiguration config){
        var gps=config.gps();var code=config.code();var time=config.timePenalty();var delayed=config.delayed();
        jdbc.update("""
            UPDATE template_stage SET gps_enabled=?,latitude=?,longitude=?,radius_m=?,outside_penalty_points=?,spoofing_penalty_points=?,
              code_enabled=?,code_hash=?,wrong_code_penalty_points=?,time_penalty_enabled=?,penalty_interval_ms=?,points_per_interval=?,
              delayed_enabled=?,minimum_duration_ms=?,early_penalty_per_second=? WHERE id=?
            """,gps.enabled(),gps.latitude(),gps.longitude(),gps.radiusMeters(),gps.outsidePenaltyPoints(),gps.spoofingPenaltyPoints(),
            code.enabled(),code.hash(),code.wrongAttemptPenaltyPoints(),time.enabled(),time.intervalMs(),time.pointsPerInterval(),
            delayed.enabled(),delayed.minimumDurationMs(),delayed.pointsPerSecond(),id);
    }
    public void deleteStage(UUID id){jdbc.update("DELETE FROM template_stage WHERE id=?",id);}
    public void order(UUID template,List<UUID> ids){for(int i=0;i<ids.size();i++)jdbc.update("UPDATE template_stage SET sequence_no=? WHERE id=? AND race_template_id=?",i+1,ids.get(i),template);}
 public record Image(UUID id,UUID templateId,String contentType,int byteSize,int width,int height,Instant createdAt,boolean used) {}
 public List<Image> images(UUID template){return jdbc.query("""
  SELECT i.*,EXISTS(SELECT 1 FROM template_stage_image u WHERE u.image_id=i.id) AS used
  FROM template_image i WHERE race_template_id=? ORDER BY created_at,id
  """,(r,n)->new Image(r.getObject("id",UUID.class),r.getObject("race_template_id",UUID.class),r.getString("content_type"),
   r.getInt("byte_size"),r.getInt("width"),r.getInt("height"),r.getTimestamp("created_at").toInstant(),r.getBoolean("used")),template);}
 public void image(UUID id,UUID template,cz.mh.rallye.rallye_backend.crews.application.PhotoFiles.Image image,Instant now){
  jdbc.update("INSERT INTO template_image(id,race_template_id,content_type,byte_size,width,height,created_at) VALUES (?,?,?,?,?,?,?)",id,template,image.type(),image.bytes().length,image.width(),image.height(),Timestamp.from(now));
 }
 public boolean imageReferenced(UUID id){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM template_image WHERE id=?)",Boolean.class,id));}
 public void deleteImage(UUID id){jdbc.update("DELETE FROM template_image WHERE id=?",id);}
 public void references(UUID template,UUID stage,Set<UUID> ids){
  jdbc.update("DELETE FROM template_stage_image WHERE stage_id=?",stage);
  for(UUID id:ids)jdbc.update("INSERT INTO template_stage_image(stage_id,image_id,race_template_id) VALUES (?,?,?)",stage,id,template);
 }
    /** Copies the complete current persisted stage model, including the existing write-only CODE hash. */
    public void copyStage(Stage source,UUID id,UUID template,int sequence,String html){
        // Copy the complete V18 row configuration inside PostgreSQL: the CODE hash is not a JDBC bind value.
        int copied=jdbc.update("""
            INSERT INTO template_stage(id,race_template_id,sequence_no,name,expected_duration_ms,max_penalty_points,instructions_html,
                gps_enabled,latitude,longitude,radius_m,outside_penalty_points,spoofing_penalty_points,
                code_enabled,code_hash,wrong_code_penalty_points,time_penalty_enabled,penalty_interval_ms,points_per_interval,
                delayed_enabled,minimum_duration_ms,early_penalty_per_second)
            SELECT ?,?,?,name,expected_duration_ms,max_penalty_points,?,
                gps_enabled,latitude,longitude,radius_m,outside_penalty_points,spoofing_penalty_points,
                code_enabled,code_hash,wrong_code_penalty_points,time_penalty_enabled,penalty_interval_ms,points_per_interval,
                delayed_enabled,minimum_duration_ms,early_penalty_per_second
            FROM template_stage WHERE id=?
            """,id,template,sequence,html,source.id());
        if(copied!=1)throw new AuthFailure(503,"TEMPLATE_COPY_INCONSISTENT");
    }
    public Set<UUID> usedImages(UUID stage){return new HashSet<>(jdbc.query("SELECT image_id FROM template_stage_image WHERE stage_id=?",(r,n)->r.getObject(1,UUID.class),stage));}
}
