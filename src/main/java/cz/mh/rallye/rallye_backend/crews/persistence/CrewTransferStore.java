package cz.mh.rallye.rallye_backend.crews.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import cz.mh.rallye.rallye_backend.crews.domain.CrewTransfer;
import cz.mh.rallye.rallye_backend.crews.domain.CrewTransfer.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CrewTransferStore {
    private final JdbcTemplate jdbc;
    public CrewTransferStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private CrewTransfer map(ResultSet rs,int row) throws SQLException {
        Timestamp resolved=rs.getTimestamp("resolved_at");
        return new CrewTransfer(rs.getObject("id",UUID.class),rs.getObject("crew_id",UUID.class),
                rs.getObject("from_account_id",UUID.class),rs.getObject("to_account_id",UUID.class),rs.getString("token_hash"),
                Status.valueOf(rs.getString("status")),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("expires_at").toInstant(),
                resolved==null?null:resolved.toInstant(),rs.getObject("resolved_by",UUID.class),rs.getString("resolution_reason"));
    }
    private CrewTransfer first(java.util.List<CrewTransfer> rows) { return rows.isEmpty()?null:rows.getFirst(); }
    /** Read identifiers under admin_control before locking related accounts/crew. */
    public CrewTransfer find(UUID id) { return first(jdbc.query("SELECT * FROM crew_transfer WHERE id=?",this::map,id)); }
    public CrewTransfer lock(UUID id) { return first(jdbc.query("SELECT * FROM crew_transfer WHERE id=? FOR UPDATE",this::map,id)); }
    public CrewTransfer pending(UUID crew) {
        return first(jdbc.query("SELECT * FROM crew_transfer WHERE crew_id=? AND status='PENDING' FOR UPDATE",this::map,crew));
    }
    public CrewTransfer insert(UUID id,UUID crew,UUID from,UUID to,String hash,Instant created,Instant expires) {
        return jdbc.queryForObject("""
                INSERT INTO crew_transfer(id,crew_id,from_account_id,to_account_id,token_hash,status,created_at,expires_at)
                VALUES (?,?,?,?,?,'PENDING',?,?) RETURNING *
                """,this::map,id,crew,from,to,hash,Timestamp.from(created),Timestamp.from(expires));
    }
    public CrewTransfer resolve(UUID id,Status status,Instant now,UUID actor,String reason) {
        return jdbc.queryForObject("""
                UPDATE crew_transfer SET status=?,resolved_at=?,resolved_by=?,resolution_reason=?
                WHERE id=? AND status='PENDING' RETURNING *
                """,this::map,status.name(),Timestamp.from(now),actor,reason,id);
    }
}
