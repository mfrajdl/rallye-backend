package cz.mh.rallye.rallye_backend.identity;

import java.time.Instant;
import cz.mh.rallye.rallye_backend.identity.api.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@WebMvcTest(ExternalAuthController.class)
@Import({AuthConfiguration.class,AuthCookies.class,ApiErrors.class,LaunchTicketPrivacyFilter.class})
class ExternalAuthHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean LoginThrottle throttle;
    @MockitoBean ExternalFlowService flows;
    @MockitoBean MobileLoginService mobile;
    @MockitoBean ExternalLoginService login;
    String start="/api/v1/auth/external/GOOGLE/requests";
    @Test void removedPurposesAreRejectedBeforeAnyFlowStarts() throws Exception {
        for(String purpose:java.util.List.of("LINK","UNLINK","REAUTHENTICATE"))
            mvc.perform(post(start).with(csrf()).contentType("application/json")
                .content("{\"purpose\":\""+purpose+"\",\"returnId\":\"web\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(flows,login);
    }
    @Test void identityManagementEndpointsDoNotExistForAuthenticatedUsers() throws Exception {
        for(String path:java.util.List.of("/api/v1/auth/identities/link","/api/v1/auth/identities/local","/api/v1/auth/reauthentications"))
            mvc.perform(post(path).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("test"))
                .with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/auth/identities/"+java.util.UUID.randomUUID())
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("test")).with(csrf()))
            .andExpect(status().isNotFound());
        verifyNoInteractions(flows,login);
    }
    @Test void startRequiresCsrfAndSetsOnlyIsolatedSecureCorrelation() throws Exception {
        String body="{\"purpose\":\"LOGIN\",\"returnId\":\"web\"}";
        mvc.perform(post(start).contentType("application/json").content(body)).andExpect(status().isForbidden());
        verifyNoInteractions(flows);
        when(flows.start(any(),any(),any(),any(),anyBoolean(),any())).thenReturn(new ExternalFlowService.Started("https://accounts.google.com/example","correlation",Instant.now()));
        mvc.perform(post(start).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk())
            .andExpect(cookie().secure("RALLYE_OIDC_GOOGLE",true)).andExpect(cookie().httpOnly("RALLYE_OIDC_GOOGLE",true))
            .andExpect(cookie().sameSite("RALLYE_OIDC_GOOGLE","None")).andExpect(cookie().doesNotExist(AuthCookies.NAME))
            .andExpect(jsonPath("$.correlation").doesNotExist()).andExpect(jsonPath("$.stage").value("WEB_LOGIN"));
    }
    @Test void applePostCallbackHasNarrowCsrfExceptionAndNoSessionIssuance() throws Exception {
        when(login.callback(any(),any(),any(),any(),any())).thenReturn(new ExternalLoginService.Outcome("https://app.example.test/complete",null,"registration"));
        mvc.perform(post("/api/v1/auth/external/APPLE/callback").param("state","state").param("code","code")
            .cookie(new Cookie("RALLYE_OIDC_APPLE","correlation"))).andExpect(status().isSeeOther()).andExpect(cookie().doesNotExist(AuthCookies.NAME));
        verify(login).callback(ExternalProviders.Provider.APPLE,"state","correlation","code",null);
        mvc.perform(post("/api/v1/auth/password-resets").contentType("application/json").content("{}" )).andExpect(status().isForbidden());
    }
    @Test void invalidCorrelationIsSafeAndWrongCallbackMethodRejected() throws Exception {
        doThrow(new AuthFailure(400,"EXTERNAL_STATE_INVALID")).when(login).callback(any(),any(),any(),any(),any());
        mvc.perform(get("/api/v1/auth/external/GOOGLE/callback").param("state","s").param("code","c"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EXTERNAL_STATE_INVALID"));
        mvc.perform(get("/api/v1/auth/external/APPLE/callback").param("state","s").param("code","c")).andExpect(status().isBadRequest());
    }
    @Test void knownIdentitySetsExistingSecureWebCookieWithoutTokenInRedirect() throws Exception {
        String secret=new SecretTokens().create();var actor=new AuthService.Principal(java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),true);
        when(login.callback(any(),any(),any(),any(),any())).thenReturn(new ExternalLoginService.Outcome("https://app.example.test/complete",new AuthService.LoginSession(secret,actor),null));
        mvc.perform(get("/api/v1/auth/external/GOOGLE/callback").param("state","state").param("code","code").cookie(new Cookie("RALLYE_OIDC_GOOGLE","correlation")))
            .andExpect(status().isSeeOther()).andExpect(cookie().httpOnly(AuthCookies.NAME,true)).andExpect(cookie().secure(AuthCookies.NAME,true))
            .andExpect(cookie().maxAge(AuthCookies.NAME,2592000)).andExpect(header().string("Location","https://app.example.test/complete?external=signed-in"));
    }
    @Test void incompleteRegistrationOnlySetsScopedCompletionCookie() throws Exception {
        when(login.callback(any(),any(),any(),any(),any())).thenReturn(new ExternalLoginService.Outcome("https://app.example.test/complete",null,"opaque-registration"));
        mvc.perform(get("/api/v1/auth/external/GOOGLE/callback").param("state","s").param("code","c"))
            .andExpect(status().isSeeOther()).andExpect(cookie().doesNotExist(AuthCookies.NAME)).andExpect(cookie().httpOnly(ExternalAuthController.REGISTRATION_COOKIE,true))
            .andExpect(cookie().secure(ExternalAuthController.REGISTRATION_COOKIE,true)).andExpect(cookie().maxAge(ExternalAuthController.REGISTRATION_COOKIE,900))
            .andExpect(header().string("Location","https://app.example.test/complete?external=complete-profile"));
    }
    @Test void completionCannotBypassCsrfWithMobileHeaderOrBearer() throws Exception {
        mvc.perform(post("/api/v1/auth/external/registrations/completions").header("X-Rallye-Client","mobile")
            .cookie(new Cookie(ExternalAuthController.REGISTRATION_COOKIE,"opaque")).contentType("application/json").content("{\"email\":\"a@example.test\",\"displayName\":\"A\"}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(login);
    }
    @Test void completeReturnsGenericAcceptedAndNoCredentials() throws Exception {
        mvc.perform(post("/api/v1/auth/external/registrations/completions").with(csrf()).cookie(new Cookie(ExternalAuthController.REGISTRATION_COOKIE,"opaque"))
            .contentType("application/json").content("{\"email\":\"a@example.test\",\"displayName\":\"A\"}"))
            .andExpect(status().isAccepted()).andExpect(content().string("")).andExpect(cookie().doesNotExist(AuthCookies.NAME));
        verify(login).complete("opaque","a@example.test","A");
    }
    @Test void appleUntrustedProfileOnlySuppliesNameNeverIdentityEmail() throws Exception {
        when(login.callback(any(),any(),any(),any(),any())).thenReturn(new ExternalLoginService.Outcome("https://app.example.test/complete",null,"opaque"));
        mvc.perform(post("/api/v1/auth/external/APPLE/callback").param("state","s").param("code","c").param("user","{\"email\":\"attacker@example.test\",\"name\":{\"firstName\":\"First\",\"lastName\":\"Last\"}}"))
            .andExpect(status().isSeeOther());
        verify(login).callback(ExternalProviders.Provider.APPLE,"s",null,"c","First Last");
    }
    @Test void duplicateStateOrCorrelationAndMalformedAppleProfileAreRejected() throws Exception {
        mvc.perform(get("/api/v1/auth/external/GOOGLE/callback").param("state","a","b").param("code","c")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/auth/external/GOOGLE/callback").param("state","a").param("code","c")
            .cookie(new Cookie("RALLYE_OIDC_GOOGLE","a"),new Cookie("RALLYE_OIDC_GOOGLE","b"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/external/APPLE/callback").param("state","a").param("code","c").param("user","not-json")).andExpect(status().isBadRequest());
        verifyNoInteractions(login);
    }
    @Test void providerDenialRedirectsOnlyToValidatedReturnWithoutReflectingError() throws Exception {
        when(flows.rejectWebCallback(any(),any(),any())).thenReturn("https://app.example.test/complete");
        mvc.perform(get("/api/v1/auth/external/GOOGLE/callback").param("state","s").param("error","arbitrary-sensitive-provider-text")
            .cookie(new Cookie("RALLYE_OIDC_GOOGLE","correlation"))).andExpect(status().isSeeOther())
            .andExpect(header().string("Location","https://app.example.test/complete?external=failed")).andExpect(cookie().doesNotExist(AuthCookies.NAME));
        verifyNoInteractions(login);
    }
    @Test void unknownProviderNeverReachesProtocolService() throws Exception {
        mvc.perform(post("/api/v1/auth/external/ATTACKER/requests").with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(flows);
    }
    @Test void mobileStartIsPublicButDoesNotSetBrowserOrSessionCookies() throws Exception {
        when(mobile.start(any(),any(),any(),anyBoolean(),any())).thenReturn(new MobileLoginService.Started(java.util.UUID.randomUUID(),"https://backend.example.test/api/v1/auth/external/mobile/launch?ticket=opaque",Instant.now().plusSeconds(600),"MOBILE_BOOTSTRAP"));
        mvc.perform(post(start).header("X-Rallye-Client","mobile").contentType("application/json")
            .content("{\"purpose\":\"LOGIN\",\"returnId\":\"android\",\"mobileChallenge\":\""+"a".repeat(43)+"\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("MOBILE_BOOTSTRAP"))
            .andExpect(jsonPath("$.requestId").exists()).andExpect(jsonPath("$.browserStartUrl").exists())
            .andExpect(jsonPath("$.token").doesNotExist()).andExpect(cookie().doesNotExist(AuthCookies.NAME))
            .andExpect(cookie().doesNotExist("RALLYE_OIDC_GOOGLE")).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().string("Referrer-Policy","no-referrer"));
        verifyNoInteractions(flows,login);
    }
    @Test void mobileChallengeCannotBeSentThroughTheWebBranchAndLongChallengeIsInvalid() throws Exception {
        for(String challenge:java.util.List.of("a".repeat(43),"a".repeat(44)))
            mvc.perform(post(start).with(csrf()).contentType("application/json").content("{\"purpose\":\"LOGIN\",\"returnId\":\"web\",\"mobileChallenge\":\""+challenge+"\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(flows,mobile,login);
    }
    @Test void mobileStartCannotUseCookieAuthenticationToBypassCsrf() throws Exception {
        mvc.perform(post(start).header("X-Rallye-Client","mobile").cookie(new Cookie(AuthCookies.NAME,new SecretTokens().create()))
            .contentType("application/json").content("{\"purpose\":\"LOGIN\",\"returnId\":\"android\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post(start).with(csrf()).header("X-Rallye-Client","mobile").cookie(new Cookie(AuthCookies.NAME,new SecretTokens().create()))
            .contentType("application/json").content("{\"purpose\":\"LOGIN\",\"returnId\":\"android\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AMBIGUOUS_CREDENTIALS"));
        verifyNoInteractions(mobile);
    }
    @Test void launchIsPublicAndSetsOnlySecureBrowserCorrelationAfterServiceSuccess() throws Exception {
        String ticket=new SecretTokens().create();
        var flow=new ExternalFlowService.Started("https://accounts.google.com/example?state=opaque","correlation",Instant.now().plusSeconds(300));
        when(mobile.launch(ticket)).thenReturn(new MobileLoginService.Launched(ExternalProviders.Provider.GOOGLE,flow));
        mvc.perform(get("/api/v1/auth/external/mobile/launch").param("ticket",ticket))
            .andExpect(status().isSeeOther()).andExpect(header().string("Location",flow.authorizationUrl()))
            .andExpect(header().string("Referrer-Policy","no-referrer")).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(cookie().secure("RALLYE_OIDC_GOOGLE",true)).andExpect(cookie().httpOnly("RALLYE_OIDC_GOOGLE",true))
            .andExpect(cookie().sameSite("RALLYE_OIDC_GOOGLE","None")).andExpect(cookie().path("RALLYE_OIDC_GOOGLE","/api/v1/auth/external/GOOGLE/callback"))
            .andExpect(cookie().doesNotExist(AuthCookies.NAME));
        verifyNoInteractions(login,flows);
    }
    @Test void invalidAndDuplicateTicketsAndRedirectInjectionNeverSetCookies() throws Exception {
        when(mobile.launch(any())).thenThrow(new AuthFailure(400,"EXTERNAL_MOBILE_REQUEST_INVALID"));
        mvc.perform(get("/api/v1/auth/external/mobile/launch").param("ticket","bad"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EXTERNAL_MOBILE_REQUEST_INVALID"))
            .andExpect(header().doesNotExist("Set-Cookie"));
        clearInvocations(mobile);
        mvc.perform(get("/api/v1/auth/external/mobile/launch").param("ticket","a","b")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/auth/external/mobile/launch").param("ticket","a").param("redirect_uri","https://attacker.test"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(mobile);
    }

    @Test void allLaunchErrorsHavePrivacyHeadersBeforeControllerOrSecurity() throws Exception {
        String path="/api/v1/auth/external/mobile/launch";
        mvc.perform(get(path)).andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Referrer-Policy","no-referrer"));
        mvc.perform(get(path).param("ticket","x").header("Authorization","Bearer invalid"))
            .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().string("Referrer-Policy","no-referrer"));
        mvc.perform(post(path).param("ticket","x")).andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Referrer-Policy","no-referrer"));
        for(int status:java.util.List.of(400,429,503)) {
            doThrow(new AuthFailure(status,"SAFE_ERROR")).when(mobile).launch(any());
            mvc.perform(get(path).param("ticket","sensitive-fixture")).andExpect(status().is(status))
                .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Referrer-Policy","no-referrer"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sensitive-fixture"))))
                .andExpect(header().doesNotExist("Location"));
        }
    }
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    @Test void unexpectedFailureKeepsSafeDiagnosticsAndNeverReflectsTicket(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        when(mobile.launch(any())).thenThrow(new IllegalStateException("synthetic-launch-ticket-must-not-leak"));
        mvc.perform(get("/api/v1/auth/external/mobile/launch").param("ticket","synthetic-launch-ticket-must-not-leak"))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Referrer-Policy","no-referrer"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic-launch-ticket-must-not-leak"))))
            .andExpect(header().doesNotExist("Location"));
        org.junit.jupiter.api.Assertions.assertTrue(output.toString().contains("java.lang.IllegalStateException"));
        org.junit.jupiter.api.Assertions.assertFalse(output.toString().contains("synthetic-launch-ticket-must-not-leak"));
    }

    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    @Test void mvcTraceWithRequestDetailsDoesNotLogTicket(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String ticket="synthetic-mvc-private-ticket";
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(mvc.getDispatcherServlet().getClass());
        var before=logger.getLevel();mvc.getDispatcherServlet().setEnableLoggingRequestDetails(true);logger.setLevel(ch.qos.logback.classic.Level.TRACE);
        try {
            doThrow(new AuthFailure(400,"EXTERNAL_MOBILE_REQUEST_INVALID")).when(mobile).launch(ticket);
            mvc.perform(get("/api/v1/auth/external/mobile/launch").queryParam("ticket",ticket))
                .andExpect(status().isBadRequest());
            org.junit.jupiter.api.Assertions.assertFalse(output.toString().contains(ticket));
        } finally {logger.setLevel(before);mvc.getDispatcherServlet().setEnableLoggingRequestDetails(false);}
    }

}
