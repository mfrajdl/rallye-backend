package cz.mh.rallye.rallye_backend.audit.application;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import cz.mh.rallye.rallye_backend.audit.persistence.AuditExportStore;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuditExportService {
    private final AdminStore roles;
    private final AccountStore accounts;
    private final AuditExportStore exports;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate snapshot,write;
    public AuditExportService(AdminStore roles,AccountStore accounts,AuditExportStore exports,AuditService audit,Clock clock,PlatformTransactionManager manager) {
        this.roles=roles;this.accounts=accounts;this.exports=exports;this.audit=audit;this.clock=clock;
        snapshot=new TransactionTemplate(manager);snapshot.setReadOnly(true);snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        write=new TransactionTemplate(manager);write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    private void authorize(AuthService.Principal actor) {
        // No FOR UPDATE: a long read-only snapshot must not hold admin_control or block retention.
        if(actor==null || !accounts.isActiveSession(actor.sessionId(),actor.accountId(),clock.instant()))throw AuthFailure.credentials();
        if(!roles.administrator(actor.accountId()))throw new AuthFailure(403,"ADMIN_REQUIRED");
    }
    public void prepare(AuthService.Principal actor,AuditExportFilter filter) {
        filter.validate();snapshot.executeWithoutResult(status->authorize(actor));
    }
    public void stream(AuthService.Principal actor,AuditExportFilter filter,OutputStream output) throws IOException {
        filter.validate();
        long count;
        try {
            count=snapshot.execute(status->{
                authorize(actor);
                var writer=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8));
                try {
                    writer.write('\ufeff');
                    AuditCsv.row(writer,"id","occurredAt","eventType","actorKind","actorId","targetType","targetId","outcome",
                            "previousCaptainId","newCaptainId","crewId","transferId","resolutionReason","metadata");
                    long rows=exports.write(filter,writer);writer.flush();return rows;
                } catch(IOException failure) {throw new UncheckedIOException(failure);}
            });
        } catch(UncheckedIOException failure) {throw failure.getCause();}
        // Generation and flush succeeded; no network acknowledgement is required. Separate writable transaction.
        try {write.executeWithoutResult(status->audit.maintenance(actor.accountId(),AuditService.Event.AUDIT_EXPORT,
                Map.of("filters",filter.metadata(),"exportedCount",count)));}
        catch(RuntimeException failure) {cz.mh.rallye.rallye_backend.identity.api.ApiErrors.logServerFailure(failure);}
    }
}
