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
}
