package cz.mh.rallye.rallye_backend.identity;

import java.time.Duration;
import cz.mh.rallye.rallye_backend.identity.application.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

class AuthPrimitivesTest {
    @Test void passwordBoundariesIncludeUnicodeCodePoints() {
        assertThrows(AuthFailure.class, () -> PasswordPolicy.validate("a".repeat(7)));
        PasswordPolicy.validate("a".repeat(8)); PasswordPolicy.validate("a".repeat(128));
        assertThrows(AuthFailure.class, () -> PasswordPolicy.validate("a".repeat(129)));
        String emoji = new String(Character.toChars(0x1f600));
        assertThrows(AuthFailure.class, () -> PasswordPolicy.validate(emoji.repeat(7)));
        PasswordPolicy.validate(emoji.repeat(128));
    }
    @Test void argon2idIsSaltedAndVerifies() {
        var encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        String first = encoder.encode("password"); String second = encoder.encode("password");
        assertTrue(first.startsWith("$argon2id$")); assertNotEquals(first, second);
        assertTrue(encoder.matches("password", first)); assertFalse(encoder.matches("wrong", first));
    }
    @Test void tokensAreRandomAndStoredAsDeterministicDigest() {
        var tokens = new SecretTokens(); String secret = tokens.create();
        assertTrue(secret.matches("[A-Za-z0-9_-]{43}")); assertNotEquals(secret, tokens.create());
        assertTrue(tokens.hash(secret).matches("[0-9a-f]{64}"));
        assertEquals(tokens.hash(secret), tokens.hash(secret)); assertNotEquals(secret, tokens.hash(secret));
    }
    @Test void accountLimitExpiresAndDifferentSourcesDoNotBypassIt() {
        var clock = new MutableClock(); var throttle = new LoginThrottle(clock, new SecretTokens());
        for (int i = 0; i < 10; i++) throttle.check("login", "email", "source-" + i);
        assertEquals(429, assertThrows(AuthFailure.class, () -> throttle.check("login", "email", "new-source")).status());
        clock.advance(Duration.ofMinutes(15)); throttle.check("login", "email", "source");
    }
    @Test void sourceLimitPreventsRotatingAccountNames() {
        var throttle = new LoginThrottle(new MutableClock(), new SecretTokens());
        for (int i = 0; i < 50; i++) throttle.check("login", "account-" + i, "source");
        assertThrows(AuthFailure.class, () -> throttle.check("login", "new-account", "source"));
    }
    @Test void transferCreateAndResendHaveSeparateAccountAndIpWindows() {
        var clock=new MutableClock();var throttle=new LoginThrottle(clock,new SecretTokens());
        for(int i=0;i<10;i++) throttle.check("crew-transfer-create","account","source");
        assertEquals(429,assertThrows(AuthFailure.class,()->throttle.check("crew-transfer-create","account","another-source")).status());
        throttle.check("crew-transfer-resend","account","source");
        for(int i=0;i<49;i++) throttle.check("crew-transfer-resend","account-"+i,"source");
        assertEquals(429,assertThrows(AuthFailure.class,()->throttle.check("crew-transfer-resend","new-account","source")).status());
        clock.advance(Duration.ofMinutes(15));throttle.check("crew-transfer-create","account","source");
        throttle.check("crew-transfer-resend","account","source");
    }
}
