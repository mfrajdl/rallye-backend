package cz.mh.rallye.rallye_backend.crews;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import cz.mh.rallye.rallye_backend.crews.application.*;
import cz.mh.rallye.rallye_backend.crews.persistence.CrewStore;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import cz.mh.rallye.rallye_backend.shared.persistence.CommandStore;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CrewServiceTest {
    CrewStore crews=mock(CrewStore.class);AccountStore accounts=mock(AccountStore.class);
    AdminStore roles=mock(AdminStore.class);CommandStore commands=mock(CommandStore.class);
    AuditService audit=mock(AuditService.class);PhotoFiles files=mock(PhotoFiles.class);
    PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    Clock clock=Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"),ZoneOffset.UTC);
    cz.mh.rallye.rallye_backend.crews.persistence.CrewTransferStore transfers=mock(cz.mh.rallye.rallye_backend.crews.persistence.CrewTransferStore.class);
    CrewService service=new CrewService(crews,accounts,roles,commands,audit,files,clock,manager,transfers);
    UUID actor=UUID.randomUUID(),id=UUID.randomUUID(),other=UUID.randomUUID(),key=UUID.randomUUID();
    AuthService.Principal principal=new AuthService.Principal(actor,UUID.randomUUID(),false);
    CrewStore.Crew row(UUID owner,long version) {
        return new CrewStore.Crew(id,owner,"Crew",null,null,null,null,null,null,null,clock.instant(),clock.instant(),version);
    }
    @BeforeEach void setup() {
        when(manager.getTransaction(any())).thenAnswer(invocation->new SimpleTransactionStatus());
        when(roles.activeVerified(actor)).thenReturn(true);
        when(accounts.isActiveSession(principal.sessionId(),actor,clock.instant())).thenReturn(true);
        when(crews.find(id)).thenReturn(row(actor,0));
        when(files.exclusive(any())).thenAnswer(invocation->((Supplier<?>)invocation.getArgument(0)).get());
    }
    @Test void validatesAllTextBoundariesAndUnicode() {
        CrewService.validate("a".repeat(100),"R".repeat(32),"d".repeat(2000));
        CrewService.validate("🚗".repeat(100),null,null);
        for(String name:new String[]{""," \t","a".repeat(101)}) assertThrows(AuthFailure.class,()->CrewService.validate(name,null,null));
        assertThrows(AuthFailure.class,()->CrewService.validate(null,null,null));
        assertThrows(AuthFailure.class,()->CrewService.validate("x","a".repeat(33),null));
        assertThrows(AuthFailure.class,()->CrewService.validate("x",null,"a".repeat(2001)));
    }
    @Test void deletionRejectsLiveTransferButAllowsExpiredHistory() {
        var pending=new cz.mh.rallye.rallye_backend.crews.domain.CrewTransfer(UUID.randomUUID(),id,actor,other,"a".repeat(64),
                cz.mh.rallye.rallye_backend.crews.domain.CrewTransfer.Status.PENDING,clock.instant(),clock.instant().plusSeconds(604800),null,null,null);
        when(transfers.pending(id)).thenReturn(pending);
        assertEquals("CREW_TRANSFER_PENDING",assertThrows(AuthFailure.class,()->service.delete(principal,id,0)).code());
        verify(crews,never()).delete(any());
        when(transfers.pending(id)).thenReturn(new cz.mh.rallye.rallye_backend.crews.domain.CrewTransfer(pending.id(),id,actor,other,pending.tokenHash(),
                pending.status(),clock.instant().minusSeconds(604800),clock.instant(),null,null,null));
        service.delete(principal,id,0);verify(crews).delete(id);
    }
    @Test void adminCaptainChangeCancelsPendingBeforeWritingCaptainAndAuditFailureRollsBack() {
        when(roles.administrator(actor)).thenReturn(true);when(roles.activeVerified(other)).thenReturn(true);
        var pending=new cz.mh.rallye.rallye_backend.crews.domain.CrewTransfer(UUID.randomUUID(),id,actor,other,"a".repeat(64),
                cz.mh.rallye.rallye_backend.crews.domain.CrewTransfer.Status.PENDING,clock.instant(),clock.instant().plusSeconds(604800),null,null,null);
        when(transfers.pending(id)).thenReturn(pending);
        when(transfers.resolve(any(),any(),any(),any(),any())).thenReturn(pending);
        doThrow(new IllegalStateException("audit failure")).when(audit).transfer(any(),any(),any(),any());
        assertThrows(IllegalStateException.class,()->service.captain(principal,id,0,other,key));
        verify(transfers).resolve(pending.id(),cz.mh.rallye.rallye_backend.crews.domain.CrewTransfer.Status.CANCELLED,
                clock.instant(),actor,"ADMIN_CAPTAIN_CHANGED");
        verify(crews,never()).captain(any(),any(),any());verify(manager).rollback(any());
    }
    @Test void normalUserCannotCreateOrphanOrForAnotherAccount() {
        for(UUID owner:Arrays.asList(null,other))
            assertEquals(403,assertThrows(AuthFailure.class,()->service.create(principal,key,"Crew",null,null,true,owner)).status());
        verifyNoInteractions(commands,audit);verify(crews,never()).insert(any(),any(),any(),any(),any(),any());
    }
    @Test void defaultCaptainIsActorAndCreateStoresOriginalResult() {
        when(crews.find(any(UUID.class))).thenAnswer(invocation->new CrewStore.Crew(invocation.getArgument(0),actor,"Crew",null,null,
                null,null,null,null,null,clock.instant(),clock.instant(),0));
        var result=service.create(principal,key,"Crew",null,null,false,null);
        assertEquals(actor,result.captainAccountId());assertEquals(0,result.version());
        verify(crews).insert(result.id(),actor,"Crew",null,null,clock.instant());
        verify(audit).crew(actor,AuditService.Event.CREW_CREATED,result.id(),null,actor);
        verify(commands).save(eq(actor),eq(key),eq(result.id()),eq("CREW_CREATE"),anyString(),anyString(),eq(clock.instant()));
    }
    @Test void administratorMayCreateOrphanWithoutUserLookupForNullCaptain() {
        when(roles.administrator(actor)).thenReturn(true);
        when(crews.find(any(UUID.class))).thenAnswer(invocation->new CrewStore.Crew(invocation.getArgument(0),null,"Crew",null,null,
                null,null,null,null,null,clock.instant(),clock.instant(),0));
        var result=service.create(principal,key,"Crew",null,null,true,null);
        assertNull(result.captainAccountId());
        verify(crews).insert(result.id(),null,"Crew",null,null,clock.instant());
        verify(audit).crew(actor,AuditService.Event.CREW_CREATED,result.id(),null,null);
    }
    @Test void foreignCrewIsIndistinguishableFromMissingAndNoWriteOccurs() {
        when(crews.find(id)).thenReturn(row(other,0));
        assertEquals(404,assertThrows(AuthFailure.class,()->service.detail(principal,id)).status());
        assertEquals(404,assertThrows(AuthFailure.class,()->service.update(principal,id,0,"x",null,null)).status());
        verifyNoInteractions(audit);
    }
    @Test void staleVersionNeverOverwritesCrew() {
        assertEquals("CREW_VERSION_CONFLICT",assertThrows(AuthFailure.class,()->service.update(principal,id,7,"x",null,null)).code());
        verify(crews,never()).update(any(),any(),any(),any(),any());verifyNoInteractions(audit);
    }
    @Test void staleDeleteDoesNotRemoveCrewAuditOrPhoto() {
        assertEquals("CREW_VERSION_CONFLICT",assertThrows(AuthFailure.class,()->service.delete(principal,id,7)).code());
        verify(crews,never()).delete(any());verifyNoInteractions(audit);verify(files,never()).remove(any());
    }
    @Test void removedAdminAndExpiredSessionCannotChangeCaptain() {
        assertEquals(403,assertThrows(AuthFailure.class,()->service.captain(principal,id,0,other,key)).status());
        when(roles.administrator(actor)).thenReturn(true);
        when(accounts.isActiveSession(any(),any(),any())).thenReturn(false);
        assertEquals(401,assertThrows(AuthFailure.class,()->service.captain(principal,id,0,other,key)).status());
        verifyNoInteractions(commands,audit);
    }
    @Test void administratorMayAssignHimselfToForeignCrewAndAuditOldAndNewOwner() {
        when(roles.administrator(actor)).thenReturn(true);
        when(crews.find(id)).thenReturn(row(other,3),row(actor,4));
        assertEquals(actor,service.captain(principal,id,3,actor,key).captainAccountId());
        verify(audit).crew(actor,AuditService.Event.CREW_CAPTAIN_CHANGED,id,other,actor);
        verify(commands).save(eq(actor),eq(key),eq(id),eq("CREW_CAPTAIN"),anyString(),anyString(),eq(clock.instant()));
    }
    @Test void unchangedAndUnavailableCaptainAreRejected() {
        when(roles.administrator(actor)).thenReturn(true);
        assertEquals("CAPTAIN_UNCHANGED",assertThrows(AuthFailure.class,()->service.captain(principal,id,0,actor,key)).code());
        assertEquals("CAPTAIN_UNAVAILABLE",assertThrows(AuthFailure.class,()->service.captain(principal,id,0,other,key)).code());
        verifyNoInteractions(audit);
    }
    @Test void captainReplayReturnsSnapshotBeforeCheckingNowStaleVersion() {
        when(roles.administrator(actor)).thenReturn(true);
        String stored=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(CrewService.view(row(other,1)));
        when(commands.replay(eq(actor),eq(key),eq("CREW_CAPTAIN"),anyString(),any())).thenReturn(stored);
        assertEquals(1,service.captain(principal,id,0,other,key).version());
        verifyNoInteractions(crews,audit);
    }
    @Test void auditFailureRollsBackTheServiceTransaction() {
        doThrow(new IllegalStateException("Test audit failure")).when(audit).crew(any(),any(),any(),any(),any());
        assertThrows(IllegalStateException.class,()->service.update(principal,id,0,"Changed",null,null));
        verify(manager).rollback(any());verify(manager,never()).commit(any());
    }
    @Test void allCrewListIsAdministrativeAndPageLimitsAreEnforced() {
        assertEquals(403,assertThrows(AuthFailure.class,()->service.list(principal,true,null,false,0,50)).status());
        assertThrows(AuthFailure.class,()->service.list(principal,false,null,false,0,101));
        when(roles.administrator(actor)).thenReturn(true);
        when(crews.list(null,true,true,0,50)).thenReturn(List.of(row(null,0)));
        assertNull(service.list(principal,true,null,true,0,50).items().getFirst().captainAccountId());
    }
}
