package cz.mh.rallye.rallye_backend.participation.application;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import org.springframework.stereotype.Component;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import cz.mh.rallye.rallye_backend.identity.application.SecretTokens;

/** No signing/encryption keys. Codes are deliberately stored as plaintext; device credentials are not. */
@Component
public final class CrewStartSecrets {
    private static final String ALPHABET="ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private final SecureRandom random=new SecureRandom();
    private final SecretTokens tokens;
    public CrewStartSecrets(SecretTokens tokens){this.tokens=tokens;}
    public String newCode(){var code=new StringBuilder(6);for(int i=0;i<6;i++)code.append(ALPHABET.charAt(random.nextInt(36)));return code.toString();}
    public String normalizeCode(String raw){
        if(raw==null)throw invalid();String code=raw.strip().toUpperCase(Locale.ROOT);
        if(!code.matches("[A-Z0-9]{6}"))throw invalid();return code;
    }
    /** Validates the client-created 256-bit credential without ever retaining its open value. */
    public DeviceHash deviceHash(String raw){
        if(raw==null||!raw.matches("[A-Za-z0-9_-]{43}"))throw invalid();
        try{byte[] bytes=Base64.getUrlDecoder().decode(raw);
            if(bytes.length!=32||!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(raw))throw invalid();
        }catch(IllegalArgumentException malformed){throw invalid();}
        return new DeviceHash(tokens.hash(raw));
    }
    public record DeviceHash(String value){
        public DeviceHash{if(value==null||!value.matches("[0-9a-f]{64}"))throw invalid();}
        @Override public String toString(){return "DeviceHash[redacted]";}
    }
    private static AuthFailure invalid(){return new AuthFailure(400,"INVALID_REQUEST");}
}
