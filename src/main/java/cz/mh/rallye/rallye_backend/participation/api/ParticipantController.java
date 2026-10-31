package cz.mh.rallye.rallye_backend.participation.api;

import java.nio.charset.StandardCharsets;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import cz.mh.rallye.rallye_backend.participation.application.ParticipantReadService;
import cz.mh.rallye.rallye_backend.templates.application.TemplateDocumentHtml;

@RestController
@RequestMapping("/api/v1/race-participations/{registrationId}")
public class ParticipantController {
 private final ParticipantReadService service;
 public ParticipantController(ParticipantReadService service){this.service=service;}
 /** Narrow exception to account authentication. Device authorization remains in the locked application transaction. */
 public static boolean isRead(HttpServletRequest request){return request.getMethod().equals("GET")&&request.getRequestURI().substring(request.getContextPath().length()).matches("/api/v1/race-participations/[^/]+/(current-run|files/[^/]+)");}
 private static String token(HttpServletRequest request){var headers=Collections.list(request.getHeaders("Authorization"));if(headers.size()!=1||!headers.getFirst().matches("Race [A-Za-z0-9_-]{43}"))throw new AuthFailure(401,"RACE_DEVICE_INVALID");return headers.getFirst().substring(5);}
 @GetMapping("/current-run") public ParticipantReadService.View current(HttpServletRequest request,@PathVariable UUID registrationId){return service.current(token(request),registrationId);}
 @GetMapping("/files/{fileName}") public ResponseEntity<byte[]> file(HttpServletRequest request,@PathVariable UUID registrationId,@PathVariable String fileName){
  var c=service.file(token(request),registrationId,fileName);var response=ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").header("X-Content-Type-Options","nosniff").header("Content-Security-Policy",c.markdown()?TemplateDocumentHtml.csp():"default-src 'none'; sandbox").contentType(MediaType.parseMediaType(c.contentType()));
  response.header("Content-Disposition",(c.pdf()?ContentDisposition.attachment():ContentDisposition.inline()).filename(c.fileName(),StandardCharsets.UTF_8).build().toString());return response.body(c.bytes());
 }
}
