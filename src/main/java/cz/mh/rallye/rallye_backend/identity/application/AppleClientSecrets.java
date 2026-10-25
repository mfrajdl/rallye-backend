package cz.mh.rallye.rallye_backend.identity.application;

import java.nio.file.*;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.*;
import java.time.Clock;
import java.util.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.*;
import org.springframework.boot.context.properties.*;
import org.springframework.stereotype.Component;

/** Short-lived ES256 client secrets. The private key is read from a protected external PKCS#8 file. */
@Component
@EnableConfigurationProperties(AppleClientSecrets.Settings.class)
public class AppleClientSecrets {
    @ConfigurationProperties("rallye.auth.apple-revocation")
    public record Settings(boolean enabled,String teamId,String keyId,String privateKeyFile,Set<String> clientIds){
        public Settings{clientIds=clientIds==null?Set.of():Set.copyOf(clientIds);}
        @Override public String toString(){return "AppleClientSecretSettings[redacted]";}
    }
    private final Settings settings;private final Clock clock;
    public AppleClientSecrets(Settings settings,Clock clock){this.settings=settings;this.clock=clock;}
    public boolean enabled(){return settings.enabled();}
    public String create(String clientId){
        try {
            if(!settings.enabled() || clientId==null || !settings.clientIds().contains(clientId)
                || settings.teamId()==null || !settings.teamId().matches("[A-Z0-9]{10}")
                || settings.keyId()==null || !settings.keyId().matches("[A-Z0-9]{10}"))throw new IllegalArgumentException();
            Path path=Path.of(settings.privateKeyFile());if(!path.isAbsolute() || Files.size(path)>16384)throw new IllegalArgumentException();
            String pem=Files.readString(path);if(!pem.startsWith("-----BEGIN PRIVATE KEY-----"))throw new IllegalArgumentException();
            byte[] der=Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----",""));
            ECPrivateKey key=(ECPrivateKey)KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));Arrays.fill(der,(byte)0);
            var params=AlgorithmParameters.getInstance("EC");params.init(new ECGenParameterSpec("secp256r1"));var curve=params.getParameterSpec(ECParameterSpec.class);
            if(!curve.getOrder().equals(key.getParams().getOrder()) || !curve.getCurve().equals(key.getParams().getCurve()))throw new IllegalArgumentException();
            var now=clock.instant();var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(settings.keyId()).build(),
                new JWTClaimsSet.Builder().issuer(settings.teamId()).subject(clientId).audience("https://appleid.apple.com")
                    .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300))).build());
            jwt.sign(new ECDSASigner(key));return jwt.serialize();
        }catch(Exception failure){throw new AuthFailure(503,"APPLE_CLIENT_CONFIGURATION_INVALID");} // Never wrap key material or provider tokens.
    }
}
