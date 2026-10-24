package cz.mh.rallye.rallye_backend.identity.persistence;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class ExternalFlowStore {
    public record Flow(UUID id,String provider,String purpose,UUID account,UUID session,String nonceHash,String correlationHash,
                       String callbackUri,String returnUri,boolean rememberMe,String challenge,Instant expiresAt) {
        @Override public String toString(){return "ExternalFlow[redacted]";}
    }
    private final JdbcTemplate jdbc;
    public ExternalFlowStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void insert(Flow f,String stateHash,Instant now) {
        jdbc.update("""
            INSERT INTO external_auth_flow(id,provider,purpose,account_id,session_id,state_hash,nonce_hash,correlation_hash,
                callback_uri,return_uri,remember_me,mobile_challenge,created_at,expires_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,f.id(),f.provider(),f.purpose(),f.account(),f.session(),stateHash,f.nonceHash(),f.correlationHash(),
            f.callbackUri(),f.returnUri(),f.rememberMe(),f.challenge(),Timestamp.from(now),Timestamp.from(f.expiresAt()));
    }
    public Flow find(String hash) {
        var rows=jdbc.query("""
            SELECT * FROM external_auth_flow WHERE state_hash=? AND consumed_at IS NULL
            """,(r,n)->new Flow(r.getObject("id",UUID.class),r.getString("provider"),r.getString("purpose"),
            r.getObject("account_id",UUID.class),r.getObject("session_id",UUID.class),r.getString("nonce_hash"),
            r.getString("correlation_hash"),r.getString("callback_uri"),r.getString("return_uri"),r.getBoolean("remember_me"),
            r.getString("mobile_challenge"),r.getTimestamp("expires_at").toInstant()),hash);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public boolean consume(UUID id,Instant now){return jdbc.update("UPDATE external_auth_flow SET consumed_at=? WHERE id=? AND consumed_at IS NULL AND expires_at>?",Timestamp.from(now),id,Timestamp.from(now))==1;}
}
