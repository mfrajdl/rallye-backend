package cz.mh.rallye.rallye_backend.identity.application;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class ExternalFlowService {
    public enum Purpose { LOGIN }
    public static final Duration FLOW_LIFE=Duration.ofMinutes(10), HANDOFF_LIFE=Duration.ofSeconds(60);
    public record Started(String authorizationUrl,String correlation,Instant expiresAt) {
        @Override public String toString(){return "ExternalFlowStarted[redacted]";}
    }
    private final ExternalProviders providers; private final ExternalFlowStore store;
    private final SecretTokens secrets; private final Clock clock; private final TransactionTemplate tx; private final ExternalOidcVerifier verifier;
    public ExternalFlowService(ExternalProviders providers,ExternalFlowStore store,SecretTokens secrets,
        Clock clock,PlatformTransactionManager manager,ExternalOidcVerifier verifier) {
        this.providers=providers;this.store=store;this.secrets=secrets;this.clock=clock;this.tx=new TransactionTemplate(manager);this.verifier=verifier;
    }
    public Started start(ExternalProviders.Provider provider,Purpose purpose,AuthService.Principal actor,String returnId,boolean remember,String challenge) {
        if(purpose!=Purpose.LOGIN)throw invalid();
        if(challenge!=null && !challenge.matches("[A-Za-z0-9_-]{43}"))throw invalid();
        providers.registration(provider);
        String target=providers.returnUri(returnId);
        return create(provider,UUID.randomUUID(),target,remember,challenge,clock.instant().truncatedTo(ChronoUnit.MILLIS));
    }
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Started startMobile(ExternalProviders.Provider provider,UUID id,String target,boolean remember,String challenge,Instant created) {
        if(challenge==null || !challenge.matches("[A-Za-z0-9_-]{43}") || !created.plus(FLOW_LIFE).isAfter(clock.instant()))
            throw new AuthFailure(400,"EXTERNAL_MOBILE_REQUEST_INVALID");
        return create(provider,id,target,remember,challenge,created);
    }
    private Started create(ExternalProviders.Provider provider,UUID id,String target,boolean remember,String challenge,Instant now) {
        var client=providers.registration(provider);
        String state=secrets.create(),nonce=secrets.create(),correlation=secrets.create();
        Instant expires=now.plus(FLOW_LIFE);
        tx.executeWithoutResult(status->{
            store.insert(new ExternalFlowStore.Flow(id,provider.name(),Purpose.LOGIN.name(),null,
                null,secrets.hash(nonce),secrets.hash(correlation),client.getRedirectUri(),target,remember,challenge,expires),secrets.hash(state),now);
        });
        var url=UriComponentsBuilder.fromUriString(client.getProviderDetails().getAuthorizationUri()).queryParam("response_type","code")
            .queryParam("client_id",client.getClientId()).queryParam("redirect_uri",client.getRedirectUri())
            .queryParam("scope",String.join(" ",client.getScopes())).queryParam("state",state).queryParam("nonce",nonce);
        if(provider==ExternalProviders.Provider.GOOGLE)url.queryParam("code_challenge",challenge(secrets.hash("rallye-oidc-pkce:"+correlation))).queryParam("code_challenge_method","S256");
        if(provider==ExternalProviders.Provider.APPLE)url.queryParam("response_mode","form_post");
        return new Started(url.build().encode().toUriString(),correlation,expires);
    }
    public void callback(ExternalProviders.Provider provider,String state,String correlation,String code) {
        // Obsolete protocol-only completion must never exchange and discard an Apple refresh token.
        // Actual web/mobile HTTP callbacks always use ExternalLoginService and its durable capture.
        if(provider==ExternalProviders.Provider.APPLE)throw new AuthFailure(503,"APPLE_CREDENTIAL_STORAGE_UNAVAILABLE");
        if(!secret(state) || !secret(correlation) || code==null || code.isBlank() || code.length()>4096)throw invalid();
        var flow=tx.execute(status->{var f=store.find(secrets.hash(state));check(f,provider,correlation);return f;});
        var registration=providers.registration(provider);
        if(!registration.getRedirectUri().equals(flow.callbackUri()))throw invalid();
        verifier.exchange(registration,code,state,flow.nonceHash(),provider==ExternalProviders.Provider.GOOGLE?secrets.hash("rallye-oidc-pkce:"+correlation):null); // Outside all DB locks.
        tx.executeWithoutResult(status->{
            var current=store.find(secrets.hash(state));check(current,provider,correlation);
            if(!store.consume(current.id(),clock.instant()))throw invalid();
        });
        // Intentionally no account creation, identity link, handoff or Rallye session in 3c.1.
    }
    public String rejectWebCallback(ExternalProviders.Provider provider,String state,String correlation) {
        if(!secret(state) || !secret(correlation))throw invalid();
        return tx.execute(status->{
            var flow=store.find(secrets.hash(state));check(flow,provider,correlation);
            if(!store.consume(flow.id(),clock.instant()))throw invalid();
            org.slf4j.LoggerFactory.getLogger(ExternalFlowService.class).info("External login rejected");
            return flow.returnUri();
        });
    }
    public record Verified(ExternalFlowStore.Flow flow,ExternalOidcVerifier.Identity identity,String state,String correlation,String clientId) {
        public Verified(ExternalFlowStore.Flow flow,ExternalOidcVerifier.Identity identity,String state,String correlation){this(flow,identity,state,correlation,null);}
        @Override public String toString(){return "VerifiedCallback[redacted]";}
    }
    public Verified verifyWebCallback(ExternalProviders.Provider provider,String state,String correlation,String code) {
        return verifyCallback(provider,state,correlation,code,false);
    }
    public Verified verifyCallback(ExternalProviders.Provider provider,String state,String correlation,String code) {
        return verifyCallback(provider,state,correlation,code,true);
    }
    private Verified verifyCallback(ExternalProviders.Provider provider,String state,String correlation,String code,boolean allowMobile) {
        if(!secret(state) || !secret(correlation) || code==null || code.isBlank() || code.length()>4096)throw invalid();
        var flow=tx.execute(status->{var f=store.find(secrets.hash(state));check(f,provider,correlation);return f;});
        if(!allowMobile && flow.challenge()!=null)throw new AuthFailure(503,"EXTERNAL_MOBILE_UNAVAILABLE");
        var client=providers.registration(provider);
        if(!client.getRedirectUri().equals(flow.callbackUri()))throw invalid();
        var proof=verifier.exchange(client,code,state,flow.nonceHash(),provider==ExternalProviders.Provider.GOOGLE?secrets.hash("rallye-oidc-pkce:"+correlation):null);
        if(proof==null || !ExternalProviders.issuer(provider).equals(proof.issuer()) || proof.subject()==null || proof.subject().isBlank() || proof.subject().length()>255)throw invalid();
        return new Verified(flow,proof,state,correlation,client.getClientId());
    }
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void consumeVerified(Verified verified) {
        var current=store.find(secrets.hash(verified.state()));
        check(current,ExternalProviders.Provider.valueOf(verified.flow().provider()),verified.correlation());
        if(!store.consume(current.id(),clock.instant()))throw invalid();
    }
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public ExternalFlowStore.Flow rejectFlow(ExternalProviders.Provider provider,String state,String correlation) {
        if(!secret(state)||!secret(correlation))throw invalid();
        var flow=store.find(secrets.hash(state));check(flow,provider,correlation);
        if(!store.consume(flow.id(),clock.instant()))throw invalid();return flow;
    }
    private void check(ExternalFlowStore.Flow flow,ExternalProviders.Provider provider,String correlation) {
        if(flow==null || !"LOGIN".equals(flow.purpose()) || flow.account()!=null || flow.session()!=null || !flow.provider().equals(provider.name()) || !flow.expiresAt().isAfter(clock.instant())
            || !java.security.MessageDigest.isEqual(flow.correlationHash().getBytes(java.nio.charset.StandardCharsets.US_ASCII),secrets.hash(correlation).getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw invalid();
    }
    private static String challenge(String verifier) {
        try {return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 unavailable");}
    }
    private static boolean secret(String s){return s!=null && s.matches("[A-Za-z0-9_-]{43}");}
    private static AuthFailure invalid(){return new AuthFailure(400,"EXTERNAL_STATE_INVALID");}
}
