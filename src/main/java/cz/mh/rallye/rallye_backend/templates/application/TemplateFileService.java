package cz.mh.rallye.rallye_backend.templates.application;

import java.io.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.application.AuthService.Principal;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore;
import cz.mh.rallye.rallye_backend.shared.persistence.CommandStore;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import cz.mh.rallye.rallye_backend.audit.application.AuditService.Event;

@Service
public class TemplateFileService {
 public record FileView(String fileName,UUID templateId,String kind,String url,String contentType,int byteSize,Integer width,Integer height,Instant createdAt,boolean used) {}
 public record Library(UUID templateId,long version,String status,List<FileView> items,List<TemplateFileReferences.Warning> missingReferences,TemplateFileContent.Limits limits) {}
 public record Uploaded(String fileName,UUID templateId,String url,long version,String status,List<TemplateFileReferences.Warning> missingReferences) {}
 public record Content(byte[] bytes,String contentType,String fileName,boolean pdf,boolean markdown){@Override public String toString(){return "FileContent[redacted]";}}
 private final TemplateStore store;private final TemplateService templates;private final TemplateImageFiles storage;private final TemplateFileContent formats;
 private final AuditService audit;private final CommandStore commands;private final Clock clock;private final TransactionTemplate tx;private final JsonMapper json=JsonMapper.builder().build();
 public TemplateFileService(TemplateStore store,TemplateService templates,TemplateImageFiles storage,TemplateFileContent formats,AuditService audit,CommandStore commands,Clock clock,PlatformTransactionManager manager){this.store=store;this.templates=templates;this.storage=storage;this.formats=formats;this.audit=audit;this.commands=commands;this.clock=clock;tx=new TransactionTemplate(manager);}
 private static String name(String raw){try{return ImageNames.validate(raw);}catch(AuthFailure e){throw new AuthFailure(400,"INVALID_FILE_NAME");}}
 private TemplateStore.Image find(UUID template,String raw){String key=ImageNames.key(name(raw));return store.images(template).stream().filter(f->f.nameKey().equals(key)).findFirst().orElseThrow(()->new AuthFailure(404,"TEMPLATE_FILE_NOT_FOUND"));}
 private void version(long actual,long requested){if(requested<0)throw new AuthFailure(400,"INVALID_REQUEST");if(actual!=requested)throw new AuthFailure(409,"TEMPLATE_VERSION_CONFLICT");}
 public Library list(Principal actor,UUID template){return tx.execute(s->{var view=templates.detail(actor,template);var files=store.images(template);var stages=store.stages(template);var used=TemplateFileReferences.usedKeys(template,stages,files);var result=files.stream().sorted(Comparator.comparing(TemplateStore.Image::nameKey)).map(f->new FileView(f.fileName(),template,f.isImage()?"IMAGE":f.isMarkdown()?"MARKDOWN":"PDF",ImageNames.fileUrl(template,f.fileName()),f.contentType(),f.byteSize(),f.width(),f.height(),f.createdAt(),used.contains(f.nameKey()))).toList();return new Library(template,view.version(),view.status(),result,TemplateFileReferences.warnings(template,stages,files),formats.limits());});}
 public Uploaded upload(Principal actor,UUID template,long expected,UUID key,String original,InputStream input){
  templates.detail(actor,template);if(key==null)throw new AuthFailure(400,"INVALID_REQUEST");String name=name(original);var data=formats.validate(name,input,storage.files);
  String digest;try{digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.bytes()));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
  String request=json.writeValueAsString(List.of(template,expected,name,digest));boolean[] written={false};
  return locked(()->{
   storage.cleanup(clock.instant().minusSeconds(86400),store::imageNameReferenced);
   try{return tx.execute(s->{
    var parent=templates.detail(actor,template);var at=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    String replay=commands.replay(actor.accountId(),key,"TEMPLATE_FILE_UPLOAD",request,at);if(replay!=null)return json.readValue(replay,Uploaded.class);
    version(parent.version(),expected);if(store.imageNameReferenced(template,name))throw new AuthFailure(409,"TEMPLATE_FILE_NAME_CONFLICT");
    UUID id=UUID.randomUUID();storage.write(template,name,data.bytes());written[0]=true;store.file(id,template,name,data,at);
    var files=store.images(template);if(data.markdown()!=null)TemplateFileReferences.validate(InstructionsMarkdown.analyzeDocument(template,data.markdown()),files);var stages=store.stages(template);var warnings=TemplateFileReferences.warnings(template,stages,files);
    if(parent.status().equals("READY")&&!warnings.isEmpty()){store.touch(template,at);audit.template(actor.accountId(),Event.TEMPLATE_DRAFTED,template,false);}
    // Resolve newly uploaded image targets also in the existing FK-backed image usage index.
    for(var stage:stages)store.references(template,stage.id(),TemplateFileReferences.imageIds(template,stage.instructionsMarkdown(),files));
    audit.template(actor.accountId(),Event.TEMPLATE_FILE_CREATED,template,false);var current=store.lock(template);
    var result=new Uploaded(name,template,ImageNames.fileUrl(template,name),current.version(),current.status(),warnings);
    commands.save(actor.accountId(),key,id,"TEMPLATE_FILE_UPLOAD",request,json.writeValueAsString(result),at);return result;
   });}catch(RuntimeException e){try{if(written[0]&&!store.imageNameReferenced(template,name))storage.remove(template,name);}catch(RuntimeException ignored){}throw storageError(e);}
  });
 }
 public Content content(Principal actor,UUID template,String name){return locked(()->tx.execute(s->{
  templates.detail(actor,template);var file=find(template,name);byte[] bytes=storage.read(template,file.fileName());if(bytes.length!=file.byteSize())throw new AuthFailure(503,"FILE_STORAGE_UNAVAILABLE");
  if(file.isMarkdown()){
   if(!Arrays.equals(bytes,file.markdownSource().getBytes(StandardCharsets.UTF_8)))throw new AuthFailure(503,"FILE_STORAGE_UNAVAILABLE");
   String html=InstructionsMarkdown.render(template,file.markdownSource(),TemplateFileReferences.names(store.images(template)));
   return new Content(TemplateDocumentHtml.page(file.fileName(),html).getBytes(StandardCharsets.UTF_8),"text/html; charset=UTF-8",file.fileName(),false,true);
  }return new Content(bytes,file.contentType(),file.fileName(),!file.isImage(),false);
 }));}
 public void delete(Principal actor,UUID template,String name,long expected){try{locked(()->{String[] actual={null};tx.executeWithoutResult(s->{var parent=templates.detail(actor,template);version(parent.version(),expected);var target=find(template,name);actual[0]=target.fileName();
   if(TemplateFileReferences.used(template,target,store.stages(template),store.images(template)))throw new AuthFailure(409,"TEMPLATE_FILE_IN_USE");store.deleteImage(target.id());audit.template(actor.accountId(),Event.TEMPLATE_FILE_DELETED,template,false);
  });storage.remove(template,actual[0]);return null;});}catch(RuntimeException e){throw storageError(e);}}
 private <T> T locked(java.util.function.Supplier<T> action){try{return storage.files.exclusive(action);}catch(RuntimeException e){throw storageError(e);}}
 private static RuntimeException storageError(RuntimeException e){if(e instanceof AuthFailure f){if(f.code().equals("PHOTO_STORAGE_UNAVAILABLE"))return new AuthFailure(503,"FILE_STORAGE_UNAVAILABLE");if(f.code().equals("TEMPLATE_IMAGE_NAME_CONFLICT"))return new AuthFailure(409,"TEMPLATE_FILE_NAME_CONFLICT");if(f.code().equals("TEMPLATE_IMAGE_REFERENCE_INVALID"))return new AuthFailure(400,"TEMPLATE_FILE_REFERENCE_INVALID");}return e;}
}
