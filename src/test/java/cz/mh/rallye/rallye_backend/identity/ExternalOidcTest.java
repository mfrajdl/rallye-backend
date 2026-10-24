package cz.mh.rallye.rallye_backend.identity;

import java.time.*;
import java.util.*;
import java.security.*;
import java.security.interfaces.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.*;
import static org.junit.jupiter.api.Assertions.*;

class ExternalOidcTest {
    SecretTokens secrets=new SecretTokens();
    Instant now=Instant.now();
    ExternalOidcVerifier verifier=new ExternalOidcVerifier(secrets,Clock.fixed(now,ZoneOffset.UTC));
    KeyPair key() throws Exception {var g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);return g.generateKeyPair();}
    String token(KeyPair key,String issuer,String audience,String nonce,Instant expires) throws Exception {
        var claims=new JWTClaimsSet.Builder().issuer(issuer).subject("subject").audience(audience).issueTime(Date.from(now.minusSeconds(1)))
            .expirationTime(Date.from(expires)).claim("nonce",nonce).build();
        var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),claims);jwt.sign(new RSASSASigner((RSAPrivateKey)key.getPrivate()));return jwt.serialize();
    }
    @Test void realSignatureIssuerAudienceTimeAndNonceValidation() throws Exception {
        var key=key();var decoder=NimbusJwtDecoder.withPublicKey((RSAPublicKey)key.getPublic()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("https://accounts.google.com"));
        String nonce=secrets.create(),hash=secrets.hash(nonce);
        String valid=token(key,"https://accounts.google.com","client",nonce,now.plusSeconds(60));
        assertDoesNotThrow(()->verifier.decode(decoder,valid,"client",hash));
        for(String bad:List.of(token(key(),"https://accounts.google.com","client",nonce,now.plusSeconds(60)),
            token(key,"https://attacker.example.test","client",nonce,now.plusSeconds(60)),
            token(key,"https://accounts.google.com","other",nonce,now.plusSeconds(60)),
            token(key,"https://accounts.google.com","client","wrong",now.plusSeconds(60)),
            token(key,"https://accounts.google.com","client",nonce,now)))
            assertEquals("EXTERNAL_PROOF_INVALID",assertThrows(AuthFailure.class,()->verifier.decode(decoder,bad,"client",hash)).code());
    }
    @Test void freshTokenIssueTimeDoesNotCreateFreshAuthenticationGrant() {
        assertEquals(Duration.ofSeconds(60),ExternalFlowService.HANDOFF_LIFE);
        // No service operation issues either grant in this protocol-only stage.
    }
}
