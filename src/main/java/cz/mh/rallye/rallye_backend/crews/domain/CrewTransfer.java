package cz.mh.rallye.rallye_backend.crews.domain;

import java.time.Instant;
import java.util.UUID;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;

/** Internal persistence/domain value, never a REST DTO. Its authentication digest must not be logged. */
public record CrewTransfer(UUID id, UUID crewId, UUID fromAccountId, UUID toAccountId, String tokenHash,
                           Status status, Instant createdAt, Instant expiresAt, Instant resolvedAt,
                           UUID resolvedBy, String resolutionReason) {
    public enum Status { PENDING, ACCEPTED, REJECTED, CANCELLED, EXPIRED }
    public boolean pendingAt(Instant now) { return status == Status.PENDING && now.isBefore(expiresAt); }
    public void requirePending(Instant now) {
        if (!pendingAt(now)) throw new AuthFailure(409,"TRANSFER_NOT_PENDING");
    }
    @Override public String toString() { return "CrewTransfer[id="+id+", status="+status+"]"; }
}
