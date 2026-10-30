package cz.mh.rallye.rallye_backend.administration;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.*;
import org.springframework.mock.env.MockEnvironment;
import cz.mh.rallye.rallye_backend.administration.application.*;
import cz.mh.rallye.rallye_backend.administration.persistence.AboutStore;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AboutServiceTest {
 final AdminStore roles=mock(AdminStore.class);final AccountStore accounts=mock(AccountStore.class);final AboutStore store=mock(AboutStore.class);final AboutConfiguration config=mock(AboutConfiguration.class);
 final ObjectProvider<BuildProperties> builds=mock(ObjectProvider.class);final ObjectProvider<GitProperties> commits=mock(ObjectProvider.class);
 final Instant now=Instant.parse("2026-10-30T10:00:00Z");final AuthService.Principal actor=new AuthService.Principal(UUID.randomUUID(),UUID.randomUUID(),false);
 final MockEnvironment environment=new MockEnvironment().withProperty("spring.application.name","rallye-backend");
 AboutService service(){return new AboutService(roles,accounts,store,environment,builds,commits,config,Clock.fixed(now,ZoneOffset.UTC));}
 @BeforeEach void setup(){when(roles.activeVerified(actor.accountId())).thenReturn(true);when(roles.administrator(actor.accountId())).thenReturn(true);when(accounts.isActiveSession(actor.sessionId(),actor.accountId(),now)).thenReturn(true);when(store.read()).thenReturn(new AboutStore.Database("PostgreSQL","17.11","27","Race event lifecycle","SUCCESS"));}
 @Test void metadataAndRuntimeAreRealAndOptionalGitCanBeMissing(){var p=new Properties();p.setProperty("version","0.2.5-test");p.setProperty("time","2026-10-30T08:00:00Z");when(builds.getIfAvailable()).thenReturn(new BuildProperties(p));var result=service().read(actor);assertEquals("0.2.5-test",result.application().backendVersion());assertEquals(Instant.parse("2026-10-30T08:00:00Z"),result.application().buildTime());assertNull(result.application().gitCommit());assertEquals("rallye-backend",result.application().name());assertEquals(now,result.runtime().serverTime());assertTrue(result.runtime().uptimeSeconds()>=0);assertEquals(Runtime.version().toString(),result.runtime().javaVersion());assertEquals(ZoneId.systemDefault().getId(),result.runtime().timezone());verify(roles,never()).lock();verify(accounts,never()).lockAccount(any());verify(accounts,never()).touch(any(),any(),any());}
 @Test void missingBuildMetadataDoesNotInventVersionOrTime(){var v=service().read(actor);assertNull(v.application().backendVersion());assertNull(v.application().buildTime());}
 @Test void fullGitHashIsExposedWithoutOtherProperties(){var p=new Properties();p.setProperty("commit.id","a".repeat(40));p.setProperty("remote.origin.url","secret-internal-repository");when(commits.getIfAvailable()).thenReturn(new GitProperties(p));assertEquals("a".repeat(40),service().read(actor).application().gitCommit());p.setProperty("commit.id","/internal/unsafe");when(commits.getIfAvailable()).thenReturn(new GitProperties(p));assertNull(service().read(actor).application().gitCommit());}
 @Test void currentAuthorityAndSessionAreRecheckedWithoutLocks(){when(roles.administrator(actor.accountId())).thenReturn(false);assertEquals(403,assertThrows(AuthFailure.class,()->service().read(actor)).status());when(accounts.isActiveSession(any(),any(),any())).thenReturn(false);assertEquals(401,assertThrows(AuthFailure.class,()->service().read(actor)).status());assertEquals(401,assertThrows(AuthFailure.class,()->service().read(null)).status());verifyNoInteractions(store,config);}
 @Test void unavailableDatabaseIsNeverReturnedAsSuccessfulDiagnostics(){when(store.read()).thenThrow(new AuthFailure(503,"ABOUT_DATABASE_UNAVAILABLE"));assertEquals("ABOUT_DATABASE_UNAVAILABLE",assertThrows(AuthFailure.class,()->service().read(actor)).code());}
}
