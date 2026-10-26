package cz.mh.rallye.rallye_backend.identity;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import cz.mh.rallye.rallye_backend.audit.application.AuditService.Event;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProfileServiceTest {
    final UUID id=UUID.randomUUID(), session=UUID.randomUUID();
    final MutableClock clock=new MutableClock();
    final AccountStore store=mock(AccountStore.class);
    final AdminStore roles=mock(AdminStore.class);
    final AuditService audit=mock(AuditService.class);
    final PasswordEncoder passwords=mock(PasswordEncoder.class);
    final AccountMailSender mail=mock(AccountMailSender.class);
    final AuthService.Principal principal=new AuthService.Principal(id,session,false);
    AuthService auth;
    @BeforeEach void setup(){when(passwords.encode(anyString())).thenReturn("dummy");auth=new AuthService(store,new SecretTokens(),passwords,mail,clock,audit,roles);clearInvocations(passwords);}
    AccountStore.Account account(String name,String status,String hash){return new AccountStore.Account(id,"person@example.test",name,status,hash);}
    @ParameterizedTest @ValueSource(strings={"LOCAL","GOOGLE","APPLE"})
    void changeIsProviderIndependentAndGetSeesUpdatedName(String provider){
        var current=new AtomicReference<>(account("Old","ACTIVE",provider.equals("LOCAL")?"local-hash":null));
        when(store.lockAccount(id)).thenAnswer(call->current.get());when(store.account(id)).thenAnswer(call->current.get());
        when(store.isActiveSession(session,id,clock.instant())).thenReturn(true);when(roles.administrator(id)).thenReturn(true);
        doAnswer(call->{current.set(account(call.getArgument(1),"ACTIVE",current.get().passwordHash()));return null;}).when(store).displayName(eq(id),anyString(),eq(clock.instant()));
        var result=auth.changeDisplayName(principal,"  Nové jméno  ");
        assertEquals("Nové jméno",result.displayName());assertEquals(id,result.id());assertEquals("person@example.test",result.email());assertEquals(List.of("USER","ADMIN"),result.roles());
        assertEquals(result,auth.me(principal));
        var order=inOrder(store,audit);order.verify(store).lockAccount(id);order.verify(store).isActiveSession(session,id,clock.instant());order.verify(store).displayName(id,"Nové jméno",clock.instant());order.verify(audit).record(id,Event.PROFILE_NAME_CHANGED,id);
        verify(store,never()).identityProvider(any());verify(store,never()).password(any(),any(),any());verify(store,never()).revokeAll(any(),any());verifyNoInteractions(passwords,mail);
    }
    @Test void repeatedNormalizedNameIsANoOp(){when(store.lockAccount(id)).thenReturn(account("Name","ACTIVE",null));when(store.isActiveSession(session,id,clock.instant())).thenReturn(true);
        assertEquals("Name",auth.changeDisplayName(principal," Name ").displayName());auth.changeDisplayName(principal,"Name");verify(store,never()).displayName(any(),any(),any());verifyNoInteractions(audit);
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ","\t\n","\u2003"})
    void invalidNamesNeverReachPersistence(String name){assertEquals(400,assertThrows(AuthFailure.class,()->auth.changeDisplayName(principal,name)).status());verifyNoInteractions(store,audit,roles);}
    @Test void lengthBoundMatchesRegistration(){assertEquals(400,assertThrows(AuthFailure.class,()->auth.changeDisplayName(principal,"x".repeat(101))).status());
        assertThrows(AuthFailure.class,()->auth.changeDisplayName(principal,"😀".repeat(51)));verifyNoInteractions(store,audit);
        when(store.lockAccount(id)).thenReturn(account("Old","ACTIVE",null));when(store.isActiveSession(session,id,clock.instant())).thenReturn(true);assertEquals("x".repeat(100),auth.changeDisplayName(principal,"x".repeat(100)).displayName());}
    @Test void nullPrincipalIsRejected(){assertEquals(401,assertThrows(AuthFailure.class,()->auth.changeDisplayName(null,"Name")).status());verifyNoInteractions(store,audit);}
    @ParameterizedTest @ValueSource(strings={"PENDING_EMAIL","DISABLED","ANONYMIZED"})
    void inactiveAccountCannotBeChanged(String status){when(store.lockAccount(id)).thenReturn(account("Old",status,null));assertEquals(401,assertThrows(AuthFailure.class,()->auth.changeDisplayName(principal,"New")).status());verify(store,never()).displayName(any(),any(),any());verifyNoInteractions(audit);}
    @Test void missingAccountAndRevokedSessionAreRejected(){assertEquals(401,assertThrows(AuthFailure.class,()->auth.changeDisplayName(principal,"New")).status());when(store.lockAccount(id)).thenReturn(account("Old","ACTIVE",null));assertEquals(401,assertThrows(AuthFailure.class,()->auth.changeDisplayName(principal,"New")).status());verify(store,never()).displayName(any(),any(),any());verifyNoInteractions(audit);}
    @Test void auditFailurePropagatesForTransactionRollback(){when(store.lockAccount(id)).thenReturn(account("Old","ACTIVE",null));when(store.isActiveSession(session,id,clock.instant())).thenReturn(true);doThrow(new IllegalStateException("audit unavailable")).when(audit).record(id,Event.PROFILE_NAME_CHANGED,id);assertThrows(IllegalStateException.class,()->auth.changeDisplayName(principal,"New"));}
}
