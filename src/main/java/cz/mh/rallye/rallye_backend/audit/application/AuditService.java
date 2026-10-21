package cz.mh.rallye.rallye_backend.audit.application;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import cz.mh.rallye.rallye_backend.audit.persistence.AuditStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    public enum Event { LOGIN_SUCCEEDED, LOGOUT_SUCCEEDED, PASSWORD_CHANGED, PASSWORD_RESET_COMPLETED,
        ADMIN_GRANTED, ADMIN_REVOKED, ADMIN_BOOTSTRAPPED }
    private final AuditStore store;
    private final Clock clock;
    public AuditService(AuditStore store, Clock clock) { this.store = store; this.clock = clock; }

    // Audit failure must roll back the operation, never create an independent transaction.
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID actor, Event event, UUID target) {
        store.append(UUID.randomUUID(), clock.instant().truncatedTo(ChronoUnit.MILLIS), actor, event, target);
    }
}
