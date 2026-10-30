package cz.mh.rallye.rallye_backend.races.api;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import tools.jackson.databind.JsonNode;
import cz.mh.rallye.rallye_backend.identity.application.AuthService.Principal;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import cz.mh.rallye.rallye_backend.races.application.LifecycleService;
import cz.mh.rallye.rallye_backend.races.persistence.LifecycleStore;
@RestController
@RequestMapping("/api/v1/race-events/{id}")
public class LifecycleController {
 public record Command(JsonNode version) implements EventController.Strict{}
 public record Resume(JsonNode version,JsonNode endAt) implements EventController.Strict{}
 private final LifecycleService service;
 public LifecycleController(LifecycleService service){this.service=service;}
 private static long version(JsonNode v){if(v==null||!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<0)throw new AuthFailure(400,"INVALID_REQUEST");return v.longValue();}
 @PostMapping("/start") public LifecycleService.Receipt start(@AuthenticationPrincipal Principal p,@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Command b){return service.command(p,id,key,version(b.version()),"STARTED",null);}
 @PostMapping("/end") public LifecycleService.Receipt end(@AuthenticationPrincipal Principal p,@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Command b){return service.command(p,id,key,version(b.version()),"ENDED",null);}
 @PostMapping("/resume") public LifecycleService.Receipt resume(@AuthenticationPrincipal Principal p,@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Resume b){if(b.endAt()==null||!b.endAt().isNull()&&!b.endAt().isString())throw new AuthFailure(400,"INVALID_REQUEST");Instant end;try{end=b.endAt().isNull()?null:Instant.parse(b.endAt().stringValue());}catch(RuntimeException failure){throw new AuthFailure(400,"INVALID_REQUEST");}return service.command(p,id,key,version(b.version()),"RESUMED",end);}
 @GetMapping("/lifecycle-history") public LifecycleService.Page<LifecycleStore.Entry> history(@AuthenticationPrincipal Principal p,@PathVariable UUID id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){return service.history(p,id,page,size);}
 @GetMapping("/running-periods") public LifecycleService.Page<LifecycleStore.Period> periods(@AuthenticationPrincipal Principal p,@PathVariable UUID id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){return service.periods(p,id,page,size);}
}
