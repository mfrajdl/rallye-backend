package cz.mh.rallye.rallye_backend.participation.api;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cz.mh.rallye.rallye_backend.identity.application.AuthService.Principal;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import cz.mh.rallye.rallye_backend.participation.application.StartCodeService;

@RestController @RequestMapping("/api/v1/race-events/{eventId}/registrations/{registrationId}")
public class StartCodeController {
 public interface Strict {@JsonAnySetter default void unexpected(String name,Object value){throw new IllegalArgumentException("Unsupported start code field");}}
 public record Original(JsonNode registrationVersion) implements Strict{}
 public record Replacement(JsonNode registrationVersion,JsonNode pointsAdjustment,JsonNode reason) implements Strict{@Override public String toString(){return "Replacement[redacted]";}}
 private final StartCodeService service;public StartCodeController(StartCodeService service){this.service=service;}
 private static AuthFailure invalid(){return new AuthFailure(400,"INVALID_REQUEST");}
 private static long version(JsonNode n){if(n==null||!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<0)throw invalid();return n.longValue();}
 private static long points(JsonNode n){if(n==null)return 0;if(!n.isString()||!n.asString().matches("-?(0|[1-9][0-9]*)"))throw invalid();try{return Long.parseLong(n.asString());}catch(NumberFormatException e){throw invalid();}}
 private static String reason(JsonNode n){if(n==null||n.isNull())return null;if(!n.isString())throw invalid();return n.asString();}
 private static <T>ResponseEntity<T> response(int status,T body){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").body(body);}
 @PostMapping("/start-codes") public ResponseEntity<StartCodeService.IssuedCodePair> originals(@AuthenticationPrincipal Principal p,@PathVariable UUID eventId,@PathVariable UUID registrationId,@RequestHeader("Idempotency-Key")UUID key,@RequestBody Original b){if(b==null)throw invalid();return response(201,service.originals(p,eventId,registrationId,key,version(b.registrationVersion())));}
 @PostMapping("/replacement-codes") public ResponseEntity<StartCodeService.IssuedCode> replacement(@AuthenticationPrincipal Principal p,@PathVariable UUID eventId,@PathVariable UUID registrationId,@RequestHeader("Idempotency-Key")UUID key,@RequestBody Replacement b){if(b==null)throw invalid();return response(201,service.replacement(p,eventId,registrationId,key,version(b.registrationVersion()),points(b.pointsAdjustment()),reason(b.reason())));}
 @GetMapping("/start-codes") public ResponseEntity<StartCodeService.Page> list(@AuthenticationPrincipal Principal p,@PathVariable UUID eventId,@PathVariable UUID registrationId,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="50")int size){return response(200,service.list(p,eventId,registrationId,page,size));}
 @GetMapping("/start-codes/{codeId}") public ResponseEntity<StartCodeService.CodeDetail> detail(@AuthenticationPrincipal Principal p,@PathVariable UUID eventId,@PathVariable UUID registrationId,@PathVariable UUID codeId){return response(200,service.detail(p,eventId,registrationId,codeId));}
}
