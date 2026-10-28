package cz.mh.rallye.rallye_backend.templates.application;

import java.time.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore.*;
import cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.shared.persistence.CommandStore;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TemplateDuplicationServiceTest {
 @TempDir Path directory;
 final TemplateService templates=mock(TemplateService.class);final TemplateStore store=mock(TemplateStore.class);
 final CommandStore commands=mock(CommandStore.class);final AuditService audit=mock(AuditService.class);
 final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
 final UUID source=UUID.randomUUID(),stage=UUID.randomUUID(),key=UUID.randomUUID();
 final AuthService.Principal actor=new AuthService.Principal(UUID.randomUUID(),UUID.randomUUID(),false);
 final Instant now=Instant.parse("2026-10-26T10:00:00Z");TemplateImageFiles files;TemplateDuplicationService service;
 @BeforeEach void setup(){files=new TemplateImageFiles(directory.toString());service=new TemplateDuplicationService(templates,store,files,commands,audit,Clock.fixed(now,ZoneOffset.UTC),manager);when(manager.getTransaction(any())).thenAnswer(c->new SimpleTransactionStatus());when(store.lock(source)).thenReturn(new Template(source,"Original","Description",-5,"READY",7,now,now));}
 @Test void createsIndependentDraftWithoutTouchingSource(){
  var result=service.duplicate(actor,source,key);assertNotEquals(source,result.id());assertEquals(result.id(),result.templateId());assertEquals(0,result.version());
  verify(store).insert(result.id(),"Original (kopie)","Description",-5,now);verify(store,never()).touch(any(),any());verify(templates,times(2)).authorize(actor);verify(audit).templateCopy(actor.accountId(),result.id(),source,result.id(),false);
 }
 @Test void unicodeNameTruncationIsWithin100CodePoints(){String name="🚗".repeat(100);String copy=TemplateDuplicationService.copyName(name);assertEquals(100,copy.codePointCount(0,copy.length()));assertTrue(copy.endsWith(" (kopie)"));assertFalse(copy.contains("\uFFFD"));assertEquals("A (kopie)",TemplateDuplicationService.copyName("A"));}
 @Test void replayPrecedesSourceLookupAndDoesNotWriteAnything(){
  UUID target=UUID.randomUUID();when(commands.replay(eq(actor.accountId()),eq(key),eq("TEMPLATE_DUPLICATE"),anyString(),eq(now))).thenReturn("{\"id\":\""+target+"\",\"templateId\":\""+target+"\",\"version\":0}");
  assertEquals(target,service.duplicate(actor,source,key).id());verifyNoInteractions(store,audit);
 }
 @Test void revokedRoleOrMissingKeyCannotCopyAnything(){doThrow(new AuthFailure(403,"ADMIN_REQUIRED")).when(templates).authorize(actor);assertEquals(403,assertThrows(AuthFailure.class,()->service.duplicate(actor,source,key)).status());verifyNoInteractions(store,commands,audit);}
 @Test void missingSourceAndStageReturnExisting404Codes(){when(store.lock(source)).thenReturn(null);assertEquals("TEMPLATE_NOT_FOUND",assertThrows(AuthFailure.class,()->service.duplicate(actor,source,key)).code());when(store.lock(source)).thenReturn(new Template(source,"Original",null,0,"DRAFT",7,now,now));assertEquals("TEMPLATE_STAGE_NOT_FOUND",assertThrows(AuthFailure.class,()->service.duplicateStage(actor,source,stage,7,key)).code());}
 @Test void stageIsInsertedImmediatelyAfterSourceAndTouchesOnce(){
  UUID other=UUID.randomUUID();var config=StageConfiguration.empty();var original=new Stage(stage,source,1,"Station",1200L,0L,"<p>Text</p>",config);
  when(store.stages(source)).thenReturn(List.of(original,new Stage(other,source,2,null,null,null)));
  var result=service.duplicateStage(actor,source,stage,7,key);assertEquals(8,result.version());
  verify(store).copyStage(original,result.id(),source,3,"<p>Text</p>");verify(store).order(source,List.of(stage,result.id(),other));verify(store).touch(source,now);verify(audit).templateCopy(actor.accountId(),result.id(),stage,source,true);
 }
 @Test void staleVersionDoesNotCopyStage(){assertEquals("TEMPLATE_VERSION_CONFLICT",assertThrows(AuthFailure.class,()->service.duplicateStage(actor,source,stage,6,key)).code());verify(store,never()).copyStage(any(),any(),any(),anyInt(),any());verifyNoInteractions(audit);}
 @Test void stageReplayAndConflictPrecedeNewVersionValidation(){
  when(commands.replay(eq(actor.accountId()),eq(key),eq("TEMPLATE_STAGE_DUPLICATE"),anyString(),eq(now))).thenThrow(new AuthFailure(409,"IDEMPOTENCY_CONFLICT"));
  assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AuthFailure.class,()->service.duplicateStage(actor,source,stage,8,key)).code());verifyNoInteractions(store,audit);
 }
 @Test void copiedFilesPreserveBytesAndAreRemovedWhenAuditFails()throws Exception{
  UUID image=UUID.randomUUID();byte[] bytes=cz.mh.rallye.rallye_backend.crews.PhotoFilesTest.image("png",2,2);
  files.files.exclusive(()->{files.write(source,"mapa.png",new cz.mh.rallye.rallye_backend.crews.application.PhotoFiles.Image(bytes,"image/png",2,2));return null;});
  when(store.images(source)).thenReturn(List.of(new Image(image,source,"mapa.png","MAPA.PNG","image/png",bytes.length,2,2,now,false)));
  doThrow(new IllegalStateException("Injected")).when(audit).templateCopy(any(),any(),any(),any(),eq(false));
  assertThrows(IllegalStateException.class,()->service.duplicate(actor,source,key));
  try(var entries=Files.list(directory)){assertEquals(Set.of("race-templates",".storage.lock"),entries.map(p->p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));}
  assertArrayEquals(bytes,files.files.exclusive(()->files.read(source,"mapa.png")));
 }
 @Test void inconsistentUsageFailsClosed(){var original=new Stage(stage,source,1,null,null,null,"<p>Text</p>",StageConfiguration.empty());when(store.stages(source)).thenReturn(List.of(original));when(store.usedImages(stage)).thenReturn(Set.of(UUID.randomUUID()));assertEquals("TEMPLATE_COPY_INCONSISTENT",assertThrows(AuthFailure.class,()->service.duplicateStage(actor,source,stage,7,key)).code());verifyNoInteractions(audit);}
}
