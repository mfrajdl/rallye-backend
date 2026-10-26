package cz.mh.rallye.rallye_backend.templates.api;

import java.net.URI;
import tools.jackson.databind.JsonNode;
import java.util.*;
import cz.mh.rallye.rallye_backend.templates.application.TemplateReadiness;
import cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import cz.mh.rallye.rallye_backend.templates.application.TemplateService;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import cz.mh.rallye.rallye_backend.identity.application.AuthService.Principal;

@RestController
@RequestMapping("/api/v1/race-templates")
public class TemplateController {
    // Reject unsupported future configuration and mass assignment rather than silently discarding it.
    public interface Strict { @JsonAnySetter default void unsupported(String field,Object value){throw new IllegalArgumentException("Unsupported template field");} }
    public record Create(String name,String description,JsonNode skipPenaltyPoints) implements Strict {}
    public record Update(JsonNode version,String name,String description,JsonNode skipPenaltyPoints) implements Strict {}
    public record StageInput(JsonNode version,String name,JsonNode expectedDurationMs,JsonNode maxPenaltyPoints) implements Strict {}
    public record StageUpdateInput(JsonNode version,String name,JsonNode expectedDurationMs,JsonNode maxPenaltyPoints,JsonNode instructionsHtml) implements Strict {}
    public record GpsInput(JsonNode enabled,JsonNode latitude,JsonNode longitude,JsonNode radiusMeters,JsonNode outsidePenaltyPoints,JsonNode spoofingPenaltyPoints) implements Strict {}
    public record CodeInput(JsonNode enabled,JsonNode newCode,JsonNode wrongAttemptPenaltyPoints) implements Strict {
        @Override public String toString(){return "CodeInput[redacted]";}
    }
    public record TimeInput(JsonNode enabled,JsonNode intervalSeconds,JsonNode pointsPerInterval) implements Strict {}
    public record DelayedInput(JsonNode enabled,JsonNode minimumDurationMs,JsonNode pointsPerSecond) implements Strict {}
    public record ConfigurationInput(JsonNode version,GpsInput gps,CodeInput code,TimeInput timePenalty,DelayedInput delayed) implements Strict {
        @Override public String toString(){return "ConfigurationInput[redacted]";}
    }
    public record Order(JsonNode version,List<UUID> stageIds) implements Strict {}
    public record State(JsonNode version,String status) implements Strict {}
    private final TemplateService service;
    public TemplateController(TemplateService service){this.service=service;}
    private static long required(JsonNode input){Long value=number(input);if(value==null || value<0)throw new AuthFailure(400,"INVALID_REQUEST");return value;}
    private static long points(JsonNode input){Long value=number(input);if(value==null)throw new AuthFailure(400,"INVALID_REQUEST");return value;}
    private static long queryVersion(long value){if(value<0)throw new AuthFailure(400,"INVALID_REQUEST");return value;}
    private static Long number(JsonNode value){
        if(value==null || value.isNull())return null;
        if(!value.isIntegralNumber() || !value.canConvertToLong())throw new AuthFailure(400,"INVALID_REQUEST");
        return value.longValue();
    }
    @PostMapping public ResponseEntity<TemplateService.Created> create(@AuthenticationPrincipal Principal actor,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Create input){var result=service.create(actor,key,input.name(),input.description(),points(input.skipPenaltyPoints()));return ResponseEntity.created(URI.create("/api/v1/race-templates/"+result.id())).body(result);}
    @GetMapping public TemplateService.Page list(@AuthenticationPrincipal Principal actor,@RequestParam(required=false) String query,@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){return service.list(actor,query,status,page,size);}
    @GetMapping("/{id}") public TemplateService.View detail(@AuthenticationPrincipal Principal actor,@PathVariable UUID id){return service.detail(actor,id);}
    @PutMapping("/{id}") public TemplateService.View update(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@RequestBody Update input){return service.update(actor,id,required(input.version()),input.name(),input.description(),points(input.skipPenaltyPoints()));}
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@RequestParam long version){service.delete(actor,id,queryVersion(version));return ResponseEntity.noContent().build();}
    @PutMapping("/{id}/status") public TemplateService.View state(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@RequestBody State input){return service.state(actor,id,required(input.version()),input.status());}
    @GetMapping("/{id}/stages") public List<TemplateService.StageView> stages(@AuthenticationPrincipal Principal actor,@PathVariable UUID id){return service.detail(actor,id).stages();}
    @PostMapping("/{id}/stages") public ResponseEntity<TemplateService.Created> add(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@RequestBody StageInput input){var result=service.createStage(actor,id,required(input.version()),key,input.name(),number(input.expectedDurationMs()),number(input.maxPenaltyPoints()));return ResponseEntity.created(URI.create("/api/v1/race-templates/"+id+"/stages/"+result.id())).body(result);}
    @GetMapping("/{id}/stages/{stageId}") public TemplateService.StageView stage(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@PathVariable UUID stageId){return service.stageDetail(actor,id,stageId);}
    @PutMapping("/{id}/stages/{stageId}") public TemplateService.View edit(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@PathVariable UUID stageId,@RequestBody StageUpdateInput input){return service.updateStage(actor,id,stageId,required(input.version()),input.name(),number(input.expectedDurationMs()),number(input.maxPenaltyPoints()),input.instructionsHtml()!=null,text(input.instructionsHtml()));}
    @DeleteMapping("/{id}/stages/{stageId}") public TemplateService.View remove(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@PathVariable UUID stageId,@RequestParam long version){return service.deleteStage(actor,id,stageId,queryVersion(version));}
    @PutMapping("/{id}/stage-order") public TemplateService.View order(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@RequestBody Order input){return service.order(actor,id,required(input.version()),input.stageIds());}
    @PostMapping("/{id}/validations") public TemplateReadiness.Report validate(@AuthenticationPrincipal Principal actor,@PathVariable UUID id){return service.readiness(actor,id);}
    @PutMapping("/{id}/stages/{stageId}/configuration")
    public TemplateService.View configure(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@PathVariable UUID stageId,@RequestBody ConfigurationInput input){
        if(input.gps()==null || input.code()==null || input.timePenalty()==null || input.delayed()==null)throw new AuthFailure(400,"INVALID_REQUEST");
        var gps=input.gps();var code=input.code();var time=input.timePenalty();var delayed=input.delayed();
        Long seconds=number(time.intervalSeconds()),interval=null;
        if(seconds!=null){try{if(seconds<=0)throw new ArithmeticException();interval=Math.multiplyExact(seconds,1000);}catch(ArithmeticException failure){throw new AuthFailure(400,"INVALID_REQUEST");}}
        var change=new TemplateService.ConfigurationChange(
                new Gps(flag(gps.enabled()),decimal(gps.latitude()),decimal(gps.longitude()),decimal(gps.radiusMeters()),number(gps.outsidePenaltyPoints()),number(gps.spoofingPenaltyPoints())),
                new TemplateService.CodeChange(flag(code.enabled()),text(code.newCode()),number(code.wrongAttemptPenaltyPoints())),
                new TimePenalty(flag(time.enabled()),interval,number(time.pointsPerInterval())),
                new Delayed(flag(delayed.enabled()),number(delayed.minimumDurationMs()),number(delayed.pointsPerSecond())));
        return service.configure(actor,id,stageId,required(input.version()),change);
    }
    private static boolean flag(JsonNode value){if(value==null || !value.isBoolean())throw new AuthFailure(400,"INVALID_REQUEST");return value.booleanValue();}
    private static Double decimal(JsonNode value){if(value==null || value.isNull())return null;if(!value.isNumber() || !Double.isFinite(value.doubleValue()))throw new AuthFailure(400,"INVALID_REQUEST");return value.doubleValue();}
    private static String text(JsonNode value){if(value==null || value.isNull())return null;if(!value.isString())throw new AuthFailure(400,"INVALID_REQUEST");return value.asString();}

}
