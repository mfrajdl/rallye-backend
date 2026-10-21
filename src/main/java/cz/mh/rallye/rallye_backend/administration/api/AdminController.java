package cz.mh.rallye.rallye_backend.administration.api;

import java.time.Instant;
import java.util.UUID;
import cz.mh.rallye.rallye_backend.identity.application.AdminService;
import cz.mh.rallye.rallye_backend.identity.application.AuthService;
import cz.mh.rallye.rallye_backend.audit.application.AuditService.Event;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final AdminService service;
    public AdminController(AdminService service) { this.service=service; }
    @PutMapping("/users/{accountId}/roles/ADMIN")
    public ResponseEntity<Void> grant(@AuthenticationPrincipal AuthService.Principal actor,
            @PathVariable UUID accountId, @RequestHeader("Idempotency-Key") UUID key) {
        service.changeRole(actor, accountId, true, key); return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/users/{accountId}/roles/ADMIN")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal AuthService.Principal actor,
            @PathVariable UUID accountId, @RequestHeader("Idempotency-Key") UUID key) {
        service.changeRole(actor, accountId, false, key); return ResponseEntity.noContent().build();
    }
    @GetMapping("/audit-events")
    public AdminService.AuditPage audit(@AuthenticationPrincipal AuthService.Principal actor,
            @RequestParam(required=false) UUID actorId, @RequestParam(required=false) UUID targetId,
            @RequestParam(required=false) Event eventType,
            @RequestParam(required=false) Instant from, @RequestParam(required=false) Instant until,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="50") int size) {
        return service.audit(actor, actorId, targetId, eventType, from, until, page, size);
    }
}
