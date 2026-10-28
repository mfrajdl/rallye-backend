package cz.mh.rallye.rallye_backend.templates.application;

import java.io.InputStream;
import java.time.*;
import java.util.*;
import java.security.MessageDigest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.application.AuthService.Principal;
import cz.mh.rallye.rallye_backend.shared.persistence.CommandStore;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import cz.mh.rallye.rallye_backend.audit.application.AuditService.Event;
import tools.jackson.databind.json.JsonMapper;

@Service
public class TemplateImageService {
 public record View(String fileName,UUID templateId,String url,String contentType,int byteSize,int width,int height,Instant createdAt,boolean used) {}
 public record Created(String fileName,UUID templateId,String url,long version) {}
 public record Content(byte[] bytes,String contentType) {}
 private final TemplateStore store;private final TemplateService templates;private final TemplateImageFiles storage;
 private final AuditService audit;private final CommandStore commands;private final Clock clock;private final TransactionTemplate tx;
 private final JsonMapper json=JsonMapper.builder().build();
 public TemplateImageService(TemplateStore store,TemplateService templates,TemplateImageFiles storage,AuditService audit,CommandStore commands,Clock clock,PlatformTransactionManager manager){
  this.store=store;this.templates=templates;this.storage=storage;this.audit=audit;this.commands=commands;this.clock=clock;tx=new TransactionTemplate(manager);
 }
 private static View view(TemplateStore.Image i){return new View(i.fileName(),i.templateId(),ImageNames.url(i.templateId(),i.fileName()),i.contentType(),i.byteSize(),i.width(),i.height(),i.createdAt(),i.used());}
 private TemplateStore.Image image(UUID template,String name){String key=ImageNames.key(name);return store.images(template).stream().filter(TemplateStore.Image::isImage).filter(i->i.nameKey().equals(key)).findFirst().orElseThrow(()->new AuthFailure(404,"TEMPLATE_IMAGE_NOT_FOUND"));}
 private void version(TemplateService.View t,long version){if(version<0)throw new AuthFailure(400,"INVALID_REQUEST");if(t.version()!=version)throw new AuthFailure(409,"TEMPLATE_VERSION_CONFLICT");}
 public List<View> list(Principal actor,UUID template){return tx.execute(s->{templates.detail(actor,template);var files=store.images(template);var stages=store.stages(template);var used=TemplateFileReferences.usedKeys(template,stages,files);return files.stream().filter(TemplateStore.Image::isImage).map(i->new View(i.fileName(),i.templateId(),ImageNames.url(template,i.fileName()),i.contentType(),i.byteSize(),i.width(),i.height(),i.createdAt(),used.contains(i.nameKey()))).toList();});}
 public Created upload(Principal actor,UUID template,long version,UUID key,String originalName,InputStream input){
  templates.detail(actor,template);if(key==null)throw new AuthFailure(400,"INVALID_REQUEST");
  String name=ImageNames.validate(originalName);var decoded=storage.files.validate(input);String digest;
  try{digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(decoded.bytes()));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
  String request=json.writeValueAsString(Arrays.asList(template,version,name,digest));UUID id=UUID.randomUUID();
  var written=new boolean[1];return storage.files.exclusive(()->{
   // Retry abandoned files after a crash or uncertain commit; uploads hold the same file lock.
   storage.cleanup(clock.instant().minusSeconds(86400),store::imageNameReferenced);
   try{return tx.execute(s->{
    var t=templates.detail(actor,template);
    var at=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    String old=commands.replay(actor.accountId(),key,"TEMPLATE_IMAGE_UPLOAD",request,at);
    if(old!=null)return json.readValue(old,Created.class);
    version(t,version);if(store.imageNameReferenced(template,name))throw new AuthFailure(409,"TEMPLATE_IMAGE_NAME_CONFLICT");storage.write(template,name,decoded);written[0]=true;store.image(id,template,name,decoded,at);
    var library=store.images(template);for(var stage:store.stages(template))store.references(template,stage.id(),TemplateFileReferences.imageIds(template,stage.instructionsMarkdown(),library));
    audit.template(actor.accountId(),Event.TEMPLATE_IMAGE_CREATED,template,false);
    var result=new Created(name,template,ImageNames.url(template,name),t.version());
    commands.save(actor.accountId(),key,id,"TEMPLATE_IMAGE_UPLOAD",request,json.writeValueAsString(result),at);return result;
   });}catch(RuntimeException failure){
    try{if(written[0]&&!store.imageNameReferenced(template,name))storage.remove(template,name);}catch(RuntimeException unavailable){/* Leave inaccessible orphan for later locked cleanup. */}
    throw failure;
   }
  });
 }
 public Content content(Principal actor,UUID template,String name){return storage.files.exclusive(()->tx.execute(s->{templates.detail(actor,template);var image=image(template,name);byte[] bytes=storage.read(template,image.fileName());if(bytes.length!=image.byteSize()||bytes.length>cz.mh.rallye.rallye_backend.crews.application.PhotoFiles.MAX_BYTES)throw new AuthFailure(503,"PHOTO_STORAGE_UNAVAILABLE");return new Content(bytes,image.contentType());}));}
 public void delete(Principal actor,UUID template,String name,long version){storage.files.exclusive(()->{
  String[] actualName=new String[1];tx.executeWithoutResult(s->{var t=templates.detail(actor,template);version(t,version);var image=image(template,name);actualName[0]=image.fileName();
   if(TemplateFileReferences.used(template,image,store.stages(template),store.images(template)))throw new AuthFailure(409,"TEMPLATE_IMAGE_IN_USE");store.deleteImage(image.id());audit.template(actor.accountId(),Event.TEMPLATE_IMAGE_DELETED,template,false);
  });storage.remove(template,actualName[0]);return null;
 });}
}
