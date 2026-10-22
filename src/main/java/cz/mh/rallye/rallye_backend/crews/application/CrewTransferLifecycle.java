package cz.mh.rallye.rallye_backend.crews.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import cz.mh.rallye.rallye_backend.crews.domain.CrewTransfer;
import cz.mh.rallye.rallye_backend.crews.domain.CrewTransfer.Status;
import cz.mh.rallye.rallye_backend.crews.persistence.CrewStore;
import cz.mh.rallye.rallye_backend.crews.persistence.CrewTransferStore;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import cz.mh.rallye.rallye_backend.identity.application.AuthService.Principal;
import cz.mh.rallye.rallye_backend.identity.persistence.AccountStore;
import cz.mh.rallye.rallye_backend.identity.persistence.AdminStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal foundation only. Future orchestration must add audit/idempotence in the SAME outer transaction. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class CrewTransferLifecycle {
    public record Transition(CrewTransfer transfer,long crewVersion) { }
    public static final Duration LIFETIME=Duration.ofHours(168);
    private final CrewTransferStore transfers;
    private final CrewStore crews;
    private final AccountStore accounts;
    private final AdminStore roles;
    private final Clock clock;
    public CrewTransferLifecycle(CrewTransferStore transfers,CrewStore crews,AccountStore accounts,AdminStore roles,Clock clock) {
        this.transfers=transfers;this.crews=crews;this.accounts=accounts;this.roles=roles;this.clock=clock;
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    private void actor(Principal actor) {
        roles.lock();
        if(actor==null) throw AuthFailure.credentials();
        accounts.lockAccount(actor.accountId());
        if(!roles.activeVerified(actor.accountId())||!accounts.isActiveSession(actor.sessionId(),actor.accountId(),now()))
            throw AuthFailure.credentials();
    }
    private void accounts(Principal actor,UUID... related) {
        Stream.of(related).filter(Objects::nonNull).filter(id->!id.equals(actor.accountId())).distinct().sorted().forEach(accounts::lockAccount);
    }
    private CrewStore.Crew crew(UUID id) {
        var crew=crews.find(id);
        if(crew==null) throw new AuthFailure(404,"CREW_NOT_FOUND");
        return crew;
    }
    private static void hash(String value) {
        if(value==null||!value.matches("[0-9a-f]{64}")) throw new AuthFailure(400,"INVALID_TOKEN_HASH");
    }
    /** Receives an already generated digest; never generates, returns or sends the raw token. */
    public Transition create(Principal actor,UUID crewId,UUID target,long expectedVersion,String tokenHash) {
        hash(tokenHash);
        if(target==null||crewId==null||expectedVersion<0) throw new AuthFailure(400,"INVALID_REQUEST");
        actor(actor);accounts(actor,target);
        var crew=crew(crewId);
        if(!actor.accountId().equals(crew.captainAccountId())) throw new AuthFailure(404,"CREW_NOT_FOUND");
        if(crew.version()!=expectedVersion) throw new AuthFailure(409,"CREW_VERSION_CONFLICT");
        if(target.equals(crew.captainAccountId())) throw new AuthFailure(409,"CAPTAIN_UNCHANGED");
        if(!roles.activeVerified(target)) throw new AuthFailure(409,"CAPTAIN_UNAVAILABLE");
        Instant at=now();
        var previous=transfers.pending(crewId);
        if(previous!=null) {
            if(previous.pendingAt(at)) throw new AuthFailure(409,"TRANSFER_ALREADY_PENDING");
            transfers.resolve(previous.id(),Status.EXPIRED,at,null,"EXPIRED");
        }
        var created=transfers.insert(UUID.randomUUID(),crewId,actor.accountId(),target,tokenHash,at,at.plus(LIFETIME));
        return new Transition(created,crew.version());
    }
    public Transition accept(Principal actor,UUID transferId,String presentedTokenHash) {
        return resolve(actor,transferId,presentedTokenHash,Status.ACCEPTED);
    }
    public Transition reject(Principal actor,UUID transferId,String presentedTokenHash) {
        return resolve(actor,transferId,presentedTokenHash,Status.REJECTED);
    }
    public Transition cancel(Principal actor,UUID transferId) {
        return resolve(actor,transferId,null,Status.CANCELLED);
    }
    private Transition resolve(Principal actor,UUID id,String presented,Status outcome) {
        actor(actor);
        var initial=transfers.find(id);
        if(initial==null) throw new AuthFailure(404,"TRANSFER_NOT_FOUND");
        boolean cancel=outcome==Status.CANCELLED;
        UUID allowed=cancel?initial.fromAccountId():initial.toAccountId();
        if(!actor.accountId().equals(allowed)) throw new AuthFailure(403,"TRANSFER_FORBIDDEN");
        initial.requirePending(now());
        accounts(actor,initial.fromAccountId(),initial.toAccountId());
        var crew=crew(initial.crewId());
        var transfer=transfers.lock(id);
        Instant at=now();transfer.requirePending(at);
        if(!Objects.equals(crew.captainAccountId(),transfer.fromAccountId()))
            throw new AuthFailure(409,"TRANSFER_OWNER_CHANGED");
        if(!cancel) {
            hash(presented);
            if(!MessageDigest.isEqual(transfer.tokenHash().getBytes(StandardCharsets.US_ASCII),presented.getBytes(StandardCharsets.US_ASCII)))
                throw new AuthFailure(400,"INVALID_OR_EXPIRED_TOKEN");
        }
        if(outcome==Status.ACCEPTED) {
            // Use the current version, so intervening text/photo changes do not invalidate the request.
            crews.captain(crew.id(),transfer.toAccountId(),at);
        }
        var resolved=transfers.resolve(id,outcome,at,actor.accountId(),cancel?"OWNER_CANCELLED":outcome.name());
        return new Transition(resolved,crew.version()+(outcome==Status.ACCEPTED?1:0));
    }
}
