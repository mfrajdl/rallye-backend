package cz.mh.rallye.rallye_backend.identity;

import java.time.*;
import java.util.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ExternalLoginServiceTest {
    ExternalFlowService flows=mock(ExternalFlowService.class);ExternalAccountStore external=mock(ExternalAccountStore.class);
    AccountStore accounts=mock(AccountStore.class);AuthService auth=mock(AuthService.class);AuditService audit=mock(AuditService.class);
    SecretTokens secrets=new SecretTokens();Clock clock=Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"),ZoneOffset.UTC);
    AppleTokenVault vault=new AppleTokenVault(new AppleTokenVault.Settings("test",Map.of("test",Base64.getEncoder().encodeToString(new byte[32]))));
    ExternalProviders providers=mock(ExternalProviders.class);PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    ExternalLoginService service() {
        when(manager.getTransaction(any())).thenAnswer(c->new SimpleTransactionStatus());
        return new ExternalLoginService(flows,external,accounts,auth,secrets,clock,audit,vault,providers,manager,
            jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
    }
    void callback(ExternalProviders.Provider provider,String email,boolean verified,String name) {
        var f=new ExternalFlowStore.Flow(UUID.randomUUID(),provider.name(),"LOGIN",null,null,"nonce","correlation","https://example.test/callback","https://example.test/return",true,null,clock.instant().plusSeconds(60));
        when(flows.verifyWebCallback(any(),any(),any(),any())).thenReturn(new ExternalFlowService.Verified(f,new ExternalOidcVerifier.Identity(ExternalProviders.issuer(provider),"subject",email,verified,name,null),"state","cookie"));
    }
    ExternalAccountStore.Registration registration(boolean verified) {
        return new ExternalAccountStore.Registration(UUID.randomUUID(),"GOOGLE","https://accounts.google.com","subject","a@example.test",verified,"Name",true,"https://example.test/return",clock.instant().plusSeconds(900),null);
    }
    @Test void missingAppleEncryptionConfigurationFailsBeforeProviderExchange() {
        vault=new AppleTokenVault(new AppleTokenVault.Settings(null,Map.of()));
        assertEquals("APPLE_CREDENTIAL_STORAGE_UNAVAILABLE",assertThrows(AuthFailure.class,()->service().callback(ExternalProviders.Provider.APPLE,"s","c","code",null)).code());
        verifyNoInteractions(flows,external,accounts,audit);
    }
    @Test void firstCallbackCreatesOnlyFifteenMinuteContextWithDigestedToken() {
        callback(ExternalProviders.Provider.GOOGLE,"A@example.test",true,"Name");
        var out=service().callback(ExternalProviders.Provider.GOOGLE,"state","cookie","code",null);
        assertNull(out.session());assertNotNull(out.registrationSecret());
        verify(external).registration(argThat(r->r.expiresAt().equals(clock.instant().plusSeconds(900)) && r.email().equals("a@example.test") && r.verified()),eq(secrets.hash(out.registrationSecret())),eq(clock.instant()));
        verifyNoInteractions(accounts,audit,auth);
    }
    @Test void knownIdentityCreatesFreshSessionAndDoesNotOverwriteProfile() {
        callback(ExternalProviders.Provider.GOOGLE,null,false,null);UUID account=UUID.randomUUID(),session=UUID.randomUUID();
        when(external.account(any(),any(),any())).thenReturn(account);
        when(accounts.lockAccount(account)).thenReturn(new AccountStore.Account(account,"original@example.test","Original","ACTIVE",null));
        when(accounts.insertSession(eq(account),any(),eq(true),any(),any())).thenReturn(session);
        var out=service().callback(ExternalProviders.Provider.GOOGLE,"s","c","code",null);
        assertEquals(account,out.session().principal().accountId());assertNull(out.registrationSecret());
        verify(accounts).insertSession(account,secrets.hash(out.session().secret()),true,clock.instant(),clock.instant().plus(Duration.ofDays(30)));
        verify(audit).record(account,AuditService.Event.LOGIN_SUCCEEDED,account);verify(external,never()).create(any(),any(),any(),any(),anyBoolean(),any());
    }
    @Test void disabledAccountNeverReceivesSession() {
        callback(ExternalProviders.Provider.GOOGLE,"a@example.test",true,"A");UUID account=UUID.randomUUID();
        when(external.account(any(),any(),any())).thenReturn(account);when(accounts.lockAccount(account)).thenReturn(new AccountStore.Account(account,"a@example.test","A","DISABLED",null));
        assertThrows(AuthFailure.class,()->service().callback(ExternalProviders.Provider.GOOGLE,"s","c","code",null));
        verify(accounts,never()).insertSession(any(),any(),anyBoolean(),any(),any());verifyNoInteractions(audit);
    }
    @Test void verifiedUnchangedEmailCreatesActiveWithoutOwnVerification() {
        var r=registration(true);when(external.registration(any())).thenReturn(r);when(external.create(any(),any(),any(),any(),anyBoolean(),any())).thenReturn(true);
        service().complete(secrets.create(),"A@example.test","New name");
        verify(external).create(any(),eq(r),eq("a@example.test"),eq("New name"),eq(true),eq(clock.instant()));verifyNoInteractions(auth);
    }
    @Test void suppliedOrUnverifiedEmailAlwaysNeedsOwnVerification() {
        for(boolean verified:List.of(false,true)) {
            reset(external,auth);var r=registration(verified);when(external.registration(any())).thenReturn(r);when(external.create(any(),any(),any(),any(),anyBoolean(),any())).thenReturn(true);
            service().complete(secrets.create(),"other@example.test","N");
            verify(external).create(any(),eq(r),eq("other@example.test"),eq("N"),eq(false),any());verify(auth).requestVerification("other@example.test");
        }
    }
    @Test void emailCollisionIsGenericAndNeverLinksOrSendsToExistingAccount() {
        var r=registration(true);when(external.registration(any())).thenReturn(r);when(external.create(any(),any(),any(),any(),anyBoolean(),any())).thenReturn(false);
        assertDoesNotThrow(()->service().complete(secrets.create(),"a@example.test","N"));
        verify(external).consume(r.id(),clock.instant());verify(external,never()).associate(any(),any());verifyNoInteractions(auth,accounts,audit);
    }
    @Test void replayAndAlreadyRegisteredIdentityDoNotModifyCredentialsOrIssueSession() {
        var r=registration(true);when(external.registration(any())).thenReturn(r);when(external.account(any(),any(),any())).thenReturn(UUID.randomUUID());
        service().complete(secrets.create(),"a@example.test","N");verify(external,never()).create(any(),any(),any(),any(),anyBoolean(),any());
        reset(external);when(external.registration(any())).thenReturn(new ExternalAccountStore.Registration(r.id(),r.provider(),r.issuer(),r.subject(),null,false,null,true,r.returnUri(),r.expiresAt(),clock.instant()));
        service().complete(secrets.create(),"a@example.test","N");verify(external,never()).consume(any(),any());verifyNoInteractions(accounts,auth,audit);
    }
    @Test void invalidExpiredAndOversizedProfileCannotCreateAnything() {
        var service=service();assertThrows(AuthFailure.class,()->service.profile("bad"));
        var r=registration(false);when(external.registration(any())).thenReturn(new ExternalAccountStore.Registration(r.id(),r.provider(),r.issuer(),r.subject(),r.email(),false,r.name(),true,r.returnUri(),clock.instant(),null));
        assertThrows(AuthFailure.class,()->service.complete(secrets.create(),"a@example.test","N"));
        assertThrows(AuthFailure.class,()->service.complete(secrets.create(),"invalid","N"));
        assertThrows(AuthFailure.class,()->service.complete(secrets.create(),"a@example.test","N".repeat(101)));
        verify(external,never()).create(any(),any(),any(),any(),anyBoolean(),any());
    }
    @Test void auditFailureRollsBackCallbackTransaction() {
        callback(ExternalProviders.Provider.GOOGLE,"a@example.test",true,"N");UUID account=UUID.randomUUID();when(external.account(any(),any(),any())).thenReturn(account);
        when(accounts.lockAccount(account)).thenReturn(new AccountStore.Account(account,"a@example.test","N","ACTIVE",null));
        doThrow(new IllegalStateException("injected")).when(audit).record(any(),any(),any());
        assertThrows(IllegalStateException.class,()->service().callback(ExternalProviders.Provider.GOOGLE,"s","c","code",null));
        verify(manager).rollback(any());
    }
}
