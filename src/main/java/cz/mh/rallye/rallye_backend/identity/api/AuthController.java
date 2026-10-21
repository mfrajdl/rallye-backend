package cz.mh.rallye.rallye_backend.identity.api;

import java.util.Map;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import cz.mh.rallye.rallye_backend.identity.application.AuthService;
import cz.mh.rallye.rallye_backend.identity.application.LoginThrottle;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    public record Registration(@NotBlank @Email @Size(max=254) String email,
            @NotNull @Size(max=256) String password, @NotBlank @Size(max=100) String displayName) {
        public Registration { if (email != null) email = AuthService.normalizeEmail(email); }
        @Override public String toString() { return "Registration[redacted]"; }
    }
    public record Login(@NotBlank @Size(max=254) String email, @NotNull @Size(max=256) String password, Boolean rememberMe) {
        @Override public String toString() { return "Login[redacted]"; }
    }
    public record EmailRequest(@NotBlank @Email @Size(max=254) String email) {
        public EmailRequest { if (email != null) email = AuthService.normalizeEmail(email); }
    }
    public record TokenRequest(@NotBlank @Size(max=128) String token) {
        @Override public String toString() { return "TokenRequest[redacted]"; }
    }
    public record Reset(@NotBlank @Size(max=128) String token, @NotNull @Size(max=256) String newPassword) {
        @Override public String toString() { return "Reset[redacted]"; }
    }
    public record Change(@NotNull @Size(max=256) String currentPassword, @NotNull @Size(max=256) String newPassword) {
        @Override public String toString() { return "Change[redacted]"; }
    }
    private final AuthService service;
    private final LoginThrottle throttle;
    private final AuthCookies cookies;
    public AuthController(AuthService service, LoginThrottle throttle, AuthCookies cookies) {
        this.service = service; this.throttle = throttle; this.cookies = cookies;
    }
    @GetMapping("/csrf") public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }
    @PostMapping("/register") public ResponseEntity<Void> register(@Valid @RequestBody Registration input, HttpServletRequest request) {
        passwordPolicy(input.password());
        limit("register", input.email(), request);
        service.register(input.email(), input.password(), input.displayName());
        return ResponseEntity.accepted().build();
    }
    @PostMapping("/email-verifications") public ResponseEntity<Void> verify(@Valid @RequestBody TokenRequest input, HttpServletRequest request) {
        limit("verify", "", request);
        service.verifyEmail(input.token());
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/email-verification-requests") public ResponseEntity<Void> verificationRequest(@Valid @RequestBody EmailRequest input, HttpServletRequest request) {
        limit("verify-request", input.email(), request);
        service.requestVerification(input.email());
        return ResponseEntity.accepted().build();
    }
    @PostMapping("/login") public Object login(@Valid @RequestBody Login input, HttpServletRequest request, HttpServletResponse response) {
        limit("login", input.email(), request);
        boolean remember = Boolean.TRUE.equals(input.rememberMe());
        var session = service.login(input.email(), input.password(), remember);
        if ("mobile".equals(request.getHeader("X-Rallye-Client"))) {
            return Map.of("token", session.secret(), "tokenType", "Bearer", "rememberMe", remember);
        }
        cookies.set(response, session.secret(), remember);
        return service.me(session.principal());
    }
    @PostMapping("/logout") public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthService.Principal principal, HttpServletResponse response) {
        service.logout(principal); cookies.clear(response);
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/me") public AuthService.UserView me(@AuthenticationPrincipal AuthService.Principal principal) { return service.me(principal); }
    @PostMapping("/password-reset-requests") public ResponseEntity<Void> resetRequest(@Valid @RequestBody EmailRequest input, HttpServletRequest request) {
        limit("reset-request", input.email(), request); service.requestReset(input.email());
        return ResponseEntity.accepted().build();
    }
    @PostMapping("/password-resets") public ResponseEntity<Void> reset(@Valid @RequestBody Reset input, HttpServletRequest request) {
        passwordPolicy(input.newPassword()); limit("reset", "", request);
        service.resetPassword(input.token(), input.newPassword());
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/password-changes") public ResponseEntity<Void> change(@AuthenticationPrincipal AuthService.Principal principal,
            @Valid @RequestBody Change input, HttpServletRequest request) {
        passwordPolicy(input.newPassword()); limit("change", principal.accountId().toString(), request);
        service.changePassword(principal, input.currentPassword(), input.newPassword());
        return ResponseEntity.noContent().build();
    }
    private void limit(String operation, String email, HttpServletRequest request) {
        throttle.check(operation, AuthService.normalizeEmail(email), request.getRemoteAddr());
    }
    static void passwordPolicy(String password) {
        cz.mh.rallye.rallye_backend.identity.application.PasswordPolicy.validate(password);
    }
}
