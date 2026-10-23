package cz.mh.rallye.rallye_backend.audit.persistence;

import java.io.*;
import java.sql.*;
import java.util.*;
import cz.mh.rallye.rallye_backend.audit.application.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class AuditExportStore {
    public static final int FETCH_SIZE=1000;
    private final JdbcTemplate jdbc;
    public AuditExportStore(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public long write(AuditExportFilter filter,Writer writer) {
        var sql=new StringBuilder("""
                SELECT id,occurred_at,actor_kind,actor_id,event_type,target_id,target_type,
                  previous_captain_id,new_captain_id,crew_id,transfer_id,resolution_reason,metadata::text AS metadata
                FROM audit_event WHERE true
                """);
        var params=new ArrayList<Object>();
        if(filter.from()!=null) {sql.append(" AND occurred_at>=?");params.add(Timestamp.from(filter.from()));}
        if(filter.until()!=null) {sql.append(" AND occurred_at<?");params.add(Timestamp.from(filter.until()));}
        if(filter.eventType()!=null) {sql.append(" AND event_type=?");params.add(filter.eventType().name());}
        if(filter.actorId()!=null) {sql.append(" AND actor_id=?");params.add(filter.actorId());}
        if(filter.targetAccountId()!=null) {sql.append(" AND target_type='ACCOUNT' AND target_id=?");params.add(filter.targetAccountId());}
        sql.append(" ORDER BY occurred_at ASC,id ASC");
        return jdbc.query(connection->{
            var statement=connection.prepareStatement(sql.toString(),ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY);
            try {statement.setFetchSize(FETCH_SIZE);for(int i=0;i<params.size();i++)statement.setObject(i+1,params.get(i));return statement;}
            catch(SQLException failure) {statement.close();throw failure;}
        },rs->{
            long count=0;
            try {
                while(rs.next()) {
                    String type=rs.getString("event_type");
                    String outcome=switch(type) {case "ACCOUNT_DELETED"->"DELETED";case "ACCOUNT_ANONYMIZED"->"ANONYMIZED";default->"";};
                    AuditCsv.row(writer,rs.getString("id"),AuditCsv.time(rs.getTimestamp("occurred_at").toInstant()),type,
                            rs.getString("actor_kind"),rs.getString("actor_id"),rs.getString("target_type"),rs.getString("target_id"),
                            outcome,rs.getString("previous_captain_id"),rs.getString("new_captain_id"),rs.getString("crew_id"),
                            rs.getString("transfer_id"),rs.getString("resolution_reason"),AuditCsv.metadata(rs.getString("metadata")));
                    count++;
                }
                return count;
            } catch(IOException failure) {throw new UncheckedIOException(failure);}
        });
    }
}
