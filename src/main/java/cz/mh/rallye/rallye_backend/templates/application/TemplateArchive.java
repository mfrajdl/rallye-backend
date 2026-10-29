package cz.mh.rallye.rallye_backend.templates.application;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.*;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration;

/** Portable v1 wire format. No database entities, account IDs, credentials or installation URLs. */
public final class TemplateArchive {
 public static final int MAX_ZIP=50*1024*1024,MAX_EXPANDED=100*1024*1024,MAX_ENTRIES=202,MAX_JSON=4*1024*1024,MAX_STAGES=500;
 private static final UUID PORTABLE=UUID.fromString("00000000-0000-0000-0000-000000000000");
 public static final JsonMapper JSON=JsonMapper.builder(tools.jackson.core.json.JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(30).maxStringLength(MAX_JSON).build()).build()).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();
 public record FileData(String fileName,String contentType,long byteSize,String sha256){}
 public record Manifest(String format,int formatVersion,Instant exportedAt,List<FileData> files){}
 public record GpsData(boolean enabled,Double latitude,Double longitude,Double radiusMeters,Long outsidePenaltyPoints,Long spoofingPenaltyPoints){}
 public record CodeData(boolean enabled,String value,Long wrongAttemptPenaltyPoints,boolean valueUnavailable){@Override public String toString(){return "CodeData[redacted]";}}
 public record TimeData(boolean enabled,Long intervalMs,Long pointsPerInterval){}
 public record DelayData(boolean enabled,Long minimumDurationMs,Long pointsPerSecond){}
 public record ConfigurationData(GpsData gps,CodeData code,TimeData timePenalty,DelayData delayed){
  public static ConfigurationData from(StageConfiguration c){var g=c.gps();var k=c.code();var t=c.timePenalty();var d=c.delayed();return new ConfigurationData(new GpsData(g.enabled(),g.latitude(),g.longitude(),g.radiusMeters(),g.outsidePenaltyPoints(),g.spoofingPenaltyPoints()),new CodeData(k.enabled(),k.value(),k.wrongAttemptPenaltyPoints(),k.valueUnavailable()),new TimeData(t.enabled(),t.intervalMs(),t.pointsPerInterval()),new DelayData(d.enabled(),d.minimumDurationMs(),d.pointsPerSecond()));}
  public StageConfiguration domain(){if(gps==null||code==null||timePenalty==null||delayed==null)throw invalid();return new StageConfiguration(new StageConfiguration.Gps(gps.enabled(),gps.latitude(),gps.longitude(),gps.radiusMeters(),gps.outsidePenaltyPoints(),gps.spoofingPenaltyPoints()),new StageConfiguration.Code(code.enabled(),code.value(),code.wrongAttemptPenaltyPoints(),code.valueUnavailable()),new StageConfiguration.TimePenalty(timePenalty.enabled(),timePenalty.intervalMs(),timePenalty.pointsPerInterval()),new StageConfiguration.Delayed(delayed.enabled(),delayed.minimumDurationMs(),delayed.pointsPerSecond()));}
  @Override public String toString(){return "ConfigurationData[redacted]";}
 }
 public record StageData(int sequenceNo,String name,Long expectedDurationMs,Long maxPenaltyPoints,String instructionsMarkdown,ConfigurationData configuration){
  public StageData(int sequenceNo,String name,Long expectedDurationMs,Long maxPenaltyPoints,String instructionsMarkdown,StageConfiguration configuration){this(sequenceNo,name,expectedDurationMs,maxPenaltyPoints,instructionsMarkdown,ConfigurationData.from(configuration));}
 @Override public String toString(){return "StageData[redacted]";}}
 public record Definition(String name,String description,long skipPenaltyPoints,long defaultWrongCodePenaltyPoints,List<StageData> stages){@Override public String toString(){return "TemplateDefinition[redacted]";}}
 public record Bundle(Manifest manifest,Definition template,Map<String,byte[]> files){@Override public String toString(){return "TemplateBundle[redacted]";}}
 public record Preview(String name,int stageCount,int fileCount,List<String> warnings){}
 public static AuthFailure invalid(){return new AuthFailure(400,"TEMPLATE_ARCHIVE_INVALID");}
 public static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
 public static byte[] read(InputStream in){try{byte[] bytes=in.readNBytes(MAX_ZIP+1);if(bytes.length>MAX_ZIP)throw new AuthFailure(413,"TEMPLATE_ARCHIVE_TOO_LARGE");return bytes;}catch(IOException failure){throw invalid();}}
 private static void name(String name){if(name==null||!ImageNames.validate(name).equals(name))throw invalid();}
 private static void text(String s,int max,boolean required){if(s==null){if(required)throw invalid();return;}if((required&&s.isBlank())||s.codePointCount(0,s.length())>max||s.indexOf(0)>=0)throw invalid();}
 /** Strict scalar types and all record fields required (nullable values remain explicit null). */
 private static void shape(JsonNode n,Class<?> type){
  if(n==null||!n.isObject())throw invalid();var fields=new HashSet<String>();
  for(var c:type.getRecordComponents()){
   fields.add(c.getName());var v=n.get(c.getName());if(v==null)throw invalid();var t=c.getType();
   if(v.isNull()){if(t.isPrimitive())throw invalid();continue;}
   if(t==String.class||t==Instant.class){if(!v.isString())throw invalid();}
   else if(t==long.class||t==Long.class||t==int.class){if(!v.isIntegralNumber()||!v.canConvertToLong()||(t==int.class&&!v.canConvertToInt()))throw invalid();}
   else if(t==boolean.class){if(!v.isBoolean())throw invalid();}
   else if(t==Double.class){if(!v.isNumber()||!Double.isFinite(v.doubleValue()))throw invalid();}
   else if(t.isRecord())shape(v,t);
   else if(t==List.class){if(!v.isArray())throw invalid();}
  }
  if(!fields.equals(new HashSet<>(n.propertyNames())))throw invalid();
 }
 public static Bundle decode(byte[] zip){
  if(zip.length>MAX_ZIP)throw new AuthFailure(413,"TEMPLATE_ARCHIVE_TOO_LARGE");
  try{
   var centralEntries=new HashMap<String,long[]>();int expected=central(zip,centralEntries);var entries=new LinkedHashMap<String,byte[]>();int total=0;
   try(var in=new ZipInputStream(new ByteArrayInputStream(zip),StandardCharsets.UTF_8)){
    for(ZipEntry e;(e=in.getNextEntry())!=null;){
     String path=e.getName();if(e.isDirectory()||entries.size()>=MAX_ENTRIES||entries.containsKey(path)||!entryName(path))throw invalid();
     int limit=path.equals("manifest.json")||path.equals("template.json")?MAX_JSON:Math.min(50*1024*1024,MAX_EXPANDED-total);
     byte[] bytes=in.readNBytes(limit+1);if(bytes.length>limit)throw new AuthFailure(413,"TEMPLATE_ARCHIVE_TOO_LARGE");total=Math.addExact(total,bytes.length);if(total>MAX_EXPANDED)throw new AuthFailure(413,"TEMPLATE_ARCHIVE_TOO_LARGE");in.closeEntry();var info=centralEntries.get(path);if(info==null||info[0]!=e.getCrc()||info[1]!=e.getCompressedSize()||info[2]!=e.getSize())throw invalid();entries.put(path,bytes);
    }
   }
   if(entries.size()!=expected||!entries.containsKey("manifest.json")||!entries.containsKey("template.json"))throw invalid();
   var m=JSON.readTree(entries.remove("manifest.json"));shape(m,Manifest.class);
   if(!m.get("format").asString().equals("rallye-template")||m.get("formatVersion").intValue()!=1)throw new AuthFailure(400,"TEMPLATE_ARCHIVE_VERSION_UNSUPPORTED");
   for(var f:m.get("files"))shape(f,FileData.class);
   var t=JSON.readTree(entries.remove("template.json"));shape(t,Definition.class);for(var stage:t.get("stages"))shape(stage,StageData.class);
   var manifest=JSON.treeToValue(m,Manifest.class);var definition=JSON.treeToValue(t,Definition.class);var files=new LinkedHashMap<String,byte[]>();var keys=new HashSet<String>();
   if(manifest.exportedAt()==null||manifest.files()==null)throw invalid();
   for(var f:manifest.files()){
    name(f.fileName());if(!keys.add(ImageNames.key(f.fileName()))||f.contentType()==null||f.sha256()==null||!f.sha256().matches("[0-9a-f]{64}"))throw invalid();
    byte[] bytes=entries.remove("files/"+f.fileName());if(bytes==null||bytes.length!=f.byteSize()||!hash(bytes).equals(f.sha256()))throw invalid();files.put(f.fileName(),bytes);
   }
   if(!entries.isEmpty())throw invalid();validate(definition,files.keySet());return new Bundle(manifest,definition,Collections.unmodifiableMap(files));
  }catch(AuthFailure failure){throw failure;}catch(Exception failure){throw invalid();}
 }
 private static boolean entryName(String path){if(path.equals("manifest.json")||path.equals("template.json"))return true;if(!path.startsWith("files/"))return false;try{name(path.substring(6));return true;}catch(RuntimeException e){return false;}}
 /** Verify non-ZIP64 single-disk central directory and exact local/central name agreement. */
 private static int central(byte[] bytes,Map<String,long[]> metadata){
  if(bytes.length<22)throw invalid();var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);int end=bytes.length-22;
  if(b.getInt(end)!=0x06054b50||b.getShort(end+4)!=0||b.getShort(end+6)!=0||b.getShort(end+20)!=0)throw invalid();
  int count=Short.toUnsignedInt(b.getShort(end+10));if(count<2||count>MAX_ENTRIES||Short.toUnsignedInt(b.getShort(end+8))!=count)throw invalid();
  long start=Integer.toUnsignedLong(b.getInt(end+16)),size=Integer.toUnsignedLong(b.getInt(end+12));if(start+size!=end||start>Integer.MAX_VALUE)throw invalid();
  int pos=(int)start;var names=new HashSet<String>();var offsets=new HashSet<Integer>();
  for(int i=0;i<count;i++){
   if(pos+46>end||b.getInt(pos)!=0x02014b50)throw invalid();int flags=Short.toUnsignedInt(b.getShort(pos+8)),len=Short.toUnsignedInt(b.getShort(pos+28)),extra=Short.toUnsignedInt(b.getShort(pos+30)),comment=Short.toUnsignedInt(b.getShort(pos+32));
   if((flags&1)!=0||pos+46+len+extra+comment>end||b.getShort(pos+34)!=0)throw invalid();
   String name=new String(bytes,pos+46,len,StandardCharsets.UTF_8);int local=b.getInt(pos+42);
   if(!entryName(name)||!names.add(name)||local<0||!offsets.add(local)||local+30>start||b.getInt(local)!=0x04034b50)throw invalid();
   int localLen=Short.toUnsignedInt(b.getShort(local+26));if(local+30+localLen>start||!name.equals(new String(bytes,local+30,localLen,StandardCharsets.UTF_8)))throw invalid();metadata.put(name,new long[]{Integer.toUnsignedLong(b.getInt(pos+16)),Integer.toUnsignedLong(b.getInt(pos+20)),Integer.toUnsignedLong(b.getInt(pos+24))});pos+=46+len+extra+comment;
  }
  if(pos!=end)throw invalid();return count;
 }
 public static void validate(Definition t,Set<String> files){
  text(t.name(),100,true);text(t.description(),2000,false);if(t.stages()==null||t.stages().size()>MAX_STAGES)throw invalid();
  for(int i=0;i<t.stages().size();i++){
   var s=t.stages().get(i);if(s==null||s.sequenceNo()!=i+1||s.expectedDurationMs()!=null&&s.expectedDurationMs()<0||s.maxPenaltyPoints()!=null&&s.maxPenaltyPoints()<0)throw invalid();
   text(s.name(),100,false);if(s.name()!=null&&s.name().isBlank())throw invalid();if(s.configuration()==null)throw invalid();var config=s.configuration().domain();if(config.code().valueUnavailable())throw invalid();config.validate();
   String code=s.configuration().code().value();if(code!=null&&!StageConfiguration.normalizeCode(code).equals(code))throw invalid();InstructionsMarkdown.validate(s.instructionsMarkdown());references(s.instructionsMarkdown(),files);
  }
 }
 public static Set<String> references(String text,Set<String> files){
  var result=new LinkedHashSet<String>();var keys=files.stream().collect(java.util.stream.Collectors.toMap(ImageNames::key,x->x));
  var parsed=InstructionsMarkdown.analyze(PORTABLE,text);
  // Only relative library references are portable; reject installation-specific API links, including absolute ones.
  parsed.node().accept(new org.commonmark.node.AbstractVisitor(){
   private void check(String url){try{var uri=java.net.URI.create(url);if(uri.getPath()!=null&&uri.getPath().startsWith("/api/v1/"))throw invalid();}catch(IllegalArgumentException e){throw invalid();}}
   @Override public void visit(org.commonmark.node.Link link){check(link.getDestination());visitChildren(link);}
   @Override public void visit(org.commonmark.node.Image image){check(image.getDestination());}
   @Override public void visit(org.commonmark.node.LinkReferenceDefinition definition){check(definition.getDestination());}
  });
  for(var r:parsed.references().values()){
   if(!keys.containsKey(r.key()))throw invalid();result.add(keys.get(r.key()));
  }return result;
 }
 public static Preview preview(Bundle b){var warnings=new ArrayList<String>();if(b.template().stages().isEmpty())warnings.add("NO_STAGES");
  UUID id=UUID.randomUUID();var d=b.template();var template=new cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore.Template(id,d.name(),d.description(),d.skipPenaltyPoints(),"DRAFT",0,Instant.EPOCH,Instant.EPOCH,d.defaultWrongCodePenaltyPoints());
  var stages=d.stages().stream().map(st->new cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore.Stage(UUID.randomUUID(),id,st.sequenceNo(),st.name(),st.expectedDurationMs(),st.maxPenaltyPoints(),st.instructionsMarkdown(),st.configuration().domain())).toList();
  if(!TemplateReadiness.check(template,stages).ready())warnings.add("NOT_READY");warnings.add("IMPORTED_AS_DRAFT");return new Preview(b.template().name(),b.template().stages().size(),b.files().size(),List.copyOf(warnings));}
 public static byte[] encode(Definition definition,Map<String,byte[]> files,Map<String,String> types,Instant at){
  var metadata=files.entrySet().stream().map(e->new FileData(e.getKey(),types.get(e.getKey()),e.getValue().length,hash(e.getValue()))).toList();
  try(var out=new ByteArrayOutputStream(){
   @Override public synchronized void write(byte[] b,int off,int len){if(len>MAX_ZIP-count)throw new AuthFailure(413,"TEMPLATE_ARCHIVE_TOO_LARGE");super.write(b,off,len);}
   @Override public synchronized void write(int b){if(count>=MAX_ZIP)throw new AuthFailure(413,"TEMPLATE_ARCHIVE_TOO_LARGE");super.write(b);}
  };var zip=new ZipOutputStream(out,StandardCharsets.UTF_8)){
   byte[] manifest=JSON.writeValueAsBytes(new Manifest("rallye-template",1,at,metadata)),template=JSON.writeValueAsBytes(definition);
   if(manifest.length>MAX_JSON||template.length>MAX_JSON||files.size()>MAX_ENTRIES-2||manifest.length+template.length+files.values().stream().mapToLong(b->b.length).sum()>MAX_EXPANDED)throw new AuthFailure(413,"TEMPLATE_ARCHIVE_TOO_LARGE");
   put(zip,"manifest.json",manifest);put(zip,"template.json",template);
   for(var e:files.entrySet())put(zip,"files/"+e.getKey(),e.getValue());zip.finish();if(out.size()>MAX_ZIP)throw new AuthFailure(413,"TEMPLATE_ARCHIVE_TOO_LARGE");return out.toByteArray();
  }catch(IOException failure){throw new IllegalStateException("Archive creation failed");}
 }
 private static void put(ZipOutputStream zip,String name,byte[] bytes)throws IOException{var entry=new ZipEntry(name);entry.setTime(0);zip.putNextEntry(entry);zip.write(bytes);zip.closeEntry();}
 private TemplateArchive(){}
}
