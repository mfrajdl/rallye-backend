package cz.mh.rallye.rallye_backend.templates.application;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore.Stage;
import cz.mh.rallye.rallye_backend.crews.application.PhotoFiles;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import cz.mh.rallye.rallye_backend.identity.application.AuthService.Principal;
import cz.mh.rallye.rallye_backend.shared.persistence.CommandStore;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;

/** Snapshot copies use the existing template lock, command ledger and private immutable image files. */
@Service
public class TemplateDuplicationService {
    private final TemplateService templates;private final TemplateStore store;private final TemplateImageFiles storage;
    private final CommandStore commands;private final AuditService audit;private final Clock clock;private final TransactionTemplate tx;
    private final JsonMapper json=JsonMapper.builder().build();
    public TemplateDuplicationService(TemplateService templates,TemplateStore store,TemplateImageFiles storage,CommandStore commands,AuditService audit,Clock clock,PlatformTransactionManager manager){
        this.templates=templates;this.store=store;this.storage=storage;this.commands=commands;this.audit=audit;this.clock=clock;tx=new TransactionTemplate(manager);
    }
    private Instant now(){return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);}
    public static String copyName(String source){String suffix=" (kopie)";int count=Math.min(source.codePointCount(0,source.length()),100-suffix.length());return source.substring(0,source.offsetByCodePoints(0,count))+suffix;}
    private TemplateService.Created replay(Principal actor,UUID key,String operation,String request){
        if(key==null)throw new AuthFailure(400,"INVALID_REQUEST");
        var old=commands.replay(actor.accountId(),key,operation,request,now());return old==null?null:json.readValue(old,TemplateService.Created.class);
    }
    private void save(Principal actor,UUID key,String operation,String request,TemplateService.Created result){commands.save(actor.accountId(),key,result.id(),operation,request,json.writeValueAsString(result),now());}
    private TemplateStore.Template source(UUID id){var t=store.lock(id);if(t==null)throw new AuthFailure(404,"TEMPLATE_NOT_FOUND");return t;}
    private Set<UUID> instructions(UUID template,Stage stage,List<TemplateStore.Image> library){
        var ids=TemplateFileReferences.imageIds(template,stage.instructionsMarkdown(),library);
        if(!ids.equals(store.usedImages(stage.id())))throw new AuthFailure(503,"TEMPLATE_COPY_INCONSISTENT");return ids;
    }
    private record Written(UUID id,UUID template,String name){}
    public TemplateService.Created duplicate(Principal actor,UUID source,UUID key){
        // Authorization before touching storage; it is repeated in the actual mutation transaction.
        tx.executeWithoutResult(s->templates.authorize(actor));
        return storage.files.exclusive(()->{
            var written=new ArrayList<Written>();
            try{return tx.execute(s->{
                templates.authorize(actor);String request=json.writeValueAsString(List.of(source));
                var old=replay(actor,key,"TEMPLATE_DUPLICATE",request);if(old!=null)return old;
                var original=source(source);var stages=store.stages(source);UUID target=UUID.randomUUID();
                store.insert(target,copyName(original.name()),original.description(),original.skipPenaltyPoints(),now());
                var mapping=new HashMap<UUID,UUID>();
                var library=store.images(source);for(var image:library){
                    UUID id=UUID.randomUUID();byte[] bytes=storage.read(source,image.fileName());
                    if(bytes.length!=image.byteSize())throw new AuthFailure(503,"TEMPLATE_COPY_INCONSISTENT");
                    String markdown=null;
                    if(image.isMarkdown()){
                        if(!Arrays.equals(bytes,image.markdownSource().getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw new AuthFailure(503,"TEMPLATE_COPY_INCONSISTENT");
                        markdown=InstructionsMarkdown.copy(source,target,image.markdownSource());bytes=markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    }
                    var content=new TemplateFileContent.Validated(bytes,image.contentType(),image.width(),image.height(),markdown);
                    storage.write(target,image.fileName(),bytes);written.add(new Written(id,target,image.fileName()));store.file(id,target,image.fileName(),content,now());mapping.put(image.id(),id);
                }
                for(var stage:stages){
                    var oldReferences=instructions(source,stage,library);String markdown=InstructionsMarkdown.copy(source,target,stage.instructionsMarkdown());
                    UUID id=UUID.randomUUID();store.copyStage(stage,id,target,stage.sequenceNo(),markdown);store.references(target,id,oldReferences.stream().map(mapping::get).collect(java.util.stream.Collectors.toSet()));
                }
                audit.templateCopy(actor.accountId(),target,source,target,false);
                var result=new TemplateService.Created(target,target,0);save(actor,key,"TEMPLATE_DUPLICATE",request,result);return result;
            });}catch(RuntimeException failure){
                // On an uncertain commit, never remove a file still referenced by the database.
                for(var item:written)try{if(!store.imageNameReferenced(item.template(),item.name()))storage.remove(item.template(),item.name());}catch(RuntimeException unavailable){/* Existing 24h locked orphan cleanup will retry. */}
                throw failure;
            }
        });
    }
    public TemplateService.Created duplicateStage(Principal actor,UUID template,UUID stageId,long version,UUID key){
        return tx.execute(s->{
            templates.authorize(actor);if(version<0)throw new AuthFailure(400,"INVALID_REQUEST");
            String request=json.writeValueAsString(List.of(template,stageId,version));
            var old=replay(actor,key,"TEMPLATE_STAGE_DUPLICATE",request);if(old!=null)return old;
            var parent=source(template);if(parent.version()!=version)throw new AuthFailure(409,"TEMPLATE_VERSION_CONFLICT");
            var stages=store.stages(template);var source=stages.stream().filter(row->row.id().equals(stageId)).findFirst().orElseThrow(()->new AuthFailure(404,"TEMPLATE_STAGE_NOT_FOUND"));
            var referenced=instructions(template,source,store.images(template));
            UUID id=UUID.randomUUID();store.copyStage(source,id,template,stages.size()+1,source.instructionsMarkdown());store.references(template,id,referenced);
            var order=new ArrayList<>(stages.stream().map(Stage::id).toList());order.add(source.sequenceNo(),id);store.order(template,order);store.touch(template,now());
            audit.templateCopy(actor.accountId(),id,stageId,template,true);
            var result=new TemplateService.Created(id,template,Math.incrementExact(version));save(actor,key,"TEMPLATE_STAGE_DUPLICATE",request,result);return result;
        });
    }
}
