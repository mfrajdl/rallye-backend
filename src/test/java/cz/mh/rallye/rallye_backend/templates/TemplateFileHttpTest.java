package cz.mh.rallye.rallye_backend.templates;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import jakarta.servlet.http.Cookie;
import cz.mh.rallye.rallye_backend.templates.api.TemplateFileController;
import cz.mh.rallye.rallye_backend.templates.application.*;
import cz.mh.rallye.rallye_backend.identity.api.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(TemplateFileController.class)
@Import({AuthConfiguration.class,AuthCookies.class,ApiErrors.class})
class TemplateFileHttpTest {
 @Autowired MockMvc mvc;@MockitoBean AuthService auth;@MockitoBean TemplateFileService service;
 final UUID id=UUID.randomUUID();final String secret=new SecretTokens().create();final AuthService.Principal actor=new AuthService.Principal(UUID.randomUUID(),UUID.randomUUID(),false);
 String root(){return "/api/v1/race-templates/"+id+"/files";}
 void login(boolean admin){when(auth.authenticate(secret)).thenReturn(actor);when(auth.isAdministrator(actor)).thenReturn(admin);}
 @Test void allFileKindsAreAdminOnly()throws Exception{mvc.perform(get(root())).andExpect(status().isUnauthorized());login(false);mvc.perform(get(root()).header("Authorization","Bearer "+secret)).andExpect(status().isForbidden());mvc.perform(get(root()+"/x.pdf").header("Authorization","Bearer "+secret)).andExpect(status().isForbidden());verifyNoInteractions(service);}
 @Test void cookieMutationsRequireCsrfAndUploadRequiresVersionAndKey()throws Exception{
  login(true);var file=new MockMultipartFile("file","x.md","text/markdown",new byte[0]);mvc.perform(multipart(root()).file(file).param("version","0").cookie(new Cookie(AuthCookies.NAME,secret)).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isForbidden());
  mvc.perform(delete(root()+"/x.md").param("version","0").cookie(new Cookie(AuthCookies.NAME,secret))).andExpect(status().isForbidden());mvc.perform(multipart(root()).file(file).header("Authorization","Bearer "+secret)).andExpect(status().isBadRequest());verifyNoInteractions(service);
 }
 @Test void uploadAndLibraryUseTheFrozenContract()throws Exception{
  login(true);UUID key=UUID.randomUUID();String url=ImageNames.fileUrl(id,"pokyny.md");var result=new TemplateFileService.Uploaded("pokyny.md",id,url,7,"DRAFT",List.of());
  when(service.upload(eq(actor),eq(id),eq(7L),eq(key),eq("pokyny.md"),any())).thenReturn(result);
  mvc.perform(multipart(root()).file(new MockMultipartFile("file","pokyny.md","text/markdown","# Text".getBytes(StandardCharsets.UTF_8))).param("version","7").header("Authorization","Bearer "+secret).header("Idempotency-Key",key)).andExpect(status().isCreated()).andExpect(header().string("Location",url)).andExpect(jsonPath("$.version").value(7)).andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.missingReferences").isArray());
  var file=new TemplateFileService.FileView("pokyny.md",id,"MARKDOWN",url,"text/markdown",6,null,null,java.time.Instant.EPOCH,false);
  when(service.list(actor,id)).thenReturn(new TemplateFileService.Library(id,7,"DRAFT",List.of(file),List.of(),new TemplateFileContent(102400,10485760).limits()));
  mvc.perform(get(root()).header("Authorization","Bearer "+secret)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].kind").value("MARKDOWN")).andExpect(jsonPath("$.items[0].width").isEmpty()).andExpect(jsonPath("$.items[0].id").doesNotExist()).andExpect(jsonPath("$.limits.pdfMaxBytes").value(10485760));
 }
 @Test void markdownIsStandaloneHtmlWithPrivacyAndCsp()throws Exception{
  login(true);String name="České pokyny.md";byte[] html=TemplateDocumentHtml.page(name,"<h1 id=\"sekce\">Sekce</h1>").getBytes(StandardCharsets.UTF_8);
  when(service.content(actor,id,name)).thenReturn(new TemplateFileService.Content(html,"text/html; charset=UTF-8",name,false,true));
  mvc.perform(get(java.net.URI.create(ImageNames.fileUrl(id,name))).header("Authorization","Bearer "+secret)).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/html")).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Referrer-Policy","no-referrer")).andExpect(header().string("X-Content-Type-Options","nosniff")).andExpect(header().string("Content-Security-Policy",TemplateDocumentHtml.csp())).andExpect(content().bytes(html));
 }
 @Test void pdfIsOnlyAnAttachmentAndDeleteIs204()throws Exception{
  login(true);when(service.content(actor,id,"rules.pdf")).thenReturn(new TemplateFileService.Content(new byte[]{1},"application/pdf","rules.pdf",true,false));
  mvc.perform(get(root()+"/rules.pdf").header("Authorization","Bearer "+secret)).andExpect(status().isOk()).andExpect(content().contentType("application/pdf")).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.startsWith("attachment;"))).andExpect(header().string("Content-Security-Policy","default-src 'none'; sandbox"));
  mvc.perform(delete(root()+"/rules.pdf").param("version","7").header("Authorization","Bearer "+secret)).andExpect(status().isNoContent());verify(service).delete(actor,id,"rules.pdf",7);
 }
}
