package cz.mh.rallye.rallye_backend.identity;

import java.util.UUID;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.AccountStore.Account;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalCancellationIdentityCheckTest {
    PasswordEncoder encoder=mock(PasswordEncoder.class);
    LocalCancellationIdentityCheck check=new LocalCancellationIdentityCheck(encoder);
    Account account(String hash) {return new Account(UUID.randomUUID(),"test@example.test","Test","ACTIVE",hash);}
    @Test void requiresCurrentPasswordAndFailsClosedForExternalIdentity() {
        when(encoder.matches("correct","hash")).thenReturn(true);
        check.verify(account("hash"),"correct");
        assertEquals("INVALID_CREDENTIALS",assertThrows(AuthFailure.class,()->check.verify(account("hash"),"wrong")).code());
        assertThrows(AuthFailure.class,()->check.verify(account(null),"correct"));
        assertThrows(AuthFailure.class,()->check.verify(account("hash"),null));
    }
}
