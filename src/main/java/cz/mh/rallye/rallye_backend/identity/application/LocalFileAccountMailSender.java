package cz.mh.rallye.rallye_backend.identity.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Explicit local development mailbox. Never exposes secrets through HTTP or logs. */
@Component
@Profile("local")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "rallye.auth.mail-mode", havingValue = "local-file")
public class LocalFileAccountMailSender implements AccountMailSender {
    private final Path directory;
    private final String baseUrl;

    public LocalFileAccountMailSender(
            @Value("${rallye.auth.local-mail-directory:${user.home}/.rallye/local-mail}") String directory,
            @Value("${rallye.auth.public-base-url:http://localhost:3000}") String baseUrl,
            @Value("${server.address:}") String address) {
        if (!"127.0.0.1".equals(address) && !"::1".equals(address)) {
            throw new IllegalArgumentException("Local mailbox requires an explicit loopback server.address");
        }
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.baseUrl = SmtpAccountMailSender.validateBaseUrl(baseUrl);
    }

    @Override public void send(String email, String purpose, String token) {
        try {
            Files.createDirectories(directory);
            boolean posix = Files.getFileStore(directory).supportsFileAttributeView("posix");
            Path file = posix
                    ? Files.createTempFile(directory, "message-", ".txt",
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    : Files.createTempFile(directory, "message-", ".txt");
            boolean activation = "EMAIL_VERIFICATION".equals(purpose);
            String text = "To: " + email + "\nPurpose: " + purpose + "\n"
                    + (activation ? "Expires in 24 hours\n" : "Expires in 30 minutes\n")
                    + baseUrl + (activation ? "/verify-email" : "/reset-password") + "#token=" + token + "\n";
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            // Do not include file contents, recipient or token in diagnostics.
            throw new IllegalStateException("Cannot write local development mailbox");
        }
    }
}
