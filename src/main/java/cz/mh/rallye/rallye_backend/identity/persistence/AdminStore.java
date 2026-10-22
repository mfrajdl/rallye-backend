package cz.mh.rallye.rallye_backend.identity.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminStore {
    public record Command(UUID target, boolean grant) { }
    private final JdbcTemplate jdbc;
    private final cz.mh.rallye.rallye_backend.shared.persistence.CommandStore commands;
    public AdminStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.commands=new cz.mh.rallye.rallye_backend.shared.persistence.CommandStore(jdbc);
    }
    public void lock() {
        // Missing singleton is corruption, not permission to continue without serialization.
        jdbc.queryForObject("SELECT id FROM admin_control WHERE id=1 FOR UPDATE", Integer.class);
    }
    public boolean administrator(UUID account) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM user_account_role r JOIN user_account a ON a.id=r.account_id
                WHERE a.id=? AND r.role='ADMIN' AND a.status='ACTIVE' AND a.email_verified_at IS NOT NULL)
                """, Boolean.class, account));
    }
    public boolean activeVerified(UUID account) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM user_account WHERE id=? AND status='ACTIVE' AND email_verified_at IS NOT NULL)", Boolean.class, account));
    }
    public boolean hasRole(UUID account) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM user_account_role WHERE account_id=? AND role='ADMIN')", Boolean.class, account));
    }
    public long count() {
        return jdbc.queryForObject("SELECT count(*) FROM user_account_role r JOIN user_account a ON a.id=r.account_id WHERE role='ADMIN' AND status='ACTIVE' AND email_verified_at IS NOT NULL", Long.class);
    }
    public void grant(UUID target, Instant now) {
        jdbc.update("INSERT INTO user_account_role(account_id,role,granted_at) VALUES (?,'ADMIN',?)", target, Timestamp.from(now));
    }
    public void revoke(UUID target) { jdbc.update("DELETE FROM user_account_role WHERE account_id=? AND role='ADMIN'", target); }
    public Command command(UUID actor, UUID key, Instant now) {
        var previous=commands.roleReplay(actor,key,now);
        return previous==null?null:new Command(previous.target(),previous.grant());
    }
    public void saveCommand(UUID actor, UUID key, UUID target, boolean grant, Instant now) {
        commands.saveRole(actor,key,target,grant,now);
    }
}
