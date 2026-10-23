package cz.mh.rallye.rallye_backend.identity.application;

public final class AuthFailure extends RuntimeException {
    private final int status;
    private final String code;
    public AuthFailure(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }
    public AuthFailure(int status,String code,Throwable cause) {
        super(code,cause);
        this.status=status;
        this.code=code;
    }
    public int status() { return status; }
    public String code() { return code; }
    public static AuthFailure credentials() { return new AuthFailure(401, "INVALID_CREDENTIALS"); }
    public static AuthFailure token() { return new AuthFailure(400, "INVALID_OR_EXPIRED_TOKEN"); }
}
