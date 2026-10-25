package cz.mh.rallye.rallye_backend.identity;

import java.util.UUID;
import cz.mh.rallye.rallye_backend.identity.api.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@WebMvcTest({AccountCancellationController.class,AuthController.class})
@Import({AuthConfiguration.class,AuthCookies.class,ApiErrors.class})
@ExtendWith(OutputCaptureExtension.class)
class AccountCancellationHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean AccountCancellationService cancellation;
    @MockitoBean LoginThrottle throttle;
    String secret=new SecretTokens().create();
    AuthService.Principal actor=new AuthService.Principal(UUID.randomUUID(),UUID.randomUUID(),false);
    static final String SELF="/api/v1/auth/account-cancellations";
    String admin(UUID target) {return "/api/v1/admin/users/"+target+"/cancellations";}
    void authenticated(boolean admin) {when(auth.authenticate(secret)).thenReturn(actor);when(auth.isAdministrator(actor)).thenReturn(admin);}
    @Test void anonymousAndNonAdminAreRejected() throws Exception {
        mvc.perform(post(SELF).header("X-Rallye-Client","mobile").header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"currentPassword\":\"password\"}"))
                .andExpect(status().isUnauthorized());
        authenticated(false);
        mvc.perform(post(admin(UUID.randomUUID())).header("Authorization","Bearer "+secret).header("Idempotency-Key",UUID.randomUUID()))
                .andExpect(status().isForbidden());verifyNoInteractions(cancellation);
    }
    @Test void bearerSelfClearsCookieAndForwardsPasswordOnlyToService() throws Exception {
        authenticated(false);UUID key=UUID.randomUUID();
        mvc.perform(post(SELF).header("Authorization","Bearer "+secret).header("Idempotency-Key",key)
                .contentType("application/json").content("{\"currentPassword\":\"synthetic-password\"}"))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().stringValues("Set-Cookie",hasItem(allOf(containsString("RALLYE_SESSION="),containsString("Max-Age=0")))));
        verify(cancellation).self(actor,"synthetic-password",false,key);
        verify(throttle).check(eq("account-cancellation"),eq(actor.accountId().toString()),anyString());
    }
    @Test void externalConfirmationIsForwardedWithoutPasswordOrFreshProof() throws Exception {
        authenticated(false);UUID key=UUID.randomUUID();
        mvc.perform(post(SELF).header("Authorization","Bearer "+secret).header("Idempotency-Key",key)
            .contentType("application/json").content("{\"confirmed\":true}"))
            .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(cancellation).self(actor,null,true,key);
    }
    @Test void explicitFalseIsNotConvertedIntoConsent() throws Exception {
        authenticated(false);UUID key=UUID.randomUUID();
        doThrow(new AuthFailure(403,"ACCOUNT_CANCELLATION_CONFIRMATION_REQUIRED")).when(cancellation).self(actor,null,false,key);
        mvc.perform(post(SELF).header("Authorization","Bearer "+secret).header("Idempotency-Key",key)
            .contentType("application/json").content("{\"confirmed\":false}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_CANCELLATION_CONFIRMATION_REQUIRED"));
        verify(cancellation).self(actor,null,false,key);
    }
    @Test void confirmationDoesNotBypassAuthenticationOrCookieCsrf() throws Exception {
        mvc.perform(post(SELF).header("X-Rallye-Client","mobile").header("Idempotency-Key",UUID.randomUUID())
            .contentType("application/json").content("{\"confirmed\":true}"))
            .andExpect(status().isUnauthorized());
        authenticated(false);
        mvc.perform(post(SELF).cookie(new Cookie(AuthCookies.NAME,secret)).header("Idempotency-Key",UUID.randomUUID())
            .contentType("application/json").content("{\"confirmed\":true}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(cancellation);
        var response=mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();
        String csrf=com.jayway.jsonpath.JsonPath.read(response.getContentAsString(),"$.token");UUID key=UUID.randomUUID();
        mvc.perform(post(SELF).cookie(new Cookie(AuthCookies.NAME,secret),response.getCookie("XSRF-TOKEN"))
            .header("X-XSRF-TOKEN",csrf).header("Idempotency-Key",key).contentType("application/json").content("{\"confirmed\":true}"))
            .andExpect(status().isNoContent());
        verify(cancellation).self(actor,null,true,key);
    }
    @Test void adminAcceptsBodylessRequestAndUsesSeparateThrottleNamespace() throws Exception {
        authenticated(true);UUID id=UUID.randomUUID(),key=UUID.randomUUID();
        mvc.perform(post(admin(id)).header("Authorization","Bearer "+secret).header("Idempotency-Key",key)).andExpect(status().isNoContent());
        verify(cancellation).administrator(actor,id,key);
        verify(throttle).check(eq("admin-account-cancellation"),eq(actor.accountId().toString()),anyString());
    }
    @Test void missingOrMalformedKeyAndPasswordReturn400() throws Exception {
        authenticated(true);
        mvc.perform(post(admin(UUID.randomUUID())).header("Authorization","Bearer "+secret)).andExpect(status().isBadRequest());
        mvc.perform(post(admin(UUID.randomUUID())).header("Authorization","Bearer "+secret).header("Idempotency-Key","not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(post(SELF).header("Authorization","Bearer "+secret).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(cancellation);
    }
    @Test void cookieAuthenticationRequiresRealCsrfToken() throws Exception {
        authenticated(false);UUID key=UUID.randomUUID();
        mvc.perform(post(SELF).cookie(new Cookie(AuthCookies.NAME,secret)).header("Idempotency-Key",key)
                .contentType("application/json").content("{\"currentPassword\":\"password\"}"))
                .andExpect(status().isForbidden());verifyNoInteractions(cancellation);
        var response=mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();
        String token=com.jayway.jsonpath.JsonPath.read(response.getContentAsString(),"$.token");
        mvc.perform(post(SELF).cookie(new Cookie(AuthCookies.NAME,secret),response.getCookie("XSRF-TOKEN"))
                .header("X-XSRF-TOKEN",token).header("Idempotency-Key",key).contentType("application/json").content("{\"currentPassword\":\"password\"}"))
                .andExpect(status().isNoContent());verify(cancellation).self(actor,"password",false,key);
    }
    @Test void errorsAreSafeAndRateLimitingStopsService() throws Exception {
        authenticated(false);UUID key=UUID.randomUUID();
        doThrow(AuthFailure.credentials()).when(cancellation).self(any(),any(),anyBoolean(),any());
        mvc.perform(post(SELF).header("Authorization","Bearer "+secret).header("Idempotency-Key",key)
                .contentType("application/json").content("{\"currentPassword\":\"secret-must-not-appear\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(content().string(not(containsString("secret-must-not-appear"))));
        reset(cancellation);doThrow(new AuthFailure(429,"TOO_MANY_ATTEMPTS")).when(throttle).check(any(),any(),any());
        mvc.perform(post(SELF).header("Authorization","Bearer "+secret).header("Idempotency-Key",key)
                .contentType("application/json").content("{\"currentPassword\":\"password\"}"))
                .andExpect(status().isTooManyRequests());verifyNoInteractions(cancellation);
    }
    @Test void adminServiceErrorsPreserveApiCodes() throws Exception {
        authenticated(true);
        for(var error:new AuthFailure[]{new AuthFailure(404,"ACCOUNT_NOT_FOUND"),new AuthFailure(409,"IDEMPOTENCY_CONFLICT"),new AuthFailure(503,"PARTICIPATION_CHECK_UNAVAILABLE"),new AuthFailure(403,"SELF_ACCOUNT_CANCELLATION_FORBIDDEN")}) {
            doThrow(error).when(cancellation).administrator(any(),any(),any());
            mvc.perform(post(admin(UUID.randomUUID())).header("Authorization","Bearer "+secret).header("Idempotency-Key",UUID.randomUUID()))
                    .andExpect(status().is(error.status())).andExpect(jsonPath("$.code").value(error.code()));
        }
    }
    @Test void unexpectedCancellationFailureLogsTransactionAndSqlCauseSafely(CapturedOutput output) throws Exception {
        authenticated(false);String sensitive="private-current-password-token-email@example.test";
        var sql=new java.sql.SQLException("Private row: "+sensitive,"23503");
        var failure=new org.springframework.transaction.TransactionSystemException(sensitive,sql);
        doThrow(failure).when(cancellation).self(any(),any(),anyBoolean(),any());
        mvc.perform(post(SELF).header("Authorization","Bearer "+secret).header("Idempotency-Key",UUID.randomUUID())
                .contentType("application/json").content("{\"currentPassword\":\""+sensitive+"\"}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString(sensitive)))).andExpect(content().string(not(containsString("SQLSTATE"))));
        org.junit.jupiter.api.Assertions.assertTrue(output.getAll().contains("Unexpected API failure"));
        safeDiagnostic(output,"TransactionSystemException","23503",sensitive);
    }
    @Test void explicitApplicationInternalErrorAlsoLogsItsCause(CapturedOutput output) throws Exception {
        authenticated(false);String sensitive="private-application-error-password";
        doThrow(new AuthFailure(500,"INTERNAL_ERROR",new java.sql.SQLException(sensitive,"P0001")))
                .when(cancellation).self(any(),any(),anyBoolean(),any());
        mvc.perform(post(SELF).header("Authorization","Bearer "+secret).header("Idempotency-Key",UUID.randomUUID())
                .contentType("application/json").content("{\"currentPassword\":\""+sensitive+"\"}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        org.junit.jupiter.api.Assertions.assertTrue(output.getAll().contains("API server failure"));
        safeDiagnostic(output,"AuthFailure","P0001",sensitive);
    }
    @Test void explicitInternalErrorBeforeControllerIsLoggedByAuthenticationFilter(CapturedOutput output) throws Exception {
        String sensitive="private-filter-session-token";
        when(auth.authenticate(secret)).thenThrow(new AuthFailure(500,"INTERNAL_ERROR",new java.sql.SQLException(sensitive,"08006")));
        mvc.perform(post(SELF).header("Authorization","Bearer "+secret).header("Idempotency-Key",UUID.randomUUID())
                .contentType("application/json").content("{\"currentPassword\":\"unused-password\"}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        verifyNoInteractions(cancellation);
        safeDiagnostic(output,"AuthFailure","08006",sensitive);
        org.junit.jupiter.api.Assertions.assertFalse(output.getAll().contains(secret));
    }
    @Test void unexpectedAuthenticationFailureIs503AndLoggedBeforeController(CapturedOutput output) throws Exception {
        String sensitive="private-filter-database-details";
        when(auth.authenticate(secret)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException(sensitive,
                new java.sql.SQLException(sensitive,"08001")));
        mvc.perform(post(SELF).header("Authorization","Bearer "+secret).header("Idempotency-Key",UUID.randomUUID())
                .contentType("application/json").content("{\"currentPassword\":\"unused-password\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTHENTICATION_UNAVAILABLE"));
        verifyNoInteractions(cancellation);safeDiagnostic(output,"DataAccessResourceFailureException","08001",sensitive);
        org.junit.jupiter.api.Assertions.assertFalse(output.getAll().contains(secret));
    }
    @Test void participationCheck503LogsPreservedSqlCauseWithoutChangingResponse(CapturedOutput output) throws Exception {
        authenticated(false);String sensitive="private-participation-database-details";
        doThrow(new AuthFailure(503,"PARTICIPATION_CHECK_UNAVAILABLE",new java.sql.SQLException(sensitive,"42P01")))
                .when(cancellation).self(any(),any(),anyBoolean(),any());
        mvc.perform(post(SELF).header("Authorization","Bearer "+secret).header("Idempotency-Key",UUID.randomUUID())
                .contentType("application/json").content("{\"currentPassword\":\"unused-password\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("PARTICIPATION_CHECK_UNAVAILABLE"))
                .andExpect(content().string(not(containsString("42P01"))));
        safeDiagnostic(output,"AuthFailure","42P01",sensitive);
    }
    private void safeDiagnostic(CapturedOutput output,String type,String state,String sensitive) {
        String log=output.getAll();
        org.junit.jupiter.api.Assertions.assertTrue(log.contains(type));
        org.junit.jupiter.api.Assertions.assertTrue(log.contains("SQLSTATE="+state));
        org.junit.jupiter.api.Assertions.assertTrue(log.contains("Caused by:"));
        org.junit.jupiter.api.Assertions.assertTrue(log.contains("at "));
        org.junit.jupiter.api.Assertions.assertFalse(log.contains(sensitive));
    }
}
