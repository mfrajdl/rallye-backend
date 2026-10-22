package cz.mh.rallye.rallye_backend.crews.api;

import java.net.URI;
import java.util.UUID;
import cz.mh.rallye.rallye_backend.crews.application.CrewTransferService;
import cz.mh.rallye.rallye_backend.identity.application.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/crews/{id}/transfers")
public class CrewTransferController {
    public record Create(@NotBlank @Email @Size(max=254) String targetEmail,@NotNull @Min(0) Long version) {
        public Create {if(targetEmail!=null) targetEmail=AuthService.normalizeEmail(targetEmail);}
        @Override public String toString() {return "CreateTransfer[redacted]";}
    }
    private final CrewTransferService service;
    private final LoginThrottle throttle;
    public CrewTransferController(CrewTransferService service,LoginThrottle throttle) {this.service=service;this.throttle=throttle;}
    @PostMapping public ResponseEntity<CrewTransferService.View> create(@AuthenticationPrincipal AuthService.Principal actor,
            @PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Create input,HttpServletRequest request) {
        throttle.check("crew-transfer-create",actor.accountId().toString(),request.getRemoteAddr());
        var result=service.create(actor,id,key,input.targetEmail(),input.version());
        return ResponseEntity.created(URI.create("/api/v1/crews/"+id+"/transfers/"+result.id())).body(result);
    }
    @PostMapping("/{transferId}/resend") public CrewTransferService.View resend(@AuthenticationPrincipal AuthService.Principal actor,
            @PathVariable UUID id,@PathVariable UUID transferId,@RequestHeader("Idempotency-Key") UUID key,HttpServletRequest request) {
        throttle.check("crew-transfer-resend",actor.accountId().toString(),request.getRemoteAddr());
        return service.resend(actor,id,transferId,key);
    }
}
