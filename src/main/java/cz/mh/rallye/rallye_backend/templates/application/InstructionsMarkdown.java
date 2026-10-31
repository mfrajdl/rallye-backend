package cz.mh.rallye.rallye_backend.templates.application;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.commonmark.Extension;
import org.commonmark.node.*;
import org.commonmark.parser.*;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.markdown.MarkdownRenderer;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.jsoup.Jsoup;
import org.jsoup.safety.*;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;

/** Source is immutable Markdown; only a fresh AST is modified for rendering/copying. */
public final class InstructionsMarkdown {
 private InstructionsMarkdown(){}
 public static final int MAX_BYTES=102400;
 private static final List<Extension> EXT=List.of(TablesExtension.create(),StrikethroughExtension.create(),TaskListItemsExtension.create(),AutolinkExtension.create());
 private static final Parser PARSER=Parser.builder().extensions(EXT).includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build();
 public record Reference(String fileName,boolean image,String fragment) {public String key(){return ImageNames.key(fileName);}}
 public record Parsed(Node node,Map<Image,String> images,Map<Node,Reference> references,int sourceOffset){
  public Set<String> imageKeys(){return Set.copyOf(images.values());}
 }
 public static Parsed analyze(UUID template,String source){validate(source);return analyzeDocument(template,source);}
 public static Parsed analyzeDocument(UUID template,String source){return parse(template,source,false);}
 public static Parsed analyzeStored(UUID template,String source){return parse(template,source,true);}
 public static Parsed analyzeEvent(UUID event,String source){return parse(event,source,false,true);}
 private static Parsed parse(UUID template,String source,boolean stored){return parse(template,source,stored,false);}
 public static Parsed analyzeSimulation(UUID id,String source){return parse(id,source,false,false,true);}
 private static Parsed parse(UUID template,String source,boolean stored,boolean event){return parse(template,source,stored,event,false);}
 private static Parsed parse(UUID template,String source,boolean stored,boolean event,boolean simulation){
  validate(source,1048576);int offset=source!=null&&source.startsWith("\ufeff")?1:0;Node node=PARSER.parse(source==null?"":source.substring(offset));var images=new LinkedHashMap<Image,String>();var references=new LinkedHashMap<Node,Reference>();
  node.accept(new AbstractVisitor(){
   @Override public void visit(Image image){String name=ImageNames.reference(template,image.getDestination(),event,simulation);images.put(image,ImageNames.key(name));references.put(image,new Reference(name,true,""));}
   @Override public void visit(Link link){try{var ref=link(template,link.getDestination(),event,simulation);if(ref!=null)references.put(link,ref);}catch(AuthFailure e){if(!stored)throw e;link.setDestination("");}visitChildren(link);}
  });return new Parsed(node,images,references,offset);
 }
 private static String fragment(String raw){
  try{String value=java.net.URLDecoder.decode(raw.replace("+","%2B"),StandardCharsets.UTF_8);if(value.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT))throw new IllegalArgumentException();return java.net.URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
  catch(RuntimeException e){throw new AuthFailure(400,"TEMPLATE_FILE_REFERENCE_INVALID");}
 }
 private static Reference link(UUID template,String destination,boolean event){return link(template,destination,event,false);}
 private static Reference link(UUID template,String destination,boolean event,boolean simulation){
  if(destination.startsWith("#")){fragment(destination.substring(1));return null;}
  try{
   var uri=java.net.URI.create(destination);String scheme=uri.getScheme();
   if(scheme!=null){scheme=scheme.toLowerCase(Locale.ROOT);if(!Set.of("http","https","mailto","tel").contains(scheme)||uri.getRawSchemeSpecificPart().isBlank()||((scheme.equals("http")||scheme.equals("https"))&&(uri.getRawAuthority()==null||uri.getRawAuthority().isBlank())))throw new IllegalArgumentException();return null;}
   if(destination.startsWith("//"))throw new IllegalArgumentException();
   int hash=destination.indexOf('#');String name=ImageNames.reference(template,hash<0?destination:destination.substring(0,hash),event,simulation);
   return new Reference(name,false,hash<0?"":"#"+fragment(destination.substring(hash+1)));
  }catch(RuntimeException e){throw new AuthFailure(400,"TEMPLATE_FILE_REFERENCE_INVALID");}
 }
 public static void validate(String source){validate(source,MAX_BYTES);}
 private static void validate(String source,int max){if(source!=null&&(source.indexOf('\0')>=0||source.getBytes(StandardCharsets.UTF_8).length>max))throw new AuthFailure(400,"INVALID_INSTRUCTIONS_MARKDOWN");}
 public static String render(UUID template,String source,Map<String,String> names){return render(template,source,names,false);}
 public static String renderEvent(UUID event,String source,Map<String,String> names){return render(event,source,names,true);}
 /** Reuse the event parser/sanitizer; only the authenticated delivery namespace differs. */
 public static String renderParticipant(UUID event,UUID registration,String source,Map<String,String> names){
  String html=renderEvent(event,source,names);if(html==null)return null;
  var doc=Jsoup.parseBodyFragment(html);doc.outputSettings().prettyPrint(false);
  for(var node:doc.select("a[href],img[src]")){String attribute=node.tagName().equals("a")?"href":"src";String value=node.attr(attribute);String prefix="/api/v1/race-events/"+event+"/files/";if(value.startsWith(prefix))node.attr(attribute,"/api/v1/race-participations/"+registration+"/files/"+value.substring(prefix.length()));}
  return doc.body().html();
 }
 /** Keep relative Markdown intact and rewrite explicit event links, including reference definitions. */
 public static String participantSource(UUID event,UUID registration,String source){
  if(source==null)return null;var parsed=analyzeEvent(event,source);var candidates=new LinkedHashSet<Node>(parsed.references().keySet());
  parsed.node().accept(new AbstractVisitor(){@Override public void visit(LinkReferenceDefinition node){candidates.add(node);}});
  record Edit(int start,int end,String text){}var edits=new ArrayList<Edit>();var changed=new HashSet<Node>();
  for(var node:candidates){String destination=node instanceof Image i?i.getDestination():node instanceof Link l?l.getDestination():((LinkReferenceDefinition)node).getDestination();String prefix="/api/v1/race-events/"+event+"/";
   if(!destination.startsWith(prefix))continue;var ref=link(event,destination,true);String next="/api/v1/race-participations/"+registration+"/files/"+java.net.URLEncoder.encode(ref.fileName(),StandardCharsets.UTF_8).replace("+","%20")+ref.fragment();
   if(node instanceof Image i)i.setDestination(next);else if(node instanceof Link l)l.setDestination(next);else ((LinkReferenceDefinition)node).setDestination(next);changed.add(node);
  }
  for(var node:changed){boolean nested=false;for(Node p=node.getParent();p!=null;p=p.getParent())if(changed.contains(p))nested=true;if(nested)continue;var spans=node.getSourceSpans();if(spans.isEmpty())throw new AuthFailure(503,"RACE_STATE_UNAVAILABLE");edits.add(new Edit(parsed.sourceOffset()+spans.getFirst().getInputIndex(),parsed.sourceOffset()+spans.getLast().getInputIndex()+spans.getLast().getLength(),MarkdownRenderer.builder().extensions(EXT).build().render(node)));}
  edits.sort(Comparator.comparingInt(Edit::start).reversed());var result=new StringBuilder(source);for(var edit:edits)result.replace(edit.start(),edit.end(),edit.text());return result.toString();
 }
 public static String renderSimulation(UUID id,String source,Map<String,String> names){return render(id,source,names,false,true);}
 private static String render(UUID template,String source,Map<String,String> names,boolean event){return render(template,source,names,event,false);}
 private static String render(UUID template,String source,Map<String,String> names,boolean event,boolean simulation){
  if(source==null)return null;var parsed=parse(template,source,true,event,simulation);
  parsed.references().forEach((node,ref)->{String name=names.getOrDefault(ref.key(),ref.fileName());String destination=(simulation?ImageNames.simulationUrl(template,name):event?ImageNames.eventUrl(template,name):ref.image()?ImageNames.url(template,name):ImageNames.fileUrl(template,name))+ref.fragment();if(node instanceof Image image)image.setDestination(destination);else ((Link)node).setDestination(destination);});
  String rendered=HtmlRenderer.builder().extensions(EXT).escapeHtml(true).sanitizeUrls(false).build().render(parsed.node());
  // hrefs originate exclusively from validated AST Link nodes; raw HTML is escaped.
  var safe=new Safelist().addTags("p","br","h1","h2","h3","h4","h5","h6","strong","em","del","ul","ol","li","blockquote","hr","a","img","pre","code","table","thead","tbody","tr","th","td","input")
   .addAttributes("a","href","title").addAttributes("img","src","alt","title").addAttributes("ol","start").addAttributes("code","class")
   .addAttributes("input","type","checked","disabled").addAttributes("th","align").addAttributes("td","align");
  var doc=new Cleaner(safe).clean(Jsoup.parseBodyFragment(rendered));doc.outputSettings().prettyPrint(false).charset(StandardCharsets.UTF_8);
  for(var input:doc.select("input")){if(!input.attr("type").equals("checkbox"))input.remove();else input.attr("disabled",true);}
  for(var code:doc.select("code[class]"))if(!code.className().matches("language-[a-zA-Z0-9_+-]+"))code.removeAttr("class");
  for(var cell:doc.select("[align]"))if(!Set.of("left","right","center").contains(cell.attr("align")))cell.removeAttr("align");
  for(var a:doc.select("a[href]")){if(a.attr("href").isEmpty()){a.removeAttr("href");continue;}String href=a.attr("href").toLowerCase(Locale.ROOT);if(href.startsWith("http:")||href.startsWith("https:")||href.startsWith("/api/v1/race-templates/")||href.startsWith("/api/v1/race-events/")||href.startsWith("/api/v1/admin/race-simulations/"))a.attr("target","_blank").attr("rel","noopener noreferrer");}
  var slugs=new HashSet<String>();for(var heading:doc.select("h1,h2,h3,h4,h5,h6")){
   String base=java.text.Normalizer.normalize(heading.text().toLowerCase(Locale.ROOT),java.text.Normalizer.Form.NFC).replaceAll("[^\\p{L}\\p{N}\\p{M}_ -]","").strip().replaceAll("\\s+","-");if(base.isEmpty())base="section";
   String slug=base;int suffix=1;while(!slugs.add(slug))slug=base+"-"+suffix++;heading.attr("id",slug);
  }
  return doc.body().html();
 }
 public static boolean hasContent(String source){
  if(source==null)return false;validate(source);var content=new boolean[1];
  PARSER.parse(source).accept(new AbstractVisitor(){
   private void text(String value){if(value.codePoints().anyMatch(c->!Character.isWhitespace(c)&&!Character.isSpaceChar(c)&&Character.getType(c)!=Character.FORMAT))content[0]=true;}
   @Override public void visit(Text n){text(n.getLiteral());}
   @Override public void visit(Code n){text(n.getLiteral());}
   @Override public void visit(FencedCodeBlock n){text(n.getLiteral());}
   @Override public void visit(IndentedCodeBlock n){text(n.getLiteral());}
   @Override public void visit(Image n){content[0]=true;}
  });return content[0];
 }
 public static String portable(UUID sourceTemplate,String source){return copy(sourceTemplate,null,source,false);}
 public static String copy(UUID sourceTemplate,UUID target,String source){return copy(sourceTemplate,target,source,false);}
 public static String copyToEvent(UUID sourceTemplate,UUID target,String source){return copy(sourceTemplate,target,source,true);}
 public static String copyToSimulation(UUID sourceTemplate,UUID target,String source){return copy(sourceTemplate,target,source,false,true);}
 private static String copy(UUID sourceTemplate,UUID target,String source,boolean event){return copy(sourceTemplate,target,source,event,false);}
 private static String copy(UUID sourceTemplate,UUID target,String source,boolean event,boolean simulation){
  if(source==null)return null;var parsed=analyzeStored(sourceTemplate,source);var changed=new LinkedHashSet<Node>();
  // Definitions may remain in the source after their usages become inline links.
  // Rewrite them too, including unused definitions, so snapshots never keep source-library URLs.
  parsed.node().accept(new AbstractVisitor(){@Override public void visit(LinkReferenceDefinition definition){if(definition.getDestination().startsWith("/api/v1/race-templates/"+sourceTemplate+"/")){var ref=link(sourceTemplate,definition.getDestination(),false);if(ref!=null)parsed.references().put(definition,ref);}}});
  parsed.references().forEach((node,ref)->{
   String destination=node instanceof Image i?i.getDestination():node instanceof Link link?link.getDestination():((LinkReferenceDefinition)node).getDestination();
   if(!destination.startsWith("/api/v1/race-templates/"+sourceTemplate+"/"))return;
   String next=target==null?java.net.URLEncoder.encode(ref.fileName(),StandardCharsets.UTF_8).replace("+","%20")+ref.fragment():(simulation?ImageNames.simulationUrl(target,ref.fileName()):event?ImageNames.eventUrl(target,ref.fileName()):ref.image()?ImageNames.url(target,ref.fileName()):ImageNames.fileUrl(target,ref.fileName()))+ref.fragment();
   if(node instanceof Image i)i.setDestination(next);else if(node instanceof Link link)link.setDestination(next);else ((LinkReferenceDefinition)node).setDestination(next);changed.add(node);
  });
  record Edit(int start,int end,String text){}var edits=new ArrayList<Edit>();
  for(var node:changed){boolean nested=false;for(Node parent=node.getParent();parent!=null;parent=parent.getParent())if(changed.contains(parent))nested=true;if(nested)continue;
   var spans=node.getSourceSpans();if(spans.isEmpty())throw new AuthFailure(503,"TEMPLATE_COPY_INCONSISTENT");
   edits.add(new Edit(parsed.sourceOffset()+spans.getFirst().getInputIndex(),parsed.sourceOffset()+spans.getLast().getInputIndex()+spans.getLast().getLength(),MarkdownRenderer.builder().extensions(EXT).build().render(node)));
  }
  edits.sort(Comparator.comparingInt(Edit::start).reversed());var result=new StringBuilder(source);int previous=source.length();
  for(var edit:edits){if(edit.end()>previous||edit.start()<0)throw new AuthFailure(503,"TEMPLATE_COPY_INCONSISTENT");result.replace(edit.start(),edit.end(),edit.text());previous=edit.start();}
  validate(result.toString(),1048576);return result.toString();
 }
}
