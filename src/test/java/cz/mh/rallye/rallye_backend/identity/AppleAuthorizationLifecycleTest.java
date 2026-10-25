package cz.mh.rallye.rallye_backend.identity;

import java.time.*;
import java.util.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import cz.mh.rallye.rallye_backend.identity.persistence.AppleRevocationStore.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AppleAuthorizationLifecycleTest {
    AppleLifecycleStore store=mock(AppleLifecycleStore.class);
    AppleRevocationFoundation foundation=mock(AppleRevocationFoundation.class);
    SecretTokens secrets=new SecretTokens();Instant now=Instant.parse("2026-10-25T12:00:00Z");
    AppleAuthorizationLifecycle service=new AppleAuthorizationLifecycle(store,foundation,secrets,Clock.fixed(now,ZoneOffset.UTC));
    AppleLifecycleStore.Identity identity=new AppleLifecycleStore.Identity("https://appleid.apple.com","subject");
    @Test void tokenlessRegistrationCannotBeAccepted(){
        assertThrows(AuthFailure.class,()->service.capture(UUID.randomUUID(),"subject","client",null,UUID.randomUUID(),null));verifyNoInteractions(foundation);
    }
    @Test void tokenlessRepeatLoginRequiresExactExistingSubjectAndClientMaterial(){
        UUID account=UUID.randomUUID();when(store.hasMaterial(account,"subject","client")).thenReturn(true);
        assertDoesNotThrow(()->service.capture(UUID.randomUUID(),"subject","client",account,null,null));verifyNoInteractions(foundation);
        assertThrows(AuthFailure.class,()->service.capture(UUID.randomUUID(),"other","client",account,null,null));
    }
    @Test void captureCannotOmitVerifiedContextOrHaveAmbiguousOwner(){
        for(String client:Arrays.asList(null,""))assertThrows(AuthFailure.class,()->service.capture(UUID.randomUUID(),"subject",client,UUID.randomUUID(),null,"synthetic"));
        assertThrows(AuthFailure.class,()->service.capture(UUID.randomUUID(),"subject","client",null,null,"synthetic"));verifyNoInteractions(foundation);
    }
    @Test void liveSiblingDefersRevocationAndOnlyDetachedContextsMayBeDeleted(){
        when(store.expiredIdentities(now)).thenReturn(List.of(identity));when(store.hasLiveContext(identity,now)).thenReturn(true);
        service.purge(now);verifyNoInteractions(foundation);verify(store).deleteDetachedExpired(now);
    }
    @Test void existingAccountOfAnyStatusPreventsAbandonmentAndBindsItsRegistrations(){
        UUID account=UUID.randomUUID(),c=UUID.randomUUID();when(store.expiredIdentities(now)).thenReturn(List.of(identity));when(store.owner(identity)).thenReturn(account);when(store.pendingForAccount(account)).thenReturn(List.of(c));
        service.purge(now);verify(foundation).bindAccount(c,account);verify(foundation,never()).enqueue(any(),any());verify(store,never()).hasLiveContext(any(),any());
    }
    @Test void definitiveExpiryEnqueuesEveryCaseBeforeDeletingContexts(){
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();when(store.expiredIdentities(now)).thenReturn(List.of(identity));when(store.pending(identity)).thenReturn(List.of(a,b));
        service.purge(now);var order=inOrder(foundation,store);order.verify(foundation).enqueue(a,Reason.REGISTRATION_ABANDONED);order.verify(foundation).enqueue(b,Reason.REGISTRATION_ABANDONED);order.verify(store).deleteDetachedExpired(now);
    }
    @Test void uncertainOrBrokenCaseFailsClosedWithoutDeletingContexts(){
        UUID c=UUID.randomUUID();when(store.expiredIdentities(now)).thenReturn(List.of(identity));when(store.pending(identity)).thenReturn(List.of(c));when(foundation.enqueue(c,Reason.REGISTRATION_ABANDONED)).thenThrow(new IllegalStateException("injected missing material"));
        assertThrows(IllegalStateException.class,()->service.purge(now));verify(store,never()).deleteDetachedExpired(any());
    }
    @Test void cancellationEnqueuesBeforeInvalidatingAndDoesNotRevokeByProposedEmail(){
        UUID account=UUID.randomUUID(),c=UUID.randomUUID(),reg=UUID.randomUUID();when(store.ownedCases(account)).thenReturn(List.of(c));when(store.registrationsForEmail(account)).thenReturn(List.of(new AppleLifecycleStore.Registration(reg,identity)));
        service.cancel(account);var order=inOrder(store,foundation);order.verify(foundation).enqueue(c,Reason.ACCOUNT_CANCELLATION);order.verify(store).invalidate(eq(reg),matches("[a-f0-9]{64}"),eq(now));
        verify(foundation,never()).enqueue(any(),eq(Reason.REGISTRATION_ABANDONED));
    }
    @Test void cancellationFailureStopsAllFurtherCleanup(){
        UUID account=UUID.randomUUID(),c=UUID.randomUUID();when(store.ownedCases(account)).thenReturn(List.of(c));when(foundation.enqueue(c,Reason.ACCOUNT_CANCELLATION)).thenThrow(new IllegalStateException("injected"));
        assertThrows(IllegalStateException.class,()->service.cancel(account));verify(store,never()).registrationsForEmail(any());
    }
    @Test void barrierUsesIssuerAndSubjectAndRedactsIdentity(){
        service.blocked(identity.issuer(),identity.subject());verify(store).blocked(secrets.hash(identity.issuer()+"\n"+identity.subject()));assertFalse(identity.toString().contains("subject"));
    }
}
