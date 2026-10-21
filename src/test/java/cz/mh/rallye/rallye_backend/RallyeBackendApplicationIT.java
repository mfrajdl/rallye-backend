package cz.mh.rallye.rallye_backend;

import javax.sql.DataSource;

import cz.mh.rallye.rallye_backend.support.TestDatabaseInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("it")
@ContextConfiguration(initializers = TestDatabaseInitializer.class)
class RallyeBackendApplicationIT {
    @Autowired
    DataSource dataSource;

    @Test
    void contextLoadsAgainstDedicatedPostgres17() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery(
                     "select current_database(), current_user, current_setting('server_version_num')::int")) {
            assertTrue(result.next());
            assertEquals("rallye_test", result.getString(1));
            assertEquals("rallye_test", result.getString(2));
            assertEquals(17, result.getInt(3) / 10000);
        }
    }
}
