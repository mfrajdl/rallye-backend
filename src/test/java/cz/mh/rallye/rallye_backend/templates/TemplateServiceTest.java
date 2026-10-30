package cz.mh.rallye.rallye_backend.templates;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import cz.mh.rallye.rallye_backend.templates.application.TemplateService;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import cz.mh.rallye.rallye_backend.audit.application.AuditService.Event;
import cz.mh.rallye.rallye_backend.shared.persistence.CommandStore;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TemplateServiceTest {
    final TemplateStore store=mock(TemplateStore.class); final AdminStore roles=mock(AdminStore.class);
    final AccountStore accounts=mock(AccountStore.class);final CommandStore commands=mock(CommandStore.class);final AuditService audit=mock(AuditService.class);
    final Instant now=Instant.parse("2026-10-26T10:00:00Z");final UUID id=UUID.randomUUID(),actor=UUID.randomUUID(),session=UUID.randomUUID(),stage=UUID.randomUUID();
    final AuthService.Principal principal=new AuthService.Principal(actor,session,false);
    TemplateService service;
    Template template(String status){return new Template(id,"Name","Description",-3,status,7,now,now);}
    @BeforeEach void setup(){service=new TemplateService(store,roles,accounts,commands,audit,Clock.fixed(now,ZoneOffset.UTC),mock(cz.mh.rallye.rallye_backend.templates.application.TemplateImageFiles.class));when(roles.activeVerified(actor)).thenReturn(true);when(roles.administrator(actor)).thenReturn(true);when(accounts.isActiveSession(session,actor,now)).thenReturn(true);when(store.lock(id)).thenReturn(template("READY"));}
    @Test void currentRoleAndSessionAreRequired(){
        assertEquals(401,assertThrows(AuthFailure.class,()->service.detail(null,id)).status());
        when(roles.administrator(actor)).thenReturn(false);assertEquals(403,assertThrows(AuthFailure.class,()->service.detail(principal,id)).status());verifyNoInteractions(store);
        when(roles.administrator(actor)).thenReturn(true);when(accounts.isActiveSession(session,actor,now)).thenReturn(false);assertEquals(401,assertThrows(AuthFailure.class,()->service.detail(principal,id)).status());verifyNoInteractions(store);
    }
    @Test void updateNormalizesAndNoOpKeepsReadyVersionAndAudit(){var v=service.update(principal,id,7," Name ","Description",-3);assertEquals(7,v.version());assertEquals("READY",v.status());verify(store,never()).update(any(),any(),any(),anyLong(),anyBoolean(),any());verifyNoInteractions(audit);}
    @Test void nameOnlyIsNotSubstantialButSignedPenaltyIs(){service.update(principal,id,7,"Changed","Description",-3);verify(store).update(id,"Changed","Description",-3,0L,false,now);service.update(principal,id,7,"Name","Description",-4);verify(store).update(id,"Name","Description",-4,0L,true,now);}
    @Test void versionIsCheckedEvenForNoOp(){assertEquals("TEMPLATE_VERSION_CONFLICT",assertThrows(AuthFailure.class,()->service.update(principal,id,6,"Name","Description",-3)).code());verifyNoInteractions(audit);}
    @Test void createReplaysBeforeAnyInsertAndNeverTreatsTargetAsAccount(){UUID key=UUID.randomUUID();when(commands.replay(eq(actor),eq(key),eq("TEMPLATE_CREATE"),anyString(),eq(now))).thenReturn("{\"id\":\""+id+"\",\"templateId\":\""+id+"\",\"version\":0}");assertEquals(id,service.create(principal,key,"Name",null,Long.MIN_VALUE).id());verify(store,never()).insert(any(),any(),any(),anyLong(),any());verifyNoInteractions(audit);}
    @Test void stageReplayPrecedesVersionAndResourceChecks(){UUID key=UUID.randomUUID();when(commands.replay(eq(actor),eq(key),eq("TEMPLATE_STAGE_CREATE"),anyString(),eq(now))).thenReturn("{\"id\":\""+stage+"\",\"templateId\":\""+id+"\",\"version\":8}");assertEquals(stage,service.createStage(principal,id,7,key,null,null,null).id());verify(store,never()).lock(any());}
    @Test void incompleteStageIsAllowedAndTouchesParent(){service.createStage(principal,id,7,UUID.randomUUID(),null,null,0L);verify(store).insertStage(any(),eq(id),eq(1),isNull(),isNull(),eq(0L));verify(store).touch(id,now);verify(audit).template(eq(actor),eq(Event.TEMPLATE_STAGE_CREATED),any(),eq(true));}
    @Test void stageUpdateNoOpKeepsState(){when(store.stages(id)).thenReturn(List.of(new Stage(stage,id,1,null,null,0L)));service.updateStage(principal,id,stage,7," ",null,0L);verify(store,never()).touch(any(),any());verify(store,never()).updateStage(any(),any(),any(),any());verifyNoInteractions(audit);}
    @Test void changedStageTouchesParentAndAudits(){when(store.stages(id)).thenReturn(List.of(new Stage(stage,id,1,"Old",null,null)));service.updateStage(principal,id,stage,7,"New",0L,0L);verify(store).updateStage(stage,"New",0L,0L);verify(store).touch(id,now);}
    @Test void deleteStageRenumbersRemaining(){UUID other=UUID.randomUUID();when(store.stages(id)).thenReturn(List.of(new Stage(stage,id,1,null,null,null),new Stage(other,id,2,null,null,null)),List.of(new Stage(other,id,2,null,null,null)));service.deleteStage(principal,id,stage,7);verify(store).deleteStage(stage);verify(store).order(id,List.of(other));verify(store).touch(id,now);}
    @Test void orderMustBeExactPermutationAndNoOpIsSilent(){when(store.stages(id)).thenReturn(List.of(new Stage(stage,id,1,null,null,null)));for(var ids:List.of(List.<UUID>of(),List.of(stage,stage),List.of(UUID.randomUUID())))assertEquals(400,assertThrows(AuthFailure.class,()->service.order(principal,id,7,ids)).status());service.order(principal,id,7,List.of(stage));verify(store,never()).touch(any(),any());verifyNoInteractions(audit);}
    @Test void reorderAuditsOnce(){UUID other=UUID.randomUUID();when(store.stages(id)).thenReturn(List.of(new Stage(stage,id,1,null,null,null),new Stage(other,id,2,null,null,null)));service.order(principal,id,7,List.of(other,stage));verify(store).order(id,List.of(other,stage));verify(audit).template(actor,Event.TEMPLATE_REORDERED,id,false);}
    @Test void cannotPublishReadyAndDraftNoOpIsSilent(){assertThrows(cz.mh.rallye.rallye_backend.templates.application.TemplateReadiness.NotReady.class,()->service.state(principal,id,7,"READY"));when(store.lock(id)).thenReturn(template("DRAFT"));service.state(principal,id,7,"DRAFT");verify(store,never()).state(any(),any(),any());verifyNoInteractions(audit);}
    @Test void deleteChecksUsageBeforeRemoval(){service.delete(principal,id,7);var order=inOrder(store,audit);order.verify(store).lock(id);order.verify(store).ensureDeletionSupported(id);order.verify(store).delete(id);order.verify(audit).template(actor,Event.TEMPLATE_DELETED,id,false);}
    @Test void missingTemplateAndForeignStageAre404(){when(store.lock(id)).thenReturn(null);assertEquals(404,assertThrows(AuthFailure.class,()->service.detail(principal,id)).status());when(store.lock(id)).thenReturn(template("DRAFT"));assertEquals(404,assertThrows(AuthFailure.class,()->service.stageDetail(principal,id,stage)).status());}
    @ParameterizedTest @ValueSource(longs={-1,Long.MIN_VALUE}) void negativeDurationAndMaximumAreRejected(long negative){assertEquals(400,assertThrows(AuthFailure.class,()->service.createStage(principal,id,7,UUID.randomUUID(),null,negative,null)).status());assertEquals(400,assertThrows(AuthFailure.class,()->service.createStage(principal,id,7,UUID.randomUUID(),null,null,negative)).status());verify(store,never()).touch(any(),any());}
    @Test void listValidatesFiltersAndHasMore(){for(int[] p:List.of(new int[]{-1,50},new int[]{0,0},new int[]{10001,50},new int[]{0,101}))assertThrows(AuthFailure.class,()->service.list(principal,null,null,p[0],p[1]));assertThrows(AuthFailure.class,()->service.list(principal,null,"OTHER",0,50));when(store.list("Name","DRAFT",0,1)).thenReturn(List.of(template("DRAFT"),template("DRAFT")));var page=service.list(principal," Name ","DRAFT",0,1);assertTrue(page.hasMore());assertEquals(1,page.items().size());}
    @Test void invalidMetadataAndNullKeyAreRejected(){for(String name:List.of(" ","x".repeat(101)))assertThrows(AuthFailure.class,()->service.create(principal,UUID.randomUUID(),name,null,0));assertThrows(AuthFailure.class,()->service.create(principal,UUID.randomUUID(),"Name","x".repeat(2001),0));assertThrows(AuthFailure.class,()->service.create(principal,null,"Name",null,0));verifyNoInteractions(audit);}
    private cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration complete(){
        return new cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration(
            new cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration.Gps(true,0.0,0.0,1.0,-1L,0L),
            new cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration.Code(true,"SAME",-2L),
            new cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration.TimePenalty(false,null,null),
            new cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration.Delayed(false,null,null));
    }
    private TemplateService.ConfigurationChange change(String newCode){var c=complete();return new TemplateService.ConfigurationChange(c.gps(),new TemplateService.CodeChange(true,newCode,-2L),c.timePenalty(),c.delayed());}
    @Test void codeReplacementStoresNormalizedValueAndNeverUsesCommands(){
        var c=complete();when(store.stages(id)).thenReturn(List.of(new Stage(stage,id,1,"Station",0L,0L,"Text",c)));
        service.configure(principal,id,stage,7,change(" new code "));
        var captor=org.mockito.ArgumentCaptor.forClass(cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration.class);verify(store).configureStage(eq(stage),captor.capture());assertEquals("NEW CODE",captor.getValue().code().value());
        verify(store).touch(id,now);verifyNoInteractions(commands);
        assertFalse(change("secret-value").toString().contains("secret-value"));assertFalse(new TemplateService.CodeChange(true,"secret-value",0L).toString().contains("secret-value"));
    }
    @Test void absentOrEquivalentCodeIsNoOpAndAdminCanReadValue(){
        when(store.stages(id)).thenReturn(List.of(new Stage(stage,id,1,"Station",0L,0L,"Text",complete())));
        service.configure(principal,id,stage,7,change(null));service.configure(principal,id,stage,7,change(" same "));
        verify(store,never()).configureStage(any(),any());verify(store,never()).touch(any(),any());verifyNoInteractions(commands,audit);
        var view=service.detail(principal,id);assertTrue(view.stages().getFirst().code().codeConfigured());
        String json=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(view);assertTrue(json.contains("SAME"));assertFalse(json.contains("newCode"));assertFalse(json.contains("statisticsVersion"));
    }
    @Test void incompleteGpsDraftCanBeSavedButInvalidDisabledCoordinatesCannot(){
        var empty=cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration.empty();when(store.stages(id)).thenReturn(List.of(new Stage(stage,id,1,null,null,null)));
        var partial=new TemplateService.ConfigurationChange(new cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration.Gps(true,null,null,null,null,null),new TemplateService.CodeChange(false,null,null),empty.timePenalty(),empty.delayed());
        service.configure(principal,id,stage,7,partial);verify(store).touch(id,now);
        var invalid=new TemplateService.ConfigurationChange(new cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration.Gps(false,91.0,null,null,null,null),partial.code(),empty.timePenalty(),empty.delayed());
        assertThrows(AuthFailure.class,()->service.configure(principal,id,stage,7,invalid));verifyNoInteractions(commands);
    }
    @Test void readinessDoesNotWriteAndStateTransitionsAreVersionedAuditedNoOps(){
        when(store.stages(id)).thenReturn(List.of(new Stage(stage,id,1,"Station",0L,0L,"Text",complete())));when(store.lock(id)).thenReturn(template("DRAFT"));
        assertTrue(service.readiness(principal,id).ready());verifyNoInteractions(audit);verify(store,never()).state(any(),any(),any());
        service.state(principal,id,7,"READY");verify(store).state(id,"READY",now);verify(audit).template(actor,Event.TEMPLATE_READIED,id,false);
        clearInvocations(store,audit);when(store.lock(id)).thenReturn(template("READY"));service.state(principal,id,7,"READY");verifyNoInteractions(audit);verify(store,never()).state(any(),any(),any());
        service.state(principal,id,7,"DRAFT");verify(store).state(id,"DRAFT",now);verify(audit).template(actor,Event.TEMPLATE_DRAFTED,id,false);
    }
    @Test void plainInstructionsSupportPreservationClearingAndNoOps(){
        when(store.stages(id)).thenReturn(List.of(new Stage(stage,id,1,"Station",0L,0L,"Text",complete())));
        service.updateStage(principal,id,stage,7,"Station",0L,0L);service.updateStage(principal,id,stage,7,"Station",0L,0L,true,"Text");verify(store,never()).touch(any(),any());
        service.updateStage(principal,id,stage,7,"Station",0L,0L,true,null);verify(store).instructionsMarkdown(stage,null);verify(store).touch(id,now);
        assertThrows(AuthFailure.class,()->service.updateStage(principal,id,stage,7,"Station",0L,0L,true,"x".repeat(102401)));
    }

    @Test void markdownNoOpPreservesExactSource(){
        String source="# Title  \r\n\nText & **bold**";
        when(store.stages(id)).thenReturn(List.of(new Stage(stage,id,1,"Station",0L,0L,source,complete())));
        service.updateStage(principal,id,stage,7,"Station",0L,0L,true,source);verify(store,never()).touch(any(),any());verifyNoInteractions(audit);
    }
    @Test void missingImageIsPreservedAsForwardReference(){
        when(store.stages(id)).thenReturn(List.of(new Stage(stage,id,1,"Station",0L,0L)));
        service.updateStage(principal,id,stage,7,"Station",0L,0L,true,"![x](missing.png)");verify(store).instructionsMarkdown(stage,"![x](missing.png)");verify(store).references(id,stage,Set.of());verify(store).touch(id,now);
    }
    @Test void sourceAndUniqueImageReferencesAreSavedTogether(){
        UUID image=UUID.randomUUID();when(store.images(id)).thenReturn(List.of(new TemplateStore.Image(image,id,"most.png","MOST.PNG","image/png",5,1,1,now,false)));
        when(store.stages(id)).thenReturn(List.of(new Stage(stage,id,1,"Station",0L,0L)));
        String markdown="![Most](most.png) ![Most](most.png)";
        service.updateStage(principal,id,stage,7,"Station",0L,0L,true,markdown);
        verify(store).instructionsMarkdown(stage,markdown);verify(store).references(id,stage,Set.of(image));verify(store).touch(id,now);
    }
    @Test void absentMaxPenaltyIsZeroAndDefaultCodePenaltyCanBeInherited(){
        var c=complete();var inherited=new cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration(c.gps(),new cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration.Code(true,"SAME",null),c.timePenalty(),c.delayed());
        when(store.lock(id)).thenReturn(new Template(id,"Name","Description",-3,"DRAFT",7,now,now,9));
        when(store.stages(id)).thenReturn(List.of(new Stage(stage,id,1,"Station",0L,null,"Text",inherited)));
        assertTrue(service.readiness(principal,id).ready());var view=service.detail(principal,id);assertEquals(0,view.stages().getFirst().maxPenaltyPoints());assertEquals(9,view.defaultWrongCodePenaltyPoints());assertNull(view.stages().getFirst().code().wrongAttemptPenaltyPoints());assertEquals(9,view.stages().getFirst().code().effectiveWrongAttemptPenaltyPoints());
        assertEquals(0,TemplateService.codeView(new cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration.Code(true,"SAME",0L),9).effectiveWrongAttemptPenaltyPoints());
    }
    @Test void legacyHashWithoutValueIsExplicitlyUnavailableAndCannotBeReady(){var c=complete();var legacy=new cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration(c.gps(),new cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration.Code(true,null,-2L,true),c.timePenalty(),c.delayed());when(store.stages(id)).thenReturn(List.of(new Stage(stage,id,1,"Station",0L,null,"Text",legacy)));assertFalse(service.readiness(principal,id).ready());var code=service.detail(principal,id).stages().getFirst().code();assertNull(code.value());assertTrue(code.valueUnavailable());assertFalse(code.codeConfigured());service.configure(principal,id,stage,7,change("recovered"));var cap=org.mockito.ArgumentCaptor.forClass(cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration.class);verify(store).configureStage(eq(stage),cap.capture());assertEquals("RECOVERED",cap.getValue().code().value());assertFalse(cap.getValue().code().valueUnavailable());}
    @Test void codeComparisonUsesNormalizedPlaintextAndNeverHashes(){var code=new cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration.Code(true,"ABC 12",null);assertTrue(code.matches(" abc 12 "));assertFalse(code.matches("other"));assertFalse(code.matches(null));assertFalse(new cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration.Code(true,null,null,true).matches("anything"));assertFalse(code.toString().contains("ABC 12"));}
    @Test void globalDefaultIsSubstantialAndIsPartOfCreateIdempotencyParameters(){service.update(principal,id,7,"Name","Description",-3,8L);verify(store).update(id,"Name","Description",-3,8L,true,now);UUID key=UUID.randomUUID();service.create(principal,key,"Name",null,0,8);verify(commands).replay(eq(actor),eq(key),eq("TEMPLATE_CREATE"),eq("[\"Name\",null,0,8]"),eq(now));}


    @Test void storageNormalizationRetainsDiacriticsWhileComparisonAcceptsVariants(){
        when(store.stages(id)).thenReturn(List.of(new Stage(stage,id,1,"Station",0L,0L,"Text",complete())));
        service.configure(principal,id,stage,7,change(" příšera | kůň "));
        var cap=org.mockito.ArgumentCaptor.forClass(cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration.class);verify(store).configureStage(eq(stage),cap.capture());
        assertEquals("PŘÍŠERA | KŮŇ",cap.getValue().code().value());assertTrue(cap.getValue().code().matches("prisera"));assertTrue(cap.getValue().code().matches("kun"));assertFalse(cap.getValue().code().matches(""));
        assertEquals("PŘÍŠERA",TemplateService.normalizeCode(" příšera "));verifyNoInteractions(commands);
    }
    @Test void invalidVariantsDoNotWriteDefinitionOrAudit(){
        when(store.stages(id)).thenReturn(List.of(new Stage(stage,id,1,"Station",0L,0L,"Text",complete())));
        for(String text:List.of("A|","|A","A| |B"))assertThrows(AuthFailure.class,()->service.configure(principal,id,stage,7,change(text)));
        verify(store,never()).configureStage(any(),any());verify(store,never()).touch(any(),any());verifyNoInteractions(commands,audit);
    }
}
