package cz.mh.rallye.rallye_backend.identity;

import java.util.UUID;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import cz.mh.rallye.rallye_backend.shared.persistence.*;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AccountCancellationServiceTest {
    AdminStore roles=mock(AdminStore.class);
    AccountStore accounts=mock(AccountStore.class);
    AccountCancellationStore store=mock(AccountCancellationStore.class);
    AccountParticipationFacts facts=mock(AccountParticipationFacts.class);
    CancellationIdentityCheck identity=mock(CancellationIdentityCheck.class);
    CommandStore commands=mock(CommandStore.class);
    CommandCleanupStore cleanup=mock(CommandCleanupStore.class);
    AuditService audit=mock(AuditService.class);
    MutableClock clock=new MutableClock();
    AuthService.Principal actor=new AuthService.Principal(UUID.randomUUID(),UUID.randomUUID(),false);
    UUID target=UUID.randomUUID(),key=UUID.randomUUID();
    AuditTransactionTest.RecordingTransactions transactions=new AuditTransactionTest.RecordingTransactions();
    AccountCancellationService service;
    AccountStore.Account current=new AccountStore.Account(actor.accountId(),"actor@example.test","Actor","ACTIVE","hash");
    @BeforeEach void setup() {
        var factory=new ProxyFactory(new AccountCancellationService(roles,accounts,store,facts,identity,commands,cleanup,audit,clock,
                mock(cz.mh.rallye.rallye_backend.crews.persistence.CrewTransferStore.class)));
        factory.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        service=(AccountCancellationService)factory.getProxy();
        when(accounts.lockAccount(actor.accountId())).thenReturn(current);
        when(accounts.isActiveSession(actor.sessionId(),actor.accountId(),clock.instant())).thenReturn(true);
        when(roles.administrator(actor.accountId())).thenReturn(true);
        when(roles.count()).thenReturn(2L);
        when(store.lock(any())).thenAnswer(call->new AccountCancellationStore.State(call.getArgument(0),"target@example.test","target@example.test","Target","ACTIVE",null));
        when(facts.forAccount(any())).thenReturn(new AccountParticipationFacts.Facts(false,false));
    }
    String error(Runnable work,int status) {var error=assertThrows(AuthFailure.class,work::run);assertEquals(status,error.status());return error.code();}
    @Test void selfChecksPasswordAndNeverPersistsIt() {
        service.self(actor,"synthetic-password",key);
        verify(identity).verify(current,"synthetic-password");
        verify(store).deleteAccount(actor.accountId());verify(store).deleteSessions(actor.accountId());
        verify(cleanup).deleteRelated(actor.accountId(),"target@example.test");
        verify(audit).record(actor.accountId(),AuditService.Event.ACCOUNT_DELETED,actor.accountId());
        verify(commands,never()).save(any(),any(),any(),any(),any(),any(),any());
        assertEquals(1,transactions.commits);
    }
    @Test void wrongPasswordPreventsAllPersistentChanges() {
        doThrow(AuthFailure.credentials()).when(identity).verify(any(),any());
        assertEquals("INVALID_CREDENTIALS",error(()->service.self(actor,"bad",key),401));
        verifyNoInteractions(store,facts,cleanup,audit,commands);assertEquals(1,transactions.rollbacks);
    }
    @Test void adminUsesCurrentAuthorityAndCannotCancelSelf() {
        when(roles.administrator(actor.accountId())).thenReturn(false);
        assertEquals("ADMIN_REQUIRED",error(()->service.administrator(actor,target,key),403));
        when(roles.administrator(actor.accountId())).thenReturn(true);
        assertEquals("SELF_ACCOUNT_CANCELLATION_FORBIDDEN",error(()->service.administrator(actor,actor.accountId(),key),403));
        verifyNoInteractions(store,cleanup,audit);
    }
    @Test void revokedSessionAndMissingActorAreDenied() {
        when(accounts.isActiveSession(any(),any(),any())).thenReturn(false);
        error(()->service.self(actor,"password",key),401);
        when(accounts.lockAccount(actor.accountId())).thenReturn(null);
        error(()->service.administrator(actor,target,key),401);verifyNoInteractions(store,identity);
    }
    @Test void lastActiveAdminIsBlockedBeforeCleanup() {
        when(roles.administrator(target)).thenReturn(true);when(roles.count()).thenReturn(1L);
        assertEquals("LAST_ADMIN_REQUIRED",error(()->service.administrator(actor,target,key),409));
        verifyNoInteractions(facts,cleanup,audit);verify(store,never()).deleteRoles(any());
    }
    @Test void historySelectsAnonymizationAndAuditOutcome() {
        when(facts.forAccount(target)).thenReturn(new AccountParticipationFacts.Facts(true,false));
        service.administrator(actor,target,key);
        verify(store).anonymize(target,clock.instant());verify(store,never()).deleteAccount(any());
        verify(audit).record(actor.accountId(),AuditService.Event.ACCOUNT_ANONYMIZED,target);
        verify(commands).save(eq(actor.accountId()),eq(key),eq(target),eq("ADMIN_ACCOUNT_CANCEL"),eq("[\""+target+"\"]"),contains("ANONYMIZED"),eq(clock.instant()));
        verifyNoInteractions(identity);
    }
    @Test void runningParticipationAndUnavailableFactsFailBeforeChanges() {
        when(facts.forAccount(target)).thenReturn(new AccountParticipationFacts.Facts(true,true));
        assertEquals("ACCOUNT_PARTICIPATION_RUNNING",error(()->service.administrator(actor,target,key),409));
        when(facts.forAccount(target)).thenThrow(new IllegalStateException("Unavailable"));
        assertEquals("PARTICIPATION_CHECK_UNAVAILABLE",error(()->service.administrator(actor,target,key),503));
        verifyNoInteractions(cleanup,audit);verify(store,never()).detachCrews(any(),any());
    }
    @Test void adminReplayPrecedesTargetLookupAndDoesNotAuditAgain() {
        when(commands.replay(any(),any(),any(),any(),any())).thenReturn("{\"outcome\":\"DELETED\"}");
        service.administrator(actor,target,key);verifyNoInteractions(store,facts,cleanup,audit);
    }
    @Test void missingOrAlreadyAnonymizedTargetIs404WithNewKey() {
        when(store.lock(target)).thenReturn(null);
        assertEquals("ACCOUNT_NOT_FOUND",error(()->service.administrator(actor,target,key),404));
        when(store.lock(target)).thenReturn(new AccountCancellationStore.State(target,null,null,null,"ANONYMIZED",clock.instant()));
        error(()->service.administrator(actor,target,key),404);verifyNoInteractions(cleanup,audit);
    }
    @Test void auditFailureRollsBackTheEntireTransactionAndDoesNotSaveReplay() {
        var marker=new IllegalStateException("Injected audit failure");
        doThrow(marker).when(audit).record(any(),any(),any());
        assertSame(marker,assertThrows(IllegalStateException.class,()->service.administrator(actor,target,key)));
        verify(store).deleteAccount(target);verify(cleanup).deleteRelated(target,"target@example.test");
        verify(commands,never()).save(any(),any(),any(),any(),any(),any(),any());
        assertEquals(1,transactions.rollbacks);assertEquals(0,transactions.commits);
    }
    @Test void cleanupFailureRollsBackAndPreventsFinalDeletion() {
        doThrow(new IllegalStateException("Injected cleanup failure")).when(cleanup).deleteRelated(any(),any());
        assertThrows(IllegalStateException.class,()->service.administrator(actor,target,key));
        verify(store,never()).deleteAccount(any());verifyNoInteractions(audit);assertEquals(1,transactions.rollbacks);
    }
    @Test void namespaceConflictIsNotHiddenByTargetLookup() {
        doThrow(new AuthFailure(409,"IDEMPOTENCY_CONFLICT")).when(commands).replay(any(),any(),any(),any(),any());
        assertEquals("IDEMPOTENCY_CONFLICT",error(()->service.administrator(actor,target,key),409));
        verifyNoInteractions(store,facts,cleanup,audit);
    }
    @Test void cascadeAuditsCaptainAndRoleRemovalInTheSameTransaction() {
        UUID crew=UUID.randomUUID();when(store.ownedCrewIds(target)).thenReturn(java.util.List.of(crew));
        when(store.deleteRoles(target)).thenReturn(1);
        service.administrator(actor,target,key);
        verify(audit).crew(actor.accountId(),AuditService.Event.CREW_CAPTAIN_CHANGED,crew,target,null);
        verify(audit).record(actor.accountId(),AuditService.Event.ADMIN_REVOKED,target);
        verify(audit).record(actor.accountId(),AuditService.Event.ACCOUNT_DELETED,target);
        assertEquals(1,transactions.commits);
    }
    @Test void unavailableParticipationPreservesOriginalCauseAndRollsBack() {
        var marker=new IllegalStateException("Private diagnostic");when(facts.forAccount(target)).thenThrow(marker);
        var failure=assertThrows(AuthFailure.class,()->service.administrator(actor,target,key));
        assertEquals(503,failure.status());assertEquals("PARTICIPATION_CHECK_UNAVAILABLE",failure.code());assertSame(marker,failure.getCause());
        verifyNoInteractions(cleanup,audit);verify(store,never()).detachCrews(any(),any());assertEquals(1,transactions.rollbacks);
    }
}
