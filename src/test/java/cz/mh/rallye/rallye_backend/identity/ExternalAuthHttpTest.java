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
@Import({AuthConfiguration.class,AuthCookies.class,ApiErrors.class})
class ExternalAuthHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean LoginThrottle throttle;
    @MockitoBean ExternalFlowService flows;
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
}
