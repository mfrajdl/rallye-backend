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
    public enum Event { TEMPLATE_DUPLICATED, TEMPLATE_STAGE_DUPLICATED, TEMPLATE_IMAGE_CREATED, TEMPLATE_IMAGE_DELETED, TEMPLATE_READIED, TEMPLATE_STAGE_CONFIGURED, TEMPLATE_CREATED, TEMPLATE_UPDATED, TEMPLATE_DELETED, TEMPLATE_DRAFTED, TEMPLATE_STAGE_CREATED, TEMPLATE_STAGE_UPDATED, TEMPLATE_STAGE_DELETED, TEMPLATE_REORDERED, LOGIN_SUCCEEDED, LOGOUT_SUCCEEDED, PASSWORD_CHANGED, PASSWORD_RESET_COMPLETED, PROFILE_NAME_CHANGED,
        APPLE_REVOCATION_SUCCEEDED, APPLE_REVOCATION_RETRIED, ADMIN_GRANTED, ADMIN_REVOKED, ADMIN_BOOTSTRAPPED, ACCOUNT_DELETED, ACCOUNT_ANONYMIZED, AUDIT_EXPORT, AUDIT_CLEANUP,
        CREW_TRANSFER_REQUESTED, CREW_TRANSFER_ACCEPTED, CREW_TRANSFER_REJECTED, CREW_TRANSFER_CANCELLED,
        CREW_CREATED, CREW_UPDATED, CREW_DELETED, CREW_CAPTAIN_CHANGED, CREW_PHOTO_CHANGED, CREW_PHOTO_DELETED, PHOTO_CLEANUP_STARTED }
    private final AuditStore store;
    private final Clock clock;
    public AuditService(AuditStore store, Clock clock) { this.store = store; this.clock = clock; }

    @Transactional(propagation=Propagation.MANDATORY)
    public void appleRevocation(UUID job){store.appendAppleRevocation(UUID.randomUUID(),clock.instant().truncatedTo(ChronoUnit.MILLIS),job);}

    @Transactional(propagation=Propagation.MANDATORY)
    public void template(UUID actor,Event event,UUID target,boolean stage){store.appendTemplate(UUID.randomUUID(),clock.instant().truncatedTo(ChronoUnit.MILLIS),actor,event,target,stage);}

    @Transactional(propagation=Propagation.MANDATORY)
    public void templateCopy(UUID actor,UUID target,UUID source,UUID template,boolean stage){
        store.appendTemplateCopy(UUID.randomUUID(),clock.instant().truncatedTo(ChronoUnit.MILLIS),actor,target,source,template,stage);
    }

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
