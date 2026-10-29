package cz.mh.rallye.rallye_backend.races.api;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cz.mh.rallye.rallye_backend.identity.application.AuthService.Principal;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import cz.mh.rallye.rallye_backend.races.application.EventService;
import cz.mh.rallye.rallye_backend.templates.application.TemplateDocumentHtml;
@RestController
@RequestMapping("/api/v1")
public class EventController {
 public interface Strict{@JsonAnySetter default void unsupported(String field,Object value){throw new IllegalArgumentException("Unsupported event field");}}
 public record Create(JsonNode templateVersion,String name,String description,Instant scheduledStartAt,Instant endAt) implements Strict{}
 public record Instructions(JsonNode version,JsonNode instructionsMarkdown) implements Strict{}
 public record Update(JsonNode version,String name,String description,Instant scheduledStartAt,Instant endAt) implements Strict{}
 private final EventService service;public EventController(EventService service){this.service=service;}
 private static long version(JsonNode v){if(v==null||!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<0)throw new AuthFailure(400,"INVALID_REQUEST");return v.longValue();}
 @PostMapping("/race-templates/{id}/events") public ResponseEntity<EventService.Created> create(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Create body){var result=service.create(actor,id,version(body.templateVersion()),key,new EventService.Input(body.name(),body.description(),body.scheduledStartAt(),body.endAt()));return ResponseEntity.created(URI.create("/api/v1/race-events/"+result.id())).body(result);}
 @GetMapping("/race-events") public EventService.Page list(@AuthenticationPrincipal Principal actor,@RequestParam(required=false) String query,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){return service.list(actor,query,page,size);}
 @GetMapping("/race-events/{id}") public EventService.View detail(@AuthenticationPrincipal Principal actor,@PathVariable UUID id){return service.detail(actor,id);}
 @PutMapping("/race-events/{id}") public EventService.View update(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@RequestBody Update body){return service.update(actor,id,version(body.version()),new EventService.Input(body.name(),body.description(),body.scheduledStartAt(),body.endAt()));}
 @PutMapping("/race-events/{id}/stages/{stageId}/instructions") public EventService.InstructionsView instructions(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@PathVariable UUID stageId,@RequestBody Instructions body){var text=body.instructionsMarkdown();if(text==null||!text.isNull()&&!text.isString())throw new AuthFailure(400,"INVALID_REQUEST");return service.updateInstructions(actor,id,stageId,version(body.version()),text.isNull()?null:text.stringValue());}
 @DeleteMapping("/race-events/{id}") public ResponseEntity<Void> delete(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@RequestParam long version){service.delete(actor,id,version);return ResponseEntity.noContent().build();}
 @GetMapping("/race-events/{id}/stages") public List<EventService.StageView> stages(@AuthenticationPrincipal Principal actor,@PathVariable UUID id){return service.stages(actor,id);}
 @GetMapping("/race-events/{id}/stages/{stageId}") public EventService.StageView stage(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@PathVariable UUID stageId){return service.stage(actor,id,stageId);}
 @GetMapping("/race-events/{id}/files") public List<EventService.FileView> files(@AuthenticationPrincipal Principal actor,@PathVariable UUID id){return service.files(actor,id);}
 @GetMapping("/race-events/{id}/files/{fileName}") public ResponseEntity<byte[]> file(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@PathVariable String fileName){var c=service.file(actor,id,fileName);var response=ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").header("Referrer-Policy","no-referrer").header("Content-Security-Policy",c.markdown()?TemplateDocumentHtml.csp():"default-src 'none'; sandbox").contentType(MediaType.parseMediaType(c.contentType()));if(c.pdf())response.header("Content-Disposition",ContentDisposition.attachment().filename(c.fileName(),StandardCharsets.UTF_8).build().toString());return response.body(c.bytes());}
}
