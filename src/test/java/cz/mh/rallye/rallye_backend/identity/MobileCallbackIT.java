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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest
@ActiveProfiles("it")
@ContextConfiguration(initializers=TestDatabaseInitializer.class)
@Import({IdentityIT.Fixtures.class,AccountCancellationServiceIT.Fixtures.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MobileCallbackIT {
    static final String SCHEMA="it_mobile_callback_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        MobileLoginIT.properties(p);
        p.add("rallye.auth.apple-vault.active-key-id",()->"test");p.add("rallye.auth.apple-vault.keys.test",()->Base64.getEncoder().encodeToString(new byte[32]));
        p.add("spring.flyway.schemas",()->SCHEMA);p.add("spring.flyway.default-schema",()->SCHEMA);
        p.add("spring.datasource.hikari.connection-init-sql",()->"SET search_path TO "+SCHEMA);
    }
    @Autowired MobileLoginService mobile;
    @Autowired ExternalFlowService flows;
    @Autowired ExternalLoginService login;
    @Autowired AuthService auth;
    @Autowired AccountCancellationService cancellation;
    @Autowired AccountCancellationServiceIT.ControlledFacts participation;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager manager;
    @Autowired PreRaceAccountParticipationFacts facts;
    @MockitoBean ExternalOidcVerifier verifier;
    @MockitoSpyBean MobileGrantStore grantStore;
    final SecretTokens secrets=new SecretTokens();
    final Map<String,ExternalOidcVerifier.Identity> proofs=new ConcurrentHashMap<>();
    record Account(ExternalProviders.Provider provider,String subject,String email,UUID id) {}
    record Run(ExternalProviders.Provider provider,String state,String correlation,UUID requestId,String code) {}
    String proof(ExternalProviders.Provider provider,String subject,String email) {
        String code=UUID.randomUUID().toString();
        proofs.put(code,new ExternalOidcVerifier.Identity(ExternalProviders.issuer(provider),subject,email,true,"Test user","synthetic-refresh-"+code));return code;
    }
    String state(String url){return org.springframework.web.util.UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("state");}
    Account account(ExternalProviders.Provider provider) {
        String subject=UUID.randomUUID().toString(),email=UUID.randomUUID()+"@example.test";
        var flow=flows.start(provider,ExternalFlowService.Purpose.LOGIN,null,"web",false,null);
        var out=login.callback(provider,state(flow.authorizationUrl()),flow.correlation(),proof(provider,subject,email),"Test user");
        login.complete(out.registrationSecret(),email,"Test user");
        return new Account(provider,subject,email,jdbc.queryForObject("SELECT account_id FROM user_identity WHERE provider=? AND subject=?",UUID.class,provider.name(),subject));
    }
    AuthService.LoginSession session(Account a) {
        var flow=flows.start(a.provider(),ExternalFlowService.Purpose.LOGIN,null,"web",false,null);
        return login.callback(a.provider(),state(flow.authorizationUrl()),flow.correlation(),proof(a.provider(),a.subject(),a.email()),null).session();
    }
    Run run(ExternalProviders.Provider provider,String subject,String email) {
        var start=mobile.start(provider,ExternalFlowService.Purpose.LOGIN,"android",true,secrets.create());
        String ticket=java.net.URI.create(start.browserStartUrl()).getQuery().substring("ticket=".length());
        var launch=mobile.launch(ticket);return new Run(provider,state(launch.flow().authorizationUrl()),launch.flow().correlation(),start.requestId(),proof(provider,subject,email));
    }
    Run run(Account a){return run(a.provider(),a.subject(),a.email());}
    ExternalLoginService.Outcome finish(Run r){return login.callback(r.provider(),r.state(),r.correlation(),r.code(),"Test user");}
    int grants(UUID attempt){return jdbc.queryForObject("SELECT count(*) FROM external_auth_grant WHERE attempt_id=?",Integer.class,attempt);}
    int sessions(UUID account){return jdbc.queryForObject("SELECT count(*) FROM user_session WHERE account_id=?",Integer.class,account);}
    void guard(){assertEquals("rallye_test",jdbc.queryForObject("SELECT current_database()",String.class));assertEquals("rallye_test",jdbc.queryForObject("SELECT current_user",String.class));assertEquals(SCHEMA,jdbc.queryForObject("SELECT current_schema()",String.class));}
    @BeforeEach void setup(){guard();doAnswer(call->proofs.get(call.getArgument(1,String.class))).when(verifier).exchange(any(),any(),any(),any(),any());}
    @Test void googleAndAppleIssueOneHashedBoundGrantWithoutSessionOrLoginAudit() {
        for(var provider:ExternalProviders.Provider.values()) {
            var a=account(provider);var r=run(a);int audit=jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE actor_id=?",Integer.class,a.id());
            var out=finish(r);assertNull(out.session());assertNull(out.registrationSecret());assertEquals(r.requestId(),out.requestId());assertEquals("https://app.example.test/mobile/return",out.returnUri());
            var row=jdbc.queryForMap("SELECT * FROM external_auth_grant WHERE attempt_id=?",r.requestId());
            assertEquals(secrets.hash(out.handoffCode()),row.get("token_hash"));assertFalse(row.containsValue(out.handoffCode()));assertEquals(a.id(),row.get("account_id"));assertNull(row.get("session_id"));
            assertEquals(((java.sql.Timestamp)row.get("created_at")).toInstant().plusSeconds(60),((java.sql.Timestamp)row.get("expires_at")).toInstant());
            assertEquals(jdbc.queryForObject("SELECT pkce_challenge FROM mobile_login_attempt WHERE id=?",String.class,r.requestId()),row.get("pkce_challenge"));
            assertEquals(0,sessions(a.id()));assertEquals(audit,jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE actor_id=?",Integer.class,a.id()));
        }
    }
    @Test void replayIsRejectedBeforeAnotherProviderExchange() {
        var r=run(account(ExternalProviders.Provider.GOOGLE));clearInvocations(verifier);finish(r);
        assertThrows(AuthFailure.class,()->finish(r));assertEquals(1,grants(r.requestId()));verify(verifier,times(1)).exchange(any(),eq(r.code()),any(),any(),any());
    }
    @Test void concurrentCallbackHasOneWinnerAndOneGrant() throws Exception {
        var r=run(account(ExternalProviders.Provider.GOOGLE));var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> call=()->{barrier.await(5,TimeUnit.SECONDS);try{finish(r);return true;}catch(AuthFailure failure){assertEquals(400,failure.status());return false;}};
            var a=pool.submit(call);var b=pool.submit(call);assertNotEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
        assertEquals(1,grants(r.requestId()));
    }
    @Test void wrongCookieProviderAndExpiredAuthorizationNeverIssueGrant() {
        var r=run(account(ExternalProviders.Provider.GOOGLE));
        for(String cookie:Arrays.asList(null,secrets.create()))assertThrows(AuthFailure.class,()->login.callback(r.provider(),r.state(),cookie,r.code(),null));
        assertThrows(AuthFailure.class,()->login.callback(ExternalProviders.Provider.APPLE,r.state(),r.correlation(),r.code(),null));
        clock.advance(Duration.ofMinutes(10));assertThrows(AuthFailure.class,()->finish(r));assertEquals(0,grants(r.requestId()));
    }
    @Test void changedReturnOrChallengeCannotRedirectOrIssueGrant() {
        var a=account(ExternalProviders.Provider.GOOGLE);
        for(String field:List.of("return_uri","pkce_challenge")) {
            var r=run(a);jdbc.update("UPDATE mobile_login_attempt SET "+field+"=? WHERE id=?",field.equals("return_uri")?"https://attacker.example.test/return":secrets.create(),r.requestId());
            assertThrows(AuthFailure.class,()->finish(r));assertEquals(0,grants(r.requestId()));
        }
    }
    @Test void disabledAccountNeverReceivesGrant() {
        var a=account(ExternalProviders.Provider.GOOGLE);var r=run(a);jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?",a.id());
        assertThrows(AuthFailure.class,()->finish(r));assertEquals(0,grants(r.requestId()));assertEquals(0,sessions(a.id()));
    }
    @Test void unknownIdentityUsesBrowserRegistrationAndEmailCollisionNeverLinks() {
        var existing=account(ExternalProviders.Provider.GOOGLE);var r=run(ExternalProviders.Provider.GOOGLE,UUID.randomUUID().toString(),existing.email());
        var out=finish(r);assertNull(out.handoffCode());assertNull(out.session());assertNotNull(out.registrationSecret());assertEquals("https://web.example.test/profile",out.returnUri());
        login.complete(out.registrationSecret(),existing.email(),"Other");assertEquals(0,grants(r.requestId()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM user_identity WHERE account_id=?",Integer.class,existing.id()));
    }
    @Test void completedNewRegistrationNeedsNewMobileLogin() {
        String subject=UUID.randomUUID().toString(),email=UUID.randomUUID()+"@example.test";var r=run(ExternalProviders.Provider.GOOGLE,subject,email);
        var out=finish(r);login.complete(out.registrationSecret(),email,"New user");assertEquals(0,grants(r.requestId()));
        assertThrows(AuthFailure.class,()->finish(r));var next=run(ExternalProviders.Provider.GOOGLE,subject,email);assertNotNull(finish(next).handoffCode());assertEquals(1,grants(next.requestId()));
    }
    @Test void denialConsumesStateAndReturnsOnlyConfiguredTargetAndCorrelation() {
        var r=run(account(ExternalProviders.Provider.GOOGLE));var rejected=login.rejectCallback(r.provider(),r.state(),r.correlation());
        assertEquals(r.requestId(),rejected.requestId());assertEquals("https://app.example.test/mobile/return",rejected.returnUri());assertEquals(0,grants(r.requestId()));assertThrows(AuthFailure.class,()->finish(r));
    }
    @Test void grantFailureKeepsCapturedAppleCredentialButCreatesNoSessionOrGrant() {
        var a=account(ExternalProviders.Provider.APPLE);var r=run(a);
        int credentials=jdbc.queryForObject("SELECT count(*) FROM apple_revocation_credential WHERE account_id=?",Integer.class,a.id());
        MobileGrantStore target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(grantStore);var marker=new IllegalStateException("injected grant failure");
        doAnswer(call->{call.callRealMethod();throw marker;}).when(target).issue(eq(a.id()),eq(r.requestId()),any(),any(),any());
        try {Throwable failure=assertThrows(RuntimeException.class,()->finish(r));while(failure.getCause()!=null)failure=failure.getCause();assertSame(marker,failure);}
        finally {doCallRealMethod().when(target).issue(eq(a.id()),eq(r.requestId()),any(),any(),any());}
        assertEquals(0,grants(r.requestId()));assertEquals(0,sessions(a.id()));
        assertNotNull(jdbc.queryForObject("SELECT consumed_at FROM external_auth_flow WHERE state_hash=?",java.sql.Timestamp.class,secrets.hash(r.state())));
        assertEquals(credentials+1,jdbc.queryForObject("SELECT count(*) FROM apple_revocation_credential WHERE account_id=?",Integer.class,a.id()));
        assertThrows(AuthFailure.class,()->finish(r));
    }
    @Test void cancellationBetweenProofAndTransactionCannotIssueOldAccountGrant() {
        var a=account(ExternalProviders.Provider.GOOGLE);var session=session(a);var r=run(a);
        doAnswer(call->{cancellation.self(session.principal(),null,true,UUID.randomUUID());return proofs.get(r.code());}).when(verifier).exchange(any(),eq(r.code()),any(),any(),any());
        var out=finish(r);assertNull(out.handoffCode());assertNotNull(out.registrationSecret());assertEquals(0,grants(r.requestId()));assertNull(auth.authenticate(session.secret()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_account WHERE id=?",Integer.class,a.id()));
    }
    @Test void cancellationAndAnonymizationRemoveGrantRatherThanRebindIt() {
        for(boolean historical:List.of(false,true)) {
            var a=account(ExternalProviders.Provider.GOOGLE);var session=session(a);var r=run(a);finish(r);assertEquals(1,grants(r.requestId()));
            if(historical)participation.overrides.put(a.id(),new AccountParticipationFacts.Facts(true,false));
            try {cancellation.self(session.principal(),null,true,UUID.randomUUID());}finally{participation.overrides.remove(a.id());}
            assertEquals(0,grants(r.requestId()));assertNull(auth.authenticate(session.secret()));
        }
    }
    @Test void concurrentCancellationAndCallbackLeaveNoGrantOrSessionForOldAccount() throws Exception {
        var a=account(ExternalProviders.Provider.GOOGLE);var session=session(a);var r=run(a);var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var callback=pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);return finish(r);});
            var cancelled=pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);cancellation.self(session.principal(),null,true,UUID.randomUUID());return true;});
            assertNotNull(callback.get(20,TimeUnit.SECONDS));assertTrue(cancelled.get(20,TimeUnit.SECONDS));
        }
        assertEquals(0,grants(r.requestId()));assertNull(auth.authenticate(session.secret()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_account WHERE id=?",Integer.class,a.id()));
    }
    @Test void databaseUniquenessAndPreciseV12GuardRemainEnforced() {
        var a=account(ExternalProviders.Provider.GOOGLE);var r=run(a);finish(r);
        var challenge=jdbc.queryForObject("SELECT pkce_challenge FROM mobile_login_attempt WHERE id=?",String.class,r.requestId());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->new TransactionTemplate(manager).executeWithoutResult(s->grantStore.issue(a.id(),r.requestId(),challenge,secrets.hash("other-code"),clock.instant())));
        assertFalse(new TransactionTemplate(manager).execute(s->facts.forAccount(a.id())).historical());
        new TransactionTemplate(manager).executeWithoutResult(s->{jdbc.update("UPDATE flyway_schema_history SET script='V12__unknown.sql' WHERE version='12'");assertThrows(AuthFailure.class,()->facts.forAccount(a.id()));s.setRollbackOnly();});
    }
    @Test void v12PreflightRejectsLegacyUnboundGrantsWithoutDeletingThem() {
        String schema=SCHEMA+"_upgrade";
        try {
            var source=jdbc.getDataSource();org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("11").load().migrate();
            UUID account=UUID.randomUUID();jdbc.update("INSERT INTO "+schema+".user_account(id,email,normalized_email,display_name,status,created_at,updated_at) VALUES (?,'legacy@example.test','legacy@example.test','Legacy','PENDING_EMAIL',now(),now())",account);
            jdbc.update("INSERT INTO "+schema+".external_auth_grant(id,account_id,kind,purpose,token_hash,pkce_challenge,created_at,expires_at) VALUES (?,?,'MOBILE','LOGIN',?,?,now(),now()+interval '60 seconds')",UUID.randomUUID(),account,secrets.hash("legacy"),secrets.create());
            assertThrows(org.flywaydb.core.api.FlywayException.class,()->org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM "+schema+".external_auth_grant",Integer.class));
        } finally {guard();assertTrue(schema.matches("it_mobile_callback_[0-9a-f]{32}_upgrade"));jdbc.execute("DROP SCHEMA IF EXISTS \""+schema+"\" CASCADE");}
    }
    @AfterAll void cleanup(){guard();assertTrue(SCHEMA.matches("it_mobile_callback_[0-9a-f]{32}"));jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");}
}
