package cz.mh.rallye.rallye_backend.support;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestDatabaseSettingsTest {
    private static Map<String, String> validSettings() {
        return new HashMap<>(Map.of(
                "RALLYE_TEST_DB_ENABLED", "true",
                "RALLYE_TEST_DB_URL", "jdbc:postgresql://127.0.0.1:5434/rallye_test",
                "RALLYE_TEST_DB_USERNAME", "rallye_test",
                "RALLYE_TEST_DB_PASSWORD", "test-only-value"));
    }

    @Test
    void acceptsDedicatedTarget() {
        var settings = TestDatabaseSettings.from(validSettings());
        assertEquals("rallye_test", settings.username());
        assertEquals("jdbc:postgresql://127.0.0.1:5434/rallye_test", settings.url());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RALLYE_TEST_DB_ENABLED", "RALLYE_TEST_DB_URL",
            "RALLYE_TEST_DB_USERNAME", "RALLYE_TEST_DB_PASSWORD"})
    void requiresEveryExplicitSetting(String key) {
        var environment = validSettings();
        environment.remove(key);
        environment.put("RALLYE_DB_PASSWORD", "development-must-not-be-used");
        assertThrows(IllegalStateException.class, () -> TestDatabaseSettings.from(environment));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://127.0.0.1:5433/rallye_dev",
            "jdbc:postgresql://127.0.0.1:5433/rallye",
            "jdbc:postgresql://127.0.0.1:5434/rallye_test?user=rallye",
            "jdbc:postgresql://127.0.0.1:5434/rallye_test?options=-c%20role=rallye",
            "jdbc:postgresql://127.0.0.1:5434/%72allye_test",
            "jdbc:postgresql://host:5434,other:5433/rallye_test",
            "jdbc:postgresql:rallye_test",
            "jdbc:postgresql://host:0/rallye_test",
            "jdbc:postgresql://host:65536/rallye_test",
            "jdbc:postgresql://host:5434/rallye_test/extra"})
    void rejectsUnsafeOrAmbiguousUrls(String url) {
        var environment = validSettings();
        environment.put("RALLYE_TEST_DB_URL", url);
        assertThrows(IllegalStateException.class, () -> TestDatabaseSettings.from(environment));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rallye", "postgres", "", "rallye_dev"})
    void rejectsNonTestRole(String username) {
        var environment = validSettings();
        environment.put("RALLYE_TEST_DB_USERNAME", username);
        assertThrows(IllegalStateException.class, () -> TestDatabaseSettings.from(environment));
    }

    @Test
    void neverIncludesSecretsInValidationError() {
        var environment = validSettings();
        environment.put("RALLYE_TEST_DB_URL", "jdbc:postgresql://host/rallye?password=secret");
        var error = assertThrows(IllegalStateException.class,
                () -> TestDatabaseSettings.from(environment));
        assertFalse(error.getMessage().contains("secret"));
        assertFalse(error.getMessage().contains("test-only-value"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_JNDI_NAME",
            "SPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_DATABASE_NAME",
            "SPRING_FLYWAY_URL", "SPRING_APPLICATION_JSON", "SPRING_CONFIG_LOCATION",
            "spring.datasource.url", "spring.flyway.jdbc-properties.user",
            "SPRING_JPA_HIBERNATE_DDL_AUTO", "SPRING_SQL_INIT_MODE"})
    void rejectsInheritedSpringConnectionOverrides(String key) {
        var environment = validSettings();
        environment.put(key, "must-not-be-used");
        assertThrows(IllegalStateException.class, () -> TestDatabaseSettings.from(environment));
    }
}
