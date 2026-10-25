package cz.mh.rallye.rallye_backend.identity.application;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExternalLoginService {
    public static final Duration COMPLETION_LIFE=Duration.ofMinutes(15);
    public record Outcome(String returnUri,AuthService.LoginSession session,String registrationSecret,String handoffCode,UUID requestId) {
        public Outcome(String returnUri,AuthService.LoginSession session,String registrationSecret){this(returnUri,session,registrationSecret,null,null);}
        @Override public String toString(){return "ExternalLoginOutcome[redacted]";}
    }
    public record Profile(String email,String displayName,boolean emailVerified,Instant expiresAt) {}
    private final ExternalFlowService flows;private final ExternalAccountStore external;private final AccountStore accounts;
    private final AuthService auth;private final SecretTokens secrets;private final Clock clock;private final AuditService audit;
    private final jakarta.validation.Validator validator;
    private final MobileCallbackService mobile;
    private final AppleTokenVault vault;private final ExternalProviders providers;private final TransactionTemplate tx;private final TransactionTemplate captureTx;
    public ExternalLoginService(ExternalFlowService flows,ExternalAccountStore external,AccountStore accounts,AuthService auth,
        SecretTokens secrets,Clock clock,AuditService audit,AppleTokenVault vault,ExternalProviders providers,PlatformTransactionManager manager,jakarta.validation.Validator validator,MobileCallbackService mobile) {
        this.flows=flows;this.external=external;this.accounts=accounts;this.auth=auth;this.secrets=secrets;this.clock=clock;
        this.audit=audit;this.validator=validator;this.mobile=mobile;this.vault=vault;this.providers=providers;this.tx=new TransactionTemplate(manager);this.captureTx=new TransactionTemplate(manager);this.captureTx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public Outcome callback(ExternalProviders.Provider provider,String state,String correlation,String code,String appleName) {
        if(provider==ExternalProviders.Provider.APPLE)vault.requireReady();
        var verified=flows.verifyCallback(provider,state,correlation,code);
        var proof=verified.identity();
        String name=validName(provider==ExternalProviders.Provider.APPLE?appleName:proof.name());
        String email=validEmail(proof.email());
        // Apple token capture survives failure of the subsequent business transaction.
        final Outcome captured=provider==ExternalProviders.Provider.APPLE?captureTx.execute(status->{
            external.lock();flows.consumeVerified(verified);
            Instant now=now();UUID account=external.account(provider.name(),proof.issuer(),proof.subject());
            if(account!=null) {
                accounts.lockAccount(account);
                external.captureApple(verified.flow().id(),proof.subject(),verified.clientId(),account,null,proof.refreshToken());
                return new Outcome(verified.flow().returnUri(),null,null);
            }
            var attempt=mobile.context(verified.flow());String target=attempt==null?verified.flow().returnUri():attempt.profileUri();
            UUID registration=UUID.randomUUID();String secret=secrets.create();
            external.registration(new ExternalAccountStore.Registration(registration,provider.name(),proof.issuer(),proof.subject(),email,
                email!=null && proof.emailVerified(),name,verified.flow().rememberMe(),target,now.plus(COMPLETION_LIFE),null),secrets.hash(secret),now);
            external.captureApple(verified.flow().id(),proof.subject(),verified.clientId(),null,registration,proof.refreshToken());
            return new Outcome(target,null,secret,null,attempt==null?null:attempt.id());
        }):null;
        return tx.execute(status->{
            external.lock();var mobileContext=mobile.context(verified.flow());
            if(provider!=ExternalProviders.Provider.APPLE)flows.consumeVerified(verified);
            if(provider==ExternalProviders.Provider.APPLE && external.appleBlocked(proof.issuer(),proof.subject()))throw new AuthFailure(503,"APPLE_AUTHORIZATION_PENDING_REVOCATION");
            Instant now=now();external.purgeExpired(now);
            if(captured!=null && captured.registrationSecret()!=null)return captured; // D12: first registration never becomes a mobile login.
            UUID account=external.account(provider.name(),proof.issuer(),proof.subject());
            if(account!=null) {
                var current=accounts.lockAccount(account);
                if(current==null || !"ACTIVE".equals(current.status()))throw AuthFailure.credentials();

                if(mobileContext!=null) {
                    String handoff=mobile.issue(account,mobileContext);
                    return new Outcome(mobileContext.returnUri(),null,null,handoff,mobileContext.id());
                }
                String secret=secrets.create();UUID session=accounts.insertSession(account,secrets.hash(secret),verified.flow().rememberMe(),now,now.plus(AuthService.idle(verified.flow().rememberMe())));
                audit.record(account,AuditService.Event.LOGIN_SUCCEEDED,account);
                return new Outcome(verified.flow().returnUri(),new AuthService.LoginSession(secret,new AuthService.Principal(account,session,verified.flow().rememberMe())),null);
            }
            if(captured!=null)throw AuthFailure.credentials();
            String target=mobileContext==null?verified.flow().returnUri():mobileContext.profileUri();
            UUID registration=UUID.randomUUID();String secret=secrets.create();
            external.registration(new ExternalAccountStore.Registration(registration,provider.name(),proof.issuer(),proof.subject(),email,
                email!=null && proof.emailVerified(),name,verified.flow().rememberMe(),target,now.plus(COMPLETION_LIFE),null),secrets.hash(secret),now);

            return new Outcome(target,null,secret,null,mobileContext==null?null:mobileContext.id());
        });
    }
    public record Rejected(String returnUri,UUID requestId) {}
    public Rejected rejectCallback(ExternalProviders.Provider provider,String state,String correlation) {
        return tx.execute(status->{
            external.lock();var flow=flows.rejectFlow(provider,state,correlation);var attempt=mobile.context(flow);
            return new Rejected(attempt==null?flow.returnUri():attempt.returnUri(),attempt==null?null:attempt.id());
        });
    }
    @Transactional
    public Profile profile(String secret) {
        external.lock();var r=require(secret);return new Profile(r.email(),r.name(),r.verified(),r.expiresAt());
    }
    @Transactional
    public void complete(String secret,String email,String name) {
        email=validEmail(email);name=validName(name);
        if(email==null || name==null)throw new AuthFailure(400,"INVALID_REQUEST");
        external.lock();var r=require(secret);
        if(r.consumedAt()!=null)return; // Same generic result; never reissues credentials or mail.
        Instant now=now();
        if("APPLE".equals(r.provider()) && external.appleBlocked(r.issuer(),r.subject())){external.consume(r.id(),now);return;}
        if(external.account(r.provider(),r.issuer(),r.subject())==null) {
            UUID account=UUID.randomUUID();boolean verified=r.verified() && email.equals(r.email());
            if(external.create(account,r,email,name,verified,now)) {
                external.associate(r.id(),account);
                if(!verified)auth.requestVerification(email);
            }
        }
        UUID existing=external.account(r.provider(),r.issuer(),r.subject());
        if(existing!=null && "APPLE".equals(r.provider()))external.associate(r.id(),existing);
        external.consume(r.id(),now);
        // Always 202: a collision neither links nor reports an existing account.
    }
    private ExternalAccountStore.Registration require(String secret) {
        if(secret==null || !secret.matches("[A-Za-z0-9_-]{43}"))throw invalid();
        var r=external.registration(secrets.hash(secret));
        if(r==null || !r.expiresAt().isAfter(now()))throw invalid();return r;
    }
    public static String credentialAad(UUID id,String clientId){return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(java.util.List.of(id.toString(),clientId));}
    public record EmailValue(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Email @jakarta.validation.constraints.Size(max=254) String value) {}
    private String validEmail(String value) {
        if(value==null)return null;String email=AuthService.normalizeEmail(value);
        return validator.validate(new EmailValue(email)).isEmpty()?email:null;
    }
    static String validName(String value){if(value==null)return null;String name=value.strip();return name.isBlank() || name.codePointCount(0,name.length())>100?null:name;}
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
    private static AuthFailure invalid(){return new AuthFailure(400,"EXTERNAL_REGISTRATION_INVALID");}
}
