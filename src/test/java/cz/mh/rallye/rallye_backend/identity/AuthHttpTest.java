package cz.mh.rallye.rallye_backend.identity;

import java.util.UUID;
import cz.mh.rallye.rallye_backend.identity.api.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({AuthConfiguration.class, AuthCookies.class, ApiErrors.class})
class AuthHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService service;
    @MockitoBean LoginThrottle throttle;
    private static final String EMAIL = "{\"email\":\"person@example.test\"}";

    @Test void webLoginRequiresCsrf() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"email\":\"person@example.test\",\"password\":\"password\"}"))
                .andExpect(status().isForbidden()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        verifyNoInteractions(service);
    }
    @Test void webLoginUsesHttpOnlySecureSessionCookieWithoutExposingSecret() throws Exception {
        String secret = new SecretTokens().create();
        var principal = new AuthService.Principal(UUID.randomUUID(), UUID.randomUUID(), false);
        when(service.login(any(), any(), eq(false))).thenReturn(new AuthService.LoginSession(secret, principal));
        when(service.me(principal)).thenReturn(new AuthService.UserView(principal.accountId(), "person@example.test", "Person"));
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
                .content("{\"email\":\"person@example.test\",\"password\":\"password\"}"))
                .andExpect(status().isOk()).andExpect(cookie().httpOnly(AuthCookies.NAME, true))
                .andExpect(cookie().secure(AuthCookies.NAME, true)).andExpect(cookie().maxAge(AuthCookies.NAME, -1))
                .andExpect(jsonPath("$.token").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist());
    }
    @Test void mobileLoginReturnsBearerTokenAndNoSessionCookie() throws Exception {
        var principal = new AuthService.Principal(UUID.randomUUID(), UUID.randomUUID(), true);
        when(service.login(any(), any(), eq(true))).thenReturn(new AuthService.LoginSession(new SecretTokens().create(), principal));
        mvc.perform(post("/api/v1/auth/login").header("X-Rallye-Client", "mobile").contentType("application/json")
                .content("{\"email\":\"person@example.test\",\"password\":\"password\",\"rememberMe\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.token").isString()).andExpect(cookie().doesNotExist(AuthCookies.NAME));
    }
    @Test void cookieCannotBypassCsrfWithMobileHeader() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie(AuthCookies.NAME, new SecretTokens().create()))
                .header("X-Rallye-Client", "mobile")).andExpect(status().isForbidden());
    }
    @Test void bearerAuthenticatesAndLogsOutWithoutCsrf() throws Exception {
        String secret = new SecretTokens().create();
        var principal = new AuthService.Principal(UUID.randomUUID(), UUID.randomUUID(), false);
        when(service.authenticate(secret)).thenReturn(principal);
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + secret))
                .andExpect(status().isNoContent());
        verify(service).logout(principal);
    }
    @Test void ambiguousCookieAndBearerAreRejected() throws Exception {
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + new SecretTokens().create())
                .cookie(new Cookie(AuthCookies.NAME, new SecretTokens().create())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AMBIGUOUS_CREDENTIALS"));
    }
    @Test void unknownResetRequestsHaveSamePublicResponse() throws Exception {
        mvc.perform(post("/api/v1/auth/password-reset-requests").with(csrf()).contentType("application/json").content(EMAIL))
                .andExpect(status().isAccepted()).andExpect(content().string(""));
    }
    @Test void errorsNeverEchoRejectedPasswordOrToken() throws Exception {
        mvc.perform(post("/api/v1/auth/password-resets").with(csrf()).contentType("application/json")
                .content("{\"token\":\"secret-token\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PASSWORD_LENGTH"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-token"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("short"))));
    }
    @Test void invalidLoginHasUniformProblemAndNoRedirect() throws Exception {
        when(service.login(any(), any(), anyBoolean())).thenThrow(AuthFailure.credentials());
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
                .content("{\"email\":\"missing\",\"password\":\"bad\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(header().doesNotExist("Location"));
    }
    @Test void exhaustedLoginLimitReturns429BeforeCheckingPassword() throws Exception {
        doThrow(new AuthFailure(429, "TOO_MANY_ATTEMPTS")).when(throttle).check(eq("login"), anyString(), anyString());
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
                .content("{\"email\":\"person@example.test\",\"password\":\"password\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "900"));
        verifyNoInteractions(service);
    }
    @Test void expiredBrowserCookieDoesNotPreventFreshLogin() throws Exception {
        var principal = new AuthService.Principal(UUID.randomUUID(), UUID.randomUUID(), false);
        when(service.login(any(), any(), eq(false))).thenReturn(new AuthService.LoginSession(new SecretTokens().create(), principal));
        when(service.me(principal)).thenReturn(new AuthService.UserView(principal.accountId(), "person@example.test", "Person"));
        mvc.perform(post("/api/v1/auth/login").with(csrf())
                .cookie(new Cookie(AuthCookies.NAME, new SecretTokens().create())).contentType("application/json")
                .content("{\"email\":\"person@example.test\",\"password\":\"password\"}"))
                .andExpect(status().isOk());
        verify(service).login("person@example.test", "password", false);
    }
    @Test
    @org.springframework.test.annotation.DirtiesContext(methodMode = org.springframework.test.annotation.DirtiesContext.MethodMode.BEFORE_METHOD)
    void realCsrfCookieAndReturnedTokenAuthorizeWebLogin() throws Exception {
        var csrfResponse = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        String token = com.jayway.jsonpath.JsonPath.read(csrfResponse.getContentAsString(), "$.token");
        var principal = new AuthService.Principal(UUID.randomUUID(), UUID.randomUUID(), true);
        when(service.login(any(), any(), eq(true))).thenReturn(new AuthService.LoginSession(new SecretTokens().create(), principal));
        when(service.me(principal)).thenReturn(new AuthService.UserView(principal.accountId(), "person@example.test", "Person"));
        mvc.perform(post("/api/v1/auth/login").cookie(csrfResponse.getCookie("XSRF-TOKEN"))
                .header("X-XSRF-TOKEN", token).contentType("application/json")
                .content("{\"email\":\"person@example.test\",\"password\":\"password\",\"rememberMe\":true}"))
                .andExpect(status().isOk()).andExpect(cookie().maxAge(AuthCookies.NAME, 2592000));
    }
}
