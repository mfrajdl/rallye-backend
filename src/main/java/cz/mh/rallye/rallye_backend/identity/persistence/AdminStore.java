package cz.mh.rallye.rallye_backend.identity.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
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
    /** Deliberately projects only administrative profile fields, never credential/session columns. */
    public record User(UUID id,String displayName,String email,String status,String identityProvider,boolean administrator,boolean captainEligible) {}
    private static final String USER_SELECT="""
        SELECT a.id,a.display_name,a.email,a.status,i.provider AS identity_provider,
            (a.status='ACTIVE' AND a.email_verified_at IS NOT NULL
                AND EXISTS(SELECT 1 FROM user_account_role r WHERE r.account_id=a.id AND r.role='ADMIN')) AS administrator,
            (a.status='ACTIVE' AND a.email_verified_at IS NOT NULL) AS captain_eligible
        FROM user_account a LEFT JOIN user_identity i ON i.account_id=a.id
        WHERE a.status<>'ANONYMIZED' AND a.cancelled_at IS NULL
        """;
    public List<User> users(String query,boolean captainEligible,int page,int size){
        var sql=new StringBuilder(USER_SELECT);var args=new ArrayList<Object>();
        if(query!=null && !query.isEmpty()){
            String pattern="%"+query.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
            sql.append(" AND (a.display_name ILIKE ? ESCAPE '!' OR a.email ILIKE ? ESCAPE '!')");args.add(pattern);args.add(pattern);
        }
        if(captainEligible)sql.append(" AND a.status='ACTIVE' AND a.email_verified_at IS NOT NULL");
        sql.append(" ORDER BY a.created_at DESC,a.id DESC LIMIT ? OFFSET ?");args.add(size+1);args.add((long)page*size);
        return jdbc.query(sql.toString(),AdminStore::mapUser,args.toArray());
    }
    public User user(UUID id){var rows=jdbc.query(USER_SELECT+" AND a.id=?",AdminStore::mapUser,id);return rows.isEmpty()?null:rows.getFirst();}
    private static User mapUser(java.sql.ResultSet r,int n)throws java.sql.SQLException{
        return new User(r.getObject("id",UUID.class),r.getString("display_name"),r.getString("email"),r.getString("status"),r.getString("identity_provider"),r.getBoolean("administrator"),r.getBoolean("captain_eligible"));
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
