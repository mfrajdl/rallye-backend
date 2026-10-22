package cz.mh.rallye.rallye_backend.identity;

import java.time.*;
import java.util.UUID;
import cz.mh.rallye.rallye_backend.crews.application.CrewTransferLifecycle;
import cz.mh.rallye.rallye_backend.crews.domain.CrewTransfer;
import cz.mh.rallye.rallye_backend.crews.domain.CrewTransfer.Status;
import cz.mh.rallye.rallye_backend.crews.persistence.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CrewTransferLifecycleTest {
    static final String HASH="a".repeat(64), OTHER_HASH="b".repeat(64);
    CrewTransferStore transfers=mock(CrewTransferStore.class);
    CrewStore crews=mock(CrewStore.class);
    AccountStore accounts=mock(AccountStore.class);
    AdminStore roles=mock(AdminStore.class);
    MutableClock clock=new MutableClock();
    CrewTransferLifecycle service=new CrewTransferLifecycle(transfers,crews,accounts,roles,clock);
    UUID from=UUID.randomUUID(),to=UUID.randomUUID(),crewId=UUID.randomUUID(),requestId=UUID.randomUUID();
    AuthService.Principal source=new AuthService.Principal(from,UUID.randomUUID(),false);
    AuthService.Principal target=new AuthService.Principal(to,UUID.randomUUID(),false);
    CrewTransfer pending;
    CrewStore.Crew crew(UUID captain,long version) {
        return new CrewStore.Crew(crewId,captain,"Crew",null,null,null,null,null,null,null,clock.instant(),clock.instant(),version);
    }
    CrewTransfer transfer(Status status,Instant created) {
        return new CrewTransfer(requestId,crewId,from,to,HASH,status,created,created.plus(Duration.ofHours(168)),
                status==Status.PENDING?null:created,status==Status.PENDING?null:to,status==Status.PENDING?null:status.name());
    }
    @BeforeEach void setup() {
        pending=transfer(Status.PENDING,clock.instant());
        when(roles.activeVerified(any())).thenReturn(true);
        when(accounts.isActiveSession(any(),any(),any())).thenReturn(true);
        when(crews.find(crewId)).thenReturn(crew(from,3));
        when(transfers.find(requestId)).thenReturn(pending);when(transfers.lock(requestId)).thenReturn(pending);
        when(transfers.insert(any(),any(),any(),any(),any(),any(),any())).thenAnswer(i->new CrewTransfer(
                i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4),Status.PENDING,
                i.getArgument(5),i.getArgument(6),null,null,null));
        when(transfers.resolve(any(),any(),any(),any(),any())).thenAnswer(i->new CrewTransfer(requestId,crewId,from,to,HASH,
                i.getArgument(1),pending.createdAt(),pending.expiresAt(),i.getArgument(2),i.getArgument(3),i.getArgument(4)));
    }
    @Test void createUsesInternalTargetAndExactly168HoursWithoutChangingCrewVersion() {
        var result=service.create(source,crewId,to,3,HASH);
        assertEquals(from,result.transfer().fromAccountId());assertEquals(to,result.transfer().toAccountId());
        assertEquals(Duration.ofHours(168),Duration.between(result.transfer().createdAt(),result.transfer().expiresAt()));
        assertEquals(3,result.crewVersion());assertEquals(Status.PENDING,result.transfer().status());
        verify(crews,never()).captain(any(),any(),any());
        var order=inOrder(roles,accounts,crews,transfers);
        order.verify(roles).lock();order.verify(accounts).lockAccount(from);order.verify(roles).activeVerified(from);
        order.verify(accounts).isActiveSession(source.sessionId(),from,clock.instant());
        order.verify(accounts).lockAccount(to);order.verify(crews).find(crewId);order.verify(transfers).pending(crewId);
    }
    @Test void onlyCurrentCaptainCanCreateAndAdministratorHasNoImplicitOverride() {
        when(roles.administrator(to)).thenReturn(true);
        assertEquals(404,assertThrows(AuthFailure.class,()->service.create(target,crewId,from,3,HASH)).status());
        verify(transfers,never()).insert(any(),any(),any(),any(),any(),any(),any());
    }
    @Test void unknownInactiveOrUnverifiedTargetCannotReceiveAndSameCaptainCannotReceive() {
        when(roles.activeVerified(to)).thenReturn(false);
        assertEquals("CAPTAIN_UNAVAILABLE",assertThrows(AuthFailure.class,()->service.create(source,crewId,to,3,HASH)).code());
        assertEquals("CAPTAIN_UNCHANGED",assertThrows(AuthFailure.class,()->service.create(source,crewId,from,3,HASH)).code());
        verify(transfers,never()).insert(any(),any(),any(),any(),any(),any(),any());
    }
    @Test void revokedSessionAndUnavailableActorCannotMutate() {
        when(accounts.isActiveSession(any(),any(),any())).thenReturn(false);
        assertEquals(401,assertThrows(AuthFailure.class,()->service.create(source,crewId,to,3,HASH)).status());
        when(accounts.isActiveSession(any(),any(),any())).thenReturn(true);when(roles.activeVerified(from)).thenReturn(false);
        assertEquals(401,assertThrows(AuthFailure.class,()->service.cancel(source,requestId)).status());
        verifyNoInteractions(transfers,crews);
    }
    @Test void invalidHashOrMissingTargetAndVersionFailBeforeWrites() {
        for(String hash:new String[]{"", "plain-token", "a".repeat(63),"A".repeat(64)})
            assertEquals(400,assertThrows(AuthFailure.class,()->service.create(source,crewId,to,3,hash)).status());
        assertEquals(400,assertThrows(AuthFailure.class,()->service.create(source,crewId,null,3,HASH)).status());
        assertEquals(400,assertThrows(AuthFailure.class,()->service.create(source,crewId,to,-1,HASH)).status());
        assertEquals("CREW_VERSION_CONFLICT",assertThrows(AuthFailure.class,()->service.create(source,crewId,to,2,HASH)).code());
        verify(transfers,never()).insert(any(),any(),any(),any(),any(),any(),any());
    }
    @Test void existingPendingRequestIsRejectedWithoutChangingEitherRequestOrCrew() {
        when(transfers.pending(crewId)).thenReturn(pending);
        assertEquals("TRANSFER_ALREADY_PENDING",assertThrows(AuthFailure.class,()->service.create(source,crewId,to,3,OTHER_HASH)).code());
        verify(transfers,never()).resolve(any(),any(),any(),any(),any());
        verify(transfers,never()).insert(any(),any(),any(),any(),any(),any(),any());
    }
    @Test void expiryBoundaryIsExclusiveAndNewRequestExpiresOldBeforeInsert() {
        assertTrue(pending.pendingAt(pending.expiresAt().minusMillis(1)));
        assertFalse(pending.pendingAt(pending.expiresAt()));
        assertFalse(pending.pendingAt(pending.expiresAt().plusMillis(1)));
        when(transfers.pending(crewId)).thenReturn(pending);clock.advance(Duration.ofHours(168));
        var result=service.create(source,crewId,to,3,OTHER_HASH);
        assertEquals(clock.instant(),result.transfer().createdAt());
        var order=inOrder(transfers);
        order.verify(transfers).pending(crewId);
        order.verify(transfers).resolve(requestId,Status.EXPIRED,clock.instant(),null,"EXPIRED");
        order.verify(transfers).insert(any(),eq(crewId),eq(from),eq(to),eq(OTHER_HASH),eq(clock.instant()),eq(clock.instant().plus(Duration.ofHours(168))));
        verify(crews,never()).captain(any(),any(),any());
    }
    @ParameterizedTest @EnumSource(value=Status.class,names={"ACCEPTED","REJECTED","CANCELLED","EXPIRED"})
    void terminalStatesCannotBeResolvedAgain(Status status) {
        when(transfers.find(requestId)).thenReturn(transfer(status,clock.instant()));
        assertEquals("TRANSFER_NOT_PENDING",assertThrows(AuthFailure.class,()->service.accept(target,requestId,HASH)).code());
        assertEquals("TRANSFER_NOT_PENDING",assertThrows(AuthFailure.class,()->service.reject(target,requestId,HASH)).code());
        assertEquals("TRANSFER_NOT_PENDING",assertThrows(AuthFailure.class,()->service.cancel(source,requestId)).code());
        verify(transfers,never()).resolve(any(),any(),any(),any(),any());verifyNoInteractions(crews);
    }
    @Test void expiredPendingCannotBeAcceptedRejectedOrCancelledEvenIfStoredAsPending() {
        clock.advance(Duration.ofHours(168));
        assertEquals("TRANSFER_NOT_PENDING",assertThrows(AuthFailure.class,()->service.accept(target,requestId,HASH)).code());
        assertEquals("TRANSFER_NOT_PENDING",assertThrows(AuthFailure.class,()->service.reject(target,requestId,HASH)).code());
        assertEquals("TRANSFER_NOT_PENDING",assertThrows(AuthFailure.class,()->service.cancel(source,requestId)).code());
        verify(transfers,never()).resolve(any(),any(),any(),any(),any());verifyNoInteractions(crews);
    }
    @Test void acceptsCurrentVersionAfterTextOrPhotoEditsAndChangesCaptainExactlyOnce() {
        when(crews.find(crewId)).thenReturn(crew(from,17));
        var accepted=service.accept(target,requestId,HASH);
        assertEquals(18,accepted.crewVersion());assertEquals(Status.ACCEPTED,accepted.transfer().status());
        verify(crews).captain(crewId,to,clock.instant());
        verify(transfers).resolve(requestId,Status.ACCEPTED,clock.instant(),to,"ACCEPTED");
    }
    @Test void rejectAndCancelDoNotChangeCrewVersion() {
        assertEquals(3,service.reject(target,requestId,HASH).crewVersion());
        assertEquals(3,service.cancel(source,requestId).crewVersion());
        verify(crews,never()).captain(any(),any(),any());
        verify(transfers).resolve(requestId,Status.REJECTED,clock.instant(),to,"REJECTED");
        verify(transfers).resolve(requestId,Status.CANCELLED,clock.instant(),from,"OWNER_CANCELLED");
    }
    @Test void wrongActorOrTokenCannotResolveAndDoesNotConsumeAnything() {
        assertEquals(403,assertThrows(AuthFailure.class,()->service.accept(source,requestId,HASH)).status());
        assertEquals(403,assertThrows(AuthFailure.class,()->service.cancel(target,requestId)).status());
        assertEquals("INVALID_OR_EXPIRED_TOKEN",assertThrows(AuthFailure.class,()->service.accept(target,requestId,OTHER_HASH)).code());
        verify(transfers,never()).resolve(any(),any(),any(),any(),any());verify(crews,never()).captain(any(),any(),any());
    }
    @Test void changedOwnerCannotBeOverwrittenByOldRequest() {
        when(crews.find(crewId)).thenReturn(crew(UUID.randomUUID(),4));
        assertEquals("TRANSFER_OWNER_CHANGED",assertThrows(AuthFailure.class,()->service.accept(target,requestId,HASH)).code());
        verify(transfers,never()).resolve(any(),any(),any(),any(),any());
    }
    @Test void transferDiagnosticsNeverIncludeTokenDigest() {
        assertFalse(pending.toString().contains(HASH));
        assertFalse(new CrewTransferLifecycle.Transition(pending,0).toString().contains(HASH));
    }
}
