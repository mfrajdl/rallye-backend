package cz.mh.rallye.rallye_backend.administration.api;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import cz.mh.rallye.rallye_backend.administration.application.AboutService;
import cz.mh.rallye.rallye_backend.identity.application.*;

@RestController
public class AboutController {
    private final AboutService service;
    public AboutController(AboutService service){this.service=service;}
    @GetMapping("/api/v1/admin/about")
    public ResponseEntity<AboutService.View> about(@AuthenticationPrincipal AuthService.Principal actor,@RequestParam Map<String,String> parameters){
        if(!parameters.isEmpty())throw new AuthFailure(400,"INVALID_REQUEST");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.read(actor));
    }
}
