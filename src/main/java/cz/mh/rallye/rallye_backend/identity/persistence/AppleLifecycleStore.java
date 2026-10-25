package cz.mh.rallye.rallye_backend.identity.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class AppleLifecycleStore {
    public record Identity(String issuer,String subject) { @Override public String toString(){return "AppleIdentity[redacted]";} }
    public record Registration(UUID id,Identity identity) { @Override public String toString(){return "AppleRegistration[redacted]";} }
    private final JdbcTemplate jdbc;
    public AppleLifecycleStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void lock(){jdbc.queryForObject("SELECT id FROM admin_control WHERE id=1 FOR UPDATE",Integer.class);}
    public void lockAccount(UUID account){
        if(jdbc.queryForList("SELECT id FROM user_account WHERE id=? FOR UPDATE",UUID.class,account).isEmpty())throw new IllegalStateException("Apple account owner is unavailable");
    }
    public boolean blocked(String digest){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM apple_revocation_barrier WHERE identity_digest=?)",Boolean.class,digest));}
    public boolean hasMaterial(UUID account,String subject,String client){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM apple_revocation_credential m JOIN apple_authorization_case c ON c.id=m.case_id WHERE c.account_id=? AND c.subject=? AND c.client_id=? AND c.status='ACCOUNT_BOUND')",Boolean.class,account,subject,client));}
    public List<UUID> pendingForAccount(UUID account){return jdbc.queryForList("SELECT c.id FROM apple_authorization_case c JOIN user_identity i ON i.provider='APPLE' AND i.issuer=c.issuer AND i.subject=c.subject WHERE i.account_id=? AND c.status='REGISTRATION_PENDING' ORDER BY c.id",UUID.class,account);}
    public UUID owner(Identity identity){var ids=jdbc.queryForList("SELECT account_id FROM user_identity WHERE provider='APPLE' AND issuer=? AND subject=?",UUID.class,identity.issuer(),identity.subject());return ids.isEmpty()?null:ids.getFirst();}
    public List<Identity> expiredIdentities(Instant now){return jdbc.query("SELECT DISTINCT issuer,subject FROM external_registration WHERE provider='APPLE' AND expires_at<=?",(r,n)->new Identity(r.getString("issuer"),r.getString("subject")),Timestamp.from(now));}
    public boolean hasLiveContext(Identity identity,Instant now){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM external_registration WHERE provider='APPLE' AND issuer=? AND subject=? AND expires_at>?)",Boolean.class,identity.issuer(),identity.subject(),Timestamp.from(now)));}
    public List<UUID> pending(Identity identity){return jdbc.queryForList("SELECT id FROM apple_authorization_case WHERE issuer=? AND subject=? AND status='REGISTRATION_PENDING' ORDER BY id",UUID.class,identity.issuer(),identity.subject());}
    public void deleteDetachedExpired(Instant now){jdbc.update("DELETE FROM external_registration r WHERE expires_at<=? AND NOT EXISTS(SELECT 1 FROM apple_authorization_case c WHERE c.registration_id=r.id)",Timestamp.from(now));}
    public List<UUID> ownedCases(UUID account){return jdbc.queryForList("SELECT id FROM apple_authorization_case WHERE account_id=? ORDER BY id",UUID.class,account);}
    public List<Registration> registrationsForEmail(UUID account){return jdbc.query("SELECT r.id,r.issuer,r.subject FROM external_registration r WHERE r.provider='APPLE' AND r.proposed_email=(SELECT normalized_email FROM user_account WHERE id=?) ORDER BY r.id",(r,n)->new Registration(r.getObject("id",UUID.class),new Identity(r.getString("issuer"),r.getString("subject"))),account);}
    public void invalidate(UUID id,String hash,Instant now){jdbc.update("UPDATE external_registration SET consumed_at=COALESCE(consumed_at,?),proposed_email=NULL,display_name=NULL,token_hash=? WHERE id=?",Timestamp.from(now),hash,id);}
}
