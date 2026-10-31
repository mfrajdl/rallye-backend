package cz.mh.rallye.rallye_backend.participation.api;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAnySetter;import tools.jackson.databind.JsonNode;
import org.springframework.http.*;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.web.bind.annotation.*;
import cz.mh.rallye.rallye_backend.identity.application.AuthService.Principal;import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;import cz.mh.rallye.rallye_backend.participation.application.CrewStartService;
@RestController @RequestMapping("/api/v1/race-events/{eventId}/registrations/{registrationId}")
public class CrewStartController {
 public interface Strict {@JsonAnySetter default void unexpected(String key,Object value){throw new IllegalArgumentException("Unsupported crew start field");}}
 public record Redeem(JsonNode registrationVersion,JsonNode code,JsonNode deviceSecret,JsonNode deviceLabel) implements Strict{@Override public String toString(){return "Redeem[redacted]";}}
 public record Revoke(JsonNode registrationVersion,JsonNode deviceGeneration,JsonNode reason) implements Strict{@Override public String toString(){return "Revoke[redacted]";}}
 private final CrewStartService service;public CrewStartController(CrewStartService service){this.service=service;}
 private static AuthFailure invalid(){return new AuthFailure(400,"INVALID_REQUEST");}
 private static long number(JsonNode n){if(n==null||!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<0)throw invalid();return n.longValue();}
 private static String text(JsonNode n){if(n==null||!n.isString())throw invalid();return n.asString();}
 private static String optional(JsonNode n){return n==null||n.isNull()?null:text(n);}
 private static <T>ResponseEntity<T> ok(T value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").body(value);}
 @GetMapping("/start-status") public ResponseEntity<CrewStartService.StartStatus> status(@AuthenticationPrincipal Principal p,@PathVariable UUID eventId,@PathVariable UUID registrationId){return ok(service.status(p,eventId,registrationId));}
 @PostMapping("/code-redemptions") public ResponseEntity<CrewStartService.RedemptionReceipt> redeem(@AuthenticationPrincipal Principal p,@PathVariable UUID eventId,@PathVariable UUID registrationId,@RequestHeader("Idempotency-Key")UUID key,@RequestBody Redeem b){if(b==null)throw invalid();return ok(service.redeem(p,eventId,registrationId,key,number(b.registrationVersion()),text(b.code()),text(b.deviceSecret()),optional(b.deviceLabel())));}
 @PostMapping("/devices/{deviceId}/revocations") public ResponseEntity<CrewStartService.RevocationReceipt> revoke(@AuthenticationPrincipal Principal p,@PathVariable UUID eventId,@PathVariable UUID registrationId,@PathVariable UUID deviceId,@RequestHeader("Idempotency-Key")UUID key,@RequestBody Revoke b){if(b==null)throw invalid();long generation=number(b.deviceGeneration());if(generation<1)throw invalid();return ok(service.revoke(p,eventId,registrationId,deviceId,key,number(b.registrationVersion()),generation,optional(b.reason())));}
}
