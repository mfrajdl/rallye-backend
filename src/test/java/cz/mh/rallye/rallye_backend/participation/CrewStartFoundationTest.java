package cz.mh.rallye.rallye_backend.participation;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import cz.mh.rallye.rallye_backend.identity.MutableClock;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.participation.application.*;
import cz.mh.rallye.rallye_backend.participation.persistence.CrewStartStore;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CrewStartFoundationTest {
    final SecretTokens tokens=new SecretTokens();
    final CrewStartSecrets secrets=new CrewStartSecrets(tokens);
    @Test void codesHaveExactlySixUniformAlphabetCharacters(){var values=new HashSet<String>();for(int i=0;i<2000;i++){String c=secrets.newCode();assertTrue(c.matches("[A-Z0-9]{6}"));values.add(c);}assertTrue(values.size()>1900);}
    @Test void canonicalCodeNeverAcceptsWrongFormat(){assertEquals("AB09CZ",secrets.normalizeCode(" ab09cz "));for(String c:List.of("A1234","A123456","ABC-12","PŘÍŠER","ABC 12",""))assertThrows(AuthFailure.class,()->secrets.normalizeCode(c));assertThrows(AuthFailure.class,()->secrets.normalizeCode(null));}
    @Test void deviceHashUsesSha256AndRejectsNonCanonicalOrShortTokens(){String raw=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);assertEquals(tokens.hash(raw),secrets.deviceHash(raw).value());assertFalse(secrets.deviceHash(raw).toString().contains(raw));assertFalse(secrets.deviceHash(raw).toString().contains(tokens.hash(raw)));for(String bad:List.of(raw+"=",raw.substring(1),raw.substring(0,42)+"B","bad"))assertThrows(AuthFailure.class,()->secrets.deviceHash(bad));assertThrows(AuthFailure.class,()->secrets.deviceHash(null));}
    @Test void tokenAndCodeRecordsDoNotAccidentallyPrintCredentials(){String raw=tokens.create();var hash=secrets.deviceHash(raw);UUID id=UUID.randomUUID();var c=new CrewStartStore.Code(id,id,"ORIGINAL",1,"AVAILABLE","ABC123",Instant.EPOCH,id,null,null,0,null,0);var d=new CrewStartStore.Device(id,id,1,hash,"ACTIVE",Instant.EPOCH,null,id,"Phone",null,null);assertFalse(c.toString().contains(c.value()));assertFalse(d.toString().contains(raw));assertFalse(d.toString().contains(hash.value()));}
    @Test void commandResultRejectsSecretFieldsBeforeCallingDatabase(){var jdbc=mock(JdbcTemplate.class);var store=new CrewStartStore(jdbc);UUID id=UUID.randomUUID();for(String json:List.of("{\"codes\":[{\"code\":\"ABC123\"}]}","{\"code\":\"ABC123\"}","{\"nested\":[{\"deviceSecret\":\"private\"}]}")){var command=new CrewStartStore.Command(id,id,id,id,"ORIGINAL_ISSUE",1,"a".repeat(64),id,null,null,Instant.EPOCH,201,json);assertThrows(IllegalArgumentException.class,()->store.insertCommand(command));}verifyNoInteractions(jdbc);}
    @Test void throttleSeparatesRaceFromLoginAndDoesNotResetAfterFailedOperation(){var clock=new MutableClock();var throttle=new LoginThrottle(clock,tokens);var attempts=new CrewStartAttempts(throttle);UUID account=UUID.randomUUID();for(int i=0;i<10;i++)attempts.check(account,"127.0.0.1");assertEquals("TOO_MANY_ATTEMPTS",assertThrows(AuthFailure.class,()->attempts.check(account,"127.0.0.2")).code());throttle.check("login",account.toString(),"127.0.0.1");clock.advance(Duration.ofMinutes(16));assertDoesNotThrow(()->attempts.check(account,"127.0.0.1"));}
    @Test void sourceLimitAppliesAcrossAccounts(){var throttle=new LoginThrottle(new MutableClock(),tokens);var attempts=new CrewStartAttempts(throttle);for(int i=0;i<50;i++)attempts.check(UUID.randomUUID(),"127.0.0.1");assertThrows(AuthFailure.class,()->attempts.check(UUID.randomUUID(),"127.0.0.1"));}
    @Test void jdbcMutationsRequireExistingTransaction(){var annotation=CrewStartStore.class.getAnnotation(org.springframework.transaction.annotation.Transactional.class);assertEquals(org.springframework.transaction.annotation.Propagation.MANDATORY,annotation.propagation());}
    @Test void splitSourceAndAccountAdmissionKeepsExistingLimitsWithoutDoubleCounting(){var attempts=new CrewStartAttempts(new LoginThrottle(new MutableClock(),tokens));UUID account=UUID.randomUUID();for(int i=0;i<10;i++){attempts.source("source");attempts.account(account);}assertThrows(AuthFailure.class,()->attempts.account(account));for(int i=10;i<50;i++)attempts.source("source");assertThrows(AuthFailure.class,()->attempts.source("source"));}

}
