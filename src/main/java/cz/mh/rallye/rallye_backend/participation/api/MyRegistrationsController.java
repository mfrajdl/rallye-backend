package cz.mh.rallye.rallye_backend.participation.api;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cz.mh.rallye.rallye_backend.identity.application.AuthService.Principal;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import cz.mh.rallye.rallye_backend.participation.application.RegistrationService;
@RestController
public class MyRegistrationsController {
 private final RegistrationService service;
 public MyRegistrationsController(RegistrationService service){this.service=service;}
 @GetMapping("/api/v1/me/registrations") public org.springframework.http.ResponseEntity<RegistrationService.Page<RegistrationService.OwnView>> list(@AuthenticationPrincipal Principal p,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size,@RequestParam Map<String,String> params){if(!java.util.Set.of("page","size").containsAll(params.keySet()))throw new AuthFailure(400,"INVALID_REQUEST");return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(service.own(p,page,size));}
 @GetMapping("/api/v1/me/registrations/summary") public RegistrationService.Summary summary(@AuthenticationPrincipal Principal p,@RequestParam Map<String,String> params){if(!params.isEmpty())throw new AuthFailure(400,"INVALID_REQUEST");return service.summary(p);}
}
