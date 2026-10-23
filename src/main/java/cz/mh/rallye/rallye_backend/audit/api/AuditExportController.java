package cz.mh.rallye.rallye_backend.audit.api;

import java.time.Instant;
import java.util.UUID;
import cz.mh.rallye.rallye_backend.audit.application.*;
import cz.mh.rallye.rallye_backend.identity.application.AuthService;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
public class AuditExportController {
    private final AuditExportService exports;
    public AuditExportController(AuditExportService exports) {this.exports=exports;}
    @GetMapping(value="/api/v1/admin/audit-events/export",produces="text/csv;charset=UTF-8")
    public ResponseEntity<StreamingResponseBody> export(@AuthenticationPrincipal AuthService.Principal actor,
            @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant until,
            @RequestParam(required=false) AuditService.Event eventType,@RequestParam(required=false) UUID actorId,
            @RequestParam(required=false) UUID targetAccountId) {
        var filter=new AuditExportFilter(from,until,eventType,actorId,targetAccountId);exports.prepare(actor,filter);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"rallye-audit.csv\"")
                .header(HttpHeaders.CACHE_CONTROL,"no-store").body(output->exports.stream(actor,filter,output));
    }
}
