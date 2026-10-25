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
    public static final String REGISTRATION_COOKIE="RALLYE_EXTERNAL_REG";
    private final ExternalFlowService service; private final LoginThrottle throttle;
    private final ExternalLoginService login;private final AuthCookies cookies;
    private final MobileLoginService mobile;private final java.time.Clock clock;
    public ExternalAuthController(ExternalFlowService service,LoginThrottle throttle,ExternalLoginService login,AuthCookies cookies,MobileLoginService mobile,java.time.Clock clock){this.service=service;this.throttle=throttle;this.login=login;this.cookies=cookies;this.mobile=mobile;this.clock=clock;}
    @PostMapping("/{provider}/requests")
    public Object start(@PathVariable ExternalProviders.Provider provider,@Valid @RequestBody Start input,
        @AuthenticationPrincipal AuthService.Principal actor,HttpServletRequest request,HttpServletResponse response) {
        throttle.check("external-start",actor==null?"":actor.accountId().toString(),request.getRemoteAddr());
        response.setHeader("Referrer-Policy","no-referrer");
        if("mobile".equals(request.getHeader("X-Rallye-Client"))) {
            if(AuthCookies.present(request) || request.getHeader("Authorization")!=null)throw new AuthFailure(400,"AMBIGUOUS_CREDENTIALS");
            return mobile.start(provider,input.purpose(),input.returnId(),Boolean.TRUE.equals(input.rememberMe()),input.mobileChallenge());
        }
        if(input.mobileChallenge()!=null)throw new AuthFailure(400,"INVALID_REQUEST");
        var flow=service.start(provider,input.purpose(),actor,input.returnId(),Boolean.TRUE.equals(input.rememberMe()),input.mobileChallenge());
        response.addHeader("Set-Cookie",ResponseCookie.from(cookie(provider),flow.correlation()).secure(true).httpOnly(true).sameSite("None")
            .path("/api/v1/auth/external/"+provider.name()+"/callback").maxAge(ExternalFlowService.FLOW_LIFE).build().toString());
        return Map.of("authorizationUrl",flow.authorizationUrl(),"expiresAt",flow.expiresAt(),"stage","WEB_LOGIN");
    }
    @GetMapping("/mobile/launch")
    public ResponseEntity<Void> launch(HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("Referrer-Policy","no-referrer");
        String ticket=LaunchTicketPrivacyFilter.ticket(request);
        if(request.getParameterMap().size()!=1 || request.getParameterValues("ticket").length!=1)
            throw new AuthFailure(400,"INVALID_REQUEST");
        throttle.check("external-mobile-launch","",request.getRemoteAddr());
        var launched=mobile.launch(ticket);var flow=launched.flow();
        response.addHeader("Set-Cookie",ResponseCookie.from(cookie(launched.provider()),flow.correlation()).secure(true).httpOnly(true).sameSite("None")
            .path("/api/v1/auth/external/"+launched.provider().name()+"/callback")
            .maxAge(java.time.Duration.between(clock.instant(),flow.expiresAt())).build().toString());
        return ResponseEntity.status(303).location(java.net.URI.create(flow.authorizationUrl())).build();
    }
    @RequestMapping(value="/{provider}/callback",method={RequestMethod.GET,RequestMethod.POST})
    public ResponseEntity<Void> callback(@PathVariable ExternalProviders.Provider provider,@RequestParam String state,@RequestParam(required=false) String code,
        @RequestParam(required=false) String error,
        HttpServletRequest request,HttpServletResponse response) {
        if((provider==ExternalProviders.Provider.APPLE)!=request.getMethod().equals("POST"))throw new AuthFailure(400,"EXTERNAL_CALLBACK_INVALID");
        throttle.check("external-callback","",request.getRemoteAddr());
        String correlation=null;
        if(request.getCookies()!=null)for(var c:request.getCookies())if(c.getName().equals(cookie(provider))) {
            if(correlation!=null)throw new AuthFailure(400,"EXTERNAL_STATE_INVALID");correlation=c.getValue();
        }
        if(request.getParameterValues("state").length!=1 || (code!=null && request.getParameterValues("code").length!=1)
            || (error!=null && request.getParameterValues("error").length!=1) || (code==null)==(error==null))throw new AuthFailure(400,"EXTERNAL_STATE_INVALID");
        if(error!=null) {
            String target=service.rejectWebCallback(provider,state,correlation);
            return ResponseEntity.status(303).location(org.springframework.web.util.UriComponentsBuilder.fromUriString(target).queryParam("external","failed").build().encode().toUri()).build();
        }
        var outcome=login.callback(provider,state,correlation,code,appleName(provider,request.getParameter("user")));
        if(outcome.session()!=null)cookies.set(response,outcome.session().secret(),outcome.session().principal().rememberMe());
        if(outcome.registrationSecret()!=null)response.addHeader("Set-Cookie",registrationCookie(outcome.registrationSecret(),ExternalLoginService.COMPLETION_LIFE.getSeconds()).toString());
        response.addHeader("Set-Cookie",ResponseCookie.from(cookie(provider),"").secure(true).httpOnly(true).sameSite("None")
            .path("/api/v1/auth/external/"+provider.name()+"/callback").maxAge(0).build().toString());
        String result=outcome.session()!=null?"signed-in":"complete-profile";
        return ResponseEntity.status(303).location(org.springframework.web.util.UriComponentsBuilder.fromUriString(outcome.returnUri())
            .queryParam("external",result).build().encode().toUri()).build();
    }
    public record Completion(@NotBlank @jakarta.validation.constraints.Email @Size(max=254) String email,@NotBlank @Size(max=200) String displayName) {}
    @GetMapping("/registrations/current")
    public ExternalLoginService.Profile profile(HttpServletRequest request) {
        throttle.check("external-profile","",request.getRemoteAddr());return login.profile(registrationSecret(request));
    }
    @PostMapping("/registrations/completions")
    public ResponseEntity<Void> complete(@Valid @RequestBody Completion input,HttpServletRequest request) {
        throttle.check("external-complete","",request.getRemoteAddr());login.complete(registrationSecret(request),input.email(),input.displayName());
        return ResponseEntity.accepted().build();
    }
    private static String registrationSecret(HttpServletRequest request) {
        String result=null;
        if(request.getCookies()!=null)for(var c:request.getCookies())if(c.getName().equals(REGISTRATION_COOKIE)) {
            if(result!=null)throw new AuthFailure(400,"EXTERNAL_REGISTRATION_INVALID");result=c.getValue();
        }
        return result;
    }
    private static ResponseCookie registrationCookie(String secret,long age){return ResponseCookie.from(REGISTRATION_COOKIE,secret)
        .secure(true).httpOnly(true).sameSite("Strict").path("/api/v1/auth/external/registrations").maxAge(age).build();}
    private static String appleName(ExternalProviders.Provider provider,String user) {
        if(provider!=ExternalProviders.Provider.APPLE || user==null)return null;
        if(user.length()>4096)throw new AuthFailure(400,"EXTERNAL_PROFILE_INVALID");
        try {
            var node=tools.jackson.databind.json.JsonMapper.builder().build().readTree(user).get("name");
            if(node==null)return null;
            var first=node.get("firstName");var last=node.get("lastName");
            return ((first!=null && first.isString()?first.asString():"")+" "+(last!=null && last.isString()?last.asString():"")).strip();
        } catch(RuntimeException failure){throw new AuthFailure(400,"EXTERNAL_PROFILE_INVALID");}
    }
    static String cookie(ExternalProviders.Provider provider){return "RALLYE_OIDC_"+provider.name();}
}
