package cz.mh.rallye.rallye_backend.identity;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import cz.mh.rallye.rallye_backend.identity.api.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({AuthConfiguration.class,AuthCookies.class,ApiErrors.class})
class ProfileHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean LoginThrottle throttle;
    final String secret=new SecretTokens().create();
    final AuthService.Principal principal=new AuthService.Principal(UUID.randomUUID(),UUID.randomUUID(),false);
    void authenticated(){when(auth.authenticate(secret)).thenReturn(principal);}
    @Test void anonymousRequestWithCsrfStillRequiresLogin()throws Exception{mvc.perform(patch("/api/v1/auth/me").with(csrf()).contentType("application/json").content("{\"displayName\":\"New\"}")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));verifyNoInteractions(auth);}
    @Test void cookiePatchCannotBypassCsrf()throws Exception{mvc.perform(patch("/api/v1/auth/me").cookie(new Cookie(AuthCookies.NAME,secret)).header("X-Rallye-Client","mobile").contentType("application/json").content("{\"displayName\":\"New\"}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));verify(auth,never()).changeDisplayName(any(),any());}
    @Test void cookieWithCsrfReturnsUpdatedProfileAndUnchangedRoles()throws Exception{authenticated();var result=new AuthService.UserView(principal.accountId(),"person@example.test","New",List.of("USER"));when(auth.changeDisplayName(principal,"New")).thenReturn(result);when(auth.me(principal)).thenReturn(result);
        mvc.perform(patch("/api/v1/auth/me").with(csrf()).cookie(new Cookie(AuthCookies.NAME,secret)).contentType("application/json").content("{\"displayName\":\"New\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(principal.accountId().toString())).andExpect(jsonPath("$.displayName").value("New")).andExpect(jsonPath("$.roles[0]").value("USER")).andExpect(jsonPath("$.passwordHash").doesNotExist()).andExpect(jsonPath("$.token").doesNotExist());
        mvc.perform(get("/api/v1/auth/me").cookie(new Cookie(AuthCookies.NAME,secret))).andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("New"));verify(auth).changeDisplayName(principal,"New");
    }
    @Test void bearerUsesSamePrincipalAndDoesNotRequireCsrf()throws Exception{authenticated();when(auth.changeDisplayName(principal,"New")).thenReturn(new AuthService.UserView(principal.accountId(),"person@example.test","New"));mvc.perform(patch("/api/v1/auth/me").header("Authorization","Bearer "+secret).contentType("application/json").content("{\"displayName\":\"New\"}")).andExpect(status().isOk());verify(auth).changeDisplayName(principal,"New");}
    static Stream<String> invalidBodies(){return Stream.of("{}","null","{\"displayName\":null}","{\"displayName\":\"\"}","{\"displayName\":\"   \"}","{\"displayName\":\""+"x".repeat(101)+"\"}","{\"displayName\":\"New\",\"id\":\""+UUID.randomUUID()+"\"}","{\"displayName\":\"New\",\"email\":\"other@example.test\"}","{\"displayName\":\"New\",\"roles\":[\"ADMIN\"]}","{\"displayName\":\"New\",\"identityProvider\":\"APPLE\"}","{\"displayName\":\"New\",\"password\":\"secret\"}");}
    @ParameterizedTest @MethodSource("invalidBodies") void rejectsInvalidAndAdditionalFields(String body)throws Exception{authenticated();mvc.perform(patch("/api/v1/auth/me").with(csrf()).cookie(new Cookie(AuthCookies.NAME,secret)).contentType("application/json").content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));verify(auth,never()).changeDisplayName(any(),any());}
    @Test void expiredBearerIsRejected()throws Exception{mvc.perform(patch("/api/v1/auth/me").header("Authorization","Bearer "+secret).contentType("application/json").content("{\"displayName\":\"New\"}")).andExpect(status().isUnauthorized());verify(auth,never()).changeDisplayName(any(),any());}
}
