package cz.mh.rallye.rallye_backend.templates;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import cz.mh.rallye.rallye_backend.templates.application.*;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore.*;
import cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import static org.junit.jupiter.api.Assertions.*;
public class TemplateFilesTest {
 @TempDir Path dir;final UUID template=UUID.randomUUID();
 Image md(String name,String text){return new Image(UUID.randomUUID(),template,name,ImageNames.key(name),"text/markdown",text.getBytes(StandardCharsets.UTF_8).length,null,null,Instant.EPOCH,false,text);}
 @Test void allowedLinksResolveAndReceiveSafeTargets(){
  String source="[web](https://example.cz) [plain](http://example.cz) [mail](mailto:info@example.cz) [tel](tel:+420123456789) [anchor](#sekce) [doc](pokyny.md#sekce) [pdf](pravidla.pdf) [map](mapa.jpg)\n\n## Sekce\n\n## Sekce";
  String html=InstructionsMarkdown.render(template,source,Map.of());assertTrue(html.contains("href=\"tel:+420123456789\""));assertTrue(html.contains("href=\"mailto:info@example.cz\""));assertTrue(html.contains("href=\"#sekce\""));assertTrue(html.contains("id=\"sekce-1\""));assertTrue(html.contains(ImageNames.fileUrl(template,"pokyny.md")+"#sekce"));assertEquals(5,org.jsoup.Jsoup.parse(html).select("a[target=_blank][rel='noopener noreferrer']").size());
 }
 @ParameterizedTest @ValueSource(strings={"javascript:alert","data:text/html,x","file:/etc/passwd","//evil.test/x","../x.md","%2e%2e%2fx.md","a.md?q=1","x%252f.md"})
 void unsafeLinksFailClosed(String href){assertThrows(AuthFailure.class,()->InstructionsMarkdown.analyze(template,"[x]("+href+")"));}
 @Test void crossTemplateAndEncodedNamesAndNestedCopy(){
  String name="České pokyny.md";String encoded=java.net.URLEncoder.encode(name,StandardCharsets.UTF_8).replace("+","%20");assertTrue(InstructionsMarkdown.render(template,"[x]("+encoded+")",Map.of()).contains(ImageNames.fileUrl(template,name)));
  assertThrows(AuthFailure.class,()->InstructionsMarkdown.analyze(template,"[x]("+ImageNames.fileUrl(UUID.randomUUID(),name)+")"));
  UUID target=UUID.randomUUID();String source="[![map]("+ImageNames.url(template,"mapa.png")+")]("+ImageNames.fileUrl(template,"pokyny.md")+")";String copy=InstructionsMarkdown.copy(template,target,source);assertFalse(copy.contains(template.toString()));assertEquals(2,InstructionsMarkdown.analyze(target,copy).references().size());
 }
 @Test void cyclesAndForwardReferencesAreNotRecursive(){
  var a=md("a.md","[B](b.md) ![Map](mapa.png)");var b=md("b.md","[A](a.md)");var files=List.of(a,b);
  var warnings=TemplateFileReferences.warnings(template,List.of(),files);assertEquals(1,warnings.size());assertEquals("mapa.png",warnings.getFirst().targetFileName());assertTrue(TemplateFileReferences.used(template,a,List.of(),files));assertTrue(TemplateFileReferences.used(template,b,List.of(),files));
  assertFalse(TemplateFileReferences.used(template,md("self.md","[self](self.md)"),List.of(),List.of()));
 }
 @Test void ordinaryLinksAlsoPreventDeletionAndPdfCannotBecomeImage(){
  var pdf=new Image(UUID.randomUUID(),template,"rules.pdf","RULES.PDF","application/pdf",20,null,null,Instant.EPOCH,false);var stage=new Stage(UUID.randomUUID(),template,1,"Stage",0L,0L,"[Rules](rules.pdf)",StageConfiguration.empty());assertTrue(TemplateFileReferences.used(template,pdf,List.of(stage),List.of(pdf)));
  assertThrows(AuthFailure.class,()->TemplateFileReferences.validate(InstructionsMarkdown.analyze(template,"![x](rules.pdf)"),List.of(pdf)));
 }
 @Test void markdownIsStrictUtf8AndLimitsAreEnforced(){
  var formats=new TemplateFileContent(20,1000);var storage=new TemplateImageFiles(dir.toString());String text="# Česky";var file=formats.validate("pokyny.md",new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),storage.files);assertEquals(text,file.markdown());assertNull(file.width());
  assertEquals(413,assertThrows(AuthFailure.class,()->formats.validate("x.md",new ByteArrayInputStream(new byte[21]),storage.files)).status());
  assertEquals("INVALID_FILE",assertThrows(AuthFailure.class,()->formats.validate("x.md",new ByteArrayInputStream(new byte[]{(byte)0xff,0}),storage.files)).code());assertThrows(AuthFailure.class,()->formats.validate("x.svg",new ByteArrayInputStream(new byte[0]),storage.files));assertThrows(IllegalArgumentException.class,()->new TemplateFileContent(0,1000));
 }
 public static byte[] pdf()throws Exception{try(var doc=new org.apache.pdfbox.pdmodel.PDDocument();var out=new ByteArrayOutputStream()){doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());doc.save(out);return out.toByteArray();}}
 @Test void pdfIsParsedAndImageExtensionMustMatchRealContent()throws Exception{
  var formats=new TemplateFileContent(102400,10485760);var storage=new TemplateImageFiles(dir.toString());byte[] pdf=pdf();assertArrayEquals(pdf,formats.validate("pravidla.pdf",new ByteArrayInputStream(pdf),storage.files).bytes());
  assertThrows(AuthFailure.class,()->formats.validate("fake.pdf",new ByteArrayInputStream("%PDF-1.7 garbage".getBytes(StandardCharsets.US_ASCII)),storage.files));assertThrows(AuthFailure.class,()->formats.validate("fake.jpg",new ByteArrayInputStream(cz.mh.rallye.rallye_backend.crews.PhotoFilesTest.image("png",2,2)),storage.files));
 }
 @Test void standaloneHtmlHasNoScriptsAndAnExactStyleHash(){String html=TemplateDocumentHtml.page("<bad>",InstructionsMarkdown.render(template,"# Title\n\n<script>bad()</script>",Map.of()));assertTrue(html.startsWith("<!doctype html>"));assertFalse(html.contains("<script>"));assertTrue(html.contains("&lt;bad&gt;"));assertTrue(TemplateDocumentHtml.csp().contains("script-src 'none'"));assertTrue(TemplateDocumentHtml.csp().contains("style-src 'sha256-"));}

 @Test void documentBomAndCzechHeadingsAreHandled(){String source="\ufeff# Sekce\n\n[Zpět](#sekce)";var html=InstructionsMarkdown.render(template,source,Map.of());assertTrue(html.contains("id=\"sekce\""));}

 @Test void copyPreservesBomAndOnlyRemapsExplicitLinks(){UUID target=UUID.randomUUID();String source="\ufeff# Nadpis\n\n[PDF]("+ImageNames.fileUrl(template,"rules.pdf")+")\n";String copy=InstructionsMarkdown.copy(template,target,source);assertTrue(copy.startsWith("\ufeff# Nadpis\n\n"));assertTrue(copy.contains(ImageNames.fileUrl(target,"rules.pdf")));assertFalse(copy.contains(template.toString()));assertEquals(1,InstructionsMarkdown.analyze(target,copy).references().size());}

 @Test void previouslyStoredUnsafeLinksRemainReadableButCannotBeSubmittedAgain(){String source="[Old](javascript:alert)";assertThrows(AuthFailure.class,()->InstructionsMarkdown.analyze(template,source));String html=InstructionsMarkdown.render(template,source,Map.of());assertFalse(html.contains("javascript:"));assertTrue(html.contains("Old"));assertTrue(TemplateFileReferences.warnings(template,List.of(new Stage(UUID.randomUUID(),template,1,"A",0L,0L,source,StageConfiguration.empty())),List.of()).isEmpty());}
}
