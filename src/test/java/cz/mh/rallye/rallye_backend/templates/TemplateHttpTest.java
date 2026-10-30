package cz.mh.rallye.rallye_backend.templates;

import java.util.*;
import java.time.Instant;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import cz.mh.rallye.rallye_backend.identity.api.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.templates.api.TemplateController;
import cz.mh.rallye.rallye_backend.templates.api.TemplateErrors;
import cz.mh.rallye.rallye_backend.templates.application.TemplateReadiness;
import cz.mh.rallye.rallye_backend.templates.application.TemplateService;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@WebMvcTest(TemplateController.class)
@Import({AuthConfiguration.class,AuthCookies.class,ApiErrors.class,TemplateErrors.class})
class TemplateHttpTest {
    @Autowired MockMvc mvc;@MockitoBean AuthService auth;@MockitoBean TemplateService service;
    final String secret=new SecretTokens().create();final UUID id=UUID.randomUUID();final AuthService.Principal principal=new AuthService.Principal(UUID.randomUUID(),UUID.randomUUID(),false);
    void login(boolean admin){when(auth.authenticate(secret)).thenReturn(principal);when(auth.isAdministrator(principal)).thenReturn(admin);}
    @Test void allRoutesAreAdminOnly()throws Exception{
        mvc.perform(get("/api/v1/race-templates")).andExpect(status().isUnauthorized());login(false);
        mvc.perform(get("/api/v1/race-templates").header("Authorization","Bearer "+secret)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/race-templates").header("Authorization","Bearer "+secret).contentType("application/json").content("{}" )).andExpect(status().isForbidden());verifyNoInteractions(service);
    }
    @Test void cookieNeedsCsrfEvenWithMobileHeader()throws Exception{login(true);mvc.perform(post("/api/v1/race-templates").cookie(new Cookie(AuthCookies.NAME,secret)).header("X-Rallye-Client","mobile").contentType("application/json").content("{}" )).andExpect(status().isForbidden());verifyNoInteractions(service);}
    @Test void createAcceptsSignedPointsAndReturnsLocation()throws Exception{login(true);UUID key=UUID.randomUUID();when(service.create(principal,key,"Name",null,-3,0)).thenReturn(new TemplateService.Created(id,id,0));mvc.perform(post("/api/v1/race-templates").header("Authorization","Bearer "+secret).header("Idempotency-Key",key).contentType("application/json").content("{\"name\":\"Name\",\"skipPenaltyPoints\":-3}"))
        .andExpect(status().isCreated()).andExpect(header().string("Location","/api/v1/race-templates/"+id)).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.version").value(0));}
    @Test void malformedBodiesAndFutureSecretFieldsAreRejected()throws Exception{login(true);for(String body:List.of("{\"name\":\"Name\"}","{\"name\":\"Name\",\"skipPenaltyPoints\":0,\"status\":\"READY\"}","{\"name\":\"Name\",\"skipPenaltyPoints\":0,\"code\":\"secret\"}","{\"name\":\"Name\",\"skipPenaltyPoints\":1.5}")){mvc.perform(post("/api/v1/race-templates").header("Authorization","Bearer "+secret).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body)).andExpect(status().isBadRequest());}verifyNoInteractions(service);}
    @Test void stageRequiresParentVersionAndRejectsHtmlCode()throws Exception{login(true);for(String body:List.of("{}","{\"version\":-1}","{\"version\":0,\"newCode\":\"secret\"}","{\"version\":0,\"instructionsMarkdown\":\"<script>bad()</script>\"}"))mvc.perform(post("/api/v1/race-templates/"+id+"/stages").header("Authorization","Bearer "+secret).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(body)).andExpect(status().isBadRequest());verifyNoInteractions(service);}
    @Test void incompleteStageAndOrderUseExistingContracts()throws Exception{login(true);UUID stage=UUID.randomUUID();when(service.createStage(eq(principal),eq(id),eq(0L),any(),isNull(),isNull(),isNull())).thenReturn(new TemplateService.Created(stage,id,1));mvc.perform(post("/api/v1/race-templates/"+id+"/stages").with(csrf()).cookie(new Cookie(AuthCookies.NAME,secret)).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"version\":0}" )).andExpect(status().isCreated());mvc.perform(put("/api/v1/race-templates/"+id+"/stage-order").header("Authorization","Bearer "+secret).contentType("application/json").content("{\"version\":1,\"stageIds\":[\""+stage+"\"]}" )).andExpect(status().isOk());verify(service).order(principal,id,1,List.of(stage));}
    @Test void deleteRequiresQueryVersionAndNotIdempotencyHeader()throws Exception{login(true);mvc.perform(delete("/api/v1/race-templates/"+id).header("Authorization","Bearer "+secret)).andExpect(status().isBadRequest());mvc.perform(delete("/api/v1/race-templates/"+id).param("version","2").header("Authorization","Bearer "+secret)).andExpect(status().isNoContent());verify(service).delete(principal,id,2);}
    @Test void missingKeyAndOverflowingIntegerAre400()throws Exception{login(true);mvc.perform(post("/api/v1/race-templates").header("Authorization","Bearer "+secret).contentType("application/json").content("{\"name\":\"Name\",\"skipPenaltyPoints\":0}" )).andExpect(status().isBadRequest());mvc.perform(post("/api/v1/race-templates").header("Authorization","Bearer "+secret).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"name\":\"Name\",\"skipPenaltyPoints\":9223372036854775808}" )).andExpect(status().isBadRequest());verifyNoInteractions(service);}
    String configuration(){return """
        {"version":7,"gps":{"enabled":true,"latitude":-90,"longitude":180,"radiusMeters":0.5,"outsidePenaltyPoints":-3,"spoofingPenaltyPoints":0},
         "code":{"enabled":true,"newCode":"synthetic-secret","wrongAttemptPenaltyPoints":-1},
         "timePenalty":{"enabled":true,"intervalSeconds":60,"pointsPerInterval":-5},
         "delayed":{"enabled":true,"minimumDurationMs":0,"pointsPerSecond":2}}
        """;}
    @Test void configurationAcceptsAndMapsIndependentChecksAndSignedTiming()throws Exception{
        login(true);UUID stage=UUID.randomUUID();
        mvc.perform(put("/api/v1/race-templates/"+id+"/stages/"+stage+"/configuration").header("Authorization","Bearer "+secret).contentType("application/json").content(configuration())).andExpect(status().isOk());
        var captor=org.mockito.ArgumentCaptor.forClass(TemplateService.ConfigurationChange.class);verify(service).configure(eq(principal),eq(id),eq(stage),eq(7L),captor.capture());
        var c=captor.getValue();org.junit.jupiter.api.Assertions.assertTrue(c.gps().enabled());org.junit.jupiter.api.Assertions.assertTrue(c.code().enabled());org.junit.jupiter.api.Assertions.assertEquals(60000,c.timePenalty().intervalMs());org.junit.jupiter.api.Assertions.assertEquals(-5,c.timePenalty().pointsPerInterval());
    }
    @Test void invalidConfigurationTypesIntervalsOverflowAndOrSwitchAreRejected()throws Exception{
        login(true);UUID stage=UUID.randomUUID();String body=configuration();
        for(String invalid:List.of(body.replace("\"intervalSeconds\":60","\"intervalSeconds\":0"),body.replace("\"intervalSeconds\":60","\"intervalSeconds\":1.5"),body.replace("\"intervalSeconds\":60","\"intervalSeconds\":9223372036854776"),body.replace("\"enabled\":true","\"enabled\":\"true\""),body.replace("\"pointsPerSecond\":2","\"pointsPerSecond\":1.5"),body.replace("\"version\":7","\"version\":7,\"completionMode\":\"OR\""),body.replace("\"newCode\":\"synthetic-secret\"","\"newCode\":123")))
            mvc.perform(put("/api/v1/race-templates/"+id+"/stages/"+stage+"/configuration").header("Authorization","Bearer "+secret).contentType("application/json").content(invalid)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(service);
    }
    @Test void readOnlyReadinessAndFailedPublicationReturnSafeFieldErrors()throws Exception{
        login(true);UUID stage=UUID.randomUUID();var report=new TemplateReadiness.Report(id,7,false,List.of(new TemplateReadiness.Error(stage,"code.codeConfigured","REQUIRED","Doplňte kód.")));
        when(service.readiness(principal,id)).thenReturn(report);when(service.state(principal,id,7,"READY")).thenThrow(new TemplateReadiness.NotReady(report));
        mvc.perform(post("/api/v1/race-templates/"+id+"/validations").header("Authorization","Bearer "+secret)).andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(false)).andExpect(jsonPath("$.version").value(7));
        mvc.perform(put("/api/v1/race-templates/"+id+"/status").header("Authorization","Bearer "+secret).contentType("application/json").content("{\"version\":7,\"status\":\"READY\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TEMPLATE_NOT_READY")).andExpect(jsonPath("$.errors[0].stageId").value(stage.toString())).andExpect(jsonPath("$.errors[0].field").value("code.codeConfigured"));
    }
    @Test void configurationPreservesCookieCsrfAndRedactsRequestObjects()throws Exception{
        login(true);UUID stage=UUID.randomUUID();
        mvc.perform(put("/api/v1/race-templates/"+id+"/stages/"+stage+"/configuration").cookie(new Cookie(AuthCookies.NAME,secret)).contentType("application/json").content(configuration())).andExpect(status().isForbidden());verifyNoInteractions(service);
        var parsed=tools.jackson.databind.json.JsonMapper.builder().build().readValue(configuration(),TemplateController.ConfigurationInput.class);
        org.junit.jupiter.api.Assertions.assertFalse(parsed.toString().contains("synthetic-secret"));org.junit.jupiter.api.Assertions.assertFalse(parsed.code().toString().contains("synthetic-secret"));
    }
    @Test void markdownInstructionsAreAcceptedAndOldOrReadOnlyFieldsAreRejected()throws Exception{
        login(true);UUID stage=UUID.randomUUID();
        mvc.perform(put("/api/v1/race-templates/"+id+"/stages/"+stage).header("Authorization","Bearer "+secret).contentType("application/json").content("{\"version\":7,\"instructionsMarkdown\":\"<p>Text</p>\"}")).andExpect(status().isOk());
        verify(service).updateStage(principal,id,stage,7,null,null,null,true,"<p>Text</p>");
        for(String field:List.of("instructionsHtml","instructionsRenderedHtml"))mvc.perform(put("/api/v1/race-templates/"+id+"/stages/"+stage).header("Authorization","Bearer "+secret).contentType("application/json").content("{\"version\":7,\""+field+"\":\"Text\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/race-templates/"+id+"/stages/"+stage).cookie(new Cookie(AuthCookies.NAME,secret)).contentType("application/json").content("{\"version\":7,\"instructionsMarkdown\":\"Text\"}")).andExpect(status().isForbidden());

        mvc.perform(put("/api/v1/race-templates/"+id+"/stages/"+stage).header("Authorization","Bearer "+secret).contentType("application/json").content("{\"version\":7,\"instructions\":\"Text\"}")).andExpect(status().isBadRequest());
    }

    @Test void mvcTraceDoesNotLogCodeFromConfigurationRequest()throws Exception{
        login(true);UUID stage=UUID.randomUUID();
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor");
        var original=logger.getLevel();var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);logger.setLevel(ch.qos.logback.classic.Level.TRACE);
        try{
            mvc.perform(put("/api/v1/race-templates/"+id+"/stages/"+stage+"/configuration").header("Authorization","Bearer "+secret).contentType("application/json").content(configuration())).andExpect(status().isOk());
            org.junit.jupiter.api.Assertions.assertFalse(appender.list.isEmpty());
            org.junit.jupiter.api.Assertions.assertTrue(appender.list.stream().noneMatch(e->e.getFormattedMessage().contains("synthetic-secret")));
        }finally{logger.setLevel(original);logger.detachAppender(appender);appender.stop();}
    }


 @Test void extendedConfigurationUsesExplicitTypeAndRadarRate()throws Exception{login(true);UUID stage=UUID.randomUUID();var cfg="{\"version\":7,\"type\":\"RADAR\",\"radarPenaltyPoints\":5,\"gps\":{\"enabled\":true,\"latitude\":50,\"longitude\":14,\"radiusMeters\":100,\"outsidePenaltyPoints\":null,\"spoofingPenaltyPoints\":null},\"code\":{\"enabled\":false,\"newCode\":null,\"wrongAttemptPenaltyPoints\":null},\"timePenalty\":{\"enabled\":false,\"intervalSeconds\":null,\"pointsPerInterval\":null},\"delayed\":{\"enabled\":false,\"minimumDurationMs\":null,\"pointsPerSecond\":null}}";mvc.perform(put("/api/v1/race-templates/"+id+"/stages/"+stage+"/configuration").header("Authorization","Bearer "+secret).contentType("application/json").content(cfg)).andExpect(status().isOk());verify(service).configure(eq(principal),eq(id),eq(stage),eq(7L),argThat(c->c.type().equals("RADAR")&&c.radarPenaltyPoints()==5));}
}
