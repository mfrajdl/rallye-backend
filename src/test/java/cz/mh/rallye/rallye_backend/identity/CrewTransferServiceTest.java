package cz.mh.rallye.rallye_backend.identity;

import java.time.*;
import java.util.*;
import cz.mh.rallye.rallye_backend.crews.application.*;
import cz.mh.rallye.rallye_backend.crews.domain.CrewTransfer;
import cz.mh.rallye.rallye_backend.crews.persistence.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import cz.mh.rallye.rallye_backend.shared.persistence.CommandStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CrewTransferServiceTest {
    CrewTransferLifecycle lifecycle=mock(CrewTransferLifecycle.class);
    CrewTransferStore transfers=mock(CrewTransferStore.class);CrewStore crews=mock(CrewStore.class);
    AccountStore accounts=mock(AccountStore.class);AdminStore roles=mock(AdminStore.class);
    CommandStore commands=mock(CommandStore.class);AccountMailSender mail=mock(AccountMailSender.class);
    MutableClock clock=new MutableClock();SecretTokens tokens=new SecretTokens();
    AuditTransactionTest.RecordingTransactions manager=new AuditTransactionTest.RecordingTransactions();
    CrewTransferService service=new CrewTransferService(lifecycle,transfers,crews,accounts,roles,commands,tokens,mail,clock,manager);
    UUID owner=UUID.randomUUID(),target=UUID.randomUUID(),crew=UUID.randomUUID(),id=UUID.randomUUID(),key=UUID.randomUUID();
    AuthService.Principal actor=new AuthService.Principal(owner,UUID.randomUUID(),false);
    String email="target@example.test";
    CrewTransfer pending(String hash) {return new CrewTransfer(id,crew,owner,target,hash,CrewTransfer.Status.PENDING,
            clock.instant(),clock.instant().plus(Duration.ofHours(168)),null,null,null);}
    @BeforeEach void setup() {
        when(roles.activeVerified(any())).thenReturn(true);when(accounts.isActiveSession(any(),any(),any())).thenReturn(true);
        when(accounts.byEmail(email)).thenReturn(new AccountStore.Account(target,email,"Target","ACTIVE",null));
        when(accounts.lockAccount(target)).thenReturn(new AccountStore.Account(target,email,"Target","ACTIVE",null));
        when(crews.find(crew)).thenReturn(new CrewStore.Crew(crew,owner,"Crew",null,null,null,null,null,null,null,clock.instant(),clock.instant(),3));
        when(lifecycle.create(eq(actor),eq(crew),eq(target),eq(3L),anyString())).thenAnswer(call->new CrewTransferLifecycle.Transition(pending(call.getArgument(4)),3));
        when(transfers.find(id)).thenReturn(pending("a".repeat(64)));when(transfers.lock(id)).thenReturn(pending("a".repeat(64)));
        when(transfers.rotate(eq(id),anyString())).thenAnswer(call->pending(call.getArgument(1)));
    }
    @Test void createStoresDigestAndOnlyMailsAfterCommit() {
        doAnswer(call->{assertEquals(1,manager.commits);assertEquals(0,manager.rollbacks);return null;})
                .when(mail).sendTransfer(anyString(),anyString(),any(),anyString(),any());
        var result=service.create(actor,crew,key," TARGET@EXAMPLE.TEST ",3);
        var secret=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mail).sendTransfer(eq(email),eq("Crew"),eq(id),secret.capture(),eq(result.expiresAt()));
        assertTrue(secret.getValue().matches("[A-Za-z0-9_-]{43}"));
        verify(lifecycle).create(actor,crew,target,3,tokens.hash(secret.getValue()));
        verify(commands).save(eq(owner),eq(key),eq(id),eq("CREW_TRANSFER_CREATE"),anyString(),argThat(json->!json.contains("token")),any());
        assertFalse(result.toString().contains(secret.getValue()));assertEquals(target,result.toAccountId());
    }
    @Test void sameKeyReplayDoesNotGenerateAnotherTransferOrEmail() {
        var original=service.create(actor,crew,key,email,3);
        var resultJson=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(commands).save(any(),any(),any(),any(),any(),resultJson.capture(),any());
        when(commands.replay(eq(owner),eq(key),any(),any(),any())).thenReturn(resultJson.getValue());
        clearInvocations(lifecycle,mail);
        assertEquals(original,service.create(actor,crew,key,email,3));verifyNoInteractions(lifecycle,mail);
    }
    @Test void conflictingKeyFailsWithoutNewWork() {
        when(commands.replay(any(),any(),any(),any(),any())).thenThrow(new AuthFailure(409,"IDEMPOTENCY_CONFLICT"));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AuthFailure.class,()->service.create(actor,crew,key,email,3)).code());
        verifyNoInteractions(lifecycle,mail);
    }
    @Test void changedVersionForUsedKeyWinsOverDomainVersionConflict() {
        service.create(actor,crew,key,email,3);
        var savedRequest=org.mockito.ArgumentCaptor.forClass(String.class);
        var savedResult=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(commands).save(eq(owner),eq(key),eq(id),eq("CREW_TRANSFER_CREATE"),savedRequest.capture(),savedResult.capture(),any());
        when(commands.replay(eq(owner),eq(key),eq("CREW_TRANSFER_CREATE"),anyString(),any())).thenAnswer(call->{
            if(!savedRequest.getValue().equals(call.getArgument(3))) throw new AuthFailure(409,"IDEMPOTENCY_CONFLICT");
            return savedResult.getValue();
        });
        when(lifecycle.create(eq(actor),eq(crew),eq(target),eq(4L),anyString()))
                .thenThrow(new AuthFailure(409,"CREW_VERSION_CONFLICT"));
        clearInvocations(lifecycle,mail);
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AuthFailure.class,()->service.create(actor,crew,key,email,4)).code());
        verifyNoInteractions(lifecycle,mail);
        assertEquals("CREW_VERSION_CONFLICT",assertThrows(AuthFailure.class,
                ()->service.create(actor,crew,UUID.randomUUID(),email,4)).code());
    }
    @Test void failureSavingIdempotenceRollsBackAndSuppressesMail() {
        doThrow(new IllegalStateException("injected command write failure")).when(commands).save(any(),any(),any(),any(),any(),any(),any());
        assertThrows(IllegalStateException.class,()->service.create(actor,crew,key,email,3));
        assertEquals(1,manager.rollbacks);verifyNoInteractions(mail);
    }
    @Test void missingOrUnavailableRecipientUsesSameSafeError() {
        when(accounts.byEmail(email)).thenReturn(null);
        assertEquals("CAPTAIN_UNAVAILABLE",assertThrows(AuthFailure.class,()->service.create(actor,crew,key,email,3)).code());
        setup();when(roles.activeVerified(target)).thenReturn(false);
        assertEquals("CAPTAIN_UNAVAILABLE",assertThrows(AuthFailure.class,()->service.create(actor,crew,key,email,3)).code());
        verifyNoInteractions(mail,lifecycle);
    }
    @Test void nonOwnerAndRevokedSessionCannotCreateEvenIfAdministrator() {
        when(crews.find(crew)).thenReturn(null);
        assertEquals(404,assertThrows(AuthFailure.class,()->service.create(actor,crew,key,email,3)).status());
        when(accounts.isActiveSession(any(),any(),any())).thenReturn(false);
        assertEquals(401,assertThrows(AuthFailure.class,()->service.create(actor,crew,key,email,3)).status());
        verifyNoInteractions(mail,lifecycle);
    }
    @ParameterizedTest @ValueSource(strings={"CAPTAIN_UNCHANGED","CREW_VERSION_CONFLICT","TRANSFER_ALREADY_PENDING"})
    void domainFailurePreventsLedgerAndMail(String code) {
        when(lifecycle.create(any(),any(),any(),anyLong(),anyString())).thenThrow(new AuthFailure(409,code));
        assertEquals(code,assertThrows(AuthFailure.class,()->service.create(actor,crew,key,email,3)).code());
        verify(commands,never()).save(any(),any(),any(),any(),any(),any(),any());verifyNoInteractions(mail);
    }
    @Test void resendRotatesSecretButPreservesExpiryAndVersion() {
        var result=service.resend(actor,crew,id,key);var secret=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mail).sendTransfer(eq(email),eq("Crew"),eq(id),secret.capture(),eq(result.expiresAt()));
        verify(transfers).rotate(id,tokens.hash(secret.getValue()));assertEquals(pending("").expiresAt(),result.expiresAt());
        verify(crews,never()).captain(any(),any(),any());
    }
    @Test void resendReplayDoesNotRotateOrMailAgain() {
        var original=service.resend(actor,crew,id,key);var json=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(commands).save(any(),any(),any(),any(),any(),json.capture(),any());
        when(commands.replay(any(),any(),any(),any(),any())).thenReturn(json.getValue());clearInvocations(transfers,mail);
        assertEquals(original,service.resend(actor,crew,id,key));verifyNoInteractions(transfers,mail);
    }
    @Test void expiredResendAndWrongOwnerAreRejected() {
        var expired=pending("a".repeat(64));clock.advance(Duration.ofHours(168));
        when(transfers.lock(id)).thenReturn(expired);
        assertEquals("TRANSFER_NOT_PENDING",assertThrows(AuthFailure.class,()->service.resend(actor,crew,id,key)).code());
        when(crews.find(crew)).thenReturn(null);
        assertEquals(404,assertThrows(AuthFailure.class,()->service.resend(actor,crew,id,key)).status());verifyNoInteractions(mail);
    }
    @Test void failedResendDoesNotMailAndRecordsRollback() {
        doThrow(new IllegalStateException("command failure")).when(commands).save(any(),any(),any(),any(),any(),any(),any());
        assertThrows(IllegalStateException.class,()->service.resend(actor,crew,id,key));
        verify(transfers).rotate(eq(id),anyString());assertEquals(1,manager.rollbacks);verifyNoInteractions(mail);
    }
    @Test void deliveryFailureAfterCommitDoesNotUndoRequest() {
        doThrow(new IllegalStateException("delivery failure")).when(mail).sendTransfer(any(),any(),any(),any(),any());
        assertNotNull(service.create(actor,crew,key,email,3));assertEquals(1,manager.commits);assertEquals(0,manager.rollbacks);
    }
}
