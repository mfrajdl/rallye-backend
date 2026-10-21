package cz.mh.rallye.rallye_backend.identity.application;

public final class PasswordPolicy {
    private PasswordPolicy() { }
    public static void validate(String password) {
        if (password == null) throw new AuthFailure(400, "INVALID_PASSWORD_LENGTH");
        int length = password.codePointCount(0, password.length());
        if (length < 8 || length > 128) throw new AuthFailure(400, "INVALID_PASSWORD_LENGTH");
    }
}
