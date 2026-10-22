package cz.mh.rallye.rallye_backend.crews;

import java.time.Instant;
import java.util.UUID;
import cz.mh.rallye.rallye_backend.crews.api.CrewTransferController;
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

@WebMvcTest(CrewTransferController.class)
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
}
