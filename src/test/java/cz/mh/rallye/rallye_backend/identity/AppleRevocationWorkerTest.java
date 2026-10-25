package cz.mh.rallye.rallye_backend.identity;

import java.time.*;
import java.util.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.AppleRevocationStore.*;
import cz.mh.rallye.rallye_backend.identity.persistence.AppleRevocationStore;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class AppleRevocationWorkerTest {
    AppleRevocationFoundation foundation=mock(AppleRevocationFoundation.class);AppleRevocationClient client=mock(AppleRevocationClient.class);AppleTokenVault vault=mock(AppleTokenVault.class);
    Instant now=Instant.parse("2026-10-25T12:00:00Z");Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    AppleRevocationWorker worker=new AppleRevocationWorker(foundation,client,vault,clock);
    Job job(int attempts){return new Job(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),Reason.ACCOUNT_CANCELLATION,Status.PROCESSING,attempts,now,UUID.randomUUID(),now.plusSeconds(120),null,null,attempts);}
    void setup(Job job){when(foundation.claim(any())).thenReturn(job);when(foundation.material(job)).thenReturn(new Material("client",new AppleTokenVault.Sealed("test",new byte[12],new byte[32])));when(vault.open(any(),any())).thenReturn("synthetic-refresh");}
    @Test void completesOnlyAfterHttp200(){var j=job(1);setup(j);when(client.revoke("client","synthetic-refresh")).thenReturn(new AppleRevocationClient.Result(200,null,false));assertTrue(worker.processNext());var order=inOrder(foundation,client);order.verify(client).revoke("client","synthetic-refresh");order.verify(foundation).complete(j);verify(foundation,never()).reportFailure(any(),any(),anyLong(),any(),any(),any(),anyBoolean());}
    @Test void networkFailureUsesExponentialRetryAndNeverCompletes(){
        for(int attempts:new int[]{1,2,30}){reset(foundation);var j=job(attempts);setup(j);when(client.revoke(any(),any())).thenReturn(new AppleRevocationClient.Result(null,AppleRevocationStore.Error.NETWORK,true));worker.processNext();long seconds=attempts==1?60:attempts==2?120:86400;
            verify(foundation).reportFailure(j.id(),j.lease(),j.version(),AppleRevocationStore.Error.NETWORK,null,now.plusSeconds(seconds),false);verify(foundation,never()).complete(any());}
    }
    @Test void permanentFailureBlocksInsteadOfAggressiveRetry(){var j=job(1);setup(j);when(client.revoke(any(),any())).thenReturn(new AppleRevocationClient.Result(400,AppleRevocationStore.Error.PROVIDER_REJECTED,false));worker.processNext();verify(foundation).reportFailure(j.id(),j.lease(),j.version(),AppleRevocationStore.Error.PROVIDER_REJECTED,400,now.plusSeconds(60),true);verify(foundation,never()).complete(any());}
    @Test void unavailableDecryptionKeyNeverSendsAnythingToApple(){var j=job(1);setup(j);when(vault.open(any(),any())).thenThrow(new AuthFailure(503,"APPLE_CREDENTIAL_STORAGE_UNAVAILABLE"));worker.processNext();verifyNoInteractions(client);verify(foundation).reportFailure(j.id(),j.lease(),j.version(),AppleRevocationStore.Error.KEY,null,now.plusSeconds(60),true);}
    @Test void staleCompletionIsNotRetriedAsAnotherOutcome(){var j=job(1);setup(j);when(client.revoke(any(),any())).thenReturn(new AppleRevocationClient.Result(200,null,false));when(foundation.complete(j)).thenReturn(false);worker.processNext();verify(foundation,never()).reportFailure(any(),any(),anyLong(),any(),any(),any(),anyBoolean());}
    @Test void databaseCompletionFailureIsNotMistakenForProviderFailure(){var j=job(1);setup(j);when(client.revoke(any(),any())).thenReturn(new AppleRevocationClient.Result(200,null,false));when(foundation.complete(j)).thenThrow(new IllegalStateException("injected"));assertThrows(IllegalStateException.class,worker::processNext);verify(foundation,never()).reportFailure(any(),any(),anyLong(),any(),any(),any(),anyBoolean());}
    @Test void emptyQueueDoesNotCallProvider(){assertFalse(worker.processNext());verifyNoInteractions(client,vault);}
}
