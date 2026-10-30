package cz.mh.rallye.rallye_backend.identity;

import java.nio.file.*;
import java.sql.Timestamp;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.flywaydb.core.Flyway;
import cz.mh.rallye.rallye_backend.administration.application.AboutService;
import cz.mh.rallye.rallye_backend.administration.persistence.AboutStore;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.AccountStore;
import cz.mh.rallye.rallye_backend.support.TestDatabaseInitializer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("it")
@ContextConfiguration(initializers=TestDatabaseInitializer.class) @Import(IdentityIT.Fixtures.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS) @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AboutIT {
 static final String SCHEMA="it_about_"+UUID.randomUUID().toString().replace("-","");
 @DynamicPropertySource static void settings(DynamicPropertyRegistry p){p.add("spring.flyway.schemas",()->SCHEMA);p.add("spring.flyway.default-schema",()->SCHEMA);p.add("spring.datasource.hikari.connection-init-sql",()->"SET search_path TO "+SCHEMA);}
 @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired DataSource source;@Autowired AccountStore accounts;@Autowired MutableClock clock;@Autowired PlatformTransactionManager manager;@Autowired Flyway flyway;@Autowired AboutService service;@Autowired BuildProperties build;
 @MockitoSpyBean AboutStore diagnostics;
 record Actor(UUID id,String token,AuthService.Principal principal){} Actor admin,user;
 Actor actor(){return new TransactionTemplate(manager).execute(s->{UUID id=UUID.randomUUID();String token=new SecretTokens().create(),email=id+"@example.test";Timestamp at=Timestamp.from(clock.instant());jdbc.update("INSERT INTO user_account(id,email,normalized_email,display_name,status,email_verified_at,created_at,updated_at) VALUES (?,?,?,'About fixture','ACTIVE',?,?,?)",id,email,email,at,at,at);jdbc.update("INSERT INTO user_identity(id,account_id,provider,issuer,subject,password_hash,created_at) VALUES (?,?,'LOCAL','local',?,'synthetic-fixture',?)",UUID.randomUUID(),id,id.toString(),at);UUID session=accounts.insertSession(id,new SecretTokens().hash(token),false,clock.instant(),clock.instant().plusSeconds(86400));return new Actor(id,token,new AuthService.Principal(id,session,false));});}
 @BeforeAll void bootstrap()throws Exception{admin=actor();user=actor();String sql=AdministrationIT.bootstrapBody(admin.id());new TransactionTemplate(manager).executeWithoutResult(s->jdbc.execute(sql));}
 @AfterEach void resetSpy(){reset(diagnostics);}
 @AfterAll void cleanup(){assertTrue(SCHEMA.matches("it_about_[0-9a-f]{32}"));assertEquals("rallye_test",jdbc.queryForObject("SELECT current_database()",String.class));assertEquals("rallye_test",jdbc.queryForObject("SELECT current_user",String.class));jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");}
 @Test void administratorSeesActualPostgresFlywayAndBuildMetadata()throws Exception{String current=flyway.info().current().getVersion().toString();var response=mvc.perform(get("/api/v1/admin/about").header("Authorization","Bearer "+admin.token())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.application.backendVersion").value(build.getVersion())).andExpect(jsonPath("$.database.type").value("PostgreSQL")).andExpect(jsonPath("$.database.version").value(jdbc.queryForObject("SHOW server_version",String.class))).andExpect(jsonPath("$.database.migrationVersion").value(current)).andExpect(jsonPath("$.database.migrationState").value("SUCCESS")).andExpect(jsonPath("$.configuration.activeProfiles[0]").value("it")).andExpect(jsonPath("$.configuration.schedulers.lifecycle.enabled").value(false)).andReturn().getResponse().getContentAsString();for(String forbidden:List.of("jdbc:","passwordHash","token_hash","privateKeyFile","rallye_test","user.home"))assertFalse(response.contains(forbidden));assertTrue(service.read(admin.principal()).runtime().uptimeSeconds()>=0);}
 @Test void userAndAnonymousAreDenied()throws Exception{mvc.perform(get("/api/v1/admin/about")).andExpect(status().isUnauthorized());mvc.perform(get("/api/v1/admin/about").header("Authorization","Bearer "+user.token())).andExpect(status().isForbidden());}
 @Test void endpointDoesNotMutateSessionDatabaseOrAudit()throws Exception{long audit=jdbc.queryForObject("SELECT count(*) FROM audit_event",Long.class),history=jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history",Long.class);var before=jdbc.queryForMap("SELECT last_used_at,expires_at,revoked_at FROM user_session WHERE id=?",admin.principal().sessionId());mvc.perform(get("/api/v1/admin/about").header("Authorization","Bearer "+admin.token())).andExpect(status().isOk());assertEquals(before,jdbc.queryForMap("SELECT last_used_at,expires_at,revoked_at FROM user_session WHERE id=?",admin.principal().sessionId()));assertEquals(audit,jdbc.queryForObject("SELECT count(*) FROM audit_event",Long.class));assertEquals(history,jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history",Long.class));}
 @Test void noAppliedMigrationIsExplicitAndHistoryDeletionIsRolledBack(){new TransactionTemplate(manager).executeWithoutResult(s->{jdbc.update("DELETE FROM flyway_schema_history");var db=service.read(admin.principal()).database();assertEquals("NONE",db.migrationState());assertNull(db.migrationVersion());assertNull(db.migrationDescription());s.setRollbackOnly();});assertNotNull(service.read(admin.principal()).database().migrationVersion());}
 @Test void newRealMigrationAndCustomHistoryTableAreReadWithoutHardcodedMaximum()throws Exception{String schema="it_about_probe_"+UUID.randomUUID().toString().replace("-","");Path dir=Files.createTempDirectory("about-migration-");try{Files.writeString(dir.resolve("V1__first.sql"),"CREATE TABLE about_probe(id integer PRIMARY KEY);");var f=Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).table("about_history").locations("filesystem:"+dir.toAbsolutePath()).load();f.migrate();var reader=new AboutStore(jdbc,f);assertEquals("1",reader.read().migrationVersion());Files.writeString(dir.resolve("V2__next.sql"),"ALTER TABLE about_probe ADD COLUMN name text;");f.migrate();assertEquals("2",reader.read().migrationVersion());assertEquals("next",reader.read().migrationDescription());assertEquals("SUCCESS",reader.read().migrationState());}finally{assertTrue(schema.matches("it_about_probe_[0-9a-f]{32}"));jdbc.execute("DROP SCHEMA IF EXISTS \""+schema+"\" CASCADE");try(var files=Files.list(dir)){for(Path file:files.toList())Files.delete(file);}Files.delete(dir);}}
 @Test void recordedMigrationFailureIsShownTruthfully(){new TransactionTemplate(manager).executeWithoutResult(s->{jdbc.update("UPDATE flyway_schema_history SET success=false WHERE installed_rank=(SELECT max(installed_rank) FROM flyway_schema_history)");assertEquals("FAILED",service.read(admin.principal()).database().migrationState());s.setRollbackOnly();});}
 @Test void unavailableDiagnosticsReturns503WithoutDetails()throws Exception{doThrow(new AuthFailure(503,"ABOUT_DATABASE_UNAVAILABLE")).when(diagnostics).read();mvc.perform(get("/api/v1/admin/about").header("Authorization","Bearer "+admin.token())).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("ABOUT_DATABASE_UNAVAILABLE"));}
}
