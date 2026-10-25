package cz.mh.rallye.rallye_backend.identity.api;

import java.util.UUID;
import cz.mh.rallye.rallye_backend.identity.application.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/external/mobile/exchanges")
public class MobileExchangeController {
    public record Exchange(@NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}") String code,@NotNull UUID requestId,
            @NotNull @Pattern(regexp="[A-Za-z0-9._~-]{43,128}") String codeVerifier) {
        @Override public String toString(){return "MobileExchange[redacted]";}
    }
    /** Same JSON contract as the existing LOCAL mobile login, with safe diagnostic rendering. */
    public record BearerSession(String token,String tokenType,boolean rememberMe) {
        @Override public String toString(){return "BearerSession[redacted]";}
    }
    private final MobileExchangeService service;private final LoginThrottle throttle;
    public MobileExchangeController(MobileExchangeService service,LoginThrottle throttle){this.service=service;this.throttle=throttle;}
    @PostMapping
    public BearerSession exchange(@Valid @RequestBody Exchange input,HttpServletRequest request) {
        throttle.check("external-exchange","",request.getRemoteAddr());
        var session=service.exchange(input.code(),input.requestId(),input.codeVerifier());
        return new BearerSession(session.secret(),"Bearer",session.principal().rememberMe());
    }
}
