package cz.mh.rallye.rallye_backend.support;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Runs before context refresh, hence before Flyway, Hibernate or SQL initialization. */
public final class TestDatabaseInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        Map<String, String> systemProperties = new java.util.HashMap<>();
        System.getProperties().forEach((key, value) -> systemProperties.put(key.toString(), value.toString()));
        TestDatabaseSettings.rejectConnectionOverrides(systemProperties);
        TestDatabaseSettings settings = TestDatabaseSettings.from(System.getenv());
        verifyServerIdentity(settings);
        // Pin both consumers to the checked target, even when development overrides are inherited.
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "guarded-test-database", Map.ofEntries(
                Map.entry("spring.datasource.url", settings.url()),
                Map.entry("spring.datasource.username", settings.username()),
                Map.entry("spring.datasource.password", settings.password()),
                Map.entry("spring.datasource.hikari.jdbc-url", settings.url()),
                Map.entry("spring.datasource.hikari.username", settings.username()),
                Map.entry("spring.datasource.hikari.password", settings.password()),
                Map.entry("spring.flyway.url", settings.url()),
                Map.entry("spring.flyway.user", settings.username()),
                Map.entry("spring.flyway.password", settings.password()),
                Map.entry("spring.flyway.clean-disabled", true),
                Map.entry("spring.flyway.baseline-on-migrate", false),
                Map.entry("spring.jpa.hibernate.ddl-auto", "validate"),
                Map.entry("spring.sql.init.mode", "never"))));
    }

    private static void verifyServerIdentity(TestDatabaseSettings settings) {
        Properties properties = new Properties();
        properties.setProperty("user", settings.username());
        properties.setProperty("password", settings.password());
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "5");
        try (var connection = DriverManager.getConnection(settings.url(), properties)) {
            connection.setReadOnly(true);
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("select current_database(), current_user")) {
                if (!result.next() || !"rallye_test".equals(result.getString(1))
                        || !"rallye_test".equals(result.getString(2))) {
                    throw new IllegalStateException("Server identity is not the dedicated test database/role");
                }
            }
        } catch (SQLException exception) {
            // Do not expose JDBC URL, credentials or driver diagnostics in this error.
            throw new IllegalStateException("Test database preflight failed; check dedicated infrastructure and credentials");
        }
    }
}
