package cz.mh.rallye.rallye_backend.identity.application;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "rallye.auth.mail-mode", havingValue = "smtp", matchIfMissing = true)
public class SmtpAccountMailSender implements AccountMailSender {
    private final JavaMailSender sender;
    private final String from;
    private final String baseUrl;
    private final java.util.concurrent.Executor executor;
    public SmtpAccountMailSender(JavaMailSender sender,
            @org.springframework.beans.factory.annotation.Qualifier("accountMailExecutor") java.util.concurrent.Executor executor,
            @Value("${rallye.auth.mail-from:rallye@localhost}") String from,
            @Value("${rallye.auth.public-base-url:http://localhost:3000}") String baseUrl) {
        this.sender = sender; this.from = from; this.executor = executor;
        this.baseUrl = validateBaseUrl(baseUrl);
    }
    static String validateBaseUrl(String baseUrl) {
        URI uri = URI.create(baseUrl);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()))))) {
            throw new IllegalArgumentException("Public auth URL must use HTTPS or local development HTTP");
        }
        return baseUrl.replaceAll("/+$", "");
    }
    @Override public void send(String email, String purpose, String token) {
        // SMTP latency must not distinguish existing accounts from unknown reset requests.
        executor.execute(() -> deliver(email, purpose, token));
    }
    private void deliver(String email, String purpose, String token) {
        boolean verify = "EMAIL_VERIFICATION".equals(purpose);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from); message.setTo(email);
        message.setSubject(verify ? "Rallye: ověření e-mailu" : "Rallye: obnova hesla");
        message.setText((verify ? "Ověřte e-mail do 24 hodin: " : "Obnovte heslo do 30 minut: ")
                + baseUrl + (verify ? "/verify-email" : "/reset-password") + "#token=" + token);
        try { sender.send(message); }
        catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(SmtpAccountMailSender.class)
                    .warn("Account email delivery failed; check SMTP configuration. Request can be repeated.");
        }
    }
}
