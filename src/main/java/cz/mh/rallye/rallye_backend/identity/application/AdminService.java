package cz.mh.rallye.rallye_backend.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import cz.mh.rallye.rallye_backend.audit.application.AuditService.Event;
import cz.mh.rallye.rallye_backend.audit.persistence.AuditStore;
import cz.mh.rallye.rallye_backend.identity.persistence.AccountStore;
import cz.mh.rallye.rallye_backend.identity.persistence.AdminStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {
    public record AuditEntry(UUID id, Instant occurredAt, String actorKind, UUID actorId, String eventType, UUID targetId) { }
    public record AuditPage(List<AuditEntry> items, int page, int size, boolean hasMore) { }
    private final AdminStore roles;
    private final AccountStore accounts;
    private final AuditService audit;
    private final AuditStore events;
    private final Clock clock;
    public AdminService(AdminStore roles, AccountStore accounts, AuditService audit, AuditStore events, Clock clock) {
        this.roles=roles; this.accounts=accounts; this.audit=audit; this.events=events; this.clock=clock;
    }
    private void authorize(AuthService.Principal actor) {
        // Serialize with role writers and bootstrap; recheck current authority and session.
        roles.lock();
        if (actor == null || !roles.administrator(actor.accountId())) throw new AuthFailure(403, "ADMIN_REQUIRED");
        if (!accounts.isActiveSession(actor.sessionId(), actor.accountId(), clock.instant())) throw AuthFailure.credentials();
    }
    @Transactional
    public void changeRole(AuthService.Principal actor, UUID target, boolean grant, UUID key) {
        authorize(actor);
        if (actor.accountId().equals(target)) throw new AuthFailure(403, "SELF_ROLE_CHANGE_FORBIDDEN");
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var previous = roles.command(actor.accountId(), key, now);
        if (previous != null) {
            if (!previous.target().equals(target) || previous.grant()!=grant) throw new AuthFailure(409, "IDEMPOTENCY_CONFLICT");
            return;
        }
        if (!roles.activeVerified(target)) throw new AuthFailure(409, "ADMIN_TARGET_UNAVAILABLE");
        boolean hasRole = roles.hasRole(target);
        if (grant && !hasRole) {
            roles.grant(target, now); audit.record(actor.accountId(), Event.ADMIN_GRANTED, target);
        } else if (!grant && hasRole) {
            if (roles.count() <= 1) throw new AuthFailure(409, "LAST_ADMIN_REQUIRED");
            roles.revoke(target); audit.record(actor.accountId(), Event.ADMIN_REVOKED, target);
        }
        roles.saveCommand(actor.accountId(), key, target, grant, now);
    }
    @Transactional
    public AuditPage audit(AuthService.Principal actor, UUID actorId, UUID targetId, Event event,
                           Instant from, Instant until, int page, int size) {
        authorize(actor);
        if (page < 0 || page > 10000 || size < 1 || size > 100 || (from != null && until != null && !from.isBefore(until))) {
            throw new AuthFailure(400, "INVALID_AUDIT_FILTER");
        }
        var found = events.find(actorId, targetId, event, from, until, page, size);
        var entries = found.stream().limit(size).map(e -> new AuditEntry(e.id(), e.occurredAt(), e.actorKind(), e.actorId(), e.eventType(), e.targetId())).toList();
        return new AuditPage(entries, page, size, found.size()>size);
    }
}
