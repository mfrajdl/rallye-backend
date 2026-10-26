package cz.mh.rallye.rallye_backend.templates;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import cz.mh.rallye.rallye_backend.templates.application.*;
import cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration;
import cz.mh.rallye.rallye_backend.templates.domain.StageConfiguration.*;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore.*;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import static org.junit.jupiter.api.Assertions.*;

class TemplateReadinessTest {
    final UUID id=UUID.randomUUID(),stageId=UUID.randomUUID();
    final Instant now=Instant.parse("2026-10-26T10:00:00Z");
    final Gps gps=new Gps(true,-90.0,180.0,0.5,-3L,0L);
    final Code code=new Code(true,"synthetic-hash",-5L);
    final StageConfiguration empty=StageConfiguration.empty();
    Template template(){return new Template(id,"Name",null,Long.MIN_VALUE,"DRAFT",3,now,now);}
    Stage stage(StageConfiguration config){return new Stage(stageId,id,1,"Station",0L,0L,"Instructions",config);}
    StageConfiguration config(Gps gps,Code code){return new StageConfiguration(gps,code,empty.timePenalty(),empty.delayed());}
    @Test void gpsCodeAndDoubleCheckHaveRequiredIndependentConfigurations(){
        assertTrue(TemplateReadiness.check(template(),List.of(stage(config(gps,empty.code())))).ready());
        assertTrue(TemplateReadiness.check(template(),List.of(stage(config(empty.gps(),code)))).ready());
        assertTrue(TemplateReadiness.check(template(),List.of(stage(config(gps,code)))).ready());
        var missingCode=TemplateReadiness.check(template(),List.of(stage(config(gps,new Code(true,null,null)))));
        assertFalse(missingCode.ready());assertTrue(missingCode.errors().stream().anyMatch(e->e.field().equals("code.codeConfigured")));
        var missingGps=TemplateReadiness.check(template(),List.of(stage(config(new Gps(true,null,null,null,null,null),code))));
        assertFalse(missingGps.ready());assertTrue(missingGps.errors().stream().anyMatch(e->e.field().equals("gps.latitude")));
        assertFalse(TemplateReadiness.check(template(),List.of(stage(empty))).ready());
    }
    @Test void incompleteAndEmptyTemplatesReportStableFieldsWithoutInputValues(){
        assertEquals("stages",TemplateReadiness.check(template(),List.of()).errors().getFirst().field());
        var report=TemplateReadiness.check(template(),List.of(new Stage(stageId,id,2,null,null,null)));
        assertFalse(report.ready());assertEquals(3,report.version());
        var fields=report.errors().stream().map(TemplateReadiness.Error::field).toList();
        assertTrue(fields.containsAll(List.of("name","instructionsHtml","sequenceNo","expectedDurationMs","maxPenaltyPoints","checks")));
        assertTrue(report.errors().stream().allMatch(e->stageId.equals(e.stageId())));
    }
    @Test void allStagesMustBeCompleteAndEnabledTimingRulesAreIndependent(){
        var both=new StageConfiguration(gps,code,new TimePenalty(true,1000L,-1L),new Delayed(true,0L,-2L));
        assertTrue(TemplateReadiness.check(template(),List.of(stage(both))).ready());
        var incomplete=new StageConfiguration(gps,code,new TimePenalty(true,null,null),new Delayed(true,null,null));
        var report=TemplateReadiness.check(template(),List.of(stage(incomplete)));
        assertEquals(Set.of("timePenalty.intervalSeconds","timePenalty.pointsPerInterval","delayed.minimumDurationMs","delayed.pointsPerSecond"),new HashSet<>(report.errors().stream().map(TemplateReadiness.Error::field).toList()));
        assertFalse(TemplateReadiness.check(template(),List.of(stage(both),new Stage(UUID.randomUUID(),id,2,null,null,null))).ready());
    }
    @ParameterizedTest @ValueSource(longs={Long.MIN_VALUE,0,Long.MAX_VALUE}) void everyRateSupportsItsFullSignedRange(long rate){
        var cfg=new StageConfiguration(new Gps(true,90.0,-180.0,1.0,rate,rate),new Code(true,"hash",rate),new TimePenalty(true,Long.MAX_VALUE/1000*1000,rate),new Delayed(true,Long.MAX_VALUE,rate));
        assertDoesNotThrow(cfg::validate);assertTrue(TemplateReadiness.check(template(),List.of(stage(cfg))).ready());
    }
    @Test void invalidValuesAreRejectedEvenForDisabledRules(){
        for(double latitude:new double[]{-90.01,90.01,Double.NaN,Double.POSITIVE_INFINITY})assertThrows(AuthFailure.class,()->config(new Gps(false,latitude,null,null,null,null),empty.code()).validate());
        for(double longitude:new double[]{-180.01,180.01,Double.NaN,Double.NEGATIVE_INFINITY})assertThrows(AuthFailure.class,()->config(new Gps(false,null,longitude,null,null,null),empty.code()).validate());
        for(double radius:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY})assertThrows(AuthFailure.class,()->config(new Gps(false,null,null,radius,null,null),empty.code()).validate());
        for(long interval:new long[]{0,-1,1,999,1001})assertThrows(AuthFailure.class,()->new StageConfiguration(empty.gps(),empty.code(),new TimePenalty(false,interval,0L),empty.delayed()).validate());
        assertThrows(AuthFailure.class,()->new StageConfiguration(empty.gps(),empty.code(),empty.timePenalty(),new Delayed(false,-1L,null)).validate());
    }
    @Test void normalizationUsesRootLocaleAndChecksNormalizedLength(){
        Locale previous=Locale.getDefault();
        try{Locale.setDefault(Locale.forLanguageTag("tr-TR"));assertEquals("KOD I",TemplateService.normalizeCode("  kod i  "));}finally{Locale.setDefault(previous);}
        assertEquals("SS".repeat(64),TemplateService.normalizeCode("ß".repeat(64)));
        assertThrows(AuthFailure.class,()->TemplateService.normalizeCode("ß".repeat(65)));
        assertEquals("A".repeat(128),TemplateService.normalizeCode(" a"+"a".repeat(127)+" "));
        for(String invalid:List.of("", " ","\t\n","a".repeat(129)))assertThrows(AuthFailure.class,()->TemplateService.normalizeCode(invalid));
    }
    @Test void whitespaceInstructionsAndNegativeMaximumCannotBeReady(){
        var report=TemplateReadiness.check(template(),List.of(new Stage(stageId,id,1,"Station",0L,-1L," \n",config(gps,code))));
        assertTrue(report.errors().stream().anyMatch(e->e.field().equals("instructionsHtml")));assertTrue(report.errors().stream().anyMatch(e->e.field().equals("maxPenaltyPoints")));
    }
}
