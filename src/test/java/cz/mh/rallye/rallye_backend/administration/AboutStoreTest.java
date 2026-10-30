package cz.mh.rallye.rallye_backend.administration;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.*;
import org.springframework.dao.DataAccessResourceFailureException;
import cz.mh.rallye.rallye_backend.administration.persistence.AboutStore;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class AboutStoreTest {
 @Test void jdbcFailureBecomesSafe503InsteadOfAnInventedMigrationStatus(){var jdbc=mock(JdbcTemplate.class);when(jdbc.execute(org.mockito.ArgumentMatchers.<ConnectionCallback<AboutStore.Database>>any())).thenThrow(new DataAccessResourceFailureException("sensitive JDBC URL and credentials"));var failure=assertThrows(AuthFailure.class,()->new AboutStore(jdbc,mock(Flyway.class)).read());assertEquals(503,failure.status());assertEquals("ABOUT_DATABASE_UNAVAILABLE",failure.code());assertFalse(failure.getMessage().contains("credentials"));}
}
