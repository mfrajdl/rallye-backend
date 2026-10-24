package cz.mh.rallye.rallye_backend.identity.persistence;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

/** Internal primitives only. The future orchestrator supplies authorization, history policy and audit. */
@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class AccountCancellationStore {
    public record State(UUID id,String email,String normalizedEmail,String displayName,String status,Instant cancelledAt) {
        @Override public String toString() {return "AccountCancellationState[redacted]";}
    }
    private final JdbcTemplate jdbc;
    public AccountCancellationStore(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public State lock(UUID id) {
        jdbc.queryForObject("SELECT id FROM admin_control WHERE id=1 FOR UPDATE",Integer.class);
        var rows=jdbc.query("SELECT * FROM user_account WHERE id=? FOR UPDATE",(rs,n)->{
            var at=rs.getTimestamp("cancelled_at");
            return new State(rs.getObject("id",UUID.class),rs.getString("email"),rs.getString("normalized_email"),
                    rs.getString("display_name"),rs.getString("status"),at==null?null:at.toInstant());
        },id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public int deleteIdentities(UUID id) {
        lock(id);
        // Invalidate all unfinished proofs of identities about to be detached, including expired contexts.
        jdbc.update("""
            DELETE FROM external_registration r WHERE EXISTS(SELECT 1 FROM user_identity i
              WHERE i.account_id=? AND i.provider=r.provider AND i.issuer=r.issuer AND i.subject=r.subject)
              OR proposed_email=(SELECT normalized_email FROM user_account WHERE id=?)
            """,id,id);
        return jdbc.update("DELETE FROM user_identity WHERE account_id=?",id);
    }
    public int deleteSessions(UUID id) {
        lock(id);
        // MOBILE grants need not reference a session; remove them explicitly also for anonymization.
        jdbc.update("DELETE FROM external_auth_grant WHERE account_id=?",id);
        jdbc.update("DELETE FROM external_auth_flow WHERE account_id=?",id);
        return jdbc.update("DELETE FROM user_session WHERE account_id=?",id);
    }
    public int deleteTokens(UUID id) {lock(id);return jdbc.update("DELETE FROM user_action_token WHERE account_id=?",id);}
    public int deleteRoles(UUID id) {lock(id);return jdbc.update("DELETE FROM user_account_role WHERE account_id=?",id);}
    public int deleteAccount(UUID id) {lock(id);return jdbc.update("DELETE FROM user_account WHERE id=?",id);}
    public int anonymize(UUID id,Instant now) {
        lock(id);
        return jdbc.update("""
                UPDATE user_account SET status='ANONYMIZED',email=NULL,normalized_email=NULL,display_name=NULL,
                    email_verified_at=NULL,cancelled_at=?,updated_at=? WHERE id=? AND status<>'ANONYMIZED'
                """,Timestamp.from(now),Timestamp.from(now),id);
    }
    public int detachCrews(UUID id,Instant now) {
        lock(id);
        jdbc.queryForList("SELECT id FROM crew WHERE captain_account_id=? ORDER BY id FOR UPDATE",id);
        return jdbc.update("UPDATE crew SET captain_account_id=NULL,version=version+1,updated_at=? WHERE captain_account_id=?",
                Timestamp.from(now),id);
    }
    public List<UUID> ownedCrewIds(UUID id) {
        lock(id);
        return jdbc.queryForList("SELECT id FROM crew WHERE captain_account_id=? ORDER BY id FOR UPDATE",UUID.class,id);
    }
    public List<UUID> pendingTransferIds(UUID id) {
        lock(id);
        // The shared control lock already excludes writers; closePendingTransfers locks crew rows first.
        return jdbc.queryForList("SELECT id FROM crew_transfer WHERE status='PENDING' AND (from_account_id=? OR to_account_id=?) ORDER BY id",UUID.class,id,id);
    }
    public int closePendingTransfers(UUID id,UUID actor,Instant now) {
        if(actor==null) throw new IllegalArgumentException("Resolution actor is required");
        lock(id);
        // A recipient's cancellation must also lock crews owned by other captains before their transfer rows.
        jdbc.queryForList("""
                SELECT id FROM crew WHERE id IN (SELECT crew_id FROM crew_transfer
                WHERE status='PENDING' AND (from_account_id=? OR to_account_id=?)) ORDER BY id FOR UPDATE
                """,id,id);
        jdbc.queryForList("SELECT id FROM crew_transfer WHERE status='PENDING' AND (from_account_id=? OR to_account_id=?) ORDER BY id FOR UPDATE",id,id);
        return jdbc.update("""
                UPDATE crew_transfer SET status=CASE WHEN expires_at<=? THEN 'EXPIRED' ELSE 'CANCELLED' END,
                    resolved_at=?,resolved_by=CASE WHEN expires_at<=? THEN NULL ELSE ? END,
                    resolution_reason=CASE WHEN expires_at<=? THEN 'EXPIRED' ELSE 'ACCOUNT_CANCELLED' END
                WHERE status='PENDING' AND (from_account_id=? OR to_account_id=?)
                """,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),actor,Timestamp.from(now),id,id);
    }
}
