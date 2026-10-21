package cz.mh.rallye.rallye_backend.identity;

import cz.mh.rallye.rallye_backend.identity.persistence.AdminStore;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminStoreTest {
    @Test void missingSingletonCannotSilentlySkipLock() {
        var jdbc=mock(JdbcTemplate.class);
        var failure=new EmptyResultDataAccessException(1);
        when(jdbc.queryForObject("SELECT id FROM admin_control WHERE id=1 FOR UPDATE",Integer.class)).thenThrow(failure);
        assertSame(failure,assertThrows(EmptyResultDataAccessException.class,()->new AdminStore(jdbc).lock()));
    }
}
