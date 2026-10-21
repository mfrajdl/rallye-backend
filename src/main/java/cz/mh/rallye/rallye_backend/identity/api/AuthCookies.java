package cz.mh.rallye.rallye_backend.identity.api;

import java.time.Duration;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class AuthCookies {
    public static final String NAME = "RALLYE_SESSION";
    private final boolean secure;
    public AuthCookies(@Value("${rallye.auth.secure-cookies:true}") boolean secure) { this.secure = secure; }
    public static boolean present(HttpServletRequest request) {
        if (request.getCookies() == null) return false;
        return java.util.Arrays.stream(request.getCookies()).anyMatch(cookie -> NAME.equals(cookie.getName()));
    }
    public static String read(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        String value = null;
        for (var cookie : request.getCookies()) {
            if (NAME.equals(cookie.getName())) {
                if (value != null) throw new cz.mh.rallye.rallye_backend.identity.application.AuthFailure(400, "AMBIGUOUS_CREDENTIALS");
                value = cookie.getValue();
            }
        }
        return value;
    }
    public void set(HttpServletResponse response, String token, boolean remember) {
        var cookie = ResponseCookie.from(NAME, token).httpOnly(true).secure(secure).sameSite("Strict").path("/");
        if (remember) cookie.maxAge(Duration.ofDays(30));
        response.addHeader("Set-Cookie", cookie.build().toString());
    }
    public void clear(HttpServletResponse response) {
        response.addHeader("Set-Cookie", ResponseCookie.from(NAME, "").httpOnly(true).secure(secure)
                .sameSite("Strict").path("/").maxAge(Duration.ZERO).build().toString());
    }
}
