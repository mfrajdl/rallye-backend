package cz.mh.rallye.rallye_backend.identity.application;

public interface AccountMailSender {
    void send(String email, String purpose, String token);
}
