package cz.mh.rallye.rallye_backend.audit.application;

import java.time.*;
import java.util.Map;
import cz.mh.rallye.rallye_backend.audit.persistence.AuditRetentionStore;
import cz.mh.rallye.rallye_backend.shared.persistence.CommandCleanupStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuditRetentionService {
    private final long days;
    private final AuditRetentionStore store;
    private final CommandCleanupStore commands;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public AuditRetentionService(@Value("${rallye.audit.retention-days:365}") long days,AuditRetentionStore store,
            CommandCleanupStore commands,AuditService audit,Clock clock,PlatformTransactionManager manager) {
        if(days<90)throw new IllegalArgumentException("Audit retention must be at least 90 days");
        clock.instant().minus(Duration.ofDays(days)); // Reject overflow before any cleanup can run.
        this.days=days;this.store=store;this.commands=commands;this.audit=audit;this.clock=clock;
        transaction=new TransactionTemplate(manager);transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public long retentionDays(){return days;}
    /** Invoked only by the scheduled trigger or tests, never a startup runner or public endpoint. */
    public boolean runScheduled() {
        return Boolean.TRUE.equals(transaction.execute(status->{
            var now=clock.instant();var day=now.atZone(AuditCsv.ZONE).toLocalDate();
            if(!store.claim(day))return false;
            Instant cutoff=now.minus(Duration.ofDays(days));
            commands.prepareForAuditDeletion(); // Same transaction: do not delete any evidence if reconstruction fails.
            long count=store.deleteBefore(cutoff);
            audit.maintenance(null,AuditService.Event.AUDIT_CLEANUP,
                    Map.of("retentionDays",days,"cutoff",cutoff.toString(),"deletedCount",count,"localDate",day.toString()));
            store.success(day);return true;
        }));
    }
}
