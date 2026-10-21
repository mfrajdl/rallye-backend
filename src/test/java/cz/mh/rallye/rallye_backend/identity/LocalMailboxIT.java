package cz.mh.rallye.rallye_backend.identity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import cz.mh.rallye.rallye_backend.identity.application.AuthService;
import cz.mh.rallye.rallye_backend.support.TestDatabaseInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles({"local", "it"})
@ContextConfiguration(initializers = TestDatabaseInitializer.class)
class LocalMailboxIT {
    @TempDir static Path mailbox;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("rallye.auth.local-mail-directory", () -> mailbox.toString());
        properties.add("rallye.auth.mail-mode", () -> "local-file");
        properties.add("server.address", () -> "127.0.0.1");
    }
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;

    @Test void registrationActivatesThroughRealLocalMailboxWithoutSmtp() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        try {
            auth.register(email, "local-password", "Local mailbox test");
            assertThrows(AuthFailure.class, () -> auth.login(email, "local-password", false));
            String text;
            try (var files = Files.list(mailbox)) {
                var messages = files.toList();
                assertEquals(1, messages.size());
                text = Files.readString(messages.getFirst());
            }
            String token = text.substring(text.indexOf("#token=") + 7).strip();
            auth.verifyEmail(token);
            assertThrows(AuthFailure.class, () -> auth.verifyEmail(token));
            var login = auth.login(email, "local-password", false);
            assertNotNull(auth.authenticate(login.secret()));
            auth.logout(login.principal());
            assertNull(auth.authenticate(login.secret()));
        } finally {
            for (String table : java.util.List.of("user_session", "user_action_token", "user_identity")) {
                jdbc.update("DELETE FROM " + table + " WHERE account_id IN (SELECT id FROM user_account WHERE normalized_email=?)", email);
            }
            jdbc.update("DELETE FROM user_account WHERE normalized_email=?", email);
        }
    }
}
