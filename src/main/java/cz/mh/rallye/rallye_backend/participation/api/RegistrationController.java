package cz.mh.rallye.rallye_backend.participation.api;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cz.mh.rallye.rallye_backend.identity.application.AuthService.Principal;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import cz.mh.rallye.rallye_backend.participation.application.RegistrationService;

@RestController @RequestMapping("/api/v1/race-events/{eventId}")
public class RegistrationController {
 public interface Strict {@JsonAnySetter default void unexpected(String name,Object value){throw new IllegalArgumentException("Unsupported registration field");}}
 public record Settings(JsonNode version,JsonNode registrationFrom,JsonNode registrationTo,JsonNode override) implements Strict{}
 public record Create(JsonNode crewId,JsonNode membersText,JsonNode childrenUnder15) implements Strict{@Override public String toString(){return "RegistrationCreate[redacted]";}}
 public record Members(JsonNode version,JsonNode membersText,JsonNode childrenUnder15) implements Strict{@Override public String toString(){return "RegistrationMembers[redacted]";}}
 private final RegistrationService service;public RegistrationController(RegistrationService service){this.service=service;}
 private static AuthFailure invalid(){return new AuthFailure(400,"INVALID_REQUEST");}
 private static long number(JsonNode n){if(n==null||!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<0)throw invalid();return n.longValue();}
 private static int children(JsonNode n){long v=number(n);if(v>Integer.MAX_VALUE)throw invalid();return (int)v;}
 private static String text(JsonNode n){if(n==null||!n.isString())throw invalid();return n.stringValue();}
 private static UUID uuid(JsonNode n){String t=text(n);try{UUID id=UUID.fromString(t);if(!id.toString().equalsIgnoreCase(t))throw invalid();return id;}catch(IllegalArgumentException e){throw invalid();}}
 private static Instant instant(JsonNode n){if(n==null)throw invalid();if(n.isNull())return null;try{return java.time.OffsetDateTime.parse(text(n)).toInstant();}catch(java.time.DateTimeException e){throw invalid();}}
 private static <T>ResponseEntity<T> ok(T body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
 @GetMapping("/registration-status") public ResponseEntity<RegistrationService.Status> status(@AuthenticationPrincipal Principal p,@PathVariable UUID eventId){return ok(service.status(p,eventId));}
 @PutMapping("/registration-settings") public ResponseEntity<RegistrationService.Status> settings(@AuthenticationPrincipal Principal p,@PathVariable UUID eventId,@RequestBody Settings b){if(b==null)throw invalid();return ok(service.settings(p,eventId,number(b.version()),instant(b.registrationFrom()),instant(b.registrationTo()),text(b.override())));}
 @GetMapping("/registration-crews") public ResponseEntity<RegistrationService.Page<RegistrationService.Crew>> crews(@AuthenticationPrincipal Principal p,@PathVariable UUID eventId,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="50")int size){return ok(service.crews(p,eventId,page,size));}
 @PostMapping("/registrations") public ResponseEntity<RegistrationService.Created> create(@AuthenticationPrincipal Principal p,@PathVariable UUID eventId,@RequestHeader("Idempotency-Key")UUID key,@RequestBody Create b){if(b==null)throw invalid();var result=service.create(p,eventId,key,uuid(b.crewId()),text(b.membersText()),children(b.childrenUnder15()));return ResponseEntity.created(URI.create("/api/v1/race-events/"+eventId+"/registrations/"+result.id())).cacheControl(CacheControl.noStore()).body(result);}
 @GetMapping("/registrations") public ResponseEntity<RegistrationService.Page<RegistrationService.View>> list(@AuthenticationPrincipal Principal p,@PathVariable UUID eventId,@RequestParam(defaultValue="own")String scope,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="50")int size){return ok(service.list(p,eventId,scope,page,size));}
 @GetMapping("/registrations/{registrationId}") public ResponseEntity<RegistrationService.View> detail(@AuthenticationPrincipal Principal p,@PathVariable UUID eventId,@PathVariable UUID registrationId){return ok(service.detail(p,eventId,registrationId));}
 @PutMapping("/registrations/{registrationId}/members") public ResponseEntity<RegistrationService.View> members(@AuthenticationPrincipal Principal p,@PathVariable UUID eventId,@PathVariable UUID registrationId,@RequestBody Members b){if(b==null)throw invalid();return ok(service.members(p,eventId,registrationId,number(b.version()),text(b.membersText()),children(b.childrenUnder15())));}
}
