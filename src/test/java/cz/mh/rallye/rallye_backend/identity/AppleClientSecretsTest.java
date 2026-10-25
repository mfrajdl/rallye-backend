package cz.mh.rallye.rallye_backend.identity;

import java.nio.file.*;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.*;
import java.util.*;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import cz.mh.rallye.rallye_backend.identity.application.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AppleClientSecretsTest {
    @TempDir Path directory;
    Instant now=Instant.parse("2026-10-25T12:00:00Z");
    @Test void generatesCorrectShortLivedEs256JwtForExactStoredClient()throws Exception{
        var generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));var keys=generator.generateKeyPair();Path key=directory.resolve("synthetic.p8");
        Files.writeString(key,"-----BEGIN PRIVATE KEY-----\n"+Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----\n");
        var settings=new AppleClientSecrets.Settings(true,"TEAM123456","KEY1234567",key.toString(),Set.of("services.client"));
        var signer=new AppleClientSecrets(settings,Clock.fixed(now,ZoneOffset.UTC));var jwt=SignedJWT.parse(signer.create("services.client"));
        assertEquals("ES256",jwt.getHeader().getAlgorithm().getName());assertEquals("KEY1234567",jwt.getHeader().getKeyID());assertTrue(jwt.verify(new ECDSAVerifier((ECPublicKey)keys.getPublic())));
        var claims=jwt.getJWTClaimsSet();assertEquals("TEAM123456",claims.getIssuer());assertEquals("services.client",claims.getSubject());assertEquals(List.of("https://appleid.apple.com"),claims.getAudience());assertEquals(now,claims.getIssueTime().toInstant());assertEquals(now.plusSeconds(300),claims.getExpirationTime().toInstant());
        assertThrows(AuthFailure.class,()->signer.create("different.client"));assertFalse(settings.toString().contains(key.toString()));
    }
    @Test void absentOrMalformedKeyIsSafeConfigurationFailure()throws Exception{
        Path key=directory.resolve("broken.p8");Files.writeString(key,"synthetic-invalid-private-material");
        var signer=new AppleClientSecrets(new AppleClientSecrets.Settings(true,"TEAM123456","KEY1234567",key.toString(),Set.of("client")),Clock.fixed(now,ZoneOffset.UTC));
        var error=assertThrows(AuthFailure.class,()->signer.create("client"));assertEquals("APPLE_CLIENT_CONFIGURATION_INVALID",error.code());assertNull(error.getCause());assertFalse(error.toString().contains("synthetic-invalid"));
    }
    @Test void appleOauthUsesBackendSignerForItsConfiguredClient(){
        var signer=org.mockito.Mockito.mock(AppleClientSecrets.class);org.mockito.Mockito.when(signer.enabled()).thenReturn(true);org.mockito.Mockito.when(signer.create("services.client")).thenReturn("synthetic-signed-jwt");
        var providers=new ExternalProviders(new ExternalProviders.Settings(Map.of(ExternalProviders.Provider.APPLE,new ExternalProviders.Client(true,"services.client",null,"https://backend.example.test/callback")),Map.of()),signer);
        assertEquals("synthetic-signed-jwt",providers.registration(ExternalProviders.Provider.APPLE).getClientSecret());org.mockito.Mockito.verify(signer).create("services.client");
    }

}
