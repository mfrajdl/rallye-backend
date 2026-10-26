package cz.mh.rallye.rallye_backend.templates;

import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import cz.mh.rallye.rallye_backend.templates.application.InstructionsHtml;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import static org.junit.jupiter.api.Assertions.*;

class InstructionsHtmlTest {
 final UUID template=UUID.randomUUID(),image=UUID.randomUUID();
 @Test void preservesFormattingTablesLinksAndIsIdempotent(){
  String raw="<h1>Title</h1><p><strong>Bold</strong><em>Italic</em><u>U</u><s>S</s><del>D</del><br></p><ul><li>A</li></ul><ol><li>B</li></ol><table><tr><td>C</td></tr></table><a href='https://example.test/x?a=1&amp;b=2' target='_blank'>link</a>";
  var clean=InstructionsHtml.sanitize(template,raw);assertTrue(clean.html().contains("<strong>Bold</strong>"));assertTrue(clean.html().contains("noopener noreferrer"));assertTrue(clean.html().contains("<table>"));
  assertEquals(clean,InstructionsHtml.sanitize(template,clean.html()));
 }
 @ParameterizedTest @ValueSource(strings={"javascript:alert(1)","java&#x73;cript:alert(1)","data:text/html,x","vbscript:x","//evil.test/x","/relative"})
 void stripsUnsafeLinkSchemes(String url){var clean=InstructionsHtml.sanitize(template,"<a href='"+url+"' onclick='x()' style='color:red'>Text</a>");assertFalse(clean.html().contains("href="));assertFalse(clean.html().contains("onclick"));assertFalse(clean.html().contains("style="));}
 @Test void removesExecutableMarkupAndAllExternalImages(){
  var c=InstructionsHtml.sanitize(template,"<script>alert(1)</script><iframe src='https://evil.test'></iframe><object>x</object><form><input></form><svg onload='bad()'></svg><img src='data:image/png;base64,x'><img src='https://evil.test/a.png'><img src='//evil.test/x'><p style='x' onerror='bad()'>Safe</p>");
  assertEquals("x<p>Safe</p>",c.html());assertTrue(c.images().isEmpty());
 }
 @Test void internalImagesAreCanonicalDeduplicatedAndForeignTemplateIsRejected(){
  String img="<img src='"+InstructionsHtml.imageUrl(template,image)+"' alt='Map' onerror='x'>";
  var c=InstructionsHtml.sanitize(template,img+img);assertEquals(Set.of(image),c.images());assertFalse(c.html().contains("onerror"));assertEquals(c,InstructionsHtml.sanitize(template,c.html()));
  assertEquals("TEMPLATE_IMAGE_REFERENCE_INVALID",assertThrows(AuthFailure.class,()->InstructionsHtml.sanitize(UUID.randomUUID(),img)).code());
 }
 @ParameterizedTest @ValueSource(strings={""," ","<p></p>","<p><br></p>","<p>&nbsp; &#8203;</p>","<script>bad()</script>"})
 void emptyMarkupIsNotReady(String raw){assertFalse(InstructionsHtml.hasContent(InstructionsHtml.sanitize(template,raw).html()));}
 @Test void textOrImageOnlyIsContent(){assertTrue(InstructionsHtml.hasContent("<p>Text</p>"));assertTrue(InstructionsHtml.hasContent(InstructionsHtml.sanitize(template,"<img src='"+InstructionsHtml.imageUrl(template,image)+"'>").html()));}
 @Test void byteLimitsApplyBeforeAndAfterCanonicalization(){
  assertThrows(AuthFailure.class,()->InstructionsHtml.sanitize(template,"č".repeat(51201)));
  assertThrows(AuthFailure.class,()->InstructionsHtml.sanitize(template,"&".repeat(30000)));
  var c=InstructionsHtml.sanitize(template,"x".repeat(102400));assertEquals(102400,c.html().getBytes(StandardCharsets.UTF_8).length);
 }
 @Test void copyingRemapsOnlyImageSourcesAndKeepsOtherContent(){
  UUID target=UUID.randomUUID(),copy=UUID.randomUUID();String url=InstructionsHtml.imageUrl(template,image);
  String html="<p>"+image+"</p><a href=\"https://example.test/"+image+"\">link</a><img src=\""+url+"\" alt=\""+image+"\">";
  var result=InstructionsHtml.copy(template,target,html,Map.of(image,copy));assertEquals(Set.of(copy),result.images());assertTrue(result.html().contains(InstructionsHtml.imageUrl(target,copy)));assertFalse(result.html().contains(url));assertTrue(result.html().contains("alt=\""+image+"\""));assertTrue(result.html().contains("https://example.test/"+image));
  assertThrows(AuthFailure.class,()->InstructionsHtml.copy(template,target,html,Map.of()));
 }
}
