package cz.mh.rallye.rallye_backend.identity;

import java.util.List;
import java.util.UUID;
import cz.mh.rallye.rallye_backend.administration.api.AdminController;
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

@WebMvcTest({AdminController.class, AuthController.class})
@Import({AuthConfiguration.class,AuthCookies.class,ApiErrors.class})
class AdminHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean AdminService admin;
    @MockitoBean LoginThrottle throttle;
    String secret=new SecretTokens().create();
    AuthService.Principal principal=new AuthService.Principal(UUID.randomUUID(),UUID.randomUUID(),false);
    void authenticated(boolean isAdmin) {
        when(auth.authenticate(secret)).thenReturn(principal);
        when(auth.isAdministrator(principal)).thenReturn(isAdmin);
    }
    @Test void anonymousAndOrdinaryUserCannotReadAudit() throws Exception {
        mvc.perform(get("/api/v1/admin/audit-events")).andExpect(status().isUnauthorized());
        authenticated(false);
        mvc.perform(get("/api/v1/admin/audit-events").header("Authorization","Bearer "+secret)).andExpect(status().isForbidden());
        verifyNoInteractions(admin);
    }
    @Test void grantAndRevokeUseCurrentAdminAndIdempotencyKey() throws Exception {
        authenticated(true); UUID target=UUID.randomUUID(),key=UUID.randomUUID();
        mvc.perform(put("/api/v1/admin/users/"+target+"/roles/ADMIN").header("Authorization","Bearer "+secret)
                .header("Idempotency-Key",key)).andExpect(status().isNoContent());
        verify(admin).changeRole(principal,target,true,key);
        UUID revokeKey=UUID.randomUUID();
        mvc.perform(delete("/api/v1/admin/users/"+target+"/roles/ADMIN").header("Authorization","Bearer "+secret)
                .header("Idempotency-Key",revokeKey)).andExpect(status().isNoContent());
        verify(admin).changeRole(principal,target,false,revokeKey);
    }
    @Test void missingKeyAndInvalidUuidAndFilterReturn400() throws Exception {
        authenticated(true);
        mvc.perform(put("/api/v1/admin/users/"+UUID.randomUUID()+"/roles/ADMIN").header("Authorization","Bearer "+secret)).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/admin/users/not-uuid/roles/ADMIN").header("Authorization","Bearer "+secret).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/audit-events?eventType=WRONG").header("Authorization","Bearer "+secret)).andExpect(status().isBadRequest());
        verifyNoInteractions(admin);
    }
    @Test void cookieAdminCannotBypassCsrf() throws Exception {
        authenticated(true);
        mvc.perform(put("/api/v1/admin/users/"+UUID.randomUUID()+"/roles/ADMIN")
                .cookie(new jakarta.servlet.http.Cookie(AuthCookies.NAME,secret)).header("Idempotency-Key",UUID.randomUUID())
                .header("X-Rallye-Client","mobile")).andExpect(status().isForbidden());
    }
    @Test void adminAuditReadReturnsSafeDto() throws Exception {
        authenticated(true);
        when(admin.audit(eq(principal),isNull(),isNull(),isNull(),isNull(),isNull(),eq(0),eq(50)))
                .thenReturn(new AdminService.AuditPage(List.of(),0,50,false));
        mvc.perform(get("/api/v1/admin/audit-events").header("Authorization","Bearer "+secret))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray())
                .andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void invalidKeyIsRejectedDuringBindingAfterRealCookieCsrfValidation() throws Exception {
        authenticated(true);
        var response=mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        String token=com.jayway.jsonpath.JsonPath.read(response.getContentAsString(),"$.token");
        var result=mvc.perform(delete("/api/v1/admin/users/"+UUID.randomUUID()+"/roles/ADMIN")
                .cookie(new jakarta.servlet.http.Cookie(AuthCookies.NAME,secret),response.getCookie("XSRF-TOKEN"))
                .header("X-XSRF-TOKEN",token).header("Idempotency-Key","test-revoke-20261021-001"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andReturn();
        org.junit.jupiter.api.Assertions.assertInstanceOf(
                org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,result.getResolvedException());
        verifyNoInteractions(admin);
        org.junit.jupiter.api.Assertions.assertEquals(0,result.getResponse().getCookie("XSRF-TOKEN").getMaxAge());
    }
    @Test void repeatedDeleteWithUuidAndFreshRealCsrfReachesService() throws Exception {
        authenticated(true);
        UUID target=UUID.randomUUID(),key=UUID.randomUUID();
        for (int attempt=0;attempt<2;attempt++) {
            var response=mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
            String token=com.jayway.jsonpath.JsonPath.read(response.getContentAsString(),"$.token");
            mvc.perform(delete("/api/v1/admin/users/"+target+"/roles/ADMIN")
                    .cookie(new jakarta.servlet.http.Cookie(AuthCookies.NAME,secret),response.getCookie("XSRF-TOKEN"))
                    .header("X-XSRF-TOKEN",token).header("Idempotency-Key",key))
                    .andExpect(status().isNoContent()).andExpect(cookie().maxAge("XSRF-TOKEN",0));
        }
        verify(admin,times(2)).changeRole(principal,target,false,key);
    }
}
