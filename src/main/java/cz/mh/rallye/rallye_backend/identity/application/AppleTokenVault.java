package cz.mh.rallye.rallye_backend.identity.application;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.boot.context.properties.*;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AppleTokenVault.Settings.class)
public class AppleTokenVault {
    @ConfigurationProperties("rallye.auth.apple-vault")
    public record Settings(String activeKeyId,Map<String,String> keys) {
        public Settings {keys=keys==null?Map.of():Map.copyOf(keys);}
        @Override public String toString(){return "AppleVaultSettings[redacted]";}
    }
    public record Sealed(String keyId,byte[] iv,byte[] ciphertext) {
        @Override public String toString(){return "SealedAppleToken[redacted]";}
    }
    private final Settings settings;private final SecureRandom random=new SecureRandom();
    public AppleTokenVault(Settings settings){this.settings=settings;}
    public void requireReady(){try {key(settings.activeKeyId());}catch(RuntimeException failure){throw unavailable();}}
    public Sealed seal(String token,String aad) {
        if(token==null || token.isBlank())throw unavailable();
        try {
            byte[] iv=new byte[12];random.nextBytes(iv);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,key(settings.activeKeyId()),new GCMParameterSpec(128,iv));cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return new Sealed(settings.activeKeyId(),iv,cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception failure){throw unavailable();}
    }
    public String open(Sealed sealed,String aad) {
        try {var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(sealed.keyId()),new GCMParameterSpec(128,sealed.iv()));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));return new String(cipher.doFinal(sealed.ciphertext()),StandardCharsets.UTF_8);
        } catch(Exception failure){throw unavailable();}
    }
    private SecretKeySpec key(String id) {
        byte[] value=Base64.getDecoder().decode(settings.keys().get(id));
        if(value.length!=32 || id==null || !id.matches("[A-Za-z0-9_-]{1,64}"))throw unavailable();
        return new SecretKeySpec(value,"AES");
    }
    private static AuthFailure unavailable(){return new AuthFailure(503,"APPLE_CREDENTIAL_STORAGE_UNAVAILABLE");}
}
