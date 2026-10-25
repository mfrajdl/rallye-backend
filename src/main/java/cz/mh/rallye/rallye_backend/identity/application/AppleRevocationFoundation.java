package cz.mh.rallye.rallye_backend.identity.application;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import cz.mh.rallye.rallye_backend.identity.persistence.AppleRevocationStore.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional Apple authorization and revocation operations; provider I/O stays outside these transactions. */
@Service
@Transactional
public class AppleRevocationFoundation {
    public record NewCase(String issuer,String subject,String clientId,UUID generation,UUID flowId,UUID account,UUID registration) {
        @Override public String toString(){return "NewAppleCase[redacted]";}
    }
    private final AppleRevocationStore store;private final AdminStore control;private final AppleTokenVault vault;private final SecretTokens secrets;private final Clock clock;private final cz.mh.rallye.rallye_backend.audit.application.AuditService audit;
    public AppleRevocationFoundation(AppleRevocationStore store,AdminStore control,AppleTokenVault vault,SecretTokens secrets,Clock clock,cz.mh.rallye.rallye_backend.audit.application.AuditService audit){this.store=store;this.control=control;this.vault=vault;this.secrets=secrets;this.clock=clock;this.audit=audit;}
    public Authorization create(NewCase input) {
        if(input==null || !"https://appleid.apple.com".equals(input.issuer()) || blank(input.subject(),255) || blank(input.clientId(),255)
            || input.generation()==null || input.flowId()==null || (input.account()==null)==(input.registration()==null))throw invalid();
        control.lock();var c=new Authorization(UUID.randomUUID(),input.issuer(),input.subject(),secrets.hash(input.issuer()+"\n"+input.subject()),input.clientId(),input.generation(),input.flowId(),input.account(),input.registration(),input.account()!=null?CaseStatus.ACCOUNT_BOUND:CaseStatus.REGISTRATION_PENDING,0);
        store.insert(c,now());return c;
    }
    public UUID addMaterial(UUID caseId,String refreshToken) {
        if(refreshToken==null || refreshToken.isBlank() || refreshToken.length()>16384)throw invalid();
        var c=locked(caseId);if(c.status()!=CaseStatus.ACCOUNT_BOUND && c.status()!=CaseStatus.REGISTRATION_PENDING)throw conflict();
        UUID id=UUID.randomUUID();var sealed=vault.seal(refreshToken,ExternalLoginService.credentialAad(id,c.clientId()));store.material(id,c,sealed,now());return id;
    }
    public void bindAccount(UUID caseId,UUID account) {
        if(account==null)throw invalid();var c=locked(caseId);if(!store.bind(c,account,now()))throw conflict();
    }
    public List<Job> enqueue(UUID caseId,Reason reason) {
        if(reason==null)throw invalid();var c=locked(caseId);
        if(c.status()==CaseStatus.REVOCATION_REQUIRED || c.status()==CaseStatus.BLOCKED){var jobs=store.jobs(caseId);if(jobs.isEmpty() || jobs.stream().anyMatch(j->j.reason()!=reason))throw conflict();return jobs;}
        if(reason==Reason.ACCOUNT_CANCELLATION && c.status()!=CaseStatus.ACCOUNT_BOUND || reason==Reason.REGISTRATION_ABANDONED && c.status()!=CaseStatus.REGISTRATION_PENDING || store.materialCount(caseId)==0)throw conflict();
        Instant now=now();if(!store.requireRevocation(c,now))throw conflict();store.enqueue(caseId,reason,now);return store.jobs(caseId);
    }
    @Transactional(readOnly=true) public Authorization find(UUID id){if(id==null)throw invalid();return store.find(id,false);}
    @Transactional(readOnly=true) public List<Job> jobs(UUID id){if(id==null)throw invalid();return store.jobs(id);}
    public Job claim(Duration lease) {
        if(lease==null || lease.compareTo(Duration.ofSeconds(1))<0 || lease.compareTo(Duration.ofMinutes(10))>0)throw invalid();
        Instant now=now();return store.claim(now,now.plus(lease).truncatedTo(ChronoUnit.MILLIS),UUID.randomUUID());
    }
    public boolean reportFailure(UUID job,UUID lease,long version,AppleRevocationStore.Error error,Integer http,Instant next,boolean blocked) {
        Instant now=now();if(job==null || lease==null || version<0 || error==null || http!=null&&(http<100||http>599) || next==null || !blocked&&!next.isAfter(now))throw invalid();
        return store.failure(job,lease,version,error,http,now,next.truncatedTo(ChronoUnit.MILLIS),blocked);
    }
    public boolean retryBlocked(UUID job,long version){if(job==null || version<0)throw invalid();return store.retry(job,version,now());}
    @Transactional(readOnly=true) public Material material(Job job){return store.material(job);}
    /** Internal worker completion: only called after Apple HTTP 200. CAS rejects stale results. */
    public boolean complete(Job job){
        var c=locked(job.caseId());Instant now=now();
        if(!store.succeed(job,now))return false;
        store.removeSucceededMaterial(job);store.finishCase(c,now);
        audit.appleRevocation(job.id());return true;
    }
    public void pruneCompleted(){control.lock();store.pruneCompleted(now().minus(Duration.ofDays(30)));}
    private Authorization locked(UUID id){if(id==null)throw invalid();control.lock();var c=store.find(id,true);if(c==null)throw conflict();return c;}
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
    private static boolean blank(String s,int max){return s==null || s.isBlank() || s.length()>max;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid Apple persistence input");}
    private static IllegalStateException conflict(){return new IllegalStateException("Apple persistence state conflict");}
}
