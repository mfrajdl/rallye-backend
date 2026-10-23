package cz.mh.rallye.rallye_backend.crews.api;

import java.util.UUID;
import cz.mh.rallye.rallye_backend.crews.application.CrewTransferService;
import cz.mh.rallye.rallye_backend.identity.application.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/crew-transfers/{transferId}")
public class CrewTransferDecisionController {
    public record Token(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token) {
        @Override public String toString() {return "TransferToken[redacted]";}
    }
    private final CrewTransferService service;
    private final LoginThrottle throttle;
    public CrewTransferDecisionController(CrewTransferService service,LoginThrottle throttle) {this.service=service;this.throttle=throttle;}
    private CrewTransferService.Decision decide(AuthService.Principal actor,UUID id,UUID key,String operation,String token,HttpServletRequest request) {
        throttle.check("crew-transfer-"+operation,actor.accountId().toString(),request.getRemoteAddr());
        return service.decide(actor,id,key,operation,token);
    }
    @PostMapping("/accept") public CrewTransferService.Decision accept(@AuthenticationPrincipal AuthService.Principal actor,
            @PathVariable UUID transferId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Token input,HttpServletRequest request) {
        return decide(actor,transferId,key,"accept",input.token(),request);
    }
    @PostMapping("/reject") public CrewTransferService.Decision reject(@AuthenticationPrincipal AuthService.Principal actor,
            @PathVariable UUID transferId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Token input,HttpServletRequest request) {
        return decide(actor,transferId,key,"reject",input.token(),request);
    }
    @PostMapping("/cancel") public CrewTransferService.Decision cancel(@AuthenticationPrincipal AuthService.Principal actor,
            @PathVariable UUID transferId,@RequestHeader("Idempotency-Key") UUID key,HttpServletRequest request) {
        return decide(actor,transferId,key,"cancel",null,request);
    }
}
