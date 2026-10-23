package cz.mh.rallye.rallye_backend.shared.persistence;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

/** Privacy cleanup includes expired records and commands issued by other actors. No time-based filter. */
@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class CommandCleanupStore {
    private final JdbcTemplate jdbc;
    private final CommandStore commands;
    public CommandCleanupStore(JdbcTemplate jdbc,CommandStore commands) {this.jdbc=jdbc;this.commands=commands;}
    record Row(UUID actor,UUID key,UUID target,String operation,String request,String result,Set<UUID> metadata) { }
    public int deleteRelated(UUID account,String email) {
        Objects.requireNonNull(account);
        jdbc.queryForObject("SELECT id FROM admin_control WHERE id=1 FOR UPDATE",Integer.class);
        var remove=new ArrayList<Row>();
        for(var row:rows()) {
            var content=CommandAccounts.inspect(row.actor,row.target,row.operation,row.request,row.result);
            var ids=related(row);
            if(ids.contains(account) || CommandAccounts.containsEmail(content.request(),email) || CommandAccounts.containsEmail(content.result(),email))remove.add(row);
        }
        int count=0;
        for(var row:remove)count+=jdbc.update("DELETE FROM admin_role_command WHERE actor_id=? AND command_key=?",row.actor,row.key);
        return count;
    }
    /** Before audit retention: resolve every legacy record while its evidence still exists, including expired rows. */
    public int prepareForAuditDeletion() {
        jdbc.queryForObject("SELECT id FROM admin_control WHERE id=1 FOR UPDATE",Integer.class);
        var plan=new LinkedHashMap<Row,Set<UUID>>();
        for(var row:rows()) {
            // Legacy captain payloads omit the previous owner. There is no command key/version in
            // the audit, and its timestamp is captured separately: even a plausible matching event
            // cannot prove this command's origin. Do not certify a union of unrelated crew history.
            if(row.metadata==null && row.operation.equals("CREW_CAPTAIN"))
                throw new IllegalStateException("Unverifiable legacy captain command; audit retention aborted");
            plan.put(row,related(row)); // Unknown format aborts before modifying anything.
        }
        int count=0;
        for(var item:plan.entrySet())if(item.getKey().metadata==null || !item.getKey().metadata.equals(item.getValue())) {
            var row=item.getKey();
            String ids="{"+String.join(",",item.getValue().stream().map(UUID::toString).sorted().toList())+"}";
            count+=jdbc.update("UPDATE admin_role_command SET related_account_ids=?::uuid[] WHERE actor_id=? AND command_key=?",ids,row.actor,row.key);
        }
        return count;
    }
    private List<Row> rows() {
        return jdbc.query("SELECT * FROM admin_role_command ORDER BY actor_id,command_key FOR UPDATE",(rs,n)->{
            var array=rs.getArray("related_account_ids");Set<UUID> metadata=null;
            if(array!=null) {
                metadata=new HashSet<>();
                for(Object id:(Object[])array.getArray())metadata.add(UUID.fromString(id.toString()));
            }
            return new Row(rs.getObject("actor_id",UUID.class),rs.getObject("command_key",UUID.class),rs.getObject("target_id",UUID.class),
                    rs.getString("operation"),rs.getString("request_data"),rs.getString("result_data"),metadata);
        });
    }
    private Set<UUID> related(Row row) {
            var content=CommandAccounts.inspect(row.actor,row.target,row.operation,row.request,row.result);
            var ids=row.metadata==null?commands.related(row.actor,row.target,row.operation,row.request,row.result):new HashSet<>(row.metadata);
            ids.addAll(content.accounts());
            if(row.metadata==null && row.operation.equals("CREW_CAPTAIN")) {
                // Conservative account-cancellation fallback only: it preserves the audit and removes
                // potentially related commands. Retention must never persist this union as proof.
                var history=jdbc.query("SELECT previous_captain_id,new_captain_id FROM audit_event WHERE target_type='CREW' AND target_id=? AND event_type='CREW_CAPTAIN_CHANGED'",
                        (rs,n)->new UUID[]{rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)},row.target);
                if(history.isEmpty())throw CommandAccounts.invalid();
                for(var owners:history)for(var owner:owners)if(owner!=null)ids.add(owner);
            }
            return ids;
    }
}
