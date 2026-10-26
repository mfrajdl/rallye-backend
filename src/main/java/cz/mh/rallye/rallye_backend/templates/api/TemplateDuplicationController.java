package cz.mh.rallye.rallye_backend.templates.api;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import cz.mh.rallye.rallye_backend.templates.application.*;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import cz.mh.rallye.rallye_backend.identity.application.AuthService.Principal;

@RestController
@RequestMapping("/api/v1/race-templates")
public class TemplateDuplicationController {
    public record StageCopy(JsonNode version) implements TemplateController.Strict {}
    private final TemplateDuplicationService service;
    public TemplateDuplicationController(TemplateDuplicationService service){this.service=service;}
    @PostMapping("/{id}/duplicate")
    public ResponseEntity<TemplateService.Created> duplicate(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key){
        var result=service.duplicate(actor,id,key);return ResponseEntity.created(URI.create("/api/v1/race-templates/"+result.id())).body(result);
    }
    @PostMapping("/{id}/stages/{stageId}/duplicate")
    public ResponseEntity<TemplateService.Created> stage(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@PathVariable UUID stageId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody StageCopy input){
        var v=input.version();if(v==null||!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<0)throw new AuthFailure(400,"INVALID_REQUEST");
        var result=service.duplicateStage(actor,id,stageId,v.longValue(),key);return ResponseEntity.created(URI.create("/api/v1/race-templates/"+id+"/stages/"+result.id())).body(result);
    }
}
