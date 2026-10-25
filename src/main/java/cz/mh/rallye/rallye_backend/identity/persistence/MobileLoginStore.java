package cz.mh.rallye.rallye_backend.identity.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class MobileLoginStore {
    public record Attempt(UUID id,String provider,String challenge,boolean rememberMe,String returnId,
            String returnUri,String profileUri,String fallbackUri,Instant createdAt,Instant expiresAt) {}
    private final JdbcTemplate jdbc;
    public MobileLoginStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void insert(Attempt a,String ticketHash) {
        jdbc.update("""
            INSERT INTO mobile_login_attempt(id,provider,pkce_challenge,remember_me,return_id,return_uri,profile_uri,
                fallback_uri,ticket_hash,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """,a.id(),a.provider(),a.challenge(),a.rememberMe(),a.returnId(),a.returnUri(),a.profileUri(),a.fallbackUri(),
            ticketHash,Timestamp.from(a.createdAt()),Timestamp.from(a.expiresAt()));
    }
    public Attempt lock(String hash,Instant now) {
        var rows=jdbc.query("""
            SELECT * FROM mobile_login_attempt WHERE ticket_hash=? AND launch_consumed_at IS NULL AND expires_at>?
            FOR UPDATE
            """,(r,n)->new Attempt(r.getObject("id",UUID.class),r.getString("provider"),r.getString("pkce_challenge"),
            r.getBoolean("remember_me"),r.getString("return_id"),r.getString("return_uri"),r.getString("profile_uri"),
            r.getString("fallback_uri"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant()),hash,Timestamp.from(now));
        return rows.isEmpty()?null:rows.getFirst();
    }
    public Attempt byFlow(UUID flow) {
        var rows=jdbc.query("SELECT * FROM mobile_login_attempt WHERE flow_id=? AND launch_consumed_at IS NOT NULL",
            (r,n)->new Attempt(r.getObject("id",UUID.class),r.getString("provider"),r.getString("pkce_challenge"),
                r.getBoolean("remember_me"),r.getString("return_id"),r.getString("return_uri"),r.getString("profile_uri"),
                r.getString("fallback_uri"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant()),flow);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public boolean consume(UUID id,UUID flow,Instant now) {
        return jdbc.update("""
            UPDATE mobile_login_attempt SET launch_consumed_at=?,flow_id=?
            WHERE id=? AND launch_consumed_at IS NULL AND expires_at>?
            """,Timestamp.from(now),flow,id,Timestamp.from(now))==1;
    }
}
