package cz.mh.rallye.rallye_backend.identity;

import java.time.*;
import java.util.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MobileLoginServiceTest {
    Instant now=Instant.parse("2026-10-25T12:00:00Z");Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    SecretTokens secrets=new SecretTokens();MobileLoginStore store=mock(MobileLoginStore.class);
    ExternalFlowService flows=mock(ExternalFlowService.class);
    ExternalProviders providers=mock(ExternalProviders.class);
    MobileAuthSettings.Target target=new MobileAuthSettings.Target("https://app.example.test/mobile/return","https://app.example.test/profile","https://app.example.test/mobile/fallback");
    MobileAuthSettings settings=new MobileAuthSettings("https://backend.example.test/api/v1/auth/external/mobile/launch",Map.of("android",target));
    MobileLoginService service(){return new MobileLoginService(settings,providers,store,flows,secrets,clock);}
    MobileLoginStore.Attempt attempt(){return new MobileLoginStore.Attempt(UUID.randomUUID(),"GOOGLE",secrets.create(),true,"android",target.returnUri(),target.profileUri(),target.fallbackUri(),now.minusSeconds(50),now.plusSeconds(550));}
    @Test void startPersistsOnlyTicketHashAndKeepsTenMinuteDeadline() {
        var result=service().start(ExternalProviders.Provider.GOOGLE,ExternalFlowService.Purpose.LOGIN,"android",true,secrets.create());
        String ticket=java.net.URI.create(result.browserStartUrl()).getQuery().substring("ticket=".length());
        assertTrue(ticket.matches("[A-Za-z0-9_-]{43}"));assertEquals(now.plusSeconds(600),result.expiresAt());assertEquals("MOBILE_BOOTSTRAP",result.stage());
        verify(store).insert(argThat(a->a.id().equals(result.requestId())&&a.rememberMe()&&a.createdAt().equals(now)&&a.expiresAt().equals(result.expiresAt())),eq(secrets.hash(ticket)));
        assertFalse(result.toString().contains(ticket));verifyNoInteractions(flows);
    }
    @Test void invalidChallengePurposeAndUnknownDestinationNeverPersist() {
        for(String challenge:Arrays.asList(null,"plain","a".repeat(44),"+".repeat(43)))
            assertEquals("INVALID_REQUEST",assertThrows(AuthFailure.class,()->service().start(ExternalProviders.Provider.GOOGLE,ExternalFlowService.Purpose.LOGIN,"android",false,challenge)).code());
        assertThrows(AuthFailure.class,()->service().start(ExternalProviders.Provider.GOOGLE,null,"android",false,secrets.create()));
        assertThrows(AuthFailure.class,()->service().start(ExternalProviders.Provider.GOOGLE,ExternalFlowService.Purpose.LOGIN,"web",false,secrets.create()));
        verifyNoInteractions(store,flows);
    }
    @Test void malformedMissingExpiredOrReplayedTicketNeverCreatesFlow() {
        for(String ticket:Arrays.asList(null,"bad",secrets.create()))
            assertEquals("EXTERNAL_MOBILE_REQUEST_INVALID",assertThrows(AuthFailure.class,()->service().launch(ticket)).code());
        verifyNoInteractions(flows);verify(store,never()).consume(any(),any(),any());
    }
    @Test void launchPreservesChallengeRememberMeAndOriginalDeadline() {
        String ticket=secrets.create();var a=attempt();when(store.lock(secrets.hash(ticket),now)).thenReturn(a);
        var started=new ExternalFlowService.Started("https://accounts.google.com/example",secrets.create(),a.expiresAt());
        when(flows.startMobile(eq(ExternalProviders.Provider.GOOGLE),any(),eq(target.returnUri()),eq(true),eq(a.challenge()),eq(a.createdAt()))).thenReturn(started);
        when(store.consume(eq(a.id()),any(),eq(now))).thenReturn(true);
        assertSame(started,service().launch(ticket).flow());
        var order=inOrder(store,flows);order.verify(store).lock(anyString(),eq(now));order.verify(flows).startMobile(any(),any(),any(),eq(true),eq(a.challenge()),eq(a.createdAt()));order.verify(store).consume(eq(a.id()),any(),eq(now));
    }
    @Test void configurationChangeAndConsumeLoserFailClosed() {
        String ticket=secrets.create();var a=attempt();when(store.lock(any(),any())).thenReturn(a);
        assertThrows(AuthFailure.class,()->service().launch(ticket));
        settings=new MobileAuthSettings(settings.launchUri(),Map.of("android",new MobileAuthSettings.Target("https://other.example.test/return",target.profileUri(),target.fallbackUri())));
        clearInvocations(flows);assertThrows(AuthFailure.class,()->service().launch(ticket));verifyNoInteractions(flows);
    }
    @Test void onlyExactHttpsServerOwnedDestinationsAreAccepted() {
        for(String bad:Arrays.asList(null,"http://example.test/a","https://user@example.test/a","https://example.test/a?code=x","https://example.test/a#x","https://*.example.test/a")) {
            var config=new MobileAuthSettings(settings.launchUri(),Map.of("android",new MobileAuthSettings.Target(bad,target.profileUri(),target.fallbackUri())));
            assertThrows(AuthFailure.class,()->config.target("android"));
        }
        assertThrows(AuthFailure.class,()->new MobileAuthSettings("https://example.test/other",Map.of()).checkedLaunchUri());
        assertEquals(target,settings.target("android"));
    }    @Test void actualProviderUrlAndStoredReturnNeverReceiveLaunchTicketOrMobileVerifier() {
        var provider=new ExternalProviders(new ExternalProviders.Settings(Map.of(ExternalProviders.Provider.GOOGLE,
            new ExternalProviders.Client(true,"synthetic-client","synthetic-secret","https://backend.example.test/api/v1/auth/external/GOOGLE/callback")),Map.of()));
        var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call->new org.springframework.transaction.support.SimpleTransactionStatus());
        var flowStore=mock(ExternalFlowStore.class);
        var protocol=new ExternalFlowService(provider,flowStore,secrets,clock,manager,mock(ExternalOidcVerifier.class));
        var application=new MobileLoginService(settings,provider,store,protocol,secrets,clock);
        String mobileChallenge=secrets.create();var started=application.start(ExternalProviders.Provider.GOOGLE,ExternalFlowService.Purpose.LOGIN,"android",false,mobileChallenge);
        String ticket=java.net.URI.create(started.browserStartUrl()).getQuery().substring("ticket=".length());
        var attempt=org.mockito.ArgumentCaptor.forClass(MobileLoginStore.Attempt.class);verify(store).insert(attempt.capture(),any());
        when(store.lock(secrets.hash(ticket),now)).thenReturn(attempt.getValue());when(store.consume(any(),any(),any())).thenReturn(true);
        var launched=application.launch(ticket);String url=launched.flow().authorizationUrl();
        assertFalse(url.contains(ticket));assertFalse(url.contains("ticket="));
        var params=org.springframework.web.util.UriComponentsBuilder.fromUriString(url).build().getQueryParams();
        assertNotEquals(mobileChallenge,params.getFirst("code_challenge"));assertEquals("S256",params.getFirst("code_challenge_method"));
        verify(flowStore).insert(argThat(flow->flow.challenge().equals(mobileChallenge)&&flow.returnUri().equals(target.returnUri())&&!flow.returnUri().contains(ticket)),any(),any());
    }

}
