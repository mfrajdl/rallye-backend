package cz.mh.rallye.rallye_backend.races;
import java.util.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import cz.mh.rallye.rallye_backend.races.api.LifecycleController;
import cz.mh.rallye.rallye_backend.races.application.LifecycleService;
import cz.mh.rallye.rallye_backend.participation.api.MyRegistrationsController;
import cz.mh.rallye.rallye_backend.participation.application.RegistrationService;
import cz.mh.rallye.rallye_backend.identity.api.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
@WebMvcTest({LifecycleController.class,MyRegistrationsController.class})
@Import({AuthConfiguration.class,AuthCookies.class,ApiErrors.class})
class LifecycleHttpTest {
 @Autowired MockMvc mvc;@MockitoBean AuthService auth;@MockitoBean LifecycleService lifecycle;@MockitoBean RegistrationService registrations;
 final String token=new SecretTokens().create();final UUID id=UUID.randomUUID(),key=UUID.randomUUID();final AuthService.Principal actor=new AuthService.Principal(UUID.randomUUID(),UUID.randomUUID(),false);
 final String base="/api/v1/race-events/"+id;
 void login(boolean admin){when(auth.authenticate(token)).thenReturn(actor);when(auth.authenticateReadOnly(token)).thenReturn(actor);when(auth.isAdministrator(actor)).thenReturn(admin);}
 @Test void allCommandsRequireAdminAndAuthentication()throws Exception{for(String op:List.of("start","end","resume")){mvc.perform(post(base+"/"+op).header("X-Rallye-Client","mobile").contentType("application/json").content("{\"version\":0}")).andExpect(status().isUnauthorized());login(false);mvc.perform(post(base+"/"+op).header("Authorization","Bearer "+token).header("Idempotency-Key",key).contentType("application/json").content("{\"version\":0}")).andExpect(status().isForbidden());}verifyNoInteractions(lifecycle);}
 @Test void cookieCommandsRequireCsrf()throws Exception{login(true);mvc.perform(post(base+"/start").cookie(new Cookie(AuthCookies.NAME,token)).header("Idempotency-Key",key).contentType("application/json").content("{\"version\":0}")).andExpect(status().isForbidden());verifyNoInteractions(lifecycle);mvc.perform(post(base+"/start").cookie(new Cookie(AuthCookies.NAME,token)).with(csrf().asHeader()).header("Idempotency-Key",key).contentType("application/json").content("{\"version\":0}")).andExpect(status().isOk());verify(lifecycle).command(actor,id,key,0,"STARTED",null);}
 @Test void strictVersionAndExplicitNullableResumeEnd()throws Exception{login(true);for(String body:List.of("{}","{\"version\":-1}","{\"version\":0.5}","{\"version\":9223372036854775808}","{\"version\":0,\"unexpected\":true}"))mvc.perform(post(base+"/start").header("Authorization","Bearer "+token).header("Idempotency-Key",key).contentType("application/json").content(body)).andExpect(status().isBadRequest());for(String body:List.of("{\"version\":0}","{\"version\":0,\"endAt\":4}","{\"version\":0,\"endAt\":\"2026-10-30T10:00:00\"}"))mvc.perform(post(base+"/resume").header("Authorization","Bearer "+token).header("Idempotency-Key",key).contentType("application/json").content(body)).andExpect(status().isBadRequest());verifyNoInteractions(lifecycle);mvc.perform(post(base+"/resume").header("Authorization","Bearer "+token).header("Idempotency-Key",key).contentType("application/json").content("{\"version\":0,\"endAt\":null}")).andExpect(status().isOk());verify(lifecycle).command(actor,id,key,0,"RESUMED",null);}
 @Test void historyAndPeriodsStayAdminOnlyAndUseNoStore()throws Exception{login(false);mvc.perform(get(base+"/lifecycle-history").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());mvc.perform(get(base+"/running-periods").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());login(true);when(lifecycle.history(actor,id,0,50)).thenReturn(new LifecycleService.Page<>(List.of(),0,50,false));mvc.perform(get(base+"/lifecycle-history").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.items").isArray()).andExpect(jsonPath("$.hasMore").value(false));verify(auth,times(3)).authenticateReadOnly(token);}
 @Test void summaryIsOwnOnlyAllowsUserAndRejectsParameters()throws Exception{login(false);when(registrations.summary(actor)).thenReturn(new RegistrationService.Summary(3));mvc.perform(get("/api/v1/me/registrations/summary").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(content().json("{\"activeCount\":3}"));mvc.perform(get("/api/v1/me/registrations/summary").param("accountId",UUID.randomUUID().toString()).header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());verify(registrations).summary(actor);}
 @Test void conflictRetainsSafeErrorContract()throws Exception{login(true);when(lifecycle.command(any(),any(),any(),anyLong(),anyString(),any())).thenThrow(new AuthFailure(409,"RACE_EVENT_VERSION_CONFLICT"));mvc.perform(post(base+"/end").header("Authorization","Bearer "+token).header("Idempotency-Key",key).contentType("application/json").content("{\"version\":0}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RACE_EVENT_VERSION_CONFLICT")).andExpect(header().string("Cache-Control","no-store"));}
}
