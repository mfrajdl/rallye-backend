package cz.mh.rallye.rallye_backend.templates.application;

import java.io.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import cz.mh.rallye.rallye_backend.identity.application.AuthService.Principal;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore;
import cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration;
import cz.mh.rallye.rallye_backend.shared.persistence.CommandStore;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import static cz.mh.rallye.rallye_backend.templates.application.TemplateArchive.*;

@Service
public class TemplateTransferService {
 private final TemplateService templates;private final TemplateStore store;private final TemplateImageFiles storage;private final TemplateFileContent formats;
 private final CommandStore commands;private final AuditService audit;private final Clock clock;private final TransactionTemplate tx;
 public TemplateTransferService(TemplateService templates,TemplateStore store,TemplateImageFiles storage,TemplateFileContent formats,CommandStore commands,AuditService audit,Clock clock,PlatformTransactionManager manager){this.templates=templates;this.store=store;this.storage=storage;this.formats=formats;this.commands=commands;this.audit=audit;this.clock=clock;tx=new TransactionTemplate(manager);}
 private Instant now(){return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);}
 private void authorize(Principal actor){tx.executeWithoutResult(s->templates.authorize(actor));}
 public byte[] export(Principal actor,UUID id){
  authorize(actor);return storage.files.exclusive(()->tx.execute(s->{
   templates.authorize(actor);var template=store.lock(id);if(template==null)throw new AuthFailure(404,"TEMPLATE_NOT_FOUND");
   var stages=store.stages(id);if(stages.size()>MAX_STAGES)throw new AuthFailure(413,"TEMPLATE_ARCHIVE_TOO_LARGE");var library=store.images(id);var byKey=new HashMap<String,TemplateStore.Image>();library.forEach(f->byKey.put(f.nameKey(),f));
   var definitions=new ArrayList<StageData>();var required=new LinkedHashSet<String>();
   for(var stage:stages){
    if(stage.configuration().code().valueUnavailable())throw new AuthFailure(409,"TEMPLATE_EXPORT_CODE_UNAVAILABLE");
    // Check stored image bindings as duplication does; broken state must not appear as a valid archive.
    if(!store.usedImages(stage.id()).equals(TemplateFileReferences.imageIds(id,stage.instructionsMarkdown(),library)))throw new AuthFailure(503,"TEMPLATE_EXPORT_INCONSISTENT");
    String markdown=InstructionsMarkdown.portable(id,stage.instructionsMarkdown());
    definitions.add(new StageData(stage.sequenceNo(),stage.name(),stage.expectedDurationMs(),stage.maxPenaltyPoints(),markdown,stage.configuration()));
    for(var ref:InstructionsMarkdown.analyze(id,stage.instructionsMarkdown()).references().values())required.add(ref.key());
   }
   var files=new LinkedHashMap<String,byte[]>();var types=new LinkedHashMap<String,String>();var visited=new HashSet<String>();var queue=new ArrayDeque<>(required);long total=0;
   while(!queue.isEmpty()){
    String key=queue.remove();if(!visited.add(key))continue;var file=byKey.get(key);if(file==null)throw new AuthFailure(409,"TEMPLATE_EXPORT_FILE_MISSING");
    byte[] bytes;try{bytes=storage.read(id,file.fileName());}catch(AuthFailure unavailable){throw new AuthFailure(503,"TEMPLATE_EXPORT_FILE_UNAVAILABLE");}if(bytes.length!=file.byteSize())throw new AuthFailure(503,"TEMPLATE_EXPORT_INCONSISTENT");
    var checked=formats.validate(file.fileName(),new ByteArrayInputStream(bytes),storage.files);if(!checked.contentType().equals(file.contentType())||!Objects.equals(checked.width(),file.width())||!Objects.equals(checked.height(),file.height())||!Objects.equals(checked.markdown(),file.markdownSource()))throw new AuthFailure(503,"TEMPLATE_EXPORT_INCONSISTENT");
    if(file.isMarkdown()){
     for(var ref:InstructionsMarkdown.analyzeDocument(id,file.markdownSource()).references().values())queue.add(ref.key());
     bytes=InstructionsMarkdown.portable(id,file.markdownSource()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    total+=bytes.length;if(total>MAX_EXPANDED-MAX_JSON*2||files.size()>=MAX_ENTRIES-2)throw new AuthFailure(413,"TEMPLATE_ARCHIVE_TOO_LARGE");files.put(file.fileName(),bytes);types.put(file.fileName(),file.contentType());
   }
   var definition=new Definition(template.name(),template.description(),template.skipPenaltyPoints(),template.defaultWrongCodePenaltyPoints(),List.copyOf(definitions));
   byte[] zip=encode(definition,files,types,now());check(zip);audit.template(actor.accountId(),AuditService.Event.TEMPLATE_EXPORTED,id,false);return zip;
  }));
 }
 private record Checked(Bundle bundle,Map<String,TemplateFileContent.Validated> files){}
 private Checked check(byte[] bytes){
  var b=decode(bytes);var files=new LinkedHashMap<String,TemplateFileContent.Validated>();
  for(var meta:b.manifest().files()){
   byte[] original=b.files().get(meta.fileName());var v=formats.validate(meta.fileName(),new ByteArrayInputStream(original),storage.files);
   if(!v.contentType().equals(meta.contentType()))throw invalid();
   if(v.contentType().startsWith("image/"))safeImage(original,v.contentType());
   if(v.markdown()!=null)references(v.markdown(),b.files().keySet());
   // Validation decodes images but archives preserve the exact original bytes, not a second lossy JPEG encode.
   files.put(meta.fileName(),new TemplateFileContent.Validated(original,v.contentType(),v.width(),v.height(),v.markdown()));
  }
  UUID namespace=UUID.randomUUID();var library=files.entrySet().stream().map(e->{var f=e.getValue();return new TemplateStore.Image(UUID.randomUUID(),namespace,e.getKey(),ImageNames.key(e.getKey()),f.contentType(),f.bytes().length,f.width(),f.height(),Instant.EPOCH,false,f.markdown());}).toList();
  for(var stage:b.template().stages())TemplateFileReferences.validate(InstructionsMarkdown.analyze(namespace,stage.instructionsMarkdown()),library);
  for(var f:files.values())if(f.markdown()!=null)TemplateFileReferences.validate(InstructionsMarkdown.analyzeDocument(namespace,f.markdown()),library);
  return new Checked(b,Collections.unmodifiableMap(files));
 }
 /** Keep exported image bytes exactly, but reject metadata instead of retaining foreign EXIF/text/polyglots. */
 private static void safeImage(byte[] bytes,String type){
  if(type.equals("image/png")){
   var b=java.nio.ByteBuffer.wrap(bytes);int p=8;boolean ended=false;
   while(p+12<=bytes.length){int n=b.getInt(p);if(n<0||n>bytes.length-p-12)throw invalid();String chunk=new String(bytes,p+4,4,java.nio.charset.StandardCharsets.US_ASCII);
    if(!Set.of("IHDR","PLTE","IDAT","IEND","tRNS").contains(chunk))throw invalid();p+=n+12;if(chunk.equals("IEND")){ended=true;break;}}
   if(!ended||p!=bytes.length)throw invalid();
  }else{
   if(bytes.length<4||bytes[bytes.length-2]!=(byte)255||bytes[bytes.length-1]!=(byte)217)throw invalid();int p=2;
   while(p+4<=bytes.length){if(bytes[p++]!=(byte)255)throw invalid();int marker=Byte.toUnsignedInt(bytes[p++]);if(marker==0xda)return;
    if(marker>=0xe1&&marker<=0xef||marker==0xfe)throw invalid();int n=(Byte.toUnsignedInt(bytes[p])<<8)|Byte.toUnsignedInt(bytes[p+1]);if(n<2||n>bytes.length-p)throw invalid();p+=n;
   }throw invalid();
  }
 }
 public Preview preview(Principal actor,InputStream input){authorize(actor);var checked=check(read(input));authorize(actor);return TemplateArchive.preview(checked.bundle());}
 public TemplateService.Created importTemplate(Principal actor,UUID key,InputStream input){
  authorize(actor);if(key==null)throw new AuthFailure(400,"INVALID_REQUEST");byte[] archive=read(input);var checked=check(archive);String request=JSON.writeValueAsString(List.of(hash(archive)));
  return storage.files.exclusive(()->{
   UUID id=UUID.randomUUID();var written=new ArrayList<String>();
   try{return tx.execute(s->{
    templates.authorize(actor);var replay=commands.replay(actor.accountId(),key,"TEMPLATE_IMPORT",request,now());if(replay!=null)return JSON.readValue(replay,TemplateService.Created.class);
    var def=checked.bundle().template();Instant at=now();store.insert(id,def.name(),def.description(),def.skipPenaltyPoints(),def.defaultWrongCodePenaltyPoints(),at);
    for(var f:checked.files().entrySet()){storage.write(id,f.getKey(),f.getValue().bytes());written.add(f.getKey());store.file(UUID.randomUUID(),id,f.getKey(),f.getValue(),at);}
    var library=store.images(id);
    for(var stage:def.stages()){
     UUID stageId=UUID.randomUUID();store.insertStage(stageId,id,stage.sequenceNo(),stage.name(),stage.expectedDurationMs(),stage.maxPenaltyPoints());store.instructionsMarkdown(stageId,stage.instructionsMarkdown());store.configureStage(stageId,stage.configuration().domain());
     store.references(id,stageId,TemplateFileReferences.imageIds(id,stage.instructionsMarkdown(),library));
    }
    audit.template(actor.accountId(),AuditService.Event.TEMPLATE_IMPORTED,id,false);var result=new TemplateService.Created(id,id,0);commands.save(actor.accountId(),key,id,"TEMPLATE_IMPORT",request,JSON.writeValueAsString(result),at);return result;
   });}catch(RuntimeException failure){
    // Never remove possibly committed content if commit outcome is uncertain. Normal rollback removes all own files.
    for(String name:written)try{if(!store.imageNameReferenced(id,name))storage.remove(id,name);}catch(RuntimeException uncertain){/* Guarded orphan maintenance remains the safe fallback. */}
    throw failure;
   }
  });
 }
}
