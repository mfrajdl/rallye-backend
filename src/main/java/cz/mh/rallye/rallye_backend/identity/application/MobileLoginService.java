package cz.mh.rallye.rallye_backend.identity.application;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import cz.mh.rallye.rallye_backend.identity.persistence.MobileLoginStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class MobileLoginService {
    public record Started(UUID requestId,String browserStartUrl,Instant expiresAt,String stage) {
        @Override public String toString(){return "MobileLoginStarted[redacted]";}
    }
    public record Launched(ExternalProviders.Provider provider,ExternalFlowService.Started flow) {
        @Override public String toString(){return "MobileLoginLaunched[redacted]";}
    }
    private final MobileAuthSettings settings;private final ExternalProviders providers;
    private final MobileLoginStore store;private final ExternalFlowService flows;private final SecretTokens secrets;private final Clock clock;
    public MobileLoginService(MobileAuthSettings settings,ExternalProviders providers,MobileLoginStore store,
            ExternalFlowService flows,SecretTokens secrets,Clock clock) {
        this.settings=settings;this.providers=providers;this.store=store;this.flows=flows;this.secrets=secrets;this.clock=clock;
    }
    @Transactional
    public Started start(ExternalProviders.Provider provider,ExternalFlowService.Purpose purpose,String returnId,boolean remember,String challenge) {
        if(purpose!=ExternalFlowService.Purpose.LOGIN || challenge==null || !challenge.matches("[A-Za-z0-9_-]{43}"))throw invalidRequest();
        providers.registration(provider);
        var target=settings.target(returnId);String launch=settings.checkedLaunchUri();
        Instant now=now(),expires=now.plus(ExternalFlowService.FLOW_LIFE);UUID id=UUID.randomUUID();String ticket=secrets.create();
        store.insert(new MobileLoginStore.Attempt(id,provider.name(),challenge,remember,returnId,target.returnUri(),
            target.profileUri(),target.fallbackUri(),now,expires),secrets.hash(ticket));
        return new Started(id,UriComponentsBuilder.fromUriString(launch).queryParam("ticket",ticket).build().encode().toUriString(),expires,"MOBILE_BOOTSTRAP");
    }
    @Transactional
    public Launched launch(String ticket) {
        if(ticket==null || !ticket.matches("[A-Za-z0-9_-]{43}"))throw invalidTicket();
        var attempt=store.lock(secrets.hash(ticket),now());
        if(attempt==null)throw invalidTicket();
        var target=settings.target(attempt.returnId());settings.checkedLaunchUri();
        if(!target.returnUri().equals(attempt.returnUri()) || !target.profileUri().equals(attempt.profileUri())
                || !target.fallbackUri().equals(attempt.fallbackUri()))throw invalidTicket();
        var provider=ExternalProviders.Provider.valueOf(attempt.provider());UUID flowId=UUID.randomUUID();
        var flow=flows.startMobile(provider,flowId,attempt.returnUri(),attempt.rememberMe(),attempt.challenge(),attempt.createdAt());
        if(!store.consume(attempt.id(),flowId,now()))throw invalidTicket();
        return new Launched(provider,flow);
    }
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
    private static AuthFailure invalidRequest(){return new AuthFailure(400,"INVALID_REQUEST");}
    private static AuthFailure invalidTicket(){return new AuthFailure(400,"EXTERNAL_MOBILE_REQUEST_INVALID");}
}
