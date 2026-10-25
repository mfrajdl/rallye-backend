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

class AppleRevocationFoundationTest {
    AppleRevocationStore store=mock(AppleRevocationStore.class);AdminStore control=mock(AdminStore.class);AppleTokenVault vault=mock(AppleTokenVault.class);SecretTokens secrets=new SecretTokens();
    Instant now=Instant.parse("2026-10-25T12:00:00Z");UUID account=UUID.randomUUID(),id=UUID.randomUUID();
    AppleRevocationFoundation service=new AppleRevocationFoundation(store,control,vault,secrets,Clock.fixed(now,ZoneOffset.UTC),mock(cz.mh.rallye.rallye_backend.audit.application.AuditService.class));
    AppleRevocationFoundation.NewCase input(UUID a,UUID r){return new AppleRevocationFoundation.NewCase("https://appleid.apple.com","subject","client",UUID.randomUUID(),UUID.randomUUID(),a,r);}
    Authorization c(CaseStatus status){return new Authorization(id,"https://appleid.apple.com","subject",secrets.hash("https://appleid.apple.com\nsubject"),"client",UUID.randomUUID(),UUID.randomUUID(),account,null,status,0);}
    @Test void requiresExactlyOneOwnerAndAllIdentityContextBeforeDatabaseAccess(){
        for(var data:List.of(input(null,null),input(account,UUID.randomUUID()),new AppleRevocationFoundation.NewCase("bad","subject","client",UUID.randomUUID(),UUID.randomUUID(),account,null),new AppleRevocationFoundation.NewCase("https://appleid.apple.com"," ","client",UUID.randomUUID(),UUID.randomUUID(),account,null),new AppleRevocationFoundation.NewCase("https://appleid.apple.com","subject","client",null,UUID.randomUUID(),account,null)))
            assertThrows(IllegalArgumentException.class,()->service.create(data));
        assertThrows(IllegalArgumentException.class,()->service.create(null));verifyNoInteractions(store,control,vault);
    }
    @Test void createsRedactedCaseWithDeterministicSubjectBinding(){
        var value=service.create(input(account,null));assertEquals(secrets.hash("https://appleid.apple.com\nsubject"),value.digest());assertEquals(CaseStatus.ACCOUNT_BOUND,value.status());
        assertFalse(value.toString().contains("subject"));var order=inOrder(control,store);order.verify(control).lock();order.verify(store).insert(value,now);
    }
    @Test void registrationCaseHasRegistrationOwner(){var value=service.create(input(null,UUID.randomUUID()));assertEquals(CaseStatus.REGISTRATION_PENDING,value.status());assertNull(value.account());}
    @Test void storesOnlySealedMaterialAndBindsAadToCredentialAndClient(){
        var c=c(CaseStatus.ACCOUNT_BOUND);when(store.find(id,true)).thenReturn(c);var sealed=new AppleTokenVault.Sealed("test",new byte[12],new byte[32]);when(vault.seal(any(),any())).thenReturn(sealed);
        UUID material=service.addMaterial(id,"synthetic-refresh");verify(vault).seal("synthetic-refresh",ExternalLoginService.credentialAad(material,"client"));verify(store).material(material,c,sealed,now);
    }
    @Test void missingVaultOrClosedCaseDoesNotStoreUnencryptedMaterial(){
        when(store.find(id,true)).thenReturn(c(CaseStatus.REVOCATION_REQUIRED));assertThrows(IllegalStateException.class,()->service.addMaterial(id,"synthetic"));verifyNoInteractions(vault);
        when(store.find(id,true)).thenReturn(c(CaseStatus.ACCOUNT_BOUND));when(vault.seal(any(),any())).thenThrow(new AuthFailure(503,"APPLE_CREDENTIAL_STORAGE_UNAVAILABLE"));assertThrows(AuthFailure.class,()->service.addMaterial(id,"synthetic"));verify(store,never()).material(any(),any(),any(),any());
    }
    @Test void enqueueRequiresMaterialAndCorrectReason(){
        when(store.find(id,true)).thenReturn(c(CaseStatus.ACCOUNT_BOUND));assertThrows(IllegalStateException.class,()->service.enqueue(id,Reason.REGISTRATION_ABANDONED));assertThrows(IllegalStateException.class,()->service.enqueue(id,Reason.ACCOUNT_CANCELLATION));verify(store,never()).requireRevocation(any(),any());
    }
    @Test void enqueueLocksCaseAndEnrollsAllMaterial(){
        var c=c(CaseStatus.ACCOUNT_BOUND);when(store.find(id,true)).thenReturn(c);when(store.materialCount(id)).thenReturn(2);when(store.requireRevocation(c,now)).thenReturn(true);
        service.enqueue(id,Reason.ACCOUNT_CANCELLATION);var order=inOrder(control,store);order.verify(control).lock();order.verify(store).find(id,true);order.verify(store).requireRevocation(c,now);order.verify(store).enqueue(id,Reason.ACCOUNT_CANCELLATION,now);
    }
    @Test void repeatedEnqueueReturnsExistingWorkWithoutRewritingIt(){
        var job=new Job(UUID.randomUUID(),id,UUID.randomUUID(),Reason.ACCOUNT_CANCELLATION,Status.BLOCKED,3,now,null,null,AppleRevocationStore.Error.KEY,null,4);
        when(store.find(id,true)).thenReturn(c(CaseStatus.REVOCATION_REQUIRED));when(store.jobs(id)).thenReturn(List.of(job));assertEquals(List.of(job),service.enqueue(id,Reason.ACCOUNT_CANCELLATION));verify(store,never()).enqueue(any(),any(),any());
        assertThrows(IllegalStateException.class,()->service.enqueue(id,Reason.REGISTRATION_ABANDONED));
    }
    @Test void claimsAreBoundedAndUseFreshLeaseIdentifiers(){
        for(Duration d:List.of(Duration.ZERO,Duration.ofMillis(999),Duration.ofMinutes(11)))assertThrows(IllegalArgumentException.class,()->service.claim(d));verifyNoInteractions(store);
        service.claim(Duration.ofMinutes(2));verify(store).claim(eq(now),eq(now.plusSeconds(120)),any(UUID.class));
    }
    @Test void failureRequiresLeaseVersionErrorAndFutureRetryDeadline(){
        assertThrows(IllegalArgumentException.class,()->service.reportFailure(id,null,0,AppleRevocationStore.Error.NETWORK,null,now.plusSeconds(60),false));
        assertThrows(IllegalArgumentException.class,()->service.reportFailure(id,UUID.randomUUID(),0,AppleRevocationStore.Error.NETWORK,null,now,false));
        assertThrows(IllegalArgumentException.class,()->service.reportFailure(id,UUID.randomUUID(),0,AppleRevocationStore.Error.NETWORK,999,now.plusSeconds(60),false));verifyNoInteractions(store);
        UUID lease=UUID.randomUUID();service.reportFailure(id,lease,2,AppleRevocationStore.Error.KEY,null,now,true);verify(store).failure(id,lease,2,AppleRevocationStore.Error.KEY,null,now,now,true);
    }
    @Test void staleCasFailureIsNotReportedAsSuccess(){UUID lease=UUID.randomUUID();assertFalse(service.reportFailure(id,lease,1,AppleRevocationStore.Error.NETWORK,null,now.plusSeconds(60),false));assertFalse(service.retryBlocked(id,1));}
    @Test void ownerBindingConflictDoesNotPassSilently(){when(store.find(id,true)).thenReturn(c(CaseStatus.ACCOUNT_BOUND));assertThrows(IllegalStateException.class,()->service.bindAccount(id,account));}
    @Test void newRepositoryCannotUseTheOldWritersNullCaseException(){
        var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);var repository=new AppleRevocationStore(jdbc);
        var sealed=new AppleTokenVault.Sealed("test",new byte[12],new byte[32]);
        assertThrows(IllegalArgumentException.class,()->repository.material(UUID.randomUUID(),null,sealed,now));
        var missingCase=new Authorization(null,"https://appleid.apple.com","subject","digest","client",UUID.randomUUID(),UUID.randomUUID(),account,null,CaseStatus.ACCOUNT_BOUND,0);
        assertThrows(IllegalArgumentException.class,()->repository.material(UUID.randomUUID(),missingCase,sealed,now));verifyNoInteractions(jdbc);
    }

}
