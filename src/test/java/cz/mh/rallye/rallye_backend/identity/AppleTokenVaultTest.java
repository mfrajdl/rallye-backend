package cz.mh.rallye.rallye_backend.identity;

import java.util.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AppleTokenVaultTest {
    @Test void authenticatedEncryptionProtectsTokenAndBindsItToCredential() {
        var settings=new AppleTokenVault.Settings("a",Map.of("a",Base64.getEncoder().encodeToString(new byte[32])));
        var vault=new AppleTokenVault(settings);var a=vault.seal("synthetic-refresh","row-a");var b=vault.seal("synthetic-refresh","row-a");
        assertEquals("synthetic-refresh",vault.open(a,"row-a"));assertFalse(Arrays.equals(a.ciphertext(),b.ciphertext()));
        assertThrows(AuthFailure.class,()->vault.open(a,"row-b"));
        byte[] corrupted=a.ciphertext().clone();corrupted[0]^=1;
        assertThrows(AuthFailure.class,()->vault.open(new AppleTokenVault.Sealed(a.keyId(),a.iv(),corrupted),"row-a"));
        assertFalse(settings.toString().contains(settings.keys().get("a")));assertFalse(a.toString().contains("synthetic"));
    }
    @Test void missingWrongAndRotatedKeysFailSafely() {
        var vault=new AppleTokenVault(new AppleTokenVault.Settings("old",Map.of("old",Base64.getEncoder().encodeToString(new byte[32]))));
        var sealed=vault.seal("synthetic-refresh","row");
        var rotated=new AppleTokenVault(new AppleTokenVault.Settings("new",Map.of("old",Base64.getEncoder().encodeToString(new byte[32]),"new",Base64.getEncoder().encodeToString(new byte[32]))));
        assertEquals("synthetic-refresh",rotated.open(sealed,"row"));
        var missing=new AppleTokenVault(new AppleTokenVault.Settings("new",Map.of()));
        assertThrows(AuthFailure.class,missing::requireReady);
        assertEquals(503,assertThrows(AuthFailure.class,()->missing.seal("token","row")).status());
        assertThrows(AuthFailure.class,()->missing.open(sealed,"row"));
    }
}
