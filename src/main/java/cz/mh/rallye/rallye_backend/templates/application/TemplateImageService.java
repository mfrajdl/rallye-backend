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
 public record View(UUID id,UUID templateId,String url,String contentType,int byteSize,int width,int height,Instant createdAt,boolean used) {}
 public record Content(byte[] bytes,String contentType) {}
 private final TemplateStore store;private final TemplateService templates;private final TemplateImageFiles storage;
 private final AuditService audit;private final CommandStore commands;private final Clock clock;private final TransactionTemplate tx;
 private final JsonMapper json=JsonMapper.builder().build();
 public TemplateImageService(TemplateStore store,TemplateService templates,TemplateImageFiles storage,AuditService audit,CommandStore commands,Clock clock,PlatformTransactionManager manager){
  this.store=store;this.templates=templates;this.storage=storage;this.audit=audit;this.commands=commands;this.clock=clock;tx=new TransactionTemplate(manager);
 }
 private static View view(TemplateStore.Image i){return new View(i.id(),i.templateId(),InstructionsHtml.imageUrl(i.templateId(),i.id()),i.contentType(),i.byteSize(),i.width(),i.height(),i.createdAt(),i.used());}
 private TemplateStore.Image image(UUID template,UUID id){return store.images(template).stream().filter(i->i.id().equals(id)).findFirst().orElseThrow(()->new AuthFailure(404,"TEMPLATE_IMAGE_NOT_FOUND"));}
 private void version(TemplateService.View t,long version){if(version<0)throw new AuthFailure(400,"INVALID_REQUEST");if(t.version()!=version)throw new AuthFailure(409,"TEMPLATE_VERSION_CONFLICT");}
 public List<View> list(Principal actor,UUID template){return tx.execute(s->{templates.detail(actor,template);return store.images(template).stream().map(TemplateImageService::view).toList();});}
 public TemplateService.Created upload(Principal actor,UUID template,long version,UUID key,InputStream input){
  templates.detail(actor,template);if(key==null)throw new AuthFailure(400,"INVALID_REQUEST");
  var decoded=storage.files.validate(input);String digest;
  try{digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(decoded.bytes()));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
  String request=json.writeValueAsString(Arrays.asList(template,version,digest));UUID id=UUID.randomUUID();
  return storage.files.exclusive(()->{
   // Retry abandoned files after a crash or uncertain commit; uploads hold the same file lock.
   storage.files.cleanup(clock.instant().minusSeconds(86400),store::imageReferenced);
   try{return tx.execute(s->{
    var t=templates.detail(actor,template);
    var at=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    String old=commands.replay(actor.accountId(),key,"TEMPLATE_IMAGE_CREATE",request,at);
    if(old!=null)return json.readValue(old,TemplateService.Created.class);
    version(t,version);storage.files.write(id,decoded);store.image(id,template,decoded,at);
    audit.template(actor.accountId(),Event.TEMPLATE_IMAGE_CREATED,template,false);
    var result=new TemplateService.Created(id,template,t.version());
    commands.save(actor.accountId(),key,id,"TEMPLATE_IMAGE_CREATE",request,json.writeValueAsString(result),at);return result;
   });}catch(RuntimeException failure){
    try{if(!store.imageReferenced(id))storage.files.remove(id);}catch(RuntimeException unavailable){/* Leave inaccessible orphan for later locked cleanup. */}
    throw failure;
   }
  });
 }
 public Content content(Principal actor,UUID template,UUID id){return storage.files.exclusive(()->tx.execute(s->{templates.detail(actor,template);var image=image(template,id);return new Content(storage.files.read(id),image.contentType());}));}
 public void delete(Principal actor,UUID template,UUID id,long version){storage.files.exclusive(()->{
  tx.executeWithoutResult(s->{var t=templates.detail(actor,template);version(t,version);var image=image(template,id);
   if(image.used())throw new AuthFailure(409,"TEMPLATE_IMAGE_IN_USE");store.deleteImage(id);audit.template(actor.accountId(),Event.TEMPLATE_IMAGE_DELETED,template,false);
  });storage.files.remove(id);return null;
 });}
}
