package cz.mh.rallye.rallye_backend.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import cz.mh.rallye.rallye_backend.audit.application.AuditService.Event;
import cz.mh.rallye.rallye_backend.audit.persistence.AuditStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AdminServiceTest {
    AdminStore roles = mock(AdminStore.class);
    AccountStore accounts = mock(AccountStore.class);
    AuditService audit = mock(AuditService.class);
    AuditStore events = mock(AuditStore.class);
    MutableClock clock = new MutableClock();
    AdminService service = new AdminService(roles, accounts, audit, events, clock);
    UUID actor = UUID.randomUUID(), target = UUID.randomUUID(), key = UUID.randomUUID(), session = UUID.randomUUID();
    AuthService.Principal principal = new AuthService.Principal(actor,session,false);
    @BeforeEach void setup() {
        when(roles.administrator(actor)).thenReturn(true);
        when(accounts.isActiveSession(session,actor,clock.instant())).thenReturn(true);
        when(roles.activeVerified(target)).thenReturn(true);
    }
    @Test void grantChecksCurrentRoleAndAuditsOnce() {
        service.changeRole(principal,target,true,key);
        var order=inOrder(roles,accounts,audit);
        order.verify(roles).lock(); order.verify(roles).administrator(actor);
        order.verify(accounts).isActiveSession(session,actor,clock.instant());
        verify(roles).grant(target,clock.instant()); verify(audit).record(actor,Event.ADMIN_GRANTED,target);
        verify(roles).saveCommand(actor,key,target,true,clock.instant());
    }
    @Test void removedAdministratorCannotUseOldSession() {
        when(roles.administrator(actor)).thenReturn(false);
        assertEquals(403,assertThrows(AuthFailure.class,()->service.changeRole(principal,target,true,key)).status());
        verifyNoInteractions(audit); verify(roles,never()).grant(any(),any());
    }
    @Test void revokedSessionCannotMutate() {
        when(accounts.isActiveSession(session,actor,clock.instant())).thenReturn(false);
        assertEquals(401,assertThrows(AuthFailure.class,()->service.changeRole(principal,target,true,key)).status());
        verifyNoInteractions(audit);
    }
    @Test void ownRoleCannotBeChanged() {
        assertEquals("SELF_ROLE_CHANGE_FORBIDDEN",assertThrows(AuthFailure.class,()->service.changeRole(principal,actor,false,key)).code());
    }
    @Test void inactiveOrUnknownTargetIsGenericConflict() {
        when(roles.activeVerified(target)).thenReturn(false);
        assertEquals("ADMIN_TARGET_UNAVAILABLE",assertThrows(AuthFailure.class,()->service.changeRole(principal,target,true,key)).code());
    }
    @Test void lastAdministratorCannotBeRemoved() {
        when(roles.hasRole(target)).thenReturn(true); when(roles.count()).thenReturn(1L);
        assertEquals("LAST_ADMIN_REQUIRED",assertThrows(AuthFailure.class,()->service.changeRole(principal,target,false,key)).code());
        verify(roles,never()).revoke(any()); verifyNoInteractions(audit);
    }
    @Test void revokeAuditsAndNoOpDoesNotAddBusinessEvent() {
        service.changeRole(principal,target,false,key); verifyNoInteractions(audit);
        when(roles.hasRole(target)).thenReturn(true); when(roles.count()).thenReturn(2L);
        service.changeRole(principal,target,false,UUID.randomUUID()); verify(audit).record(actor,Event.ADMIN_REVOKED,target);
    }
    @Test void replayReturnsWithoutRepeatingAndDifferentParametersConflict() {
        when(roles.command(actor,key,clock.instant())).thenReturn(new AdminStore.Command(target,true));
        service.changeRole(principal,target,true,key); verifyNoInteractions(audit);
        assertEquals(409,assertThrows(AuthFailure.class,()->service.changeRole(principal,target,false,key)).status());
        verify(roles,never()).grant(any(),any());
    }
    @Test void auditValidatesFiltersAndProvidesBoundedDtoPage() {
        assertThrows(AuthFailure.class,()->service.audit(principal,null,null,null,null,null,-1,50));
        assertThrows(AuthFailure.class,()->service.audit(principal,null,null,null,null,null,0,101));
        assertThrows(AuthFailure.class,()->service.audit(principal,null,null,null,Instant.MAX,Instant.MIN,0,10));
        when(events.find(null,target,Event.ADMIN_GRANTED,null,null,0,1)).thenReturn(List.of(
                new AuditStore.Entry(UUID.randomUUID(),clock.instant(),"USER",actor,"ADMIN_GRANTED",target),
                new AuditStore.Entry(UUID.randomUUID(),clock.instant(),"USER",actor,"ADMIN_GRANTED",target)));
        var page=service.audit(principal,null,target,Event.ADMIN_GRANTED,null,null,0,1);
        assertEquals(1,page.items().size()); assertTrue(page.hasMore());
    }
    @Test void revokeReplayAlsoAcrossSessionsDoesNotRepeatWritesAndDifferentTargetConflicts() {
        when(roles.command(actor,key,clock.instant())).thenReturn(new AdminStore.Command(target,false));
        var otherSession=new AuthService.Principal(actor,UUID.randomUUID(),false);
        when(accounts.isActiveSession(otherSession.sessionId(),actor,clock.instant())).thenReturn(true);
        service.changeRole(principal,target,false,key);
        service.changeRole(otherSession,target,false,key);
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AuthFailure.class,
                ()->service.changeRole(principal,UUID.randomUUID(),false,key)).code());
        verifyNoInteractions(audit);
        verify(roles,never()).revoke(any());
        verify(roles,never()).saveCommand(any(),any(),any(),anyBoolean(),any());
        verify(roles,never()).activeVerified(any());
    }
    @Test void userListNormalizesQueryPaginatesAndReturnsExistingRoles(){
        var first=new AdminStore.User(target,"Target","target@example.test","ACTIVE","LOCAL",true,true);
        var extra=new AdminStore.User(UUID.randomUUID(),"Extra","extra@example.test","ACTIVE","GOOGLE",false,true);
        when(roles.users("Target",true,2,1)).thenReturn(List.of(first,extra));
        var page=service.users(principal," Target ",true,2,1);assertEquals(2,page.page());assertEquals(1,page.size());assertTrue(page.hasMore());assertEquals(1,page.items().size());
        assertEquals(List.of("USER","ADMIN"),page.items().getFirst().roles());assertEquals("LOCAL",page.items().getFirst().identityProvider());assertTrue(page.items().getFirst().captainEligible());
    }
    @Test void userReadRequiresCurrentAdminAndActiveSession(){
        when(roles.administrator(actor)).thenReturn(false);assertEquals("ADMIN_REQUIRED",assertThrows(AuthFailure.class,()->service.users(principal,null,false,0,50)).code());
        assertThrows(AuthFailure.class,()->service.user(principal,target));verify(roles,never()).users(any(),anyBoolean(),anyInt(),anyInt());verify(roles,never()).user(any());
        when(roles.administrator(actor)).thenReturn(true);when(accounts.isActiveSession(session,actor,clock.instant())).thenReturn(false);
        assertEquals(401,assertThrows(AuthFailure.class,()->service.user(principal,target)).status());
    }
    @Test void invalidUserFiltersNeverReachSearch(){
        for(int[] page:List.of(new int[]{-1,50},new int[]{10001,50},new int[]{0,0},new int[]{0,101}))assertEquals(400,assertThrows(AuthFailure.class,()->service.users(principal,null,false,page[0],page[1])).status());
        assertThrows(AuthFailure.class,()->service.users(principal,"x".repeat(255),false,0,50));verify(roles,never()).users(any(),anyBoolean(),anyInt(),anyInt());
    }
    @Test void missingUserIs404AndOrdinaryUserHasOnlyImplicitUserRole(){
        assertEquals("ACCOUNT_NOT_FOUND",assertThrows(AuthFailure.class,()->service.user(principal,target)).code());
        when(roles.user(target)).thenReturn(new AdminStore.User(target,"Pending","pending@example.test","PENDING_EMAIL","LOCAL",false,false));
        var user=service.user(principal,target);assertEquals(List.of("USER"),user.roles());assertFalse(user.captainEligible());
    }

}
