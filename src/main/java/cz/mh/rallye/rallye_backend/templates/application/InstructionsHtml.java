package cz.mh.rallye.rallye_backend.templates.application;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.*;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;

/** Canonical allowlisted HTML. Parsing never performs network access. */
public final class InstructionsHtml {
 private InstructionsHtml() {}
 public static final int MAX_BYTES=100*1024;
 private static final Pattern IMAGE=Pattern.compile("/api/v1/race-templates/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/images/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");
 public record Clean(String html,Set<UUID> images) { public Clean {images=Set.copyOf(images);} }
 public static String imageUrl(UUID template,UUID image){return "/api/v1/race-templates/"+template+"/images/"+image;}
 private static void size(String html){if(html.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new AuthFailure(400,"INSTRUCTIONS_HTML_TOO_LARGE");}
 public static Clean sanitize(UUID template,String raw) {
  if(raw==null)return new Clean(null,Set.of());size(raw);
  if(raw.indexOf('\0')>=0)throw new AuthFailure(400,"INVALID_INSTRUCTIONS_HTML");
  var safe=new Safelist().addTags("p","br","h1","h2","h3","h4","h5","h6","strong","b","em","i","u","s","del","ul","ol","li","a","img","table","thead","tbody","tfoot","tr","th","td")
   .addAttributes("a","href","title","target").addProtocols("a","href","https","http").addAttributes("img","src","alt","title");
  var doc=new Cleaner(safe).clean(Jsoup.parseBodyFragment(raw));
  doc.outputSettings().prettyPrint(false).charset(StandardCharsets.UTF_8).syntax(Document.OutputSettings.Syntax.html);
  for(var a:doc.select("a")) {
   if(!a.attr("href").matches("(?i)https?://[^\\s]+"))a.removeAttr("href");
   if(a.attr("target").equals("_blank"))a.attr("rel","noopener noreferrer");else a.removeAttr("target");
  }
  var ids=new HashSet<UUID>();
  for(var img:doc.select("img")) {
   var match=IMAGE.matcher(img.attr("src"));if(!match.matches()){img.remove();continue;}
   if(!UUID.fromString(match.group(1)).equals(template))throw new AuthFailure(400,"TEMPLATE_IMAGE_REFERENCE_INVALID");
   ids.add(UUID.fromString(match.group(2)));
  }
  String html=doc.body().html();size(html);return new Clean(html,ids);
 }
 public static Clean copy(UUID source,UUID target,String html,Map<UUID,UUID> mapping){
  var clean=sanitize(source,html);
  if(!Objects.equals(clean.html(),html)||!mapping.keySet().containsAll(clean.images()))throw new AuthFailure(503,"TEMPLATE_COPY_INCONSISTENT");
  if(html==null)return clean;
  var document=Jsoup.parseBodyFragment(html);
  document.outputSettings().prettyPrint(false).charset(StandardCharsets.UTF_8).syntax(Document.OutputSettings.Syntax.html);
  for(var img:document.select("img")){
   var match=IMAGE.matcher(img.attr("src"));
   if(!match.matches())throw new AuthFailure(503,"TEMPLATE_COPY_INCONSISTENT");
   img.attr("src",imageUrl(target,mapping.get(UUID.fromString(match.group(2)))));
  }
  return sanitize(target,document.body().html());
 }
 public static boolean hasContent(String html) {
  if(html==null)return false;var body=Jsoup.parseBodyFragment(html).body();
  return body.text().codePoints().anyMatch(c->!Character.isWhitespace(c)&&!Character.isSpaceChar(c)&&Character.getType(c)!=Character.FORMAT)
   ||body.select("img[src]").stream().anyMatch(e->IMAGE.matcher(e.attr("src")).matches());
 }
}
