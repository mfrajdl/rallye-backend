package cz.mh.rallye.rallye_backend.templates;
import java.util.*;
import java.time.*;
import java.io.*;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import cz.mh.rallye.rallye_backend.templates.application.*;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import cz.mh.rallye.rallye_backend.audit.application.AuditService.Event;
import cz.mh.rallye.rallye_backend.shared.persistence.CommandStore;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class TemplateFileServiceTest {
 @TempDir Path directory;final TemplateStore store=mock(TemplateStore.class);final TemplateService templates=mock(TemplateService.class);final AuditService audit=mock(AuditService.class);final CommandStore commands=mock(CommandStore.class);final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
 final UUID id=UUID.randomUUID(),key=UUID.randomUUID();final AuthService.Principal actor=new AuthService.Principal(UUID.randomUUID(),UUID.randomUUID(),false);final Instant at=Instant.parse("2026-10-27T10:00:00Z");final List<Image> library=new ArrayList<>();TemplateFileService service;TemplateImageFiles storage;long v;String status;
 TemplateService.View parent(){return new TemplateService.View(id,"Template",null,0,status,v,at,at,List.of());}
 @BeforeEach void setup(){v=7;status="READY";storage=new TemplateImageFiles(directory.toString());service=new TemplateFileService(store,templates,storage,new TemplateFileContent(102400,10485760),audit,commands,Clock.fixed(at,ZoneOffset.UTC),manager);when(manager.getTransaction(any())).thenAnswer(c->new SimpleTransactionStatus());when(templates.detail(actor,id)).thenAnswer(c->parent());when(store.lock(id)).thenAnswer(c->new Template(id,"Template",null,0,status,v,at,at));when(store.images(id)).thenAnswer(c->List.copyOf(library));
  doAnswer(c->{TemplateFileContent.Validated data=c.getArgument(3);String name=c.getArgument(2);library.add(new Image(c.getArgument(0),id,name,ImageNames.key(name),data.contentType(),data.bytes().length,data.width(),data.height(),at,false,data.markdown()));return null;}).when(store).file(any(),eq(id),anyString(),any(),any());
  doAnswer(c->{status="DRAFT";v++;return null;}).when(store).touch(id,at);
 }
 TemplateFileService.Uploaded upload(String name,String text,long version){return service.upload(actor,id,version,key,name,new ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
 @Test void missingReferenceInvalidatesReadyAndAuditsWithoutSourceInLedger(){var result=upload("guide.md","[Later](later.pdf)",7);assertEquals(8,result.version());assertEquals("DRAFT",result.status());assertEquals(1,result.missingReferences().size());verify(audit).template(actor.accountId(),Event.TEMPLATE_DRAFTED,id,false);verify(audit).template(actor.accountId(),Event.TEMPLATE_FILE_CREATED,id,false);
  var request=org.mockito.ArgumentCaptor.forClass(String.class);verify(commands).save(eq(actor.accountId()),eq(key),any(),eq("TEMPLATE_FILE_UPLOAD"),request.capture(),anyString(),eq(at));assertFalse(request.getValue().contains("[Later]"));assertTrue(request.getValue().matches(".*[0-9a-f]{64}.*"));}
 @Test void validDocumentKeepsReadyAndAggregateVersion(){var result=upload("guide.md","# Text",7);assertEquals(7,result.version());assertEquals("READY",result.status());verify(store,never()).touch(any(),any());}
 @Test void missingReferenceInDraftDoesNotIncrementVersion(){status="DRAFT";var result=upload("guide.md","[Later](later.md)",7);assertEquals(7,result.version());verify(store,never()).touch(any(),any());}
 @Test void replayPrecedesVersionAndWrites(){when(commands.replay(eq(actor.accountId()),eq(key),eq("TEMPLATE_FILE_UPLOAD"),anyString(),eq(at))).thenReturn("{\"fileName\":\"guide.md\",\"templateId\":\""+id+"\",\"url\":\""+ImageNames.fileUrl(id,"guide.md")+"\",\"version\":3,\"status\":\"DRAFT\",\"missingReferences\":[]}");assertEquals(3,upload("guide.md","Text",1).version());verify(store,never()).file(any(),any(),any(),any(),any());verifyNoInteractions(audit);}
 @Test void staleVersionAndNameCollisionDoNotWrite(){assertEquals("TEMPLATE_VERSION_CONFLICT",assertThrows(AuthFailure.class,()->upload("guide.md","Text",6)).code());when(store.imageNameReferenced(id,"guide.md")).thenReturn(true);assertEquals("TEMPLATE_FILE_NAME_CONFLICT",assertThrows(AuthFailure.class,()->upload("guide.md","Text",7)).code());verifyNoInteractions(audit);}
 @Test void filesystemCollisionMapsToNewErrorAndPreservesBytes(){storage.files.exclusive(()->{storage.write(id,"guide.md","Original".getBytes(java.nio.charset.StandardCharsets.UTF_8));return null;});assertEquals("TEMPLATE_FILE_NAME_CONFLICT",assertThrows(AuthFailure.class,()->upload("guide.md","Replacement",7)).code());assertEquals("Original",new String(storage.read(id,"guide.md"),java.nio.charset.StandardCharsets.UTF_8));}
 @Test void unavailablePhysicalFileMapsToFileStorageError(){library.add(new Image(UUID.randomUUID(),id,"missing.pdf","MISSING.PDF","application/pdf",10,null,null,at,false));assertEquals("FILE_STORAGE_UNAVAILABLE",assertThrows(AuthFailure.class,()->service.content(actor,id,"missing.pdf")).code());}
 @Test void currentAuthorizationHappensBeforeReadingAnUpload(){doThrow(new AuthFailure(403,"ADMIN_REQUIRED")).when(templates).detail(actor,id);assertEquals(403,assertThrows(AuthFailure.class,()->upload("guide.md","Text",7)).status());verifyNoInteractions(commands,audit);}

 @Test void invalidImageReferenceUsesTheFrozenFileError(){assertEquals("TEMPLATE_FILE_REFERENCE_INVALID",assertThrows(AuthFailure.class,()->upload("guide.md","![Bad](https://evil.test/a.png)",7)).code());}
}
