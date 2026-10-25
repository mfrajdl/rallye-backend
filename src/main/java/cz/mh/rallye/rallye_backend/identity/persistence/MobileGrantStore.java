package cz.mh.rallye.rallye_backend.identity.persistence;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class MobileGrantStore {
    private final JdbcTemplate jdbc;
    public MobileGrantStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void issue(UUID account,UUID attempt,String challenge,String tokenHash,Instant now) {
        jdbc.update("""
            INSERT INTO external_auth_grant(id,account_id,kind,purpose,token_hash,pkce_challenge,created_at,expires_at,attempt_id)
            VALUES (?,?,'MOBILE','LOGIN',?,?,?,?,?)
            """,UUID.randomUUID(),account,tokenHash,challenge,Timestamp.from(now),Timestamp.from(now.plusSeconds(60)),attempt);
    }
    public record Grant(UUID id,UUID account,UUID attempt,String challenge,Instant createdAt,Instant expiresAt,Instant consumedAt) {
        @Override public String toString(){return "MobileGrant[redacted]";}
    }
    /** Initial lookup uses lock=false; control/account locks must precede the locking reread. */
    public Grant find(String hash,boolean lock) {
        var rows=jdbc.query("SELECT * FROM external_auth_grant WHERE token_hash=? AND kind='MOBILE' AND purpose='LOGIN'"+(lock?" FOR UPDATE":""),
            (r,n)->new Grant(r.getObject("id",UUID.class),r.getObject("account_id",UUID.class),r.getObject("attempt_id",UUID.class),
                r.getString("pkce_challenge"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("expires_at").toInstant(),
                r.getTimestamp("consumed_at")==null?null:r.getTimestamp("consumed_at").toInstant()),hash);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public boolean boundAttempt(Grant grant, String provider) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM mobile_login_attempt a JOIN external_auth_flow f ON f.id=a.flow_id
             WHERE a.id=? AND a.provider=? AND a.launch_consumed_at IS NOT NULL AND a.pkce_challenge=?
               AND f.provider=a.provider AND f.purpose='LOGIN' AND f.account_id IS NULL AND f.session_id IS NULL
               AND f.mobile_challenge=a.pkce_challenge AND f.remember_me=a.remember_me AND f.return_uri=a.return_uri
               AND f.expires_at=a.expires_at AND f.consumed_at IS NOT NULL)
            """,Boolean.class,grant.attempt(),provider,grant.challenge()));
    }
    public boolean rememberMe(UUID attempt) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT remember_me FROM mobile_login_attempt WHERE id=?",Boolean.class,attempt));
    }
    public boolean consume(UUID id,Instant now) {
        return jdbc.update("UPDATE external_auth_grant SET consumed_at=? WHERE id=? AND consumed_at IS NULL AND created_at<=? AND expires_at>?",
            Timestamp.from(now),id,Timestamp.from(now),Timestamp.from(now))==1;
    }

}
