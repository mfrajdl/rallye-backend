package cz.mh.rallye.rallye_backend.identity;

import cz.mh.rallye.rallye_backend.identity.application.SmtpAccountMailSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountMailTest {
    @Test void linksUseConfiguredOriginAndFragmentRatherThanQuery() {
        var smtp = mock(JavaMailSender.class);
        var sender = new SmtpAccountMailSender(smtp, Runnable::run, "rallye@example.test", "https://rallye.example.test");
        sender.send("person@example.test", "EMAIL_VERIFICATION", "test-token");
        var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(smtp).send(message.capture());
        assertTrue(message.getValue().getText().contains("https://rallye.example.test/verify-email#token=test-token"));
        assertFalse(message.getValue().getText().contains("?token="));
    }
    @Test void mailUsesBoundedExecutorInsteadOfBlockingResetRequest() {
        var smtp = mock(JavaMailSender.class);
        var executor = mock(java.util.concurrent.Executor.class);
        new SmtpAccountMailSender(smtp, executor, "rallye@example.test", "https://rallye.example.test")
                .send("person@example.test", "PASSWORD_RESET", "test-token");
        verify(executor).execute(any(Runnable.class)); verifyNoInteractions(smtp);
    }
    @Test void rejectInsecurePublicLinkOriginAndEmbeddedCredentials() {
        var smtp = mock(JavaMailSender.class);
        assertThrows(IllegalArgumentException.class, () -> new SmtpAccountMailSender(smtp, Runnable::run,
                "from@example.test", "http://public.example.test"));
        assertThrows(IllegalArgumentException.class, () -> new SmtpAccountMailSender(smtp, Runnable::run,
                "from@example.test", "https://user:secret@example.test"));
    }
}
