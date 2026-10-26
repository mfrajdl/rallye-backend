package cz.mh.rallye.rallye_backend.templates;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import jakarta.servlet.http.Cookie;
import cz.mh.rallye.rallye_backend.templates.api.TemplateDuplicationController;
import cz.mh.rallye.rallye_backend.templates.application.*;
import cz.mh.rallye.rallye_backend.identity.api.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TemplateDuplicationController.class)
@Import({AuthConfiguration.class,AuthCookies.class,ApiErrors.class})
class TemplateDuplicationHttpTest {
 @Autowired MockMvc mvc;@MockitoBean AuthService auth;@MockitoBean TemplateDuplicationService service;
 final UUID source=UUID.randomUUID(),stage=UUID.randomUUID(),target=UUID.randomUUID(),key=UUID.randomUUID();
 final String secret=new SecretTokens().create();final AuthService.Principal actor=new AuthService.Principal(UUID.randomUUID(),UUID.randomUUID(),false);
 String full(){return "/api/v1/race-templates/"+source+"/duplicate";}String one(){return "/api/v1/race-templates/"+source+"/stages/"+stage+"/duplicate";}
 void login(boolean admin){when(auth.authenticate(secret)).thenReturn(actor);when(auth.isAdministrator(actor)).thenReturn(admin);}
 @Test void templateCopyReturns201LocationAndOnlyCreatedDto()throws Exception{
  login(true);when(service.duplicate(actor,source,key)).thenReturn(new TemplateService.Created(target,target,0));
  mvc.perform(post(full()).header("Authorization","Bearer "+secret).header("Idempotency-Key",key)).andExpect(status().isCreated()).andExpect(header().string("Location","/api/v1/race-templates/"+target)).andExpect(jsonPath("$",org.hamcrest.Matchers.aMapWithSize(3))).andExpect(jsonPath("$.id").value(target.toString())).andExpect(jsonPath("$.templateId").value(target.toString())).andExpect(jsonPath("$.version").value(0));
 }
 @Test void stageCopyUsesRequiredVersionAndReturnsNewId()throws Exception{
  login(true);when(service.duplicateStage(actor,source,stage,7,key)).thenReturn(new TemplateService.Created(target,source,8));
  mvc.perform(post(one()).header("Authorization","Bearer "+secret).header("Idempotency-Key",key).contentType("application/json").content("{\"version\":7}")).andExpect(status().isCreated()).andExpect(header().string("Location","/api/v1/race-templates/"+source+"/stages/"+target)).andExpect(jsonPath("$.version").value(8));
 }
 @Test void requiresAdminOnBothEndpoints()throws Exception{
  mvc.perform(post(full()).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isUnauthorized());login(false);
  mvc.perform(post(full()).header("Authorization","Bearer "+secret).header("Idempotency-Key",key)).andExpect(status().isForbidden());
  mvc.perform(post(one()).header("Authorization","Bearer "+secret).header("Idempotency-Key",key).contentType("application/json").content("{\"version\":7}")).andExpect(status().isForbidden());verifyNoInteractions(service);
 }
 @Test void cookieMutationsRequireCsrf()throws Exception{
  login(true);for(String url:new String[]{full(),one()})mvc.perform(post(url).cookie(new Cookie(AuthCookies.NAME,secret)).header("Idempotency-Key",key).contentType("application/json").content("{\"version\":7}")).andExpect(status().isForbidden());verifyNoInteractions(service);
 }
 @Test void rejectsMissingKeysAndMalformedVersionsAndSecretFields()throws Exception{
  login(true);mvc.perform(post(full()).header("Authorization","Bearer "+secret)).andExpect(status().isBadRequest());
  for(String body:new String[]{"{}","{\"version\":null}","{\"version\":-1}","{\"version\":1.5}","{\"version\":7,\"codeHash\":\"secret\"}"})mvc.perform(post(one()).header("Authorization","Bearer "+secret).header("Idempotency-Key",key).contentType("application/json").content(body)).andExpect(status().isBadRequest());verifyNoInteractions(service);
 }
 @Test void existing404And409ErrorsAreKept()throws Exception{
  login(true);when(service.duplicate(actor,source,key)).thenThrow(new AuthFailure(404,"TEMPLATE_NOT_FOUND"));mvc.perform(post(full()).header("Authorization","Bearer "+secret).header("Idempotency-Key",key)).andExpect(status().isNotFound());
  when(service.duplicateStage(actor,source,stage,7,key)).thenThrow(new AuthFailure(409,"IDEMPOTENCY_CONFLICT"));mvc.perform(post(one()).header("Authorization","Bearer "+secret).header("Idempotency-Key",key).contentType("application/json").content("{\"version\":7}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
 }
}
