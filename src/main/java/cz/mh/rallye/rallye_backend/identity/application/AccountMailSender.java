package cz.mh.rallye.rallye_backend.identity.application;

public interface AccountMailSender {
    void send(String email, String purpose, String token);
    default void sendTransfer(String email,String crewName,java.util.UUID transferId,String token,java.time.Instant expiresAt) {
        throw new UnsupportedOperationException("Transfer mail is not supported by this adapter");
    }
}
