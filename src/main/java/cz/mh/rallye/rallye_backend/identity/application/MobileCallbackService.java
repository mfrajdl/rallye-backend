package cz.mh.rallye.rallye_backend.identity.application;

import java.time.Clock;
import java.util.UUID;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Shared callback transaction supplies the control/account locks and atomic state consumption. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class MobileCallbackService {
    private final MobileLoginStore attempts;private final MobileGrantStore grants;
    private final MobileAuthSettings settings;private final SecretTokens secrets;private final Clock clock;
    public MobileCallbackService(MobileLoginStore attempts,MobileGrantStore grants,MobileAuthSettings settings,SecretTokens secrets,Clock clock) {
        this.attempts=attempts;this.grants=grants;this.settings=settings;this.secrets=secrets;this.clock=clock;
    }
    /** Null is only valid for a genuine web flow, never a mobile flow lacking its attempt. */
    public MobileLoginStore.Attempt context(ExternalFlowStore.Flow flow) {
        var attempt=attempts.byFlow(flow.id());
        if(flow.challenge()==null) {if(attempt!=null)throw invalid();return null;}
        if(attempt==null || !attempt.provider().equals(flow.provider()) || !attempt.challenge().equals(flow.challenge())
                || attempt.rememberMe()!=flow.rememberMe() || !attempt.returnUri().equals(flow.returnUri())
                || !attempt.expiresAt().equals(flow.expiresAt()) || !attempt.expiresAt().isAfter(clock.instant()))throw invalid();
        var allowed=settings.target(attempt.returnId());
        if(!allowed.returnUri().equals(attempt.returnUri()) || !allowed.profileUri().equals(attempt.profileUri())
                || !allowed.fallbackUri().equals(attempt.fallbackUri()))throw invalid();
        return attempt;
    }
    public String issue(UUID account,MobileLoginStore.Attempt attempt) {
        var now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        if(!attempt.expiresAt().isAfter(now))throw invalid();
        String code=secrets.create();
        grants.issue(account,attempt.id(),attempt.challenge(),secrets.hash(code),now);
        return code;
    }
    private static AuthFailure invalid(){return new AuthFailure(400,"EXTERNAL_STATE_INVALID");}
}
