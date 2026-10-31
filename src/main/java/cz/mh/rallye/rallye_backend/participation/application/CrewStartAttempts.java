package cz.mh.rallye.rallye_backend.participation.application;

import java.util.UUID;
import org.springframework.stereotype.Component;
import cz.mh.rallye.rallye_backend.identity.application.LoginThrottle;

/** The existing per-process login limiter, with a separate operation namespace. */
@Component
public final class CrewStartAttempts {
    private final LoginThrottle throttle;
    public CrewStartAttempts(LoginThrottle throttle){this.throttle=throttle;}
    public void check(UUID accountId,String source){throttle.check("race-code-redemption",accountId==null?"":accountId.toString(),source);}
    public void source(String source){throttle.checkSource("race-code-redemption",source);}
    public void account(UUID account){if(account!=null)throttle.checkAccount("race-code-redemption",account.toString());}
}
