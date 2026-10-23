package cz.mh.rallye.rallye_backend.audit.persistence;

import java.sql.Timestamp;
import java.time.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class AuditRetentionStore {
    public static final long LOCK_KEY=0x52414c4c00000005L;
    private final JdbcTemplate jdbc;
    public AuditRetentionStore(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public boolean claim(LocalDate day) {
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)",Boolean.class,LOCK_KEY)))return false;
        var last=jdbc.queryForObject("SELECT last_success_local_date FROM audit_cleanup_control WHERE id=1 FOR UPDATE",java.sql.Date.class);
        return last==null || last.toLocalDate().isBefore(day);
    }
    public long deleteBefore(Instant cutoff) {
        return jdbc.queryForObject("WITH removed AS (DELETE FROM audit_event WHERE occurred_at<? RETURNING 1) SELECT count(*) FROM removed",Long.class,Timestamp.from(cutoff));
    }
    public void success(LocalDate day) {
        jdbc.update("UPDATE audit_cleanup_control SET last_success_local_date=? WHERE id=1",java.sql.Date.valueOf(day));
    }
}
