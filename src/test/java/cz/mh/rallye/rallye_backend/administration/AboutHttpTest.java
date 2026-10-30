package cz.mh.rallye.rallye_backend.administration;
import java.time.Instant;
import java.util.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import cz.mh.rallye.rallye_backend.administration.api.AboutController;
import cz.mh.rallye.rallye_backend.administration.application.AboutService;
import cz.mh.rallye.rallye_backend.administration.persistence.AboutStore;
import cz.mh.rallye.rallye_backend.identity.api.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(AboutController.class) @Import({AuthConfiguration.class,AuthCookies.class,ApiErrors.class})
class AboutHttpTest {
 @Autowired MockMvc mvc;@MockitoBean AuthService auth;@MockitoBean AboutService service;
 final String token=new SecretTokens().create();final AuthService.Principal actor=new AuthService.Principal(UUID.randomUUID(),UUID.randomUUID(),false);
 void login(boolean admin){when(auth.authenticateReadOnly(token)).thenReturn(actor);when(auth.isAdministrator(actor)).thenReturn(admin);}
 AboutService.View view(){return new AboutService.View(new AboutService.Application("rallye-backend","0.2-test",Instant.parse("2026-10-30T08:00:00Z"),null),new AboutStore.Database("PostgreSQL","17.11","27","Race event lifecycle","SUCCESS"),new AboutService.RuntimeInfo("21","4.1.1",Instant.parse("2026-10-30T10:00:00Z"),"UTC",42),null);}
 @Test void unauthenticatedIs401AndUserIs403()throws Exception{mvc.perform(get("/api/v1/admin/about")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));login(false);mvc.perform(get("/api/v1/admin/about").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());verifyNoInteractions(service);}
 @Test void adminReceivesJsonNoStoreAndReadonlyAuthentication()throws Exception{login(true);when(service.read(actor)).thenReturn(view());mvc.perform(get("/api/v1/admin/about").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.application.backendVersion").value("0.2-test")).andExpect(jsonPath("$.application.gitCommit").isEmpty()).andExpect(jsonPath("$.database.migrationVersion").value("27")).andExpect(jsonPath("$.runtime.serverTime").value("2026-10-30T10:00:00Z")).andExpect(jsonPath("$.runtime.uptimeSeconds").value(42));verify(auth).authenticateReadOnly(token);verify(auth,never()).authenticate(token);}
 @Test void cookieGetRequiresNoCsrfAndRejectsAnyQueryParameters()throws Exception{login(true);when(service.read(actor)).thenReturn(view());mvc.perform(get("/api/v1/admin/about").cookie(new Cookie(AuthCookies.NAME,token))).andExpect(status().isOk());mvc.perform(get("/api/v1/admin/about").param("includeSecrets","true").header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());verify(service).read(actor);}
 @Test void unavailableDatabaseReturnsSafe503()throws Exception{login(true);when(service.read(actor)).thenThrow(new AuthFailure(503,"ABOUT_DATABASE_UNAVAILABLE",new IllegalStateException("DO_NOT_LEAK_JDBC_PASSWORD")));mvc.perform(get("/api/v1/admin/about").header("Authorization","Bearer "+token)).andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.code").value("ABOUT_DATABASE_UNAVAILABLE")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("DO_NOT_LEAK"))));}
}
