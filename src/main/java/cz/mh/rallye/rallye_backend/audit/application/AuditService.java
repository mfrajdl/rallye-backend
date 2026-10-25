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
        APPLE_REVOCATION_SUCCEEDED, APPLE_REVOCATION_RETRIED, ADMIN_GRANTED, ADMIN_REVOKED, ADMIN_BOOTSTRAPPED, ACCOUNT_DELETED, ACCOUNT_ANONYMIZED, AUDIT_EXPORT, AUDIT_CLEANUP,
        CREW_TRANSFER_REQUESTED, CREW_TRANSFER_ACCEPTED, CREW_TRANSFER_REJECTED, CREW_TRANSFER_CANCELLED,
        CREW_CREATED, CREW_UPDATED, CREW_DELETED, CREW_CAPTAIN_CHANGED, CREW_PHOTO_CHANGED, CREW_PHOTO_DELETED, PHOTO_CLEANUP_STARTED }
    private final AuditStore store;
    private final Clock clock;
    public AuditService(AuditStore store, Clock clock) { this.store = store; this.clock = clock; }

    @Transactional(propagation=Propagation.MANDATORY)
    public void appleRevocation(UUID job){store.appendAppleRevocation(UUID.randomUUID(),clock.instant().truncatedTo(ChronoUnit.MILLIS),job);}

    // Audit failure must roll back the operation, never create an independent transaction.
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID actor, Event event, UUID target) {
        store.append(UUID.randomUUID(), clock.instant().truncatedTo(ChronoUnit.MILLIS), actor, event, target);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void maintenance(UUID actor,Event event,java.util.Map<String,Object> metadata) {
        store.appendAuditMaintenance(UUID.randomUUID(),clock.instant().truncatedTo(ChronoUnit.MILLIS),actor,event,
                tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(metadata));
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void crew(UUID actor, Event event, UUID target, UUID previousCaptain, UUID newCaptain) {
        store.appendCrew(UUID.randomUUID(),clock.instant().truncatedTo(ChronoUnit.MILLIS),actor,event,target,previousCaptain,newCaptain);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void photoCleanup(UUID actor) {
        store.appendMaintenance(UUID.randomUUID(),clock.instant().truncatedTo(ChronoUnit.MILLIS),actor);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void transfer(UUID actor,Event event,cz.mh.rallye.rallye_backend.crews.domain.CrewTransfer transfer,String reason) {
        store.appendTransfer(UUID.randomUUID(),clock.instant().truncatedTo(ChronoUnit.MILLIS),actor,event,
                transfer.id(),transfer.crewId(),transfer.fromAccountId(),transfer.toAccountId(),reason);
    }
}
