package cz.mh.rallye.rallye_backend.identity.api;

import java.util.Map;
import cz.mh.rallye.rallye_backend.identity.application.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/external")
public class ExternalAuthController {
    public record Start(@NotNull ExternalFlowService.Purpose purpose,@NotBlank @Size(max=100) String returnId,Boolean rememberMe,
                        @Size(max=43) String mobileChallenge) {}
    private final ExternalFlowService service; private final LoginThrottle throttle;
    public ExternalAuthController(ExternalFlowService service,LoginThrottle throttle){this.service=service;this.throttle=throttle;}
    @PostMapping("/{provider}/requests")
    public Map<String,Object> start(@PathVariable ExternalProviders.Provider provider,@Valid @RequestBody Start input,
        @AuthenticationPrincipal AuthService.Principal actor,HttpServletRequest request,HttpServletResponse response) {
        throttle.check("external-start",actor==null?"":actor.accountId().toString(),request.getRemoteAddr());
        var flow=service.start(provider,input.purpose(),actor,input.returnId(),Boolean.TRUE.equals(input.rememberMe()),input.mobileChallenge());
        response.addHeader("Set-Cookie",ResponseCookie.from(cookie(provider),flow.correlation()).secure(true).httpOnly(true).sameSite("None")
            .path("/api/v1/auth/external/"+provider.name()+"/callback").maxAge(ExternalFlowService.FLOW_LIFE).build().toString());
        return Map.of("authorizationUrl",flow.authorizationUrl(),"expiresAt",flow.expiresAt(),"stage","PROTOCOL_ONLY");
    }
    @RequestMapping(value="/{provider}/callback",method={RequestMethod.GET,RequestMethod.POST})
    public ResponseEntity<Void> callback(@PathVariable ExternalProviders.Provider provider,@RequestParam String state,@RequestParam String code,
        HttpServletRequest request,HttpServletResponse response) {
        if((provider==ExternalProviders.Provider.APPLE)!=request.getMethod().equals("POST"))throw new AuthFailure(400,"EXTERNAL_CALLBACK_INVALID");
        throttle.check("external-callback","",request.getRemoteAddr());
        String correlation=null;
        if(request.getCookies()!=null)for(var c:request.getCookies())if(c.getName().equals(cookie(provider))) {
            if(correlation!=null)throw new AuthFailure(400,"EXTERNAL_STATE_INVALID");correlation=c.getValue();
        }
        service.callback(provider,state,correlation,code);
        response.addHeader("Set-Cookie",ResponseCookie.from(cookie(provider),"").secure(true).httpOnly(true).sameSite("None")
            .path("/api/v1/auth/external/"+provider.name()+"/callback").maxAge(0).build().toString());
        return ResponseEntity.noContent().build();
    }
    static String cookie(ExternalProviders.Provider provider){return "RALLYE_OIDC_"+provider.name();}
}
