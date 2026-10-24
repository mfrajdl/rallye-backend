package cz.mh.rallye.rallye_backend.identity.persistence;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class ExternalAccountStore {
    public record Registration(UUID id,String provider,String issuer,String subject,String email,boolean verified,String name,
            boolean rememberMe,String returnUri,Instant expiresAt,Instant consumedAt) {
        @Override public String toString(){return "ExternalRegistration[redacted]";}
    }
    private final JdbcTemplate jdbc;
    public ExternalAccountStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void lock(){jdbc.queryForObject("SELECT id FROM admin_control WHERE id=1 FOR UPDATE",Integer.class);}
    public void purgeExpired(Instant now){jdbc.update("DELETE FROM external_registration WHERE expires_at<=?",at(now));}
    public UUID account(String provider,String issuer,String subject) {
        var ids=jdbc.queryForList("SELECT account_id FROM user_identity WHERE provider=? AND issuer=? AND subject=?",UUID.class,provider,issuer,subject);
        return ids.isEmpty()?null:ids.getFirst();
    }
    public void registration(Registration r,String hash,Instant now) {
        jdbc.update("""
            INSERT INTO external_registration(id,provider,issuer,subject,proposed_email,email_verified,display_name,remember_me,
                return_uri,token_hash,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """,r.id(),r.provider(),r.issuer(),r.subject(),r.email(),r.verified(),r.name(),r.rememberMe(),r.returnUri(),hash,at(now),at(r.expiresAt()));
    }
    public Registration registration(String hash) {
        var rows=jdbc.query("SELECT * FROM external_registration WHERE token_hash=? FOR UPDATE",(r,n)->new Registration(r.getObject("id",UUID.class),
            r.getString("provider"),r.getString("issuer"),r.getString("subject"),r.getString("proposed_email"),r.getBoolean("email_verified"),
            r.getString("display_name"),r.getBoolean("remember_me"),r.getString("return_uri"),r.getTimestamp("expires_at").toInstant(),r.getTimestamp("consumed_at")==null?null:r.getTimestamp("consumed_at").toInstant()),hash);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public void consume(UUID id,Instant now){jdbc.update("UPDATE external_registration SET consumed_at=?,proposed_email=NULL,display_name=NULL WHERE id=?",at(now),id);}
    public boolean create(UUID id,Registration r,String email,String name,boolean verified,Instant now) {
        int inserted=jdbc.update("""
            INSERT INTO user_account(id,email,normalized_email,display_name,status,created_at,updated_at,email_verified_at)
            VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(normalized_email) DO NOTHING
            """,id,email,email,name,verified?"ACTIVE":"PENDING_EMAIL",at(now),at(now),verified?at(now):null);
        if(inserted==0)return false;
        jdbc.update("INSERT INTO user_identity(id,account_id,provider,issuer,subject,created_at) VALUES (?,?,?,?,?,?)",UUID.randomUUID(),id,r.provider(),r.issuer(),r.subject(),at(now));
        return true;
    }
    public void credential(UUID id,UUID account,UUID registration,String clientId,
            String keyId,byte[] iv,byte[] ciphertext,Instant now) {
        jdbc.update("""
            INSERT INTO apple_revocation_credential(id,account_id,registration_id,client_id,key_id,iv,ciphertext,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """,id,account,registration,clientId,keyId,iv,ciphertext,at(now));
    }
    public boolean hasCredential(UUID account,String clientId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM apple_revocation_credential WHERE account_id=? AND client_id=?)",Boolean.class,account,clientId));
    }
    public void associate(UUID registration,UUID account) {jdbc.update("UPDATE apple_revocation_credential SET account_id=?,registration_id=NULL WHERE registration_id=?",account,registration);}
    private static Timestamp at(Instant at){return Timestamp.from(at);}
}
