package cz.mh.rallye.rallye_backend.identity.api;

import java.util.UUID;
import cz.mh.rallye.rallye_backend.identity.application.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class AccountCancellationController {
    public record Confirmation(@Size(max=256) String currentPassword,Boolean confirmed) {
        @AssertTrue public boolean isConfirmationProvided() {return currentPassword!=null || confirmed!=null;}
        @Override public String toString() {return "CancellationConfirmation[redacted]";}
    }
    private final AccountCancellationService service;
    private final LoginThrottle throttle;
    private final AuthCookies cookies;
    public AccountCancellationController(AccountCancellationService service,LoginThrottle throttle,AuthCookies cookies) {
        this.service=service;this.throttle=throttle;this.cookies=cookies;
    }
    @PostMapping("/api/v1/auth/account-cancellations")
    public ResponseEntity<Void> self(@AuthenticationPrincipal AuthService.Principal actor,
            @RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Confirmation input,
            HttpServletRequest request,HttpServletResponse response) {
        throttle.check("account-cancellation",actor.accountId().toString(),request.getRemoteAddr());
        service.self(actor,input.currentPassword(),Boolean.TRUE.equals(input.confirmed()),key);cookies.clear(response);
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/api/v1/admin/users/{accountId}/cancellations")
    public ResponseEntity<Void> administrator(@AuthenticationPrincipal AuthService.Principal actor,@PathVariable UUID accountId,
            @RequestHeader("Idempotency-Key") UUID key,HttpServletRequest request) {
        throttle.check("admin-account-cancellation",actor.accountId().toString(),request.getRemoteAddr());
        service.administrator(actor,accountId,key);return ResponseEntity.noContent().build();
    }
}
