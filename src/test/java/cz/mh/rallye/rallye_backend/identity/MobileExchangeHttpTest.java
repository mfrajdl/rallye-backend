package cz.mh.rallye.rallye_backend.identity;

import java.util.*;
import cz.mh.rallye.rallye_backend.identity.api.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@WebMvcTest(MobileExchangeController.class)
@Import({AuthConfiguration.class,AuthCookies.class,ApiErrors.class,LaunchTicketPrivacyFilter.class})
class MobileExchangeHttpTest {
    static final String PATH="/api/v1/auth/external/mobile/exchanges";
    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean MobileExchangeService service;
    @MockitoBean LoginThrottle throttle;
    SecretTokens secrets=new SecretTokens();String code=secrets.create(),verifier=secrets.create();UUID request=UUID.randomUUID();
    String body(){return "{\"code\":\""+code+"\",\"requestId\":\""+request+"\",\"codeVerifier\":\""+verifier+"\"}";}
    AuthService.LoginSession ready(){var session=new AuthService.LoginSession(secrets.create(),new AuthService.Principal(UUID.randomUUID(),UUID.randomUUID(),true));when(service.exchange(code,request,verifier)).thenReturn(session);return session;}
    @Test void anonymousMobileExchangeNeedsNoCsrfOrSessionAndReturnsOnlyBearerJson() throws Exception {
        var session=ready();mvc.perform(post(PATH).header("X-Rallye-Client","mobile").contentType("application/json").content(body()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.token").value(session.secret())).andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.rememberMe").value(true)).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().string("Referrer-Policy","no-referrer")).andExpect(header().doesNotExist("Set-Cookie")).andExpect(header().doesNotExist("Location"));
        verify(throttle).check(eq("external-exchange"),eq(""),any());verifyNoInteractions(auth);
    }
    @Test void cookieAndAuthorizationNeverSubstituteForPkceEvenWithCsrf() throws Exception {
        mvc.perform(post(PATH).with(csrf()).header("X-Rallye-Client","mobile").cookie(new Cookie(AuthCookies.NAME,secrets.create())).contentType("application/json").content(body()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST")).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(post(PATH).header("X-Rallye-Client","mobile").header("Authorization","Bearer "+secrets.create()).contentType("application/json").content(body()))
            .andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(service,auth);
    }
    @Test void explicitMobileHeaderIsRequiredEvenWithValidCsrf() throws Exception {
        mvc.perform(post(PATH).with(csrf()).contentType("application/json").content(body())).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"));verifyNoInteractions(service);
    }
    @Test void malformedParametersAre400WithoutSensitiveDetails() throws Exception {
        for(String body:List.of("{}",body().replace(code,"bad"),body().replace(request.toString(),"not-a-uuid"),body().replace(verifier,"too-short"),"{bad-json"))
            mvc.perform(post(PATH).header("X-Rallye-Client","mobile").contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST")).andExpect(header().string("Cache-Control","no-store"));
        verifyNoInteractions(service);
    }
    @Test void grantRejectionsUseUniformSafeResponse() throws Exception {
        doThrow(new AuthFailure(400,"EXTERNAL_HANDOFF_INVALID")).when(service).exchange(any(),any(),any());
        mvc.perform(post(PATH).header("X-Rallye-Client","mobile").contentType("application/json").content(body()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EXTERNAL_HANDOFF_INVALID")).andExpect(header().doesNotExist("Set-Cookie"));
    }
    @Test void sourceThrottleRunsBeforeExchange() throws Exception {
        doThrow(new AuthFailure(429,"TOO_MANY_ATTEMPTS")).when(throttle).check(any(),any(),any());
        mvc.perform(post(PATH).header("X-Rallye-Client","mobile").contentType("application/json").content(body()))
            .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","900")).andExpect(header().string("Cache-Control","no-store"));verifyNoInteractions(service);
    }
    @Test void browserSensitiveEndpointsStillRequireCsrf() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("{}" )).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/external/registrations/completions").header("X-Rallye-Client","mobile").contentType("application/json").content("{}" )).andExpect(status().isForbidden());
    }
    @Test void errorsBeforeControllerKeepPrivacyHeaders() throws Exception {
        mvc.perform(post(PATH).header("X-Rallye-Client","mobile").contentType("text/plain").content(body())).andExpect(status().isUnsupportedMediaType())
            .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Referrer-Policy","no-referrer"));
    }
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    @Test void traceLoggingAndUnexpectedErrorsNeverRevealInputOrIssuedToken(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var session=ready();var context=(ch.qos.logback.classic.LoggerContext)org.slf4j.LoggerFactory.getILoggerFactory();
        String[] names={"org.springframework.web.servlet.DispatcherServlet","org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor"};
        var levels=new HashMap<String,ch.qos.logback.classic.Level>();
        for(String name:names){var logger=context.getLogger(name);levels.put(name,logger.getLevel());logger.setLevel(ch.qos.logback.classic.Level.TRACE);}
        mvc.getDispatcherServlet().setEnableLoggingRequestDetails(true);
        try {
            mvc.perform(post(PATH).header("X-Rallye-Client","mobile").contentType("application/json").content(body())).andExpect(status().isOk());
            mvc.perform(post(PATH).header("X-Rallye-Client","mobile").contentType("application/json").content(body().replace(verifier,verifier+"!"))).andExpect(status().isBadRequest());
            doThrow(new IllegalStateException(code+verifier)).when(service).exchange(code,request,verifier);
            mvc.perform(post(PATH).header("X-Rallye-Client","mobile").contentType("application/json").content(body())).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andExpect(header().string("Cache-Control","no-store"));
            for(String secret:List.of(code,verifier,session.secret()))assertFalse(output.toString().contains(secret));
        } finally {levels.forEach((name,level)->context.getLogger(name).setLevel(level));mvc.getDispatcherServlet().setEnableLoggingRequestDetails(false);}
    }
}
