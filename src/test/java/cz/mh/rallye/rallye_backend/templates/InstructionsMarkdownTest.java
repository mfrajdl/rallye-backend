package cz.mh.rallye.rallye_backend.templates;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import cz.mh.rallye.rallye_backend.templates.application.*;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import static org.junit.jupiter.api.Assertions.*;
class InstructionsMarkdownTest {
 final UUID template=UUID.randomUUID();final String name="mapa trasy č. 1.png";final Map<String,String> library=Map.of(ImageNames.key(name),name);
 @Test void supportsCommonMarkAndGfm(){String source="# Title\n\n**bold** *italic* ~~old~~\n\n> Quote\n\n---\n\n- item\n- [x] done\n\n1. first\n\n| A | B |\n|---|---|\n| x | y |\n\n`inline`\n\n```java\nint x = 1;\n```\n";
  var parsed=InstructionsMarkdown.analyze(template,source);String html=InstructionsMarkdown.render(template,source,Map.of());assertNotNull(parsed.node());for(String tag:List.of("<h1 ","<strong>","<em>","<del>","<blockquote>","<hr>","<ul>","<ol>","<table>","<code>","<pre>","checkbox","disabled"))assertTrue(html.contains(tag),tag);}
 @Test void bothImageSyntaxesAndReferenceImagesResolveSameName(){String url=ImageNames.url(template,name);String relative=url.substring(url.lastIndexOf('/')+1);for(String source:List.of("![Map]("+relative+")","![Map]("+url+")","![Map][m]\n\n[m]: "+relative)){
  assertEquals(Set.of(ImageNames.key(name)),InstructionsMarkdown.analyze(template,source).imageKeys());assertTrue(InstructionsMarkdown.render(template,source,library).contains("src=\""+url+"\""));}}
 @ParameterizedTest @ValueSource(strings={"https://evil.test/a.png","//evil.test/a.png","data:image/png;base64,x","javascript:alert","file:/x","../x.png","%2e%2e%2fx.png","x%252fy.png","x.png?q=1","x.png#fragment","x%5cy.png"})
 void rejectsUnsafeImages(String path){assertThrows(AuthFailure.class,()->InstructionsMarkdown.analyze(template,"![x]("+path+")"));}
 @Test void foreignTemplateAndMissingImageAreRejected(){assertThrows(AuthFailure.class,()->InstructionsMarkdown.analyze(template,"![x]("+ImageNames.url(UUID.randomUUID(),name)+")"));assertTrue(InstructionsMarkdown.render(template,"![x](missing.png)",library).contains("/images/missing.png"));}
 @Test void rawHtmlAndDangerousLinksCannotExecute(){String raw="<script>alert(1)</script>\n\n<img src='https://evil.test/a' onerror='bad()'>\n\n<iframe src='x'></iframe>";assertThrows(AuthFailure.class,()->InstructionsMarkdown.analyze(template,"[bad](javascript:alert)"));String html=InstructionsMarkdown.render(template,raw,Map.of());assertFalse(html.contains("<script"));assertFalse(html.contains("<img"));assertFalse(html.contains("<iframe"));assertFalse(html.contains("href=\"javascript:"));assertTrue(html.contains("&lt;script&gt;"));}
 @Test void codeAndEscapedImageSyntaxAreNotReferences(){String raw="`![x](evil.png)`\n\n```\n![x](data:x)\n```\n";assertTrue(InstructionsMarkdown.analyze(template,raw).imageKeys().isEmpty());}
 @Test void copyPreservesRelativeSourceAndOnlyChangesExplicitImageNodes(){UUID target=UUID.randomUUID();String url=ImageNames.url(template,name);String relative=url.substring(url.lastIndexOf('/')+1);String raw="# Title  \n\n![relative]("+relative+")\n\n![explicit]("+url+")\n\n`"+url+"`\n";String copy=InstructionsMarkdown.copy(template,target,raw);assertTrue(copy.startsWith("# Title  \n\n![relative]("+relative+")"));assertTrue(copy.contains(ImageNames.url(target,name)));assertTrue(copy.contains("`"+url+"`"));assertEquals("x",InstructionsMarkdown.copy(template,target,"x"));assertEquals(2,InstructionsMarkdown.analyze(target,copy).images().size());}
 @Test void copiesExplicitReferenceStyleImageWithoutChangingOtherText(){UUID target=UUID.randomUUID();String raw="![map][ref]\n\n[ref]: "+ImageNames.url(template,name)+"\n";String copy=InstructionsMarkdown.copy(template,target,raw);assertTrue(copy.contains(ImageNames.url(target,name)));assertEquals(1,InstructionsMarkdown.analyze(target,copy).images().size());}
 @Test void limitIsUtf8AndSourceIsNotCanonicalized(){String raw="# Title  \r\n\r\n Text & <b>raw</b>  \r\n";InstructionsMarkdown.analyze(template,raw);assertEquals(raw,InstructionsMarkdown.copy(template,UUID.randomUUID(),raw));assertThrows(AuthFailure.class,()->InstructionsMarkdown.analyze(template,"č".repeat(51201)));assertDoesNotThrow(()->InstructionsMarkdown.analyze(template,"x".repeat(102400)));}
 @ParameterizedTest @ValueSource(strings={""," ","# ","<p></p>","\n\n","---"}) void emptyStructureIsNotReady(String source){assertFalse(InstructionsMarkdown.hasContent(source));}
}
