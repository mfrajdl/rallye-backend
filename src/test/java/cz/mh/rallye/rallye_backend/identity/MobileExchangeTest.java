package cz.mh.rallye.rallye_backend.identity;

import java.time.*;
import java.util.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MobileExchangeTest {
    Instant now=Instant.parse("2026-10-25T12:00:00Z");Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    SecretTokens secrets=new SecretTokens();String code=secrets.create(),verifier=secrets.create();UUID request=UUID.randomUUID(),account=UUID.randomUUID(),session=UUID.randomUUID();
    MobileGrantStore grants=mock(MobileGrantStore.class);AccountStore accounts=mock(AccountStore.class);AdminStore control=mock(AdminStore.class);AuditService audit=mock(AuditService.class);
    MobileGrantStore.Grant grant=new MobileGrantStore.Grant(UUID.randomUUID(),account,request,MobileExchangeService.challenge(verifier),now,now.plusSeconds(60),null);
    MobileExchangeService service(){return new MobileExchangeService(grants,accounts,control,audit,secrets,clock);}
    void ready(){
        when(grants.find(secrets.hash(code),false)).thenReturn(grant);when(grants.find(secrets.hash(code),true)).thenReturn(grant);
        when(accounts.lockAccount(account)).thenReturn(new AccountStore.Account(account,"a@example.test","A","ACTIVE",null));
        when(accounts.identityProvider(account)).thenReturn("GOOGLE");when(grants.boundAttempt(grant,"GOOGLE")).thenReturn(true);
        when(grants.consume(eq(grant.id()),any())).thenReturn(true);when(accounts.insertSession(eq(account),any(),anyBoolean(),any(),any())).thenReturn(session);
    }
    @Test void pkceS256MatchesRfc7636Vector(){
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",MobileExchangeService.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }
    @Test void inputValidationHappensBeforePersistence(){
        for(String invalid:Arrays.asList(null,"",code+"=","x".repeat(100)))assertEquals("INVALID_REQUEST",assertThrows(AuthFailure.class,()->service().exchange(invalid,request,verifier)).code());
        for(String invalid:Arrays.asList(null,"x".repeat(42),"x".repeat(129),"!".repeat(43),"á".repeat(43)))assertThrows(AuthFailure.class,()->service().exchange(code,request,invalid));
        assertThrows(AuthFailure.class,()->service().exchange(code,null,verifier));verifyNoInteractions(grants,accounts,control,audit);
    }
    @Test void successUsesExistingSessionIdleAndAuditAndCorrectLockOrder(){
        ready();when(grants.rememberMe(request)).thenReturn(true);var result=service().exchange(code,request,verifier);
        assertEquals(new AuthService.Principal(account,session,true),result.principal());assertFalse(result.toString().contains(result.secret()));
        var order=inOrder(grants,accounts,control,audit);order.verify(grants).find(secrets.hash(code),false);order.verify(control).lock();order.verify(accounts).lockAccount(account);order.verify(grants).find(secrets.hash(code),true);
        order.verify(grants).consume(grant.id(),now);order.verify(accounts).insertSession(account,secrets.hash(result.secret()),true,now,now.plus(Duration.ofDays(30)));order.verify(audit).record(account,AuditService.Event.LOGIN_SUCCEEDED,account);
    }
    @Test void ordinarySessionHas24HourIdle(){ready();service().exchange(code,request,verifier);verify(accounts).insertSession(eq(account),any(),eq(false),eq(now),eq(now.plus(Duration.ofHours(24))));}
    @Test void wrongCodeRequestAndVerifierShareErrorAndNeverConsume(){
        ready();assertEquals("EXTERNAL_HANDOFF_INVALID",assertThrows(AuthFailure.class,()->service().exchange(secrets.create(),request,verifier)).code());
        assertEquals("EXTERNAL_HANDOFF_INVALID",assertThrows(AuthFailure.class,()->service().exchange(code,UUID.randomUUID(),verifier)).code());
        assertEquals("EXTERNAL_HANDOFF_INVALID",assertThrows(AuthFailure.class,()->service().exchange(code,request,secrets.create())).code());
        verify(grants,never()).consume(any(),any());verify(accounts,never()).insertSession(any(),any(),anyBoolean(),any(),any());verifyNoInteractions(audit);
    }
    @Test void expiryConsumedAndFutureGrantFailClosed(){
        for(var value:List.of(new MobileGrantStore.Grant(grant.id(),account,request,grant.challenge(),now.minusSeconds(60),now,null),
                new MobileGrantStore.Grant(grant.id(),account,request,grant.challenge(),now,now.plusSeconds(60),now),
                new MobileGrantStore.Grant(grant.id(),account,request,grant.challenge(),now.plusSeconds(1),now.plusSeconds(61),null))) {
            ready();when(grants.find(secrets.hash(code),true)).thenReturn(value);assertThrows(AuthFailure.class,()->service().exchange(code,request,verifier));
        }
        verify(grants,never()).consume(any(),any());verifyNoInteractions(audit);
    }
    @Test void accountDisappearanceOrInactiveStateAndChangedBindingFailClosed(){
        ready();when(accounts.lockAccount(account)).thenReturn(null);assertThrows(AuthFailure.class,()->service().exchange(code,request,verifier));
        when(accounts.lockAccount(account)).thenReturn(new AccountStore.Account(account,null,null,"ANONYMIZED",null));assertThrows(AuthFailure.class,()->service().exchange(code,request,verifier));
        ready();when(grants.find(secrets.hash(code),true)).thenReturn(null);assertThrows(AuthFailure.class,()->service().exchange(code,request,verifier));
        ready();when(grants.boundAttempt(grant,"GOOGLE")).thenReturn(false);assertThrows(AuthFailure.class,()->service().exchange(code,request,verifier));
        ready();when(accounts.identityProvider(account)).thenReturn("LOCAL");assertThrows(AuthFailure.class,()->service().exchange(code,request,verifier));
        verify(grants,never()).consume(any(),any());verifyNoInteractions(audit);
    }
    @Test void failedConditionalConsumeNeverCreatesSession(){ready();when(grants.consume(any(),any())).thenReturn(false);assertThrows(AuthFailure.class,()->service().exchange(code,request,verifier));verify(accounts,never()).insertSession(any(),any(),anyBoolean(),any(),any());verifyNoInteractions(audit);}
}
