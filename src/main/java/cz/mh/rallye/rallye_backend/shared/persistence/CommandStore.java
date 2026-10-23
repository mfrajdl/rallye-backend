package cz.mh.rallye.rallye_backend.shared.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.Set;
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
        var accounts=related(actor,target,operation,request,result);
        jdbc.update("DELETE FROM admin_role_command WHERE actor_id=? AND command_key=? AND expires_at<=?",actor,key,Timestamp.from(now));
        jdbc.update("""
                INSERT INTO admin_role_command(actor_id,command_key,target_id,operation,request_data,result_data,grant_role,created_at,expires_at,related_account_ids)
                VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?,?,?::uuid[])
                """,actor,key,target,operation,request,result,grant,Timestamp.from(now),Timestamp.from(now.plusSeconds(86400)),array(accounts));
    }
    Set<UUID> related(UUID actor,UUID target,String operation,String request,String result) {
        var ids=CommandAccounts.inspect(actor,target,operation,request,result).accounts();
        if(operation.startsWith("CREW_TRANSFER_")) {
            var rows=jdbc.query("SELECT from_account_id,to_account_id FROM crew_transfer WHERE id=?",(rs,n)->
                    new UUID[]{rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)},target);
            if(rows.size()!=1)throw CommandAccounts.invalid();
            ids.add(rows.getFirst()[0]);ids.add(rows.getFirst()[1]);
        }
        return ids;
    }
    /** Adds the previous owner, which is absent from the captain-change response. Same transaction as save. */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void relate(UUID actor,UUID key,UUID account) {
        if(account!=null)jdbc.update("""
                UPDATE admin_role_command SET related_account_ids=ARRAY(SELECT DISTINCT unnest(related_account_ids || ARRAY[?]::uuid[]))
                WHERE actor_id=? AND command_key=?
                """,account,actor,key);
    }
    private static String array(Set<UUID> ids) {
        return "{"+String.join(",",ids.stream().map(UUID::toString).sorted().toList())+"}";
    }
}
