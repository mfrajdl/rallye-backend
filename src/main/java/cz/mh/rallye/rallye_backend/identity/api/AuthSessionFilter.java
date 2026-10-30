package cz.mh.rallye.rallye_backend.identity.api;

import java.io.IOException;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import cz.mh.rallye.rallye_backend.identity.application.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class AuthSessionFilter extends OncePerRequestFilter {
    private final AuthService service;
    private final AuthCookies cookies;
    public AuthSessionFilter(AuthService service, AuthCookies cookies) { this.service = service; this.cookies = cookies; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        try {
            String cookie = AuthCookies.read(request);
            String header = request.getHeader("Authorization");
            if (header != null && cookie != null) throw new AuthFailure(400, "AMBIGUOUS_CREDENTIALS");
            String secret = cookie;
            if (header != null) {
                if (!header.startsWith("Bearer ")) throw AuthFailure.credentials();
                secret = header.substring(7);
            }
            if (secret != null) {
                String path=request.getRequestURI().substring(request.getContextPath().length());
                boolean readOnly="GET".equals(request.getMethod())&&(path.equals("/api/v1/admin/about")||path.equals("/api/v1/me/registrations")||path.equals("/api/v1/me/registrations/summary")||path.equals("/api/v1/race-events")||path.startsWith("/api/v1/race-events/"));
                var principal = secret.matches("[A-Za-z0-9_-]{43}") ? (readOnly?service.authenticateReadOnly(secret):service.authenticate(secret)) : null;
                if (principal == null) {
                    if (cookie == null) throw AuthFailure.credentials();
                    // An expired browser cookie must not prevent a fresh login/password reset.
                    cookies.clear(response);
                } else {
                    var context = SecurityContextHolder.createEmptyContext();
                    var authorities = new java.util.ArrayList<SimpleGrantedAuthority>();
                    authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                    if (service.isAdministrator(principal)) authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                    context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null,
                            authorities));
                    SecurityContextHolder.setContext(context);
                    if (cookie != null && principal.rememberMe() && !path.equals("/api/v1/me/registrations")) cookies.set(response, secret, true);
                }
            }
        } catch (AuthFailure failure) {
            if(failure.status()>=500) ApiErrors.logServerFailure(failure);
            ApiErrors.write(response, failure.status(), failure.code());
            return;
        } catch (RuntimeException failure) {
            ApiErrors.logServerFailure(failure);
            ApiErrors.write(response, 503, "AUTHENTICATION_UNAVAILABLE");
            return;
        }
        chain.doFilter(request, response);
    }
}
