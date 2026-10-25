package cz.mh.rallye.rallye_backend.identity;

import java.util.*;
import cz.mh.rallye.rallye_backend.identity.persistence.AccountStore;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountIdentityProviderTest {
    @Test void readsOnlySingleKnownProviderFromDatabase() {
        var jdbc=mock(JdbcTemplate.class);var store=new AccountStore(jdbc);UUID id=UUID.randomUUID();
        for(String provider:List.of("LOCAL","GOOGLE","APPLE")) {
            when(jdbc.queryForList("SELECT provider FROM user_identity WHERE account_id=?",String.class,id)).thenReturn(List.of(provider));
            assertEquals(provider,store.identityProvider(id));
        }
    }
    @Test void missingMultipleOrUnknownIdentityFailsClosed() {
        var jdbc=mock(JdbcTemplate.class);var store=new AccountStore(jdbc);UUID id=UUID.randomUUID();
        for(List<String> providers:List.of(List.<String>of(),List.of("LOCAL","GOOGLE"),List.of("UNKNOWN"))) {
            when(jdbc.queryForList("SELECT provider FROM user_identity WHERE account_id=?",String.class,id)).thenReturn(providers);
            var error=assertThrows(AuthFailure.class,()->store.identityProvider(id));
            assertEquals(503,error.status());assertEquals("ACCOUNT_IDENTITY_UNAVAILABLE",error.code());
        }
    }
}
