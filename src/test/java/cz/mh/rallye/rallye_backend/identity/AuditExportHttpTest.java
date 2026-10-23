package cz.mh.rallye.rallye_backend.identity;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import cz.mh.rallye.rallye_backend.audit.api.AuditExportController;
import cz.mh.rallye.rallye_backend.audit.application.*;
import cz.mh.rallye.rallye_backend.identity.api.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuditExportController.class)
@Import({AuthConfiguration.class,AuthCookies.class,ApiErrors.class})
class AuditExportHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean AuditExportService exports;
    String secret=new SecretTokens().create();
    AuthService.Principal actor=new AuthService.Principal(UUID.randomUUID(),UUID.randomUUID(),false);
    static final String URL="/api/v1/admin/audit-events/export";
    void authenticated(boolean admin) {when(auth.authenticate(secret)).thenReturn(actor);when(auth.isAdministrator(actor)).thenReturn(admin);}
    @Test void onlyAdministratorCanExport() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());authenticated(false);
        mvc.perform(get(URL).header("Authorization","Bearer "+secret)).andExpect(status().isForbidden());verifyNoInteractions(exports);
    }
    @Test void adminStreamsCsvWithDownloadHeadersAndAllFilters() throws Exception {
        authenticated(true);UUID target=UUID.randomUUID();
        doAnswer(call->{((java.io.OutputStream)call.getArgument(2)).write("\ufeff\"id\"\r\n".getBytes(StandardCharsets.UTF_8));return null;})
                .when(exports).stream(any(),any(),any());
        var result=mvc.perform(get(URL).header("Authorization","Bearer "+secret)
                .param("from","2026-01-01T00:00:00Z").param("until","2027-01-01T00:00:00Z")
                .param("eventType","LOGIN_SUCCEEDED").param("actorId",actor.accountId().toString()).param("targetAccountId",target.toString()))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition","attachment; filename=\"rallye-audit.csv\""))
                .andExpect(header().string("Cache-Control","no-store")).andExpect(content().string("\ufeff\"id\"\r\n"));
        verify(exports).prepare(actor,new AuditExportFilter(Instant.parse("2026-01-01T00:00:00Z"),Instant.parse("2027-01-01T00:00:00Z"),AuditService.Event.LOGIN_SUCCEEDED,actor.accountId(),target));
    }
    @Test void malformedFiltersAre400AndAuthorizationIsCheckedBeforeStreaming() throws Exception {
        authenticated(true);
        mvc.perform(get(URL).header("Authorization","Bearer "+secret).param("actorId","bad"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        doThrow(new AuthFailure(403,"ADMIN_REQUIRED")).when(exports).prepare(any(),any());
        mvc.perform(get(URL).header("Authorization","Bearer "+secret)).andExpect(status().isForbidden());
        verify(exports,never()).stream(any(),any(),any());
    }
}
