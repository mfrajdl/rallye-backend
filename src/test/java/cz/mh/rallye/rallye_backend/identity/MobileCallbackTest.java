package cz.mh.rallye.rallye_backend.identity;

import java.time.*;
import java.util.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MobileCallbackTest {
    Instant now=Instant.parse("2026-10-25T12:00:00Z");Clock clock=Clock.fixed(now,ZoneOffset.UTC);SecretTokens secrets=new SecretTokens();
    MobileLoginStore attempts=mock(MobileLoginStore.class);MobileGrantStore grants=mock(MobileGrantStore.class);
    MobileAuthSettings.Target target=new MobileAuthSettings.Target("https://app.example.test/return","https://web.example.test/profile","https://web.example.test/fallback");
    MobileAuthSettings settings=new MobileAuthSettings("https://backend.example.test/api/v1/auth/external/mobile/launch",Map.of("android",target));
    MobileLoginStore.Attempt attempt=new MobileLoginStore.Attempt(UUID.randomUUID(),"GOOGLE",secrets.create(),true,"android",target.returnUri(),target.profileUri(),target.fallbackUri(),now.minusSeconds(100),now.plusSeconds(500));
    ExternalFlowStore.Flow flow(String provider,String challenge,boolean remember,Instant expiry) {
        return new ExternalFlowStore.Flow(UUID.randomUUID(),provider,"LOGIN",null,null,"nonce","correlation","https://backend.example.test/callback",target.returnUri(),remember,challenge,expiry);
    }
    MobileCallbackService service(){return new MobileCallbackService(attempts,grants,settings,secrets,clock);}
    @Test void onlyMatchingLaunchedAttemptAndCurrentlyAllowedDestinationsAreAccepted() {
        var f=flow("GOOGLE",attempt.challenge(),true,attempt.expiresAt());when(attempts.byFlow(f.id())).thenReturn(attempt);
        assertEquals(attempt,service().context(f));verify(attempts).byFlow(f.id());
        settings=new MobileAuthSettings(settings.launchUri(),Map.of("android",new MobileAuthSettings.Target("https://changed.example.test/return",target.profileUri(),target.fallbackUri())));
        assertThrows(AuthFailure.class,()->service().context(f));verifyNoInteractions(grants);
    }
    @Test void providerChallengeRememberAndExpiryMixupsNeverIssueGrant() {
        for(var f:List.of(flow("APPLE",attempt.challenge(),true,attempt.expiresAt()),flow("GOOGLE",secrets.create(),true,attempt.expiresAt()),
                flow("GOOGLE",attempt.challenge(),false,attempt.expiresAt()),flow("GOOGLE",attempt.challenge(),true,now))) {
            when(attempts.byFlow(f.id())).thenReturn(attempt);assertThrows(AuthFailure.class,()->service().context(f));
        }
        verifyNoInteractions(grants);
    }
    @Test void mobileFlowWithoutAttemptAndWebFlowWithAttemptFailClosed() {
        var mobile=flow("GOOGLE",secrets.create(),true,attempt.expiresAt());assertThrows(AuthFailure.class,()->service().context(mobile));
        var web=flow("GOOGLE",null,true,attempt.expiresAt());assertNull(service().context(web));
        when(attempts.byFlow(web.id())).thenReturn(attempt);assertThrows(AuthFailure.class,()->service().context(web));
    }
    @Test void issuerStoresOnlyHashAndExactMobileAttemptBinding() {
        UUID account=UUID.randomUUID();String code=service().issue(account,attempt);
        assertTrue(code.matches("[A-Za-z0-9_-]{43}"));
        verify(grants).issue(account,attempt.id(),attempt.challenge(),secrets.hash(code),now);
    }
    @Test void expiryIsRecheckedAtIssuanceAfterProofAndOtherWork() {
        clock=Clock.fixed(attempt.expiresAt(),ZoneOffset.UTC);
        assertThrows(AuthFailure.class,()->service().issue(UUID.randomUUID(),attempt));verifyNoInteractions(grants);
    }
}
