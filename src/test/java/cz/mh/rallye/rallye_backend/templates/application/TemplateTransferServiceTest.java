package cz.mh.rallye.rallye_backend.templates.application;
import java.io.*;import java.nio.file.*;import java.time.*;import java.util.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import org.springframework.transaction.*;import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore;import cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration;import cz.mh.rallye.rallye_backend.shared.persistence.CommandStore;import cz.mh.rallye.rallye_backend.audit.application.AuditService;import cz.mh.rallye.rallye_backend.identity.application.*;
class TemplateTransferServiceTest {
 @TempDir Path directory;final TemplateService templates=mock(TemplateService.class);final TemplateStore store=mock(TemplateStore.class);final CommandStore commands=mock(CommandStore.class);final AuditService audit=mock(AuditService.class);final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);final UUID source=UUID.randomUUID(),stage=UUID.randomUUID();final Instant now=TemplateArchiveTest.AT;final AuthService.Principal actor=new AuthService.Principal(UUID.randomUUID(),UUID.randomUUID(),false);TemplateImageFiles files;TemplateTransferService service;
 @BeforeEach void setup(){files=new TemplateImageFiles(directory.toString());service=new TemplateTransferService(templates,store,files,new TemplateFileContent(102400,10485760),commands,audit,Clock.fixed(now,ZoneOffset.UTC),manager);when(manager.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());when(store.lock(source)).thenReturn(new TemplateStore.Template(source,"Source","Description",-2,"READY",3,now,now,7));}
 TemplateStore.Image md(String name,String text){byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);files.write(source,name,bytes);return new TemplateStore.Image(UUID.randomUUID(),source,name,ImageNames.key(name),"text/markdown",bytes.length,null,null,now,false,text);}
 void station(String text){when(store.stages(source)).thenReturn(List.of(new TemplateStore.Stage(stage,source,1,"S",60000L,0L,text,StageConfiguration.empty())));}
 @Test void exportsOnlyReachableFilesIncludingCyclesAndRewritesInstallationPaths(){var a=md("a.md","[B](b.md)");var b=md("b.md","[A]("+ImageNames.fileUrl(source,"a.md")+")");var orphan=md("unused.md","Not used");station("[Guide]("+ImageNames.fileUrl(source,"a.md")+") [Again](a.md)");when(store.images(source)).thenReturn(List.of(a,b,orphan));var result=TemplateArchive.decode(service.export(actor,source));assertEquals(Set.of("a.md","b.md"),result.files().keySet());assertFalse(TemplateArchive.JSON.writeValueAsString(result.template()).contains(source.toString()));assertFalse(new String(result.files().get("b.md"),java.nio.charset.StandardCharsets.UTF_8).contains(source.toString()));assertEquals(60000L,result.template().stages().getFirst().expectedDurationMs());verify(audit).template(actor.accountId(),AuditService.Event.TEMPLATE_EXPORTED,source,false);}
 @Test void missingRequiredFileFailsBeforeAudit(){station("[Absent](no.md)");assertEquals("TEMPLATE_EXPORT_FILE_MISSING",assertThrows(AuthFailure.class,()->service.export(actor,source)).code());verifyNoInteractions(audit);}
 @Test void missingPhysicalContentFailsWithoutIncompleteResponse(){var meta=new TemplateStore.Image(UUID.randomUUID(),source,"a.md","A.MD","text/markdown",1,null,null,now,false,"x");station("[A](a.md)");when(store.images(source)).thenReturn(List.of(meta));assertThrows(AuthFailure.class,()->service.export(actor,source));verifyNoInteractions(audit);}
 @Test void previewHasNoPersistentWrites(){var preview=service.preview(actor,new ByteArrayInputStream(TemplateArchiveTest.archive()));assertEquals(1,preview.stageCount());verifyNoInteractions(store,commands,audit);}
 @Test void failedValidationDoesNotCreateAnything(){assertThrows(AuthFailure.class,()->service.importTemplate(actor,UUID.randomUUID(),new ByteArrayInputStream(new byte[]{1})));verifyNoInteractions(store,commands,audit);}
 @Test void sameArchiveAndKeyUsesExistingLedgerWithoutWrites(){UUID id=UUID.randomUUID(),key=UUID.randomUUID();var created=new TemplateService.Created(id,id,0);when(commands.replay(eq(actor.accountId()),eq(key),eq("TEMPLATE_IMPORT"),anyString(),eq(now))).thenReturn(TemplateArchive.JSON.writeValueAsString(created));assertEquals(created,service.importTemplate(actor,key,new ByteArrayInputStream(TemplateArchiveTest.archive())));verifyNoInteractions(store,audit);}
 @Test void importsWithNewIdsAndOnlyDigestInLedger(){UUID key=UUID.randomUUID();var result=service.importTemplate(actor,key,new ByteArrayInputStream(TemplateArchiveTest.archive()));verify(store).insert(result.id(),"Export","Description",-2,Long.MIN_VALUE,now);verify(store).insertStage(argThat(id->!id.equals(stage)),eq(result.id()),eq(1),eq("Station"),eq(60000L),eq(0L));verify(store).configureStage(any(),eq(TemplateArchiveTest.definition("x").stages().getFirst().configuration().domain()));verify(commands).save(eq(actor.accountId()),eq(key),eq(result.id()),eq("TEMPLATE_IMPORT"),argThat(text->TemplateArchive.JSON.readTree(text).size()==1&&TemplateArchive.JSON.readTree(text).get(0).asString().matches("[0-9a-f]{64}")),anyString(),eq(now));verify(audit).template(actor.accountId(),AuditService.Event.TEMPLATE_IMPORTED,result.id(),false);verify(store,never()).touch(any(),any());}
 @Test void auditFailureRollsBackAndRemovesWrittenFiles()throws Exception{doThrow(new IllegalStateException("Injected audit failure")).when(audit).template(any(),eq(AuditService.Event.TEMPLATE_IMPORTED),any(),eq(false));assertThrows(IllegalStateException.class,()->service.importTemplate(actor,UUID.randomUUID(),new ByteArrayInputStream(TemplateArchiveTest.archive())));verify(manager).rollback(any());verify(commands,never()).save(any(),any(),any(),any(),any(),any(),any());try(var stream=Files.walk(directory)){assertEquals(0,stream.filter(Files::isRegularFile).filter(p->!p.getFileName().toString().equals(".storage.lock")).count());}}

 @Test void failedSecondFileWriteCleansFirstFileAndDoesNotCommit()throws Exception{
  var failing=new TemplateImageFiles(directory.resolve("failing").toString()){
   int count;@Override public void write(UUID template,String name,byte[] bytes){if(++count==2)throw new AuthFailure(503,"PHOTO_STORAGE_UNAVAILABLE");super.write(template,name,bytes);}
  };
  service=new TemplateTransferService(templates,store,failing,new TemplateFileContent(102400,10485760),commands,audit,Clock.fixed(now,ZoneOffset.UTC),manager);
  var data=new LinkedHashMap<String,byte[]>();data.put("a.md","[B](b.md)".getBytes(java.nio.charset.StandardCharsets.UTF_8));data.put("b.md","Text".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  var archive=TemplateArchive.encode(TemplateArchiveTest.definition("[A](a.md)"),data,Map.of("a.md","text/markdown","b.md","text/markdown"),now);
  assertThrows(AuthFailure.class,()->service.importTemplate(actor,UUID.randomUUID(),new ByteArrayInputStream(archive)));verify(manager).rollback(any());verifyNoInteractions(audit);
  try(var stream=Files.walk(directory)){assertEquals(0,stream.filter(Files::isRegularFile).filter(p->!p.getFileName().toString().equals(".storage.lock")).count());}
 }
 @Test void wrongMimeAndNonImageImageReferenceAreRejectedBeforeWrites(){
  byte[] wrong=TemplateArchive.encode(TemplateArchiveTest.definition("[A](a.md)"),Map.of("a.md","Text".getBytes(java.nio.charset.StandardCharsets.UTF_8)),Map.of("a.md","application/pdf"),now);
  assertThrows(AuthFailure.class,()->service.preview(actor,new ByteArrayInputStream(wrong)));
  byte[] image=TemplateArchive.encode(TemplateArchiveTest.definition("![A](a.md)"),Map.of("a.md","Text".getBytes(java.nio.charset.StandardCharsets.UTF_8)),Map.of("a.md","text/markdown"),now);
  assertThrows(AuthFailure.class,()->service.preview(actor,new ByteArrayInputStream(image)));verifyNoInteractions(store,commands,audit);
 }
 @Test void validPngAndJpegBytesArePreservedAfterValidation()throws Exception{
  for(String ext:List.of("png","jpg")){
   byte[] image=cz.mh.rallye.rallye_backend.crews.PhotoFilesTest.image(ext.equals("jpg")?"jpeg":"png",2,2);
   byte[] archive=TemplateArchive.encode(TemplateArchiveTest.definition("![Image](map."+ext+")"),Map.of("map."+ext,image),Map.of("map."+ext,ext.equals("jpg")?"image/jpeg":"image/png"),now);
   assertEquals(1,service.preview(actor,new ByteArrayInputStream(archive)).fileCount());
   service.importTemplate(actor,UUID.randomUUID(),new ByteArrayInputStream(archive));
   verify(store).file(any(),any(),eq("map."+ext),argThat(v->Arrays.equals(image,v.bytes())),eq(now));
  }
 }
}
