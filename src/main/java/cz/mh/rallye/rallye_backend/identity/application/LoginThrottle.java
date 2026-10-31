package cz.mh.rallye.rallye_backend.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Bounded, per-process fixed windows. Both account and source limits apply to every attempt. */
@Component
public final class LoginThrottle {
    private record Window(Instant until, int count) { }
    private final Map<String, Window> windows = new HashMap<>();
    private final Clock clock;
    private final SecretTokens tokens;
    public LoginThrottle(Clock clock, SecretTokens tokens) { this.clock = clock; this.tokens = tokens; }
    public synchronized void check(String operation, String email, String source) {
        Instant now = clock.instant();
        windows.entrySet().removeIf(entry -> !entry.getValue().until().isAfter(now));
        if (!email.isBlank()) take(operation + ":email:" + tokens.hash(email), 10, now);
        take(operation + ":source:" + tokens.hash(source), 50, now);
    }
    /** Split admission for race HTTP source checks before parsing and account checks after authentication. */
    public synchronized void checkSource(String operation,String source){Instant now=clock.instant();windows.entrySet().removeIf(e->!e.getValue().until().isAfter(now));take(operation+":source:"+tokens.hash(source),50,now);}
    public synchronized void checkAccount(String operation,String account){Instant now=clock.instant();windows.entrySet().removeIf(e->!e.getValue().until().isAfter(now));if(!account.isBlank())take(operation+":email:"+tokens.hash(account),10,now);}
    private void take(String key, int maximum, Instant now) {
        Window window = windows.get(key);
        if (window == null) {
            if (windows.size() >= 10000) throw new AuthFailure(429, "TOO_MANY_ATTEMPTS");
            window = new Window(now.plus(Duration.ofMinutes(15)), 0);
        }
        if (window.count() >= maximum) throw new AuthFailure(429, "TOO_MANY_ATTEMPTS");
        windows.put(key, new Window(window.until(), window.count() + 1));
    }
}
