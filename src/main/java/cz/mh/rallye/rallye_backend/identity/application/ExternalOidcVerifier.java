package cz.mh.rallye.rallye_backend.identity.application;

import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.security.oauth2.client.endpoint.*;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

/** Protocol proof only. No profile persistence, account selection or session issuance. */
@Component
public class ExternalOidcVerifier {
    private final SecretTokens secrets;
    private final java.time.Clock clock;
    public ExternalOidcVerifier(SecretTokens secrets,java.time.Clock clock){this.secrets=secrets;this.clock=clock;}
    public record Identity(String issuer,String subject,String email,boolean emailVerified,String name,String refreshToken) {
        @Override public String toString(){return "VerifiedExternalIdentity[redacted]";}
    }
    public Identity exchange(ClientRegistration client,String code,String state,String nonceHash,String pkceVerifier) {
        var request=OAuth2AuthorizationRequest.authorizationCode().authorizationUri(client.getProviderDetails().getAuthorizationUri())
            .clientId(client.getClientId()).redirectUri(client.getRedirectUri()).scopes(client.getScopes()).state(state)
            .attributes(a->{if(pkceVerifier!=null)a.put("code_verifier",pkceVerifier);}).build();
        var response=OAuth2AuthorizationResponse.success(code).redirectUri(client.getRedirectUri()).state(state).build();
        try {
            var tokenClient=new RestClientAuthorizationCodeTokenResponseClient();
            var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(5000);factory.setReadTimeout(10000);
            var rest=org.springframework.web.client.RestClient.builder().requestFactory(factory)
                .messageConverters(converters->{converters.clear();converters.add(new org.springframework.http.converter.FormHttpMessageConverter());
                    converters.add(new org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter());})
                .defaultStatusHandler(new org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler()).build();
            tokenClient.setRestClient(rest);
            var tokens=tokenClient.getTokenResponse(new OAuth2AuthorizationCodeGrantRequest(client,new OAuth2AuthorizationExchange(request,response)));
            Object value=tokens.getAdditionalParameters().get("id_token");
            if(!(value instanceof String raw))throw invalid();
            var decoder=NimbusJwtDecoder.withJwkSetUri(client.getProviderDetails().getJwkSetUri()).jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new org.springframework.web.client.RestTemplate(factory)).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(client.getProviderDetails().getIssuerUri()));
            Jwt jwt=decode(decoder,raw,client.getClientId(),nonceHash);
            Object verified=jwt.getClaims().get("email_verified");
            return new Identity(jwt.getIssuer().toString(),jwt.getSubject(),jwt.getClaimAsString("email"),
                Boolean.TRUE.equals(verified) || "true".equals(verified),jwt.getClaimAsString("name"),
                tokens.getRefreshToken()==null?null:tokens.getRefreshToken().getTokenValue());
        } catch(AuthFailure failure){throw failure;}
        catch(RuntimeException failure){throw invalid();} // Provider responses may contain secrets: do not log/wrap them.
    }
    public Jwt decode(JwtDecoder decoder,String raw,String clientId,String nonceHash) {
        try { Jwt jwt=decoder.decode(raw);validate(jwt,clientId,nonceHash);return jwt; }
        catch(RuntimeException e){throw invalid();}
    }
    public void validate(Jwt jwt,String clientId,String nonceHash) {
        String nonce=jwt.getClaimAsString("nonce");
        String azp=jwt.getClaimAsString("azp");
        if(jwt.getExpiresAt()==null || jwt.getIssuedAt()==null || jwt.getSubject()==null || jwt.getSubject().isBlank()
            || (jwt.getExpiresAt()!=null && !jwt.getExpiresAt().isAfter(clock.instant()))
            || (jwt.getIssuedAt()!=null && jwt.getIssuedAt().isAfter(clock.instant().plusSeconds(60)))
            || !jwt.getAudience().contains(clientId) || (jwt.getAudience().size()>1 && !clientId.equals(azp))
            || (azp!=null && !clientId.equals(azp)) || nonce==null || !java.security.MessageDigest.isEqual(
                secrets.hash(nonce).getBytes(java.nio.charset.StandardCharsets.US_ASCII),nonceHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw invalid();
    }
    private static AuthFailure invalid(){return new AuthFailure(400,"EXTERNAL_PROOF_INVALID");}
}
