package cz.mh.rallye.rallye_backend.audit.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import cz.mh.rallye.rallye_backend.audit.application.AuditService.Event;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditStore {
    public record Entry(UUID id, Instant occurredAt, String actorKind, UUID actorId, String eventType, UUID targetId,
                        String targetType,UUID previousCaptainId,UUID newCaptainId,UUID crewId,UUID transferId,String resolutionReason) {
        public Entry(UUID id,Instant time,String kind,UUID actor,String event,UUID target,String targetType,UUID previous,UUID next) {
            this(id,time,kind,actor,event,target,targetType,previous,next,null,null,null);
        }
        public Entry(UUID id,Instant time,String kind,UUID actor,String event,UUID target) {
            this(id,time,kind,actor,event,target,"ACCOUNT",null,null);
        }
    }
    public void appendMaintenance(UUID id,Instant time,UUID actor) {
        jdbc.update("""
                INSERT INTO audit_event(id,occurred_at,actor_kind,actor_id,event_type,target_id,target_type)
                VALUES (?,?,'USER',?,'PHOTO_CLEANUP_STARTED',?,'PHOTO_STORAGE')
                """,id,Timestamp.from(time),actor,actor);
    }
    public void appendCrew(UUID id, Instant time, UUID actor, Event event, UUID crew, UUID previous, UUID next) {
        jdbc.update("""
                INSERT INTO audit_event(id,occurred_at,actor_kind,actor_id,event_type,target_id,target_type,previous_captain_id,new_captain_id)
                VALUES (?,?,'USER',?,?,?,'CREW',?,?)
                """,id,Timestamp.from(time),actor,event.name(),crew,previous,next);
    }
    public void appendTransfer(UUID id,Instant time,UUID actor,Event event,UUID transfer,UUID crew,UUID from,UUID to,String reason) {
        jdbc.update("""
                INSERT INTO audit_event(id,occurred_at,actor_kind,actor_id,event_type,target_id,target_type,
                  previous_captain_id,new_captain_id,crew_id,transfer_id,resolution_reason)
                VALUES (?,?,'USER',?,?,?,'CREW_TRANSFER',?,?,?,?,?)
                """,id,Timestamp.from(time),actor,event.name(),transfer,from,to,crew,transfer,reason);
    }
    private final JdbcTemplate jdbc;
    public AuditStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void appendAuditMaintenance(UUID id,Instant time,UUID actor,Event event,String metadata) {
        jdbc.update("""
                INSERT INTO audit_event(id,occurred_at,actor_kind,actor_id,event_type,target_id,target_type,metadata)
                VALUES (?,?,?,?,?,?,'AUDIT_LOG',?::jsonb)
                """,id,Timestamp.from(time),actor==null?"SYSTEM":"USER",actor,event.name(),
                UUID.fromString("00000000-0000-0000-0000-000000000005"),metadata);
    }
    public void appendRegistration(UUID id,Instant time,UUID actor,Event event,UUID target,UUID raceEvent,long version){
        String metadata=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(java.util.Map.of("raceEventId",raceEvent,"version",version));
        jdbc.update("INSERT INTO audit_event(id,occurred_at,actor_kind,actor_id,event_type,target_id,target_type,metadata) VALUES (?,?,'USER',?,?,?,?,?::jsonb)",id,Timestamp.from(time),actor,event.name(),target,event==Event.RACE_REGISTRATION_SETTINGS_UPDATED?"RACE_EVENT":"REGISTRATION",metadata);
    }
    public void appendSimulation(UUID id,Instant at,UUID actor,Event event,UUID target){jdbc.update("INSERT INTO audit_event(id,occurred_at,actor_kind,actor_id,event_type,target_id,target_type) VALUES (?,?,?,?,?,?,'SIMULATION')",id,Timestamp.from(at),actor==null?"SYSTEM":"USER",actor,event.name(),target);}
    public void appendRaceInstructions(UUID id,Instant time,UUID actor,UUID target,UUID stage){
        String metadata=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(java.util.Map.of("stageId",stage));
        jdbc.update("INSERT INTO audit_event(id,occurred_at,actor_kind,actor_id,event_type,target_id,target_type,metadata) VALUES (?,?,'USER',?,'RACE_EVENT_STAGE_INSTRUCTIONS_UPDATED',?,'RACE_EVENT',?::jsonb)",id,Timestamp.from(time),actor,target,metadata);
    }
    public void appendRaceLifecycle(UUID id,Instant time,UUID actor,Event event,UUID target,UUID transition,long version){
        String metadata=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(java.util.Map.of("transitionId",transition,"version",version));
        jdbc.update("INSERT INTO audit_event(id,occurred_at,actor_kind,actor_id,event_type,target_id,target_type,metadata) VALUES (?,?,?,?,?,?,'RACE_EVENT',?::jsonb)",id,Timestamp.from(time),actor==null?"SYSTEM":"USER",actor,event.name(),target,metadata);
    }
    public void appendRace(UUID id,Instant time,UUID actor,Event event,UUID target,UUID source,long version){
        String metadata=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(java.util.Map.of("sourceTemplateId",source,"sourceTemplateVersion",version));
        jdbc.update("INSERT INTO audit_event(id,occurred_at,actor_kind,actor_id,event_type,target_id,target_type,metadata) VALUES (?,?,'USER',?,?,?,'RACE_EVENT',?::jsonb)",id,Timestamp.from(time),actor,event.name(),target,metadata);
    }
    public void appendTemplate(UUID id,Instant time,UUID actor,Event event,UUID target,boolean stage){
        jdbc.update("INSERT INTO audit_event(id,occurred_at,actor_kind,actor_id,event_type,target_id,target_type) VALUES (?,?,'USER',?,?,?,?)",id,Timestamp.from(time),actor,event.name(),target,stage?"TEMPLATE_STAGE":"TEMPLATE");
    }
    public void appendTemplateCopy(UUID id,Instant time,UUID actor,UUID target,UUID source,UUID template,boolean stage){
        String metadata=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(
                java.util.Map.of("sourceId",source,"templateId",template));
        jdbc.update("INSERT INTO audit_event(id,occurred_at,actor_kind,actor_id,event_type,target_id,target_type,metadata) VALUES (?,?,'USER',?,?,?,?,?::jsonb)",
                id,Timestamp.from(time),actor,stage?"TEMPLATE_STAGE_DUPLICATED":"TEMPLATE_DUPLICATED",target,stage?"TEMPLATE_STAGE":"TEMPLATE",metadata);
    }
    public void append(UUID id, Instant time, UUID actor, Event type, UUID target) {
        jdbc.update("INSERT INTO audit_event(id,occurred_at,actor_kind,actor_id,event_type,target_id) VALUES (?,?,'USER',?,?,?)",
                id, Timestamp.from(time), actor, type.name(), target);
    }
    public void appendAppleRevocation(UUID id,Instant time,UUID job){
        jdbc.update("INSERT INTO audit_event(id,occurred_at,actor_kind,event_type,target_id,target_type) VALUES (?,?,'SYSTEM','APPLE_REVOCATION_SUCCEEDED',?,'APPLE_REVOCATION')",id,Timestamp.from(time),job);
    }
    public List<Entry> find(UUID actor, UUID target, Event event, Instant from, Instant until, int page, int size) {
        var sql = new StringBuilder("SELECT * FROM audit_event WHERE true");
        var values = new ArrayList<Object>();
        if (actor != null) { sql.append(" AND actor_id=?"); values.add(actor); }
        if (target != null) { sql.append(" AND target_id=?"); values.add(target); }
        if (event != null) { sql.append(" AND event_type=?"); values.add(event.name()); }
        if (from != null) { sql.append(" AND occurred_at>=?"); values.add(Timestamp.from(from)); }
        if (until != null) { sql.append(" AND occurred_at<?"); values.add(Timestamp.from(until)); }
        sql.append(" ORDER BY occurred_at DESC,id DESC LIMIT ? OFFSET ?");
        values.add(size + 1); values.add((long) page * size);
        return jdbc.query(sql.toString(), (rs, row) -> new Entry(rs.getObject("id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant(), rs.getString("actor_kind"),
                rs.getObject("actor_id", UUID.class), rs.getString("event_type"), rs.getObject("target_id", UUID.class),
                rs.getString("target_type"),rs.getObject("previous_captain_id",UUID.class),rs.getObject("new_captain_id",UUID.class),
                rs.getObject("crew_id",UUID.class),rs.getObject("transfer_id",UUID.class),rs.getString("resolution_reason")),
                values.toArray());
    }
}
