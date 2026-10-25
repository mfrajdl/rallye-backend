package cz.mh.rallye.rallye_backend.identity.application;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import cz.mh.rallye.rallye_backend.identity.persistence.AppleRevocationStore.Reason;
import cz.mh.rallye.rallye_backend.identity.persistence.AppleLifecycleStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** All callers share control -> account -> case locking. No provider calls or task completion. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class AppleAuthorizationLifecycle {
    private final AppleLifecycleStore store;
    private final AppleRevocationFoundation foundation;
    private final SecretTokens secrets;
    private final Clock clock;
    public AppleAuthorizationLifecycle(AppleLifecycleStore store,AppleRevocationFoundation foundation,SecretTokens secrets,Clock clock){this.store=store;this.foundation=foundation;this.secrets=secrets;this.clock=clock;}
    public boolean blocked(String issuer,String subject){return store.blocked(secrets.hash(issuer+"\n"+subject));}
    public void capture(UUID flow,String subject,String client,UUID account,UUID registration,String token){
        store.lock();
        if(flow==null || subject==null || subject.isBlank() || client==null || client.isBlank() || (account==null)==(registration==null))throw unavailable();
        if(account!=null)store.lockAccount(account);
        if(token==null || token.isBlank()){
            if(account!=null && store.hasMaterial(account,subject,client))return;
            throw unavailable();
        }
        var c=foundation.create(new AppleRevocationFoundation.NewCase("https://appleid.apple.com",subject,client,UUID.randomUUID(),flow,account,registration));
        foundation.addMaterial(c.id(),token);
    }
    /** Bind every still-owned registration for this exact identity, never by email. */
    public void bindRegistrations(UUID account){
        store.lock();store.lockAccount(account);
        for(UUID id:store.pendingForAccount(account))foundation.bindAccount(id,account);
    }
    /** D14: only after ALL contexts expire, with no account of any status. */
    public void purge(Instant now){
        store.lock();
        for(var identity:store.expiredIdentities(now)){
            UUID account=store.owner(identity);
            if(account!=null){bindRegistrations(account);continue;}
            if(store.hasLiveContext(identity,now))continue;
            for(UUID id:store.pending(identity))foundation.enqueue(id,Reason.REGISTRATION_ABANDONED);
        }
        // Still-live/ambiguous ownership remains protected by the FK; never discard material.
        store.deleteDetachedExpired(now);
    }
    /** Called before identities/contexts disappear, in the cancellation transaction. */
    public void cancel(UUID account){
        store.lock();bindRegistrations(account);
        for(UUID id:store.ownedCases(account))foundation.enqueue(id,Reason.ACCOUNT_CANCELLATION);
        // A proposed email is not identity: invalidate these forms, never revoke another account.
        for(var registration:store.registrationsForEmail(account)){
            UUID other=store.owner(registration.identity());
            if(other!=null && !other.equals(account))bindRegistrations(other);
            store.invalidate(registration.id(),secrets.hash(secrets.create()),clock.instant().truncatedTo(ChronoUnit.MILLIS));
        }
    }
    private static AuthFailure unavailable(){return new AuthFailure(503,"APPLE_CREDENTIAL_STORAGE_UNAVAILABLE");}
}
