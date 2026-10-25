package cz.mh.rallye.rallye_backend.identity.application;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MobileExchangeService {
    private final MobileGrantStore grants;private final AccountStore accounts;private final AdminStore control;
    private final AuditService audit;private final SecretTokens secrets;private final Clock clock;
    public MobileExchangeService(MobileGrantStore grants,AccountStore accounts,AdminStore control,AuditService audit,SecretTokens secrets,Clock clock) {
        this.grants=grants;this.accounts=accounts;this.control=control;this.audit=audit;this.secrets=secrets;this.clock=clock;
    }
    public static String challenge(String verifier) {
        try {return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));}
        catch(NoSuchAlgorithmException error){throw new IllegalStateException("SHA-256 unavailable");}
    }
    @Transactional
    public AuthService.LoginSession exchange(String code,UUID requestId,String codeVerifier) {
        if(code==null || !code.matches("[A-Za-z0-9_-]{43}") || requestId==null || codeVerifier==null
                || !codeVerifier.matches("[A-Za-z0-9._~-]{43,128}"))throw new AuthFailure(400,"INVALID_REQUEST");
        String hash=secrets.hash(code);var initial=grants.find(hash,false);
        if(initial==null)throw invalid();
        control.lock();var account=accounts.lockAccount(initial.account());
        if(account==null || !"ACTIVE".equals(account.status()))throw invalid();
        var grant=grants.find(hash,true);Instant now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if(grant==null || !grant.account().equals(account.id()) || !grant.attempt().equals(requestId) || grant.consumedAt()!=null
                || grant.createdAt().isAfter(now) || !grant.expiresAt().isAfter(now)
                || !MessageDigest.isEqual(challenge(codeVerifier).getBytes(StandardCharsets.US_ASCII),grant.challenge().getBytes(StandardCharsets.US_ASCII)))throw invalid();
        String provider=accounts.identityProvider(account.id());
        if(!List.of("GOOGLE","APPLE").contains(provider) || !grants.boundAttempt(grant,provider))throw invalid();
        boolean remember=grants.rememberMe(requestId);
        if(!grants.consume(grant.id(),now))throw invalid();
        String token=secrets.create();
        UUID session=accounts.insertSession(account.id(),secrets.hash(token),remember,now,now.plus(AuthService.idle(remember)));
        audit.record(account.id(),AuditService.Event.LOGIN_SUCCEEDED,account.id());
        return new AuthService.LoginSession(token,new AuthService.Principal(account.id(),session,remember));
    }
    private static AuthFailure invalid(){return new AuthFailure(400,"EXTERNAL_HANDOFF_INVALID");}
}
