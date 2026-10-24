package cz.mh.rallye.rallye_backend.identity;

import java.time.*;
import java.util.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ExternalFlowTest {
    SecretTokens secrets=new SecretTokens();
    Clock clock=Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"),ZoneOffset.UTC);
    ExternalFlowStore store=mock(ExternalFlowStore.class);
    AccountStore accounts=mock(AccountStore.class);
    ExternalOidcVerifier verifier=mock(ExternalOidcVerifier.class);
    ExternalProviders providers=new ExternalProviders(new ExternalProviders.Settings(Map.of(
        ExternalProviders.Provider.GOOGLE,new ExternalProviders.Client(true,"client","synthetic-secret","https://backend.example.test/api/v1/auth/external/GOOGLE/callback"),
        ExternalProviders.Provider.APPLE,new ExternalProviders.Client(true,"apple-client","synthetic-secret","https://backend.example.test/api/v1/auth/external/APPLE/callback")),Map.of("web","https://app.example.test/complete")));
    ExternalFlowService service() {
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
        return new ExternalFlowService(providers,store,secrets,clock,manager,verifier);
    }
    ExternalFlowStore.Flow flow(String purpose,Instant expiry,String correlation) {
        return new ExternalFlowStore.Flow(UUID.randomUUID(),"GOOGLE",purpose,null,null,secrets.hash("nonce"),secrets.hash(correlation),
            providers.registration(ExternalProviders.Provider.GOOGLE).getRedirectUri(),"https://app.example.test/complete",false,null,expiry);
    }
    @Test void defaultProvidersAreDisabledAndConfigurationIsRedacted() {
        var settings=new ExternalProviders.Settings(null,null);
        assertEquals(503,assertThrows(AuthFailure.class,()->new ExternalProviders(settings).registration(ExternalProviders.Provider.GOOGLE)).status());
        assertFalse(new ExternalProviders.Client(true,"client","synthetic-secret","uri").toString().contains("synthetic-secret"));
    }
    @Test void startStoresOnlyDigestsAndExactlyTenMinuteLifetime() {
        var result=service().start(ExternalProviders.Provider.GOOGLE,ExternalFlowService.Purpose.LOGIN,null,"web",true,null);
        assertEquals(clock.instant().plusSeconds(600),result.expiresAt());
        assertTrue(result.authorizationUrl().contains("nonce="));assertTrue(result.authorizationUrl().contains("state="));
        verify(store).insert(argThat(f->f.correlationHash().equals(secrets.hash(result.correlation())) && f.account()==null && f.rememberMe()),matches("[0-9a-f]{64}"),eq(clock.instant()));
        verifyNoInteractions(verifier,accounts);
        assertFalse(result.toString().contains(result.correlation()));
    }
    @Test void appleUsesFormPostAndNoUnsupportedOpenidScope() {
        var result=service().start(ExternalProviders.Provider.APPLE,ExternalFlowService.Purpose.LOGIN,null,"web",false,null);
        assertTrue(result.authorizationUrl().contains("response_mode=form_post"));
        assertFalse(providers.registration(ExternalProviders.Provider.APPLE).getScopes().contains("openid"));
    }
    @Test void removedPurposesCannotBeParsedOrPersisted() {
        for(String purpose:List.of("LINK","UNLINK","REAUTHENTICATE"))
            assertThrows(IllegalArgumentException.class,()->ExternalFlowService.Purpose.valueOf(purpose));
        assertThrows(AuthFailure.class,()->service().start(ExternalProviders.Provider.GOOGLE,null,null,"web",false,null));
        verifyNoInteractions(store,verifier,accounts);
    }
    @Test void validBoundPurposeStillCannotPretendFreshExternalAuthentication() {
        var actor=new AuthService.Principal(UUID.randomUUID(),UUID.randomUUID(),false);
        when(accounts.lockAccount(actor.accountId())).thenReturn(new AccountStore.Account(actor.accountId(),"a@example.test","A","ACTIVE",null));
        when(accounts.isActiveSession(eq(actor.sessionId()),eq(actor.accountId()),any())).thenReturn(true);
        String correlation=secrets.create(),state=secrets.create();
        var f=new ExternalFlowStore.Flow(UUID.randomUUID(),"GOOGLE","REAUTHENTICATE",actor.accountId(),actor.sessionId(),secrets.hash("nonce"),secrets.hash(correlation),
            providers.registration(ExternalProviders.Provider.GOOGLE).getRedirectUri(),"https://app.example.test/complete",false,null,clock.instant().plusSeconds(10));
        when(store.find(any())).thenReturn(f);
        assertEquals("EXTERNAL_STATE_INVALID",assertThrows(AuthFailure.class,()->service().callback(ExternalProviders.Provider.GOOGLE,state,correlation,"code")).code());
        verifyNoInteractions(verifier);verify(store,never()).consume(any(),any());
    }
    @Test void providerDenialConsumesOnlyBoundStateWithoutTokenExchange() {
        String state=secrets.create(),correlation=secrets.create();var f=flow("LOGIN",clock.instant().plusSeconds(10),correlation);
        when(store.find(any())).thenReturn(f,null);when(store.consume(f.id(),clock.instant())).thenReturn(true);
        var service=service();assertEquals(f.returnUri(),service.rejectWebCallback(ExternalProviders.Provider.GOOGLE,state,correlation));
        assertThrows(AuthFailure.class,()->service.rejectWebCallback(ExternalProviders.Provider.GOOGLE,state,correlation));verifyNoInteractions(verifier);
    }
    @Test void invalidChallengeOrUnknownReturnNeverPersists() {
        assertThrows(AuthFailure.class,()->service().start(ExternalProviders.Provider.GOOGLE,ExternalFlowService.Purpose.LOGIN,null,"web",false,"plain"));
        assertThrows(AuthFailure.class,()->service().start(ExternalProviders.Provider.GOOGLE,ExternalFlowService.Purpose.LOGIN,null,"untrusted",false,null));
        verifyNoInteractions(store);
    }
    @Test void invalidExpiredAndWrongCorrelationNeverExchange() {
        String state=secrets.create(),correlation=secrets.create();var service=service();
        for(var f:Arrays.asList(null,flow("LOGIN",clock.instant(),correlation),flow("LOGIN",clock.instant().plusSeconds(1),secrets.create()))) {
            when(store.find(secrets.hash(state))).thenReturn(f);
            assertThrows(AuthFailure.class,()->service.callback(ExternalProviders.Provider.GOOGLE,state,correlation,"code"));
        }
        assertThrows(AuthFailure.class,()->service.callback(ExternalProviders.Provider.GOOGLE,"bad",correlation,"code"));
        verifyNoInteractions(verifier);verify(store,never()).consume(any(),any());
    }
    @Test void validProofConsumesOnceAndReplayIsRejectedWithoutSecondExchange() {
        String state=secrets.create(),correlation=secrets.create();var f=flow("LOGIN",clock.instant().plusSeconds(1),correlation);
        when(store.find(secrets.hash(state))).thenReturn(f,f,null);
        when(store.consume(f.id(),clock.instant())).thenReturn(true);
        var service=service();service.callback(ExternalProviders.Provider.GOOGLE,state,correlation,"code");
        assertThrows(AuthFailure.class,()->service.callback(ExternalProviders.Provider.GOOGLE,state,correlation,"code"));
        verify(verifier,times(1)).exchange(any(),eq("code"),eq(state),eq(f.nonceHash()),any());
        verify(store,times(1)).consume(f.id(),clock.instant());verifyNoInteractions(accounts);
    }
    @Test void failedProofNeverConsumesAndConcurrentFinalLoserFails() {
        String state=secrets.create(),correlation=secrets.create();var f=flow("LOGIN",clock.instant().plusSeconds(5),correlation);
        when(store.find(any())).thenReturn(f);doThrow(new AuthFailure(400,"EXTERNAL_PROOF_INVALID")).when(verifier).exchange(any(),any(),any(),any(),any());
        assertThrows(AuthFailure.class,()->service().callback(ExternalProviders.Provider.GOOGLE,state,correlation,"code"));
        verify(store,never()).consume(any(),any());
        reset(verifier);when(store.consume(any(),any())).thenReturn(false);
        assertThrows(AuthFailure.class,()->service().callback(ExternalProviders.Provider.GOOGLE,state,correlation,"code"));
    }
}
