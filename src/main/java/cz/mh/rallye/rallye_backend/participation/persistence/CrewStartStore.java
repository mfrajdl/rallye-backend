package cz.mh.rallye.rallye_backend.participation.persistence;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import cz.mh.rallye.rallye_backend.participation.application.CrewStartSecrets.DeviceHash;

/** Infrastructure primitives only. Caller must lock control/accounts/crew/event before registration,
 * then run/code/device. No authorization, audit, lifecycle decisions, or complete start operation here.
 * Deferred constraints validate the complete graph when the caller commits.
 */
@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class CrewStartStore {
    public record Registration(UUID id,UUID eventId,UUID currentRunId,long deviceGeneration,long version,Instant startedAt){}
    public record Run(UUID id,UUID registrationId,UUID eventId,long ordinal,String status,long activationGeneration,Instant startedAt,Instant completedAt,Instant endedAt,long version){}
    public record Progress(UUID runId,UUID eventId,UUID stageId,int sequence,String status,Instant activatedAt,Instant completedAt,Instant endedAt,boolean codeSatisfied,boolean gpsSatisfied){}
    public record Code(UUID id,UUID registrationId,String kind,long generation,String state,String value,Instant issuedAt,UUID issuedBy,Instant usedAt,UUID usedDeviceId,long points,String reason,long version){
        @Override public String toString(){return "RaceStartCode["+id+",redacted]";}
    }
    public record Device(UUID id,UUID registrationId,long generation,DeviceHash hash,String state,Instant createdAt,Instant revokedAt,UUID codeId,String label,String reason,UUID revokedBy){
        @Override public String toString(){return "RaceDevice["+id+",redacted]";}
    }
    public record Command(UUID id,UUID registrationId,UUID actor,UUID key,String operation,int fingerprintVersion,String fingerprint,UUID codeId,UUID secondCodeId,UUID deviceId,Instant executedAt,int responseStatus,String resultJson){
        @Override public String toString(){return "RaceStartCommand["+id+",redacted]";}
    }
    public record Adjustment(UUID id,UUID registrationId,UUID codeId,long points,Instant appliedAt,String reason){}
    private final JdbcTemplate jdbc;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public CrewStartStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static Timestamp ts(Instant at){return at==null?null:Timestamp.from(at);}
    private static Instant at(ResultSet r,String field)throws SQLException{var value=r.getTimestamp(field);return value==null?null:value.toInstant();}
    private static UUID id(ResultSet r,String field)throws SQLException{return r.getObject(field,UUID.class);}
    private static <T>T first(List<T> rows){return rows.isEmpty()?null:rows.getFirst();}
    public Registration registration(UUID id,boolean lock){return first(jdbc.query("SELECT * FROM crew_registration WHERE id=?"+(lock?" FOR UPDATE":""),(r,n)->new Registration(id(r,"id"),id(r,"race_event_id"),id(r,"current_run_id"),r.getLong("device_generation"),r.getLong("version"),at(r,"individual_started_at")),id));}
    /** Primitive binding: orchestration must insert run/progress/code/device/receipt in the same transaction. */
    public boolean bindInitialRun(UUID registration,long expected,UUID run,UUID captain,Instant now){return jdbc.update("""
        UPDATE crew_registration SET current_run_id=?,device_generation=1,individual_started_at=?,
        historical_captain_account_id=?,started_members_text=members_text,started_children_under_15=children_under_15,
        version=version+1,updated_at=? WHERE id=? AND version=? AND individual_started_at IS NULL
        """,run,ts(now),captain,ts(now),registration,expected)==1;}
    public boolean advanceDeviceGeneration(UUID registration,long expected,long oldGeneration,Instant now){return jdbc.update("UPDATE crew_registration SET device_generation=device_generation+1,version=version+1,updated_at=? WHERE id=? AND version=? AND device_generation=? AND individual_started_at IS NOT NULL",ts(now),registration,expected,oldGeneration)==1;}
    public boolean touchRegistration(UUID registration,long expected,Instant now){return jdbc.update("UPDATE crew_registration SET version=version+1,updated_at=? WHERE id=? AND version=?",ts(now),registration,expected)==1;}
    private static Run run(ResultSet r,int n)throws SQLException{return new Run(id(r,"id"),id(r,"registration_id"),id(r,"race_event_id"),r.getLong("ordinal"),r.getString("status"),r.getLong("activation_generation"),at(r,"started_at"),at(r,"completed_at"),at(r,"participation_ended_at"),r.getLong("version"));}
    public Run run(UUID registration,UUID run,boolean lock){return first(jdbc.query("SELECT * FROM race_run WHERE registration_id=? AND id=?"+(lock?" FOR UPDATE":""),CrewStartStore::run,registration,run));}
    public void insertRun(Run r){jdbc.update("INSERT INTO race_run(id,registration_id,race_event_id,ordinal,status,activation_generation,started_at,completed_at,participation_ended_at,version) VALUES (?,?,?,?,?,?,?,?,?,?)",r.id(),r.registrationId(),r.eventId(),r.ordinal(),r.status(),r.activationGeneration(),ts(r.startedAt()),ts(r.completedAt()),ts(r.endedAt()),r.version());}
    public boolean touchRun(UUID registration,UUID run,long expected){return jdbc.update("UPDATE race_run SET version=version+1 WHERE registration_id=? AND id=? AND version=?",registration,run,expected)==1;}
    public boolean completeRun(UUID registration,UUID run,long expected,Instant now){return jdbc.update("UPDATE race_run SET status='COMPLETED',completed_at=?,participation_ended_at=?,version=version+1 WHERE registration_id=? AND id=? AND version=? AND status='RUNNING'",ts(now),ts(now),registration,run,expected)==1;}
    public void insertProgress(Progress p){jdbc.update("INSERT INTO race_stage_progress(run_id,race_event_id,event_stage_id,sequence_no,status,activated_at,completed_at,ended_at,code_satisfied,gps_satisfied) VALUES (?,?,?,?,?,?,?,?,?,?)",p.runId(),p.eventId(),p.stageId(),p.sequence(),p.status(),ts(p.activatedAt()),ts(p.completedAt()),ts(p.endedAt()),p.codeSatisfied(),p.gpsSatisfied());}
    public List<Progress> progress(UUID run){return jdbc.query("SELECT * FROM race_stage_progress WHERE run_id=? ORDER BY sequence_no",(r,n)->new Progress(id(r,"run_id"),id(r,"race_event_id"),id(r,"event_stage_id"),r.getInt("sequence_no"),r.getString("status"),at(r,"activated_at"),at(r,"completed_at"),at(r,"ended_at"),r.getBoolean("code_satisfied"),r.getBoolean("gps_satisfied")),run);}
    public boolean updateProgress(Progress p,String expectedStatus){return jdbc.update("UPDATE race_stage_progress SET status=?,activated_at=?,completed_at=?,ended_at=?,code_satisfied=?,gps_satisfied=? WHERE run_id=? AND event_stage_id=? AND status=?",p.status(),ts(p.activatedAt()),ts(p.completedAt()),ts(p.endedAt()),p.codeSatisfied(),p.gpsSatisfied(),p.runId(),p.stageId(),expectedStatus)==1;}
    private static Code code(ResultSet r,int n)throws SQLException{return new Code(id(r,"id"),id(r,"registration_id"),r.getString("kind"),r.getLong("generation"),r.getString("state"),r.getString("code"),at(r,"issued_at"),id(r,"issued_by_account_id"),at(r,"used_at"),id(r,"used_device_id"),r.getLong("points_adjustment"),r.getString("reason"),r.getLong("version"));}
    public Code code(UUID registration,UUID code,boolean lock){return first(jdbc.query("SELECT * FROM race_start_code WHERE registration_id=? AND id=?"+(lock?" FOR UPDATE":""),CrewStartStore::code,registration,code));}
    /** Exact plaintext comparison. Normalization belongs to CrewStartSecrets before invoking the store. */
    public Code availableCode(UUID registration,String value){return first(jdbc.query("SELECT * FROM race_start_code WHERE registration_id=? AND code=? AND state='AVAILABLE'",CrewStartStore::code,registration,value));}
    public boolean codeValueExists(UUID registration,String value){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM race_start_code WHERE registration_id=? AND code=?)",Boolean.class,registration,value));}
    /** Caller must hold registration FOR UPDATE before assigning/inserting the next ordinal. */
    public long nextCodeGeneration(UUID registration){return jdbc.queryForObject("SELECT coalesce(max(generation),0)+1 FROM race_start_code WHERE registration_id=?",Long.class,registration);}
    public List<Code> codes(UUID registration,int page,int size){if(page<0||page>10000||size<1||size>100)throw new IllegalArgumentException("Invalid code page");return jdbc.query("SELECT * FROM race_start_code WHERE registration_id=? ORDER BY issued_at DESC,id DESC LIMIT ? OFFSET ?",CrewStartStore::code,registration,size+1,(long)page*size);}
    public void insertCode(Code c){if(!"AVAILABLE".equals(c.state())||c.version()!=0||c.usedAt()!=null||c.usedDeviceId()!=null)throw new IllegalArgumentException("New code must be available");jdbc.update("INSERT INTO race_start_code(id,registration_id,kind,generation,code,issued_at,issued_by_account_id,points_adjustment,reason) VALUES (?,?,?,?,?,?,?,?,?)",c.id(),c.registrationId(),c.kind(),c.generation(),c.value(),ts(c.issuedAt()),c.issuedBy(),c.points(),c.reason());}
    public boolean consumeCode(UUID registration,UUID code,long expected,UUID device,Instant now){return jdbc.update("UPDATE race_start_code SET state='USED',used_at=?,used_device_id=?,version=version+1 WHERE registration_id=? AND id=? AND version=? AND state='AVAILABLE'",ts(now),device,registration,code,expected)==1;}

    private static Device device(ResultSet r,int n)throws SQLException{return new Device(id(r,"id"),id(r,"registration_id"),r.getLong("generation"),new DeviceHash(r.getString("credential_hash")),r.getString("state"),at(r,"created_at"),at(r,"revoked_at"),id(r,"created_by_code_id"),r.getString("label"),r.getString("revocation_reason"),id(r,"revoked_by_account_id"));}
    public Device device(UUID registration,UUID device,boolean lock){return first(jdbc.query("SELECT * FROM race_device_authorization WHERE registration_id=? AND id=?"+(lock?" FOR UPDATE":""),CrewStartStore::device,registration,device));}
    public boolean deviceHashExists(DeviceHash hash){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM race_device_authorization WHERE credential_hash=?)",Boolean.class,hash.value()));}
    public Device currentDevice(UUID registration,boolean lock){return first(jdbc.query("SELECT * FROM race_device_authorization WHERE registration_id=? AND state='ACTIVE'"+(lock?" FOR UPDATE":""),CrewStartStore::device,registration));}
    public Device activeDevice(DeviceHash hash){return first(jdbc.query("SELECT d.* FROM race_device_authorization d JOIN crew_registration r ON r.id=d.registration_id WHERE d.credential_hash=? AND d.state='ACTIVE' AND d.generation=r.device_generation",CrewStartStore::device,hash.value()));}
    public void insertDevice(Device d){if(!"ACTIVE".equals(d.state())||d.revokedAt()!=null||d.reason()!=null||d.revokedBy()!=null)throw new IllegalArgumentException("New device must be active");jdbc.update("INSERT INTO race_device_authorization(id,registration_id,generation,credential_hash,state,created_at,created_by_code_id,label) VALUES (?,?,?,?,'ACTIVE',?,?,?)",d.id(),d.registrationId(),d.generation(),d.hash().value(),ts(d.createdAt()),d.codeId(),d.label());}
    public boolean revokeDevice(UUID registration,UUID device,long generation,Instant now,UUID actor,String reason){return jdbc.update("UPDATE race_device_authorization SET state='REVOKED',revoked_at=?,revoked_by_account_id=?,revocation_reason=? WHERE registration_id=? AND id=? AND generation=? AND state='ACTIVE'",ts(now),actor,reason,registration,device,generation)==1;}
    private static Command command(ResultSet r,int n)throws SQLException{return new Command(id(r,"id"),id(r,"registration_id"),id(r,"actor_account_id"),id(r,"command_key"),r.getString("operation"),r.getInt("fingerprint_version"),r.getString("request_fingerprint"),id(r,"code_id"),id(r,"second_code_id"),id(r,"device_id"),at(r,"executed_at"),r.getInt("response_status"),r.getString("result"));}
    public Command command(UUID actor,UUID key){return first(jdbc.query("SELECT * FROM race_start_command WHERE actor_account_id=? AND command_key=?",CrewStartStore::command,actor,key));}
    public void insertCommand(Command c){JsonNode result=JSON.readTree(c.resultJson());if(!result.isObject())throw new IllegalArgumentException("Invalid command result");safeResult(result);jdbc.update("INSERT INTO race_start_command(id,registration_id,actor_account_id,command_key,operation,fingerprint_version,request_fingerprint,code_id,second_code_id,device_id,executed_at,response_status,result) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)",c.id(),c.registrationId(),c.actor(),c.key(),c.operation(),c.fingerprintVersion(),c.fingerprint(),c.codeId(),c.secondCodeId(),c.deviceId(),ts(c.executedAt()),c.responseStatus(),c.resultJson());}
    private static void safeResult(JsonNode node){if(node.isObject()){for(String name:node.propertyNames()){if(Set.of("code","devicesecret","token","credential","password","authorization","cookie").contains(name.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Secret field is not allowed in command result");safeResult(node.get(name));}}else if(node.isArray())for(var value:node)safeResult(value);}
    public void insertAdjustment(Adjustment a){jdbc.update("INSERT INTO race_points_adjustment(id,registration_id,source_code_id,points,applied_at,reason) VALUES (?,?,?,?,?,?)",a.id(),a.registrationId(),a.codeId(),a.points(),ts(a.appliedAt()),a.reason());}
    public List<Adjustment> adjustments(UUID registration){return jdbc.query("SELECT * FROM race_points_adjustment WHERE registration_id=? ORDER BY applied_at,id",(r,n)->new Adjustment(id(r,"id"),id(r,"registration_id"),id(r,"source_code_id"),r.getLong("points"),at(r,"applied_at"),r.getString("reason")),registration);}
}
