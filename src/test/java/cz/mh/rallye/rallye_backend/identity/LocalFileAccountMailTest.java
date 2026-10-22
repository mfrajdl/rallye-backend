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

    @Test void transferMailUsesSameDedicatedClientLinkInFileAndSmtp() throws Exception {
        var local=new LocalFileAccountMailSender(directory.toString(),"http://localhost:3000","127.0.0.1");
        local.transferPage("https://client.example.test/captain");
        var transport=org.mockito.Mockito.mock(org.springframework.mail.javamail.JavaMailSender.class);
        var smtp=new SmtpAccountMailSender(transport,Runnable::run,"rallye@example.test","http://localhost:3000");
        smtp.transferPage("https://client.example.test/captain");
        var id=java.util.UUID.randomUUID();var expires=java.time.Instant.parse("2026-10-29T12:00:00Z");
        local.sendTransfer("target@example.test","Our crew",id,"test-secret",expires);
        smtp.sendTransfer("target@example.test","Our crew",id,"test-secret",expires);
        var message=org.mockito.ArgumentCaptor.forClass(org.springframework.mail.SimpleMailMessage.class);
        org.mockito.Mockito.verify(transport).send(message.capture());
        String text;
        try(var paths=Files.list(directory)) {text=Files.readString(paths.findFirst().orElseThrow());}
        assertTrue(text.contains("Purpose: CREW_TRANSFER"));assertTrue(text.contains(message.getValue().getText()));
        assertTrue(text.contains("Our crew"));assertTrue(text.contains("7 dní"));assertTrue(text.contains(expires.toString()));
        assertTrue(text.contains("https://client.example.test/captain#transferId="+id+"&token=test-secret"));
        assertFalse(text.contains("/reset-password"));
        assertThrows(IllegalArgumentException.class,()->local.transferPage("http://public.example.test/captain"));
        assertThrows(IllegalArgumentException.class,()->smtp.transferPage("https://client.example.test/page?token=x"));
    }

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
