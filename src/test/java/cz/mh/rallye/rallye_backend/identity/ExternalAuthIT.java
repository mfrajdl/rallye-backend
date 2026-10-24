package cz.mh.rallye.rallye_backend.identity;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import cz.mh.rallye.rallye_backend.support.TestDatabaseInitializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("it")
@ContextConfiguration(initializers=TestDatabaseInitializer.class)
@Import(IdentityIT.Fixtures.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExternalAuthIT {
    static final String SCHEMA="it_external_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void schema(DynamicPropertyRegistry p) {
        p.add("rallye.auth.external.providers.GOOGLE.enabled",()->"true");
        p.add("rallye.auth.external.providers.GOOGLE.client-id",()->"synthetic-client");
        p.add("rallye.auth.external.providers.GOOGLE.client-secret",()->"synthetic-secret");
        p.add("rallye.auth.external.providers.GOOGLE.callback-uri",()->"https://backend.example.test/api/v1/auth/external/GOOGLE/callback");
        p.add("rallye.auth.external.returns.web",()->"https://app.example.test/complete");
        p.add("spring.flyway.schemas",()->SCHEMA);p.add("spring.flyway.default-schema",()->SCHEMA);
        p.add("spring.datasource.hikari.connection-init-sql",()->"SET search_path TO "+SCHEMA);
    }
    @Autowired ExternalFlowService flows;
    @Autowired AuthService auth;
    @Autowired AccountCancellationService cancellation;
    @Autowired AccountCancellationStore cancellationStore;
    @Autowired IdentityIT.TestMail mail;
    @org.springframework.test.context.bean.override.mockito.MockitoBean ExternalOidcVerifier verifier;
    @Autowired ExternalFlowStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired PreRaceAccountParticipationFacts facts;
    SecretTokens secrets=new SecretTokens();
    TransactionTemplate tx(){return new TransactionTemplate(manager);}
    void guard(){assertEquals("rallye_test",jdbc.queryForObject("SELECT current_database()",String.class));assertEquals("rallye_test",jdbc.queryForObject("SELECT current_user",String.class));assertEquals(SCHEMA,jdbc.queryForObject("SELECT current_schema()",String.class));}
    @BeforeEach void checked(){guard();}
    ExternalFlowStore.Flow insert(Instant now) {
        var f=new ExternalFlowStore.Flow(UUID.randomUUID(),"GOOGLE","LOGIN",null,null,secrets.hash("nonce"),secrets.hash("cookie"),"https://example.test/callback","https://example.test/return",false,null,now.plusSeconds(600));
        tx().executeWithoutResult(s->store.insert(f,secrets.hash(f.id().toString()),now));return f;
    }
    @Test void concurrentCompletionHasOneWinnerAndReplayCannotFindState() throws Exception {
        Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);var f=insert(now);var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> call=()->{barrier.await(5,TimeUnit.SECONDS);return tx().execute(s->store.consume(f.id(),now.plusSeconds(1)));};
            var a=pool.submit(call);var b=pool.submit(call);assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertNull(tx().execute(s->store.find(secrets.hash(f.id().toString()))));
    }
    @Test void exactExpiryAndRollbackPreserveInvariant() {
        Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);var f=insert(now);
        assertEquals(Boolean.FALSE,tx().execute(s->store.consume(f.id(),f.expiresAt())));
        assertThrows(IllegalStateException.class,()->tx().executeWithoutResult(s->{assertTrue(store.consume(f.id(),now));throw new IllegalStateException("injected");}));
        assertEquals(Boolean.TRUE,tx().execute(s->store.consume(f.id(),now)));
    }
    @Test void reviewedMigrationsAcceptedButUnknownScriptAndFutureRejected() {
        assertFalse(tx().execute(s->facts.forAccount(UUID.randomUUID())).historical());
        tx().executeWithoutResult(s->{jdbc.update("UPDATE flyway_schema_history SET script='V8__unknown.sql' WHERE version='8'");
            assertThrows(AuthFailure.class,()->facts.forAccount(UUID.randomUUID()));s.setRollbackOnly();});
        tx().executeWithoutResult(s->{jdbc.update("UPDATE flyway_schema_history SET version='10' WHERE version='8'");
            assertThrows(AuthFailure.class,()->facts.forAccount(UUID.randomUUID()));s.setRollbackOnly();});
    }
    @Test void migrationUpgradesV7AndRejectsDuplicateProviderWithoutDeletingData() {
        String upgrade=SCHEMA+"_upgrade";
        try {
            var source=jdbc.getDataSource();
            org.flywaydb.core.Flyway.configure().dataSource(source).schemas(upgrade).defaultSchema(upgrade).target("7").load().migrate();
            UUID user=UUID.randomUUID();
            jdbc.update("INSERT INTO "+upgrade+".user_account(id,email,normalized_email,display_name,status,created_at,updated_at) VALUES (?,'a@example.test','a@example.test','A','PENDING_EMAIL',now(),now())",user);
            for(int i=0;i<2;i++)jdbc.update("INSERT INTO "+upgrade+".user_identity(id,account_id,provider,issuer,subject,created_at) VALUES (?,?,'GOOGLE','https://accounts.google.com',?,now())",UUID.randomUUID(),user,"subject-"+i);
            assertThrows(RuntimeException.class,()->org.flywaydb.core.Flyway.configure().dataSource(source).schemas(upgrade).defaultSchema(upgrade).load().migrate());
            assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM "+upgrade+".user_identity",Integer.class));
        } finally {guard();assertTrue(upgrade.matches("it_external_[0-9a-f]{32}_upgrade"));jdbc.execute("DROP SCHEMA IF EXISTS \""+upgrade+"\" CASCADE");}
    }
    @Test void concurrentProtocolCallbacksHaveExactlyOneFinalWinner() throws Exception {
        var start=flows.start(ExternalProviders.Provider.GOOGLE,ExternalFlowService.Purpose.LOGIN,null,"web",false,null);
        String state=org.springframework.web.util.UriComponentsBuilder.fromUriString(start.authorizationUrl()).build().getQueryParams().getFirst("state");
        var barrier=new CyclicBarrier(2);
        org.mockito.Mockito.doAnswer(call->{barrier.await(10,TimeUnit.SECONDS);return null;}).when(verifier).exchange(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> callback=()->{try {flows.callback(ExternalProviders.Provider.GOOGLE,state,start.correlation(),"synthetic-code");return true;}
                catch(AuthFailure failure){assertEquals("EXTERNAL_STATE_INVALID",failure.code());return false;}};
            var a=pool.submit(callback);var b=pool.submit(callback);assertNotEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
    }
    @Test void cleanV7UpgradePreservesAccountAndAcceptsOnlyReviewedV8() {
        String upgrade=SCHEMA+"_clean_upgrade";
        try {
            var source=jdbc.getDataSource();
            org.flywaydb.core.Flyway.configure().dataSource(source).schemas(upgrade).defaultSchema(upgrade).target("7").load().migrate();
            UUID user=UUID.randomUUID();
            jdbc.update("INSERT INTO "+upgrade+".user_account(id,email,normalized_email,display_name,status,created_at,updated_at) VALUES (?,'b@example.test','b@example.test','B','PENDING_EMAIL',now(),now())",user);
            org.flywaydb.core.Flyway.configure().dataSource(source).schemas(upgrade).defaultSchema(upgrade).load().migrate();
            assertEquals(user,jdbc.queryForObject("SELECT id FROM "+upgrade+".user_account",UUID.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM "+upgrade+".external_auth_flow",Integer.class));
        } finally {guard();assertTrue(upgrade.matches("it_external_[0-9a-f]{32}_clean_upgrade"));jdbc.execute("DROP SCHEMA IF EXISTS \""+upgrade+"\" CASCADE");}
    }
    @Test void cancellingAccountDeletesBoundContextAndGrantsWithoutChangingAuditPolicy() {
        String email=UUID.randomUUID()+"@example.test";
        auth.register(email,"test-password","A");auth.verifyEmail(mail.token(email,"EMAIL_VERIFICATION"));
        var login=auth.login(email,"test-password",false);
        flows.start(ExternalProviders.Provider.GOOGLE,ExternalFlowService.Purpose.LINK,login.principal(),"web",false,null);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM external_auth_flow WHERE account_id=?",Integer.class,login.principal().accountId()));
        jdbc.update("INSERT INTO external_auth_grant(id,account_id,session_id,kind,purpose,token_hash,created_at,expires_at) VALUES (?,?,?,'REAUTH','LINK',?,now(),now()+interval '5 minutes')",
            UUID.randomUUID(),login.principal().accountId(),login.principal().sessionId(),secrets.hash(UUID.randomUUID().toString()));
        cancellation.self(login.principal(),"test-password",UUID.randomUUID());
        for(String table:List.of("external_auth_flow","external_auth_grant"))
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE account_id=?",Integer.class,login.principal().accountId()));
        assertNull(auth.authenticate(login.secret()));
    }
    @Test void anonymizationRemovesEvenSessionlessMobileGrants() {
        String email=UUID.randomUUID()+"@example.test";
        auth.register(email,"test-password","A");auth.verifyEmail(mail.token(email,"EMAIL_VERIFICATION"));
        var login=auth.login(email,"test-password",false);UUID account=login.principal().accountId();
        jdbc.update("INSERT INTO external_auth_grant(id,account_id,kind,purpose,token_hash,pkce_challenge,created_at,expires_at) VALUES (?,?,'MOBILE','LOGIN',?,?,now(),now()+interval '60 seconds')",UUID.randomUUID(),account,secrets.hash(UUID.randomUUID().toString()),secrets.create());
        tx().executeWithoutResult(status->{cancellationStore.deleteIdentities(account);cancellationStore.deleteSessions(account);
            cancellationStore.deleteTokens(account);cancellationStore.deleteRoles(account);cancellationStore.anonymize(account,Instant.now());});
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM external_auth_grant WHERE account_id=?",Integer.class,account));
        assertEquals("ANONYMIZED",jdbc.queryForObject("SELECT status FROM user_account WHERE id=?",String.class,account));
        tx().executeWithoutResult(status->cancellationStore.deleteAccount(account));
    }
    @Test void fullProtocolCallbackDoesNotCreateAccountSessionOrLoginAudit() {
        var start=flows.start(ExternalProviders.Provider.GOOGLE,ExternalFlowService.Purpose.LOGIN,null,"web",false,null);
        var query=org.springframework.web.util.UriComponentsBuilder.fromUriString(start.authorizationUrl()).build().getQueryParams();
        long accounts=jdbc.queryForObject("SELECT count(*) FROM user_account",Long.class);
        long sessions=jdbc.queryForObject("SELECT count(*) FROM user_session",Long.class);
        long audit=jdbc.queryForObject("SELECT count(*) FROM audit_event",Long.class);
        flows.callback(ExternalProviders.Provider.GOOGLE,query.getFirst("state"),start.correlation(),"synthetic-code");
        assertThrows(AuthFailure.class,()->flows.callback(ExternalProviders.Provider.GOOGLE,query.getFirst("state"),start.correlation(),"synthetic-code"));
        assertEquals(accounts,jdbc.queryForObject("SELECT count(*) FROM user_account",Long.class));
        assertEquals(sessions,jdbc.queryForObject("SELECT count(*) FROM user_session",Long.class));
        assertEquals(audit,jdbc.queryForObject("SELECT count(*) FROM audit_event",Long.class));
    }
    @Test void databaseRejectsDuplicateProviderAndWrongGrantLifetime() {
        String email=UUID.randomUUID()+"@example.test";
        auth.register(email,"test-password","A");auth.verifyEmail(mail.token(email,"EMAIL_VERIFICATION"));
        var login=auth.login(email,"test-password",false);UUID account=login.principal().accountId();
        try {
            jdbc.update("INSERT INTO user_identity(id,account_id,provider,issuer,subject,created_at) VALUES (?,?,'GOOGLE','https://accounts.google.com','one',now())",UUID.randomUUID(),account);
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO user_identity(id,account_id,provider,issuer,subject,created_at) VALUES (?,?,'GOOGLE','https://accounts.google.com','two',now())",UUID.randomUUID(),account));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO external_auth_grant(id,account_id,kind,purpose,token_hash,pkce_challenge,created_at,expires_at) VALUES (?,?,'MOBILE','LOGIN',?,?,now(),now()+interval '61 seconds')",UUID.randomUUID(),account,secrets.hash("token"),secrets.create()));
        } finally {cancellation.self(login.principal(),"test-password",UUID.randomUUID());}
    }
    @AfterAll void cleanup(){guard();assertTrue(SCHEMA.matches("it_external_[0-9a-f]{32}"));jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");}
}
