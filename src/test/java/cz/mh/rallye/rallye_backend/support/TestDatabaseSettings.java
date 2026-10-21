package cz.mh.rallye.rallye_backend.support;

import java.util.Map;
import java.util.regex.Pattern;

/** Test-only allowlist: reject URL options, multiple hosts and encoded database names. */
final class TestDatabaseSettings {
    private static final Pattern URL = Pattern.compile(
            "jdbc:postgresql://[a-zA-Z0-9.-]+:([0-9]{1,5})/rallye_test");

    private final String url;
    private final String username;
    private final String password;

    private TestDatabaseSettings(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    static TestDatabaseSettings from(Map<String, String> environment) {
        rejectConnectionOverrides(environment);
        if (!"true".equals(environment.get("RALLYE_TEST_DB_ENABLED"))) {
            throw new IllegalStateException("Integration database requires RALLYE_TEST_DB_ENABLED=true");
        }
        String url = environment.getOrDefault("RALLYE_TEST_DB_URL", "");
        var matcher = URL.matcher(url);
        if (!matcher.matches() || Integer.parseInt(matcher.group(1)) < 1
                || Integer.parseInt(matcher.group(1)) > 65535) {
            throw new IllegalStateException("Test JDBC URL must name only rallye_test with explicit host/port and no options");
        }
        String username = environment.getOrDefault("RALLYE_TEST_DB_USERNAME", "");
        if (!"rallye_test".equals(username)) {
            throw new IllegalStateException("Integration database requires the dedicated rallye_test role");
        }
        String password = environment.getOrDefault("RALLYE_TEST_DB_PASSWORD", "");
        if (password.isBlank()) {
            throw new IllegalStateException("RALLYE_TEST_DB_PASSWORD must be supplied separately");
        }
        return new TestDatabaseSettings(url, username, password);
    }

    static void rejectConnectionOverrides(Map<String, String> properties) {
        boolean overridden = properties.keySet().stream().map(key -> key
                        .replace("_", "").replace("-", "").replace(".", "").toLowerCase(java.util.Locale.ROOT))
                .anyMatch(key -> key.startsWith("springdatasource") || key.startsWith("springflyway")
                        || key.startsWith("springjpa") || key.startsWith("springsql")
                        || key.startsWith("springconfig") || key.equals("springapplicationjson"));
        if (overridden) {
            throw new IllegalStateException("Remove Spring connection/config overrides for integration tests; use only RALLYE_TEST_DB_* settings");
        }
    }

    String url() { return url; }
    String username() { return username; }
    String password() { return password; }
}
