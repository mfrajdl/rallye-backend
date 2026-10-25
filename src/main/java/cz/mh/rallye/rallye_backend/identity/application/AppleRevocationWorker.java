package cz.mh.rallye.rallye_backend.identity.application;

import java.time.*;
import cz.mh.rallye.rallye_backend.identity.persistence.AppleRevocationStore.Error;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;

@Component
@EnableScheduling
@ConditionalOnProperty(name="rallye.auth.apple-revocation.enabled",havingValue="true")
public class AppleRevocationWorker {
    private final AppleRevocationFoundation foundation;private final AppleRevocationClient client;private final AppleTokenVault vault;private final Clock clock;
    public AppleRevocationWorker(AppleRevocationFoundation foundation,AppleRevocationClient client,AppleTokenVault vault,Clock clock){this.foundation=foundation;this.client=client;this.vault=vault;this.clock=clock;}
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void run(){
        try{for(int i=0;i<5 && processNext();i++){}foundation.pruneCompleted();}
        catch(RuntimeException failure){cz.mh.rallye.rallye_backend.identity.api.ApiErrors.logServerFailure(failure);}
    }
    /** Claim commits before any network I/O. There is deliberately no transaction on this method. */
    public boolean processNext(){
        var job=foundation.claim(Duration.ofMinutes(2));if(job==null)return false;
        var material=foundation.material(job);
        AppleRevocationClient.Result result;
        if(material==null)result=new AppleRevocationClient.Result(null,Error.MATERIAL,false);
        else {
            String token;
            try{token=vault.open(material.sealed(),ExternalLoginService.credentialAad(job.credential(),material.clientId()));}
            catch(AuthFailure failure){return fail(job,new AppleRevocationClient.Result(null,Error.KEY,false));}
            result=client.revoke(material.clientId(),token);
        }
        if(result.success())foundation.complete(job);
        else fail(job,result);
        return true;
    }
    private boolean fail(cz.mh.rallye.rallye_backend.identity.persistence.AppleRevocationStore.Job job,AppleRevocationClient.Result result){
        long seconds=Math.min(86400L,60L<<Math.min(11,Math.max(0,job.attempts()-1)));
        boolean stored=foundation.reportFailure(job.id(),job.lease(),job.version(),result.error(),result.httpStatus(),clock.instant().plusSeconds(seconds),!result.retryable());
        if(stored && !result.retryable())org.slf4j.LoggerFactory.getLogger(AppleRevocationWorker.class).warn("Apple revocation blocked: job={} category={} http={}",job.id(),result.error(),result.httpStatus());
        return true;
    }
}
