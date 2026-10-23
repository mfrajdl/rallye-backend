package cz.mh.rallye.rallye_backend.crews;

import java.time.Instant;
import java.util.UUID;
import cz.mh.rallye.rallye_backend.crews.api.CrewTransferController;
import cz.mh.rallye.rallye_backend.crews.api.CrewTransferDecisionController;
import cz.mh.rallye.rallye_backend.crews.application.CrewTransferService;
import cz.mh.rallye.rallye_backend.identity.api.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import org.junit.jupiter.api.*;
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

@WebMvcTest({CrewTransferController.class,CrewTransferDecisionController.class})
@Import({AuthConfiguration.class,AuthCookies.class,ApiErrors.class})
class CrewTransferHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean CrewTransferService service;
    @MockitoBean AuthService auth;
    @MockitoBean LoginThrottle throttle;
    String secret=new SecretTokens().create();UUID crew=UUID.randomUUID(),id=UUID.randomUUID(),key=UUID.randomUUID();
    AuthService.Principal actor=new AuthService.Principal(UUID.randomUUID(),UUID.randomUUID(),false);
    String url() {return "/api/v1/crews/"+crew+"/transfers";}
    @BeforeEach void setup() {when(auth.authenticate(secret)).thenReturn(actor);}
    @Test void createContractAndSafeLocationAndBody() throws Exception {
        when(service.create(any(),any(),any(),any(),anyLong())).thenReturn(new CrewTransferService.View(id,crew,actor.accountId(),UUID.randomUUID(),"PENDING",Instant.EPOCH,Instant.EPOCH.plusSeconds(604800)));
        mvc.perform(post(url()).header("Authorization","Bearer "+secret).header("Idempotency-Key",key)
                .contentType("application/json").content("{\"targetEmail\":\" TARGET@EXAMPLE.TEST \",\"version\":3}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location",url()+"/"+id))
                .andExpect(jsonPath("$.id").value(id.toString())).andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.tokenHash").doesNotExist());
        verify(service).create(actor,crew,key,"target@example.test",3);
        verify(throttle).check(eq("crew-transfer-create"),eq(actor.accountId().toString()),any());
    }
    @Test void invalidBodyAndKeyDoNotReachService() throws Exception {
        for(String body:new String[]{"{}","{\"targetEmail\":\"invalid\",\"version\":3}","{\"targetEmail\":\"a@example.test\",\"version\":-1}"})
            mvc.perform(post(url()).header("Authorization","Bearer "+secret).header("Idempotency-Key",key)
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post(url()).header("Authorization","Bearer "+secret).header("Idempotency-Key","not-uuid")
                .contentType("application/json").content("{\"targetEmail\":\"a@example.test\",\"version\":3}"))
                .andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void authenticationAndCookieCsrfRemainRequired() throws Exception {
        mvc.perform(post(url()).with(csrf()).header("Idempotency-Key",key).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(url()).cookie(new jakarta.servlet.http.Cookie(AuthCookies.NAME,secret)).header("Idempotency-Key",key)
                .contentType("application/json").content("{\"targetEmail\":\"a@example.test\",\"version\":3}"))
                .andExpect(status().isForbidden());verifyNoInteractions(service);
    }
    @Test void cookieWithCsrfCanResendAndLimiterIsOperationSpecific() throws Exception {
        mvc.perform(post(url()+"/"+id+"/resend").with(csrf()).cookie(new jakarta.servlet.http.Cookie(AuthCookies.NAME,secret))
                .header("Idempotency-Key",key)).andExpect(status().isOk());
        verify(service).resend(actor,crew,id,key);verify(throttle).check(eq("crew-transfer-resend"),eq(actor.accountId().toString()),any());
    }
    @Test void rateLimitAndBusinessConflictAreReturnedAsSafeCodes() throws Exception {
        doThrow(new AuthFailure(429,"TOO_MANY_ATTEMPTS")).when(throttle).check(any(),any(),any());
        mvc.perform(post(url()+"/"+id+"/resend").header("Authorization","Bearer "+secret).header("Idempotency-Key",key))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
        reset(throttle);when(service.resend(any(),any(),any(),any())).thenThrow(new AuthFailure(409,"IDEMPOTENCY_CONFLICT"));
        mvc.perform(post(url()+"/"+id+"/resend").header("Authorization","Bearer "+secret).header("Idempotency-Key",key))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"accept","reject","cancel"})
    void decisionEndpointsSupportBearerAndCookieCsrf(String operation) throws Exception {
        String token=operation.equals("cancel")?null:new SecretTokens().create();
        when(service.decide(any(),any(),any(),any(),any())).thenReturn(
                new CrewTransferService.Decision(id,crew,operation.toUpperCase(),4,Instant.EPOCH));
        var request=post("/api/v1/crew-transfers/"+id+"/"+operation).header("Idempotency-Key",key);
        if(token!=null) request.contentType("application/json").content("{\"token\":\""+token+"\"}");
        mvc.perform(request.header("Authorization","Bearer "+secret)).andExpect(status().isOk())
                .andExpect(jsonPath("$.token").doesNotExist());
        verify(service).decide(actor,id,key,operation,token);
        verify(throttle).check(eq("crew-transfer-"+operation),eq(actor.accountId().toString()),any());
        var cookie=post("/api/v1/crew-transfers/"+id+"/"+operation).header("Idempotency-Key",key)
                .cookie(new jakarta.servlet.http.Cookie(AuthCookies.NAME,secret));
        if(token!=null)cookie.contentType("application/json").content("{\"token\":\""+token+"\"}");
        mvc.perform(cookie).andExpect(status().isForbidden());
        mvc.perform(cookie.with(csrf())).andExpect(status().isOk());
    }
    @Test void badDecisionTokenAndUnknownRequestRemainSafe() throws Exception {
        mvc.perform(post("/api/v1/crew-transfers/"+id+"/accept").header("Authorization","Bearer "+secret)
                .header("Idempotency-Key",key).contentType("application/json").content("{\"token\":\"bad\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        when(service.decide(any(),any(),any(),any(),any())).thenThrow(new AuthFailure(404,"TRANSFER_NOT_FOUND"));
        mvc.perform(post("/api/v1/crew-transfers/"+id+"/cancel").header("Authorization","Bearer "+secret)
                .header("Idempotency-Key",key)).andExpect(status().isNotFound());
    }
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void unsupportedGetIs405WithAllowAndWithoutUnexpectedErrorLog(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        mvc.perform(get(url()).header("Authorization","Bearer "+secret))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.title").value("Method Not Allowed"))
                .andExpect(header().string("Allow",org.hamcrest.Matchers.containsString("POST")));
        verifyNoInteractions(service);
        org.junit.jupiter.api.Assertions.assertFalse(output.getAll().contains("Unexpected API failure"));
    }
    @Test void unmappedUrlIs404InsteadOfInternalError() throws Exception {
        mvc.perform(get("/api/v1/no-such-resource").header("Authorization","Bearer "+secret))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
    @Test void unacceptableResponseTypeIs406InsteadOfInternalError() throws Exception {
        when(service.resend(any(),any(),any(),any())).thenReturn(new CrewTransferService.View(id,crew,actor.accountId(),UUID.randomUUID(),"PENDING",Instant.EPOCH,Instant.EPOCH));
        mvc.perform(post(url()+"/"+id+"/resend").header("Authorization","Bearer "+secret).header("Idempotency-Key",key)
                .accept("application/xml")).andExpect(status().isNotAcceptable()).andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
    }
    @Test void unsupportedRequestMediaTypeKeeps415Contract() throws Exception {
        mvc.perform(post(url()).header("Authorization","Bearer "+secret).header("Idempotency-Key",key)
                .contentType("text/plain").content("invalid input"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        verifyNoInteractions(service);
    }
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void unexpectedAcceptFailureLogsSafeStackAndSqlStateButKeepsGenericResponse(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String sensitive="private-token-password-and-row-hash";
        var sql=new java.sql.SQLException("Failing row contains "+sensitive,"23514");
        sql.setNextException(new java.sql.SQLException("Another secret: "+sensitive,"42703"));
        var failure=new org.springframework.dao.DataIntegrityViolationException(sensitive,sql);
        failure.addSuppressed(new IllegalStateException(sensitive));
        when(service.decide(any(),any(),any(),any(),any())).thenThrow(failure);
        mvc.perform(post("/api/v1/crew-transfers/"+id+"/accept").header("Authorization","Bearer "+secret)
                .header("Idempotency-Key",key).contentType("application/json")
                .content("{\"token\":\""+new SecretTokens().create()+"\"}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(sensitive))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SQLSTATE"))));
        org.junit.jupiter.api.Assertions.assertTrue(output.getAll().contains("Unexpected API failure"));
        org.junit.jupiter.api.Assertions.assertTrue(output.getAll().contains("DataIntegrityViolationException"));
        org.junit.jupiter.api.Assertions.assertTrue(output.getAll().contains("SQLSTATE=23514"));
        org.junit.jupiter.api.Assertions.assertTrue(output.getAll().contains("SQLSTATE=42703"));
        org.junit.jupiter.api.Assertions.assertTrue(output.getAll().contains("Caused by:"));
        org.junit.jupiter.api.Assertions.assertTrue(output.getAll().contains("Suppressed:"));
        org.junit.jupiter.api.Assertions.assertTrue(output.getAll().contains("at "));
        org.junit.jupiter.api.Assertions.assertFalse(output.getAll().contains(sensitive));
    }
}
