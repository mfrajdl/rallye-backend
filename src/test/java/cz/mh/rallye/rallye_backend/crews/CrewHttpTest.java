package cz.mh.rallye.rallye_backend.crews;

import java.time.Instant;
import java.util.UUID;
import cz.mh.rallye.rallye_backend.crews.api.CrewController;
import cz.mh.rallye.rallye_backend.crews.application.CrewService;
import cz.mh.rallye.rallye_backend.identity.api.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CrewController.class)
@Import({AuthConfiguration.class,AuthCookies.class,ApiErrors.class})
class CrewHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean CrewService crews;
    @MockitoBean AuthService auth;
    String secret=new SecretTokens().create();
    AuthService.Principal principal=new AuthService.Principal(UUID.randomUUID(),UUID.randomUUID(),false);
    UUID id=UUID.randomUUID(),key=UUID.randomUUID();
    @BeforeEach void setup() {when(auth.authenticate(secret)).thenReturn(principal);}
    @Test void createDistinguishesOmittedCaptainFromExplicitNullAndReturnsLocation() throws Exception {
        when(crews.create(any(),any(),any(),any(),any(),anyBoolean(),any())).thenReturn(
                new CrewService.View(id,principal.accountId(),"Crew",null,null,false,Instant.EPOCH,Instant.EPOCH,0));
        mvc.perform(post("/api/v1/crews").header("Authorization","Bearer "+secret).header("Idempotency-Key",key)
                .contentType("application/json").content("{\"name\":\"Crew\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location","/api/v1/crews/"+id));
        verify(crews).create(principal,key,"Crew",null,null,false,null);
        mvc.perform(post("/api/v1/crews").header("Authorization","Bearer "+secret).header("Idempotency-Key",key)
                .contentType("application/json").content("{\"name\":\"Crew\",\"captainAccountId\":null}"))
                .andExpect(status().isCreated());
        verify(crews).create(principal,key,"Crew",null,null,true,null);
    }
    @Test void malformedKeysMissingVersionsAndMissingCaptainFieldAre400() throws Exception {
        mvc.perform(post("/api/v1/crews").header("Authorization","Bearer "+secret).header("Idempotency-Key","invalid")
                .contentType("application/json").content("{\"name\":\"Crew\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(put("/api/v1/crews/"+id).header("Authorization","Bearer "+secret)
                .contentType("application/json").content("{\"name\":\"Crew\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/crews/"+id).header("Authorization","Bearer "+secret)).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/crews/"+id+"/captain").header("Authorization","Bearer "+secret).header("Idempotency-Key",key)
                .contentType("application/json").content("{\"version\":0}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(crews);
    }
    @Test void anonymousAndCookieRequestsWithoutCsrfNeverReachService() throws Exception {
        mvc.perform(get("/api/v1/crews")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/crews/"+id+"?version=0").cookie(new jakarta.servlet.http.Cookie(AuthCookies.NAME,secret)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(crews);
    }
    @Test void photoIsServedThroughAuthenticatedEndpointWithCorrectHeaders() throws Exception {
        when(crews.photo(principal,id)).thenReturn(new CrewService.Photo(new byte[]{1,2},"image/png"));
        mvc.perform(get("/api/v1/crews/"+id+"/photo").header("Authorization","Bearer "+secret))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void deleteWithoutQueryVersionIs400EvenWithValidIdempotencyKeyOrJsonVersion() throws Exception {
        var result=mvc.perform(delete("/api/v1/crews/"+id).header("Authorization","Bearer "+secret)
                .header("Idempotency-Key",key).contentType("application/json").content("{\"version\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST")).andReturn();
        var error=org.junit.jupiter.api.Assertions.assertInstanceOf(
                org.springframework.web.bind.MissingServletRequestParameterException.class,result.getResolvedException());
        org.junit.jupiter.api.Assertions.assertEquals("version",error.getParameterName());
        verifyNoInteractions(crews);
    }
    @Test void deleteRequiresNonnegativeIntegerQueryVersion() throws Exception {
        for(String version:new String[]{"","abc","-1"})
            mvc.perform(delete("/api/v1/crews/"+id+"?version="+version).header("Authorization","Bearer "+secret))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(crews);
    }
    @Test void deleteWithQueryVersionAndNoBodyReturns204WithoutRequiringIdempotencyKey() throws Exception {
        mvc.perform(delete("/api/v1/crews/"+id+"?version=0").header("Authorization","Bearer "+secret))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(crews).delete(principal,id,0);
        UUID another=UUID.randomUUID();
        mvc.perform(delete("/api/v1/crews/"+another+"?version=3").header("Authorization","Bearer "+secret)
                .header("Idempotency-Key",key)).andExpect(status().isNoContent());
        verify(crews).delete(principal,another,3);
    }
    @Test void staleDeleteVersionRemains409RatherThan400() throws Exception {
        doThrow(new AuthFailure(409,"CREW_VERSION_CONFLICT")).when(crews).delete(principal,id,0);
        mvc.perform(delete("/api/v1/crews/"+id+"?version=0").header("Authorization","Bearer "+secret))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CREW_VERSION_CONFLICT"));
    }
    @Test void listAddsOnlyCaptainDisplayNameAndKeepsPageEnvelopeAndDetailContract()throws Exception{
        var item=new CrewService.ListItem(id,principal.accountId(),"Crew","RZ","Description",false,Instant.EPOCH,Instant.EPOCH,2,"Jana Nováková");
        when(crews.list(principal,false,null,false,0,50)).thenReturn(new CrewService.Page(java.util.List.of(item),0,50,false));
        mvc.perform(get("/api/v1/crews").header("Authorization","Bearer "+secret)).andExpect(status().isOk())
            .andExpect(jsonPath("$",org.hamcrest.Matchers.aMapWithSize(4))).andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(50)).andExpect(jsonPath("$.hasMore").value(false))
            .andExpect(jsonPath("$.items[0]",org.hamcrest.Matchers.aMapWithSize(10))).andExpect(jsonPath("$.items[0].captainAccountId").value(principal.accountId().toString())).andExpect(jsonPath("$.items[0].captainDisplayName").value("Jana Nováková"))
            .andExpect(jsonPath("$.items[0].name").value("Crew")).andExpect(jsonPath("$.items[0].registrationPlate").value("RZ")).andExpect(jsonPath("$.items[0].description").value("Description")).andExpect(jsonPath("$.items[0].hasPhoto").value(false))
            .andExpect(jsonPath("$.items[0].version").value(2)).andExpect(jsonPath("$.items[0].createdAt").value(Instant.EPOCH.toString())).andExpect(jsonPath("$.items[0].updatedAt").value(Instant.EPOCH.toString()));
        when(crews.detail(principal,id)).thenReturn(new CrewService.View(id,principal.accountId(),"Crew","RZ","Description",false,Instant.EPOCH,Instant.EPOCH,2));
        mvc.perform(get("/api/v1/crews/"+id).header("Authorization","Bearer "+secret)).andExpect(status().isOk()).andExpect(jsonPath("$",org.hamcrest.Matchers.aMapWithSize(9))).andExpect(jsonPath("$",org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasKey("captainDisplayName"))));
    }
    @Test void orphanListSerializesExplicitNullCaptainFieldsAndUsesExistingScope()throws Exception{
        when(crews.list(principal,true,null,true,0,2)).thenReturn(new CrewService.Page(java.util.List.of(new CrewService.ListItem(id,null,"Orphan",null,null,false,Instant.EPOCH,Instant.EPOCH,0,null)),0,2,false));
        mvc.perform(get("/api/v1/crews").param("scope","all").param("orphaned","true").param("size","2").header("Authorization","Bearer "+secret)).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0]",org.hamcrest.Matchers.hasKey("captainAccountId"))).andExpect(jsonPath("$.items[0]",org.hamcrest.Matchers.hasKey("captainDisplayName")))
            .andExpect(jsonPath("$.items[0].captainAccountId").value(org.hamcrest.Matchers.nullValue())).andExpect(jsonPath("$.items[0].captainDisplayName").value(org.hamcrest.Matchers.nullValue()));
    }

}
