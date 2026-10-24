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
    String start="/api/v1/auth/external/GOOGLE/requests";
    @Test void startRequiresCsrfAndSetsOnlyIsolatedSecureCorrelation() throws Exception {
        String body="{\"purpose\":\"LOGIN\",\"returnId\":\"web\"}";
        mvc.perform(post(start).contentType("application/json").content(body)).andExpect(status().isForbidden());
        verifyNoInteractions(flows);
        when(flows.start(any(),any(),any(),any(),anyBoolean(),any())).thenReturn(new ExternalFlowService.Started("https://accounts.google.com/example","correlation",Instant.now()));
        mvc.perform(post(start).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk())
            .andExpect(cookie().secure("RALLYE_OIDC_GOOGLE",true)).andExpect(cookie().httpOnly("RALLYE_OIDC_GOOGLE",true))
            .andExpect(cookie().sameSite("RALLYE_OIDC_GOOGLE","None")).andExpect(cookie().doesNotExist(AuthCookies.NAME))
            .andExpect(jsonPath("$.correlation").doesNotExist()).andExpect(jsonPath("$.stage").value("PROTOCOL_ONLY"));
    }
    @Test void applePostCallbackHasNarrowCsrfExceptionAndNoSessionIssuance() throws Exception {
        mvc.perform(post("/api/v1/auth/external/APPLE/callback").param("state","state").param("code","code")
            .cookie(new Cookie("RALLYE_OIDC_APPLE","correlation"))).andExpect(status().isNoContent()).andExpect(cookie().doesNotExist(AuthCookies.NAME));
        verify(flows).callback(ExternalProviders.Provider.APPLE,"state","correlation","code");
        mvc.perform(post("/api/v1/auth/password-resets").contentType("application/json").content("{}" )).andExpect(status().isForbidden());
    }
    @Test void invalidCorrelationIsSafeAndWrongCallbackMethodRejected() throws Exception {
        doThrow(new AuthFailure(400,"EXTERNAL_STATE_INVALID")).when(flows).callback(any(),any(),any(),any());
        mvc.perform(get("/api/v1/auth/external/GOOGLE/callback").param("state","s").param("code","c"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EXTERNAL_STATE_INVALID"));
        mvc.perform(get("/api/v1/auth/external/APPLE/callback").param("state","s").param("code","c")).andExpect(status().isBadRequest());
    }
    @Test void unknownProviderNeverReachesProtocolService() throws Exception {
        mvc.perform(post("/api/v1/auth/external/ATTACKER/requests").with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(flows);
    }
}
