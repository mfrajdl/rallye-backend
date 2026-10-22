package cz.mh.rallye.rallye_backend.shared.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** One ledger and actor/key namespace shared with the original ADMIN operations. Caller holds admin_control. */
@Repository
public class CommandStore {
    public record RoleCommand(UUID target,boolean grant) { }
    private final JdbcTemplate jdbc;
    public CommandStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public RoleCommand roleReplay(UUID actor,UUID key,Instant now) {
        var rows=jdbc.query("SELECT target_id,grant_role,operation FROM admin_role_command WHERE actor_id=? AND command_key=? AND expires_at>?",
                (rs,n)->{
                    if(!"ADMIN_ROLE".equals(rs.getString("operation"))) throw new AuthFailure(409,"IDEMPOTENCY_CONFLICT");
                    return new RoleCommand(rs.getObject("target_id",UUID.class),rs.getBoolean("grant_role"));
                },actor,key,Timestamp.from(now));
        return rows.isEmpty()?null:rows.getFirst();
    }
    public String replay(UUID actor, UUID key, String operation, String request, Instant now) {
        var rows=jdbc.query("""
                SELECT operation, request_data=?::jsonb AS same_request, result_data::text AS result
                FROM admin_role_command WHERE actor_id=? AND command_key=? AND expires_at>?
                """, (rs,n)-> {
            if (!operation.equals(rs.getString("operation")) || !rs.getBoolean("same_request"))
                throw new AuthFailure(409,"IDEMPOTENCY_CONFLICT");
            return rs.getString("result");
        },request,actor,key,Timestamp.from(now));
        return rows.isEmpty()?null:rows.getFirst();
    }
    public void save(UUID actor, UUID key, UUID target, String operation, String request, String result, Instant now) {
        insert(actor,key,target,operation,request,result,null,now);
    }
    public void saveRole(UUID actor,UUID key,UUID target,boolean grant,Instant now) {
        insert(actor,key,target,"ADMIN_ROLE",null,null,grant,now);
    }
    private void insert(UUID actor,UUID key,UUID target,String operation,String request,String result,Boolean grant,Instant now) {
        jdbc.update("DELETE FROM admin_role_command WHERE actor_id=? AND command_key=? AND expires_at<=?",actor,key,Timestamp.from(now));
        jdbc.update("""
                INSERT INTO admin_role_command(actor_id,command_key,target_id,operation,request_data,result_data,grant_role,created_at,expires_at)
                VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?,?)
                """,actor,key,target,operation,request,result,grant,Timestamp.from(now),Timestamp.from(now.plusSeconds(86400)));
    }
}
