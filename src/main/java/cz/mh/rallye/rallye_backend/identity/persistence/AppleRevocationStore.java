package cz.mh.rallye.rallye_backend.identity.persistence;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import cz.mh.rallye.rallye_backend.identity.application.AppleTokenVault;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class AppleRevocationStore {
    public enum CaseStatus { ACCOUNT_BOUND, REGISTRATION_PENDING, REVOCATION_REQUIRED, BLOCKED, COMPLETED }
    public enum Status { PENDING, PROCESSING, RETRY_WAIT, BLOCKED, SUCCEEDED }
    public enum Reason { ACCOUNT_CANCELLATION, REGISTRATION_ABANDONED }
    public enum Error { NETWORK, RATE_LIMIT, PROVIDER_SERVER, CLIENT_CONFIG, MATERIAL, KEY, INTEGRITY, RELATION, PROVIDER_REJECTED, UNEXPECTED }
    public record Authorization(UUID id,String issuer,String subject,String digest,String clientId,UUID generation,UUID flowId,UUID account,UUID registration,CaseStatus status,long version) {
        @Override public String toString(){return "AppleAuthorization[redacted]";}
    }
    public record Job(UUID id,UUID caseId,UUID credential,Reason reason,Status status,int attempts,Instant nextAttempt,UUID lease,Instant leaseUntil,Error error,Integer httpStatus,long version) {
        @Override public String toString(){return "AppleRevocationJob[redacted]";}
    }
    private final JdbcTemplate jdbc;
    public AppleRevocationStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void insert(Authorization c,Instant now) {
        jdbc.update("""
          INSERT INTO apple_authorization_case(id,issuer,subject,identity_digest,client_id,generation,origin_flow_id,account_id,registration_id,origin_account_id,origin_registration_id,status,created_at,updated_at)
          VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
          """,c.id(),c.issuer(),c.subject(),c.digest(),c.clientId(),c.generation(),c.flowId(),c.account(),c.registration(),c.account(),c.registration(),c.status().name(),at(now),at(now));
    }
    public Authorization find(UUID id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM apple_authorization_case WHERE id=?"+(lock?" FOR UPDATE":""),(r,n)->new Authorization(r.getObject("id",UUID.class),r.getString("issuer"),r.getString("subject"),r.getString("identity_digest"),r.getString("client_id"),r.getObject("generation",UUID.class),r.getObject("origin_flow_id",UUID.class),r.getObject("account_id",UUID.class),r.getObject("registration_id",UUID.class),CaseStatus.valueOf(r.getString("status")),r.getLong("version")),id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public void material(UUID id,Authorization c,AppleTokenVault.Sealed sealed,Instant now) {
        // Do not let the new repository accidentally use the approved old-writer NULL-case exception.
        if(id==null || c==null || c.id()==null || sealed==null || now==null)
            throw new IllegalArgumentException("Apple material requires a complete case binding");
        jdbc.update("INSERT INTO apple_revocation_credential(id,case_id,account_id,registration_id,client_id,key_id,iv,ciphertext,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
            id,c.id(),c.account(),c.registration(),c.clientId(),sealed.keyId(),sealed.iv(),sealed.ciphertext(),at(now));
    }
    public boolean bind(Authorization c,UUID account,Instant now) {
        int count=jdbc.update("UPDATE apple_authorization_case SET account_id=?,origin_account_id=?,registration_id=NULL,status='ACCOUNT_BOUND',version=version+1,updated_at=? WHERE id=? AND version=? AND status='REGISTRATION_PENDING'",account,account,at(now),c.id(),c.version());
        if(count==1)jdbc.update("UPDATE apple_revocation_credential SET account_id=?,registration_id=NULL WHERE case_id=?",account,c.id());return count==1;
    }
    public int materialCount(UUID id){return jdbc.queryForObject("SELECT count(*) FROM apple_revocation_credential WHERE case_id=?",Integer.class,id);}
    public boolean requireRevocation(Authorization c,Instant now) {
        int count=jdbc.update("UPDATE apple_authorization_case SET status='REVOCATION_REQUIRED',account_id=NULL,registration_id=NULL,version=version+1,updated_at=? WHERE id=? AND version=? AND status IN ('ACCOUNT_BOUND','REGISTRATION_PENDING')",at(now),c.id(),c.version());
        if(count==1){
            jdbc.update("UPDATE apple_revocation_credential SET account_id=NULL,registration_id=NULL WHERE case_id=?",c.id());
            jdbc.update("INSERT INTO apple_revocation_barrier(identity_digest,created_at) VALUES (?,?) ON CONFLICT(identity_digest) DO NOTHING",c.digest(),at(now));
        }
        return count==1;
    }
    public void enqueue(UUID caseId,Reason reason,Instant now) {
        var materials=jdbc.queryForList("SELECT id FROM apple_revocation_credential WHERE case_id=? ORDER BY id",UUID.class,caseId);
        for(UUID material:materials)jdbc.update("""
            INSERT INTO apple_revocation_job(id,case_id,credential_id,reason,status,next_attempt_at,created_at,updated_at)
            VALUES (?,?,?,?,'PENDING',?,?,?) ON CONFLICT(credential_id) DO NOTHING
            """,UUID.randomUUID(),caseId,material,reason.name(),at(now),at(now),at(now));
    }
    public List<Job> jobs(UUID caseId){return jdbc.query("SELECT * FROM apple_revocation_job WHERE case_id=? ORDER BY created_at,id",this::mapJob,caseId);}
    public Job job(UUID id){var rows=jdbc.query("SELECT * FROM apple_revocation_job WHERE id=?",this::mapJob,id);return rows.isEmpty()?null:rows.getFirst();}
    public Job claim(Instant now,Instant until,UUID lease) {
        var rows=jdbc.query("""
          WITH picked AS (
            SELECT id FROM apple_revocation_job WHERE (status IN ('PENDING','RETRY_WAIT') AND next_attempt_at<=?)
                OR (status='PROCESSING' AND lease_until<=?) ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
          ) UPDATE apple_revocation_job j SET status='PROCESSING',lease_id=?,lease_until=?,attempts=attempts+1,
              last_error=NULL,last_http_status=NULL,version=version+1,updated_at=? FROM picked WHERE j.id=picked.id RETURNING j.*
          """,this::mapJob,at(now),at(now),lease,at(until),at(now));
        return rows.isEmpty()?null:rows.getFirst();
    }
    public boolean failure(UUID id,UUID lease,long version,Error error,Integer http,Instant now,Instant next,boolean blocked) {
        return jdbc.update("""
          UPDATE apple_revocation_job SET status=?,last_error=?,last_http_status=?,next_attempt_at=?,lease_id=NULL,lease_until=NULL,version=version+1,updated_at=?
          WHERE id=? AND status='PROCESSING' AND lease_id=? AND version=? AND lease_until>?
          """,blocked?"BLOCKED":"RETRY_WAIT",error.name(),http,at(next),at(now),id,lease,version,at(now))==1;
    }
    public boolean retry(UUID id,long version,Instant now) {
        return jdbc.update("UPDATE apple_revocation_job SET status='PENDING',last_error=NULL,last_http_status=NULL,next_attempt_at=?,version=version+1,updated_at=? WHERE id=? AND status='BLOCKED' AND version=?",at(now),at(now),id,version)==1;
    }
    public record Material(String clientId,AppleTokenVault.Sealed sealed) {
        @Override public String toString(){return "AppleRevocationMaterial[redacted]";}
    }
    public Material material(Job job){
        var rows=jdbc.query("SELECT client_id,key_id,iv,ciphertext FROM apple_revocation_credential WHERE id=? AND case_id=?",(r,n)->new Material(r.getString("client_id"),new AppleTokenVault.Sealed(r.getString("key_id"),r.getBytes("iv"),r.getBytes("ciphertext"))),job.credential(),job.caseId());
        return rows.isEmpty()?null:rows.getFirst();
    }
    public boolean succeed(Job job,Instant now){
        return jdbc.update("""
            UPDATE apple_revocation_job SET status='SUCCEEDED',completed_at=?,lease_id=NULL,lease_until=NULL,
                last_error=NULL,last_http_status=NULL,version=version+1,updated_at=?
            WHERE id=? AND case_id=? AND credential_id=? AND status='PROCESSING' AND lease_id=? AND version=? AND lease_until>?
            """,at(now),at(now),job.id(),job.caseId(),job.credential(),job.lease(),job.version(),at(now))==1;
    }
    public void removeSucceededMaterial(Job job){
        if(jdbc.update("DELETE FROM apple_revocation_credential WHERE id=? AND case_id=?",job.credential(),job.caseId())!=1)throw new IllegalStateException("Apple completed material is missing");
    }
    public void finishCase(Authorization c,Instant now){
        jdbc.update("""
            UPDATE apple_authorization_case SET status='COMPLETED',version=version+1,updated_at=?
            WHERE id=? AND status IN ('REVOCATION_REQUIRED','BLOCKED')
                AND NOT EXISTS(SELECT 1 FROM apple_revocation_job WHERE case_id=? AND status<>'SUCCEEDED')
                AND NOT EXISTS(SELECT 1 FROM apple_revocation_credential WHERE case_id=?)
            """,at(now),c.id(),c.id(),c.id());
        // Include registration cases captured behind the barrier, not only already-enqueued work.
        jdbc.update("DELETE FROM apple_revocation_barrier b WHERE identity_digest=? AND NOT EXISTS(SELECT 1 FROM apple_authorization_case c WHERE c.identity_digest=b.identity_digest AND c.status<>'COMPLETED')",c.digest());
    }
    public void pruneCompleted(Instant cutoff){
        jdbc.update("""
            DELETE FROM apple_revocation_job j WHERE j.status='SUCCEEDED' AND j.completed_at<?
                AND EXISTS(SELECT 1 FROM apple_authorization_case c WHERE c.id=j.case_id AND c.status='COMPLETED')
                AND NOT EXISTS(SELECT 1 FROM apple_revocation_job other WHERE other.case_id=j.case_id AND (other.status<>'SUCCEEDED' OR other.completed_at>=?))
            """,at(cutoff),at(cutoff));
        jdbc.update("DELETE FROM apple_authorization_case c WHERE status='COMPLETED' AND updated_at<? AND NOT EXISTS(SELECT 1 FROM apple_revocation_job WHERE case_id=c.id)",at(cutoff));
    }
    private Job mapJob(ResultSet r,int ignored)throws SQLException {
        String error=r.getString("last_error");Integer http=r.getObject("last_http_status",Integer.class);
        return new Job(r.getObject("id",UUID.class),r.getObject("case_id",UUID.class),r.getObject("credential_id",UUID.class),Reason.valueOf(r.getString("reason")),Status.valueOf(r.getString("status")),r.getInt("attempts"),r.getTimestamp("next_attempt_at").toInstant(),r.getObject("lease_id",UUID.class),r.getTimestamp("lease_until")==null?null:r.getTimestamp("lease_until").toInstant(),error==null?null:Error.valueOf(error),http,r.getLong("version"));
    }
    private static Timestamp at(Instant instant){return Timestamp.from(instant);}
}
