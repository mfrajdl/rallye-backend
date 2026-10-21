package cz.mh.rallye.rallye_backend.identity.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC persistence is confined to this module; no persistence objects are API DTOs. */
@Repository
public class AccountStore {
    public record Account(UUID id, String email, String name, String status, String passwordHash) {
        @Override public String toString() { return "Account[redacted]"; }
    }
    public record Action(UUID id, UUID accountId, String purpose, Instant expiresAt, Instant consumedAt) { }
    public record Session(UUID id, UUID accountId, boolean rememberMe, Instant expiresAt) { }
    private final JdbcTemplate jdbc;
    public AccountStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private Account mapAccount(ResultSet rs, int row) throws SQLException {
        return new Account(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name"),
                rs.getString("status"), rs.getString("password_hash"));
    }
    private static <T> T first(List<T> values) { return values.isEmpty() ? null : values.getFirst(); }
    public Account byEmail(String normalizedEmail) {
        return first(jdbc.query("""
                SELECT a.*, i.password_hash FROM user_account a
                LEFT JOIN user_identity i ON i.account_id=a.id AND i.provider='LOCAL'
                WHERE a.normalized_email=?
                """, this::mapAccount, normalizedEmail));
    }
    public Account lockAccount(UUID id) {
        // Always lock account before token/session, including login/reset/password change.
        jdbc.queryForList("SELECT id FROM user_account WHERE id=? FOR UPDATE", id);
        return first(jdbc.query("""
                SELECT a.*, i.password_hash FROM user_account a
                LEFT JOIN user_identity i ON i.account_id=a.id AND i.provider='LOCAL' WHERE a.id=?
                """, this::mapAccount, id));
    }
    public Account account(UUID id) {
        return first(jdbc.query("""
                SELECT a.*, i.password_hash FROM user_account a
                LEFT JOIN user_identity i ON i.account_id=a.id AND i.provider='LOCAL' WHERE a.id=?
                """, this::mapAccount, id));
    }
    public boolean isActiveSession(UUID id, UUID account, Instant now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM user_session WHERE id=? AND account_id=?
                  AND revoked_at IS NULL AND expires_at>?)
                """, Boolean.class, id, account, at(now)));
    }
    public boolean create(UUID id, String email, String name, String hash, Instant now) {
        int inserted = jdbc.update("""
                INSERT INTO user_account(id,email,normalized_email,display_name,status,created_at,updated_at)
                VALUES (?,?,?,?,'PENDING_EMAIL',?,?) ON CONFLICT(normalized_email) DO NOTHING
                """, id, email, email, name, at(now), at(now));
        if (inserted == 0) return false;
        jdbc.update("""
                INSERT INTO user_identity(id,account_id,provider,issuer,subject,password_hash,created_at)
                VALUES (?,?,'LOCAL','local',?,?,?)
                """, UUID.randomUUID(), id, id.toString(), hash, at(now));
        return true;
    }
    public void insertAction(UUID account, String purpose, String hash, Instant now, Instant expiry) {
        jdbc.update("""
                INSERT INTO user_action_token(id,account_id,purpose,token_hash,created_at,expires_at)
                VALUES (?,?,?,?,?,?)
                """, UUID.randomUUID(), account, purpose, hash, at(now), at(expiry));
    }
    public Action action(String hash) {
        return first(jdbc.query("SELECT * FROM user_action_token WHERE token_hash=?", (rs, row) ->
                new Action(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                        rs.getString("purpose"), instant(rs, "expires_at"), instant(rs, "consumed_at")), hash));
    }
    public boolean consume(UUID id, String purpose, Instant now) {
        return jdbc.update("""
                UPDATE user_action_token SET consumed_at=? WHERE id=? AND purpose=?
                AND consumed_at IS NULL AND expires_at>?
                """, at(now), id, purpose, at(now)) == 1;
    }
    public void activate(UUID id, Instant now) {
        jdbc.update("UPDATE user_account SET status='ACTIVE',email_verified_at=?,updated_at=? WHERE id=?",
                at(now), at(now), id);
    }
    public void password(UUID id, String hash, Instant now) {
        jdbc.update("UPDATE user_identity SET password_hash=? WHERE account_id=? AND provider='LOCAL'", hash, id);
        jdbc.update("UPDATE user_account SET updated_at=? WHERE id=?", at(now), id);
        jdbc.update("UPDATE user_action_token SET consumed_at=? WHERE account_id=? AND purpose='PASSWORD_RESET' AND consumed_at IS NULL",
                at(now), id);
    }
    public UUID insertSession(UUID account, String hash, boolean remember, Instant now, Instant expires) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO user_session(id,account_id,token_hash,remember_me,created_at,last_used_at,expires_at)
                VALUES (?,?,?,?,?,?,?)
                """, id, account, hash, remember, at(now), at(now), at(expires));
        return id;
    }
    public Session session(String hash) {
        return first(jdbc.query("SELECT * FROM user_session WHERE token_hash=? AND revoked_at IS NULL", (rs, row) ->
                new Session(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                        rs.getBoolean("remember_me"), instant(rs, "expires_at")), hash));
    }
    public boolean touch(UUID id, Instant now, Instant expiry) {
        return jdbc.update("""
                UPDATE user_session SET last_used_at=?,expires_at=?
                WHERE id=? AND revoked_at IS NULL AND expires_at>?
                """, at(now), at(expiry), id, at(now)) == 1;
    }
    public void revoke(UUID id, Instant now) {
        jdbc.update("UPDATE user_session SET revoked_at=? WHERE id=? AND revoked_at IS NULL", at(now), id);
    }
    public void revokeOthers(UUID account, UUID keep, Instant now) {
        jdbc.update("UPDATE user_session SET revoked_at=? WHERE account_id=? AND id<>? AND revoked_at IS NULL",
                at(now), account, keep);
    }
    public void revokeAll(UUID account, Instant now) {
        jdbc.update("UPDATE user_session SET revoked_at=? WHERE account_id=? AND revoked_at IS NULL", at(now), account);
    }
    private static java.sql.Timestamp at(Instant instant) { return java.sql.Timestamp.from(instant); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
