package cz.mh.rallye.rallye_backend.templates;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import jakarta.servlet.http.Cookie;
import cz.mh.rallye.rallye_backend.templates.api.TemplateImageController;
import cz.mh.rallye.rallye_backend.templates.application.*;
import cz.mh.rallye.rallye_backend.identity.api.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TemplateImageController.class)
@Import({AuthConfiguration.class,AuthCookies.class,ApiErrors.class})
class TemplateImageHttpTest {
 @Autowired MockMvc mvc;@MockitoBean AuthService auth;@MockitoBean TemplateImageService service;
 final UUID id=UUID.randomUUID(),image=UUID.randomUUID();final String secret=new SecretTokens().create();
 final AuthService.Principal principal=new AuthService.Principal(UUID.randomUUID(),UUID.randomUUID(),false);
 String url(){return InstructionsHtml.imageUrl(id,image);}
 void login(boolean admin){when(auth.authenticate(secret)).thenReturn(principal);when(auth.isAdministrator(principal)).thenReturn(admin);}
 @Test void imagesArePrivateAndAllRoutesRequireAdmin()throws Exception{
  mvc.perform(get(url())).andExpect(status().isUnauthorized());login(false);
  mvc.perform(get(url()).header("Authorization","Bearer "+secret)).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/race-templates/"+id+"/images").header("Authorization","Bearer "+secret)).andExpect(status().isForbidden());verifyNoInteractions(service);
 }
 @Test void downloadSetsActualTypePrivacyAndNosniff()throws Exception{
  login(true);when(service.content(principal,id,image)).thenReturn(new TemplateImageService.Content(new byte[]{1,2},"image/png"));
  mvc.perform(get(url()).header("Authorization","Bearer "+secret)).andExpect(status().isOk()).andExpect(content().contentType("image/png")).andExpect(header().string("X-Content-Type-Options","nosniff")).andExpect(header().string("Cache-Control","no-store")).andExpect(content().bytes(new byte[]{1,2}));
 }
 @Test void uploadRequiresVersionAndIdempotencyKeyAndUsesGeneratedLocation()throws Exception{
  login(true);var file=new MockMultipartFile("file","../../unsafe.jpg","text/plain",new byte[]{1});
  mvc.perform(multipart("/api/v1/race-templates/"+id+"/images").file(file).header("Authorization","Bearer "+secret)).andExpect(status().isBadRequest());
  UUID key=UUID.randomUUID();when(service.upload(eq(principal),eq(id),eq(3L),eq(key),any())).thenReturn(new TemplateService.Created(image,id,3));
  mvc.perform(multipart("/api/v1/race-templates/"+id+"/images").file(file).param("version","3").header("Authorization","Bearer "+secret).header("Idempotency-Key",key)).andExpect(status().isCreated()).andExpect(header().string("Location",url()));
 }
 @Test void cookieUploadAndDeleteNeedCsrf()throws Exception{
  login(true);mvc.perform(multipart("/api/v1/race-templates/"+id+"/images").file(new MockMultipartFile("file",new byte[]{1})).cookie(new Cookie(AuthCookies.NAME,secret)).param("version","0").header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isForbidden());
  mvc.perform(delete(url()).param("version","0").cookie(new Cookie(AuthCookies.NAME,secret))).andExpect(status().isForbidden());verifyNoInteractions(service);
 }
 @Test void deleteUsedImageReturnsConflictAndMissingImageIs404()throws Exception{
  login(true);doThrow(new AuthFailure(409,"TEMPLATE_IMAGE_IN_USE")).when(service).delete(principal,id,image,3);
  mvc.perform(delete(url()).param("version","3").header("Authorization","Bearer "+secret)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TEMPLATE_IMAGE_IN_USE"));
  when(service.content(principal,id,image)).thenThrow(new AuthFailure(404,"TEMPLATE_IMAGE_NOT_FOUND"));
  mvc.perform(get(url()).header("Authorization","Bearer "+secret)).andExpect(status().isNotFound());
 }
}
