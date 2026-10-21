package cz.mh.rallye.rallye_backend.identity;

import cz.mh.rallye.rallye_backend.identity.application.LocalFileAccountMailSender;
import cz.mh.rallye.rallye_backend.identity.application.SmtpAccountMailSender;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class LocalFileAccountMailTest {
    @TempDir Path directory;

    @Test void mailboxContainsActivationAndResetLinksWithoutExternalDelivery() throws Exception {
        var sender = new LocalFileAccountMailSender(directory.toString(), "http://localhost:3000/", "127.0.0.1");
        sender.send("local@example.test", "EMAIL_VERIFICATION", "activation-secret");
        sender.send("local@example.test", "PASSWORD_RESET", "reset-secret");
        try (var files = Files.list(directory)) {
            var contents = files.map(path -> {
                try { return Files.readString(path); }
                catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            }).toList();
            assertEquals(2, contents.size());
            assertTrue(contents.stream().anyMatch(text -> text.contains("/verify-email#token=activation-secret")));
            assertTrue(contents.stream().anyMatch(text -> text.contains("/reset-password#token=reset-secret")));
        }
    }

    @Test void mailboxRejectsWildcardAndMissingBindAddress() {
        for (String address : new String[]{"", "0.0.0.0", "192.168.1.2"}) {
            assertThrows(IllegalArgumentException.class, () -> new LocalFileAccountMailSender(
                    directory.toString(), "http://localhost:3000", address));
        }
    }

    @Test void localModeRequiresExplicitLocalProfileAndDoesNotCreateSmtpSender() {
        var runner = new ApplicationContextRunner()
                .withUserConfiguration(LocalFileAccountMailSender.class, SmtpAccountMailSender.class)
                .withPropertyValues("rallye.auth.mail-mode=local-file", "server.address=127.0.0.1",
                        "rallye.auth.local-mail-directory=" + directory);
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(0, context.getBeansOfType(LocalFileAccountMailSender.class).size());
            assertEquals(0, context.getBeansOfType(SmtpAccountMailSender.class).size());
        });
        runner.withPropertyValues("spring.profiles.active=local").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(LocalFileAccountMailSender.class).size());
            assertEquals(0, context.getBeansOfType(SmtpAccountMailSender.class).size());
        });
    }
}
