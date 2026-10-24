package cz.mh.rallye.rallye_backend.identity;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import cz.mh.rallye.rallye_backend.audit.persistence.AuditStore;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import cz.mh.rallye.rallye_backend.support.TestDatabaseInitializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest
@ActiveProfiles("it")
@ContextConfiguration(initializers=TestDatabaseInitializer.class)
@Import(IdentityIT.Fixtures.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExternalWebLoginIT {
    static final String SCHEMA="it_external_web_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void schema(DynamicPropertyRegistry p) {
        for(String provider:List.of("GOOGLE","APPLE")) {
            String prefix="rallye.auth.external.providers."+provider+".";
            p.add(prefix+"enabled",()->"true");p.add(prefix+"client-id",()->provider+"-synthetic-client");p.add(prefix+"client-secret",()->"synthetic-secret");
            p.add(prefix+"callback-uri",()->"https://backend.example.test/api/v1/auth/external/"+provider+"/callback");
        }
        p.add("rallye.auth.external.returns.web",()->"https://app.example.test/complete");
        p.add("rallye.auth.apple-vault.active-key-id",()->"test");p.add("rallye.auth.apple-vault.keys.test",()->Base64.getEncoder().encodeToString(new byte[32]));
        p.add("spring.flyway.schemas",()->SCHEMA);p.add("spring.flyway.default-schema",()->SCHEMA);
        p.add("spring.datasource.hikari.connection-init-sql",()->"SET search_path TO "+SCHEMA);
    }
    @Autowired ExternalFlowService flows;
    @Autowired ExternalLoginService login;
    @Autowired AuthService auth;
    @Autowired AccountCancellationService cancellation;
    @Autowired IdentityIT.TestMail mail;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired AppleTokenVault vault;
    @MockitoBean ExternalOidcVerifier verifier;
    @MockitoSpyBean AuditStore audit;
    @MockitoSpyBean ExternalAccountStore external;
    SecretTokens tokens=new SecretTokens();
    AuthService.Principal root;
    record Context(ExternalProviders.Provider provider,String state,String correlation) {}
    @BeforeAll void bootstrap() throws Exception {
        String email=UUID.randomUUID()+"@example.test";auth.register(email,"test-password","Root");auth.verifyEmail(mail.token(email,"EMAIL_VERIFICATION"));
        root=auth.login(email,"test-password",false).principal();
        String bootstrap=AdministrationIT.bootstrapBody(root.accountId());
        new TransactionTemplate(manager).executeWithoutResult(s->jdbc.execute(bootstrap));
    }
    void guard(){assertEquals("rallye_test",jdbc.queryForObject("SELECT current_database()",String.class));assertEquals("rallye_test",jdbc.queryForObject("SELECT current_user",String.class));assertEquals(SCHEMA,jdbc.queryForObject("SELECT current_schema()",String.class));}
    @BeforeEach void checked(){guard();}
    String email(){return UUID.randomUUID()+"@example.test";}
    Context context(ExternalProviders.Provider provider,String subject,String email,boolean verified,String name,boolean remember) {
        var start=flows.start(provider,ExternalFlowService.Purpose.LOGIN,null,"web",remember,null);
        String state=org.springframework.web.util.UriComponentsBuilder.fromUriString(start.authorizationUrl()).build().getQueryParams().getFirst("state");
        when(verifier.exchange(any(),eq("code"),eq(state),any(),any())).thenReturn(new ExternalOidcVerifier.Identity(ExternalProviders.issuer(provider),subject,email,verified,name,provider==ExternalProviders.Provider.APPLE?"synthetic-refresh-"+UUID.randomUUID():null));
        return new Context(provider,state,start.correlation());
    }
    ExternalLoginService.Outcome finish(Context c,String name){return login.callback(c.provider(),c.state(),c.correlation(),"code",name);}
    UUID account(String subject){return jdbc.queryForObject("SELECT account_id FROM user_identity WHERE subject=?",UUID.class,subject);}
    String signup(String subject,String email,boolean verified,String name) {
        var result=finish(context(ExternalProviders.Provider.GOOGLE,subject,email,verified,name,false),null);
        login.complete(result.registrationSecret(),email,name);return result.registrationSecret();
    }
    @Test void verifiedFirstRegistrationAndRepeatedLoginReuseSameAccountAndExistingSessions() {
        String subject=UUID.randomUUID().toString(),email=email();String registration=signup(subject,email,true,"Name");UUID id=account(subject);
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM user_account WHERE id=?",String.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_identity WHERE account_id=? AND provider='LOCAL'",Integer.class,id));
        var first=finish(context(ExternalProviders.Provider.GOOGLE,subject,email,true,"Changed",true),null);
        var second=finish(context(ExternalProviders.Provider.GOOGLE,subject,null,false,null,false),null);
        assertEquals(id,first.session().principal().accountId());assertEquals(id,second.session().principal().accountId());assertNotEquals(first.session().secret(),second.session().secret());
        assertEquals("Name",auth.me(second.session().principal()).displayName());assertNotNull(auth.authenticate(first.session().secret()));
        auth.logout(first.session().principal());assertNull(auth.authenticate(first.session().secret()));assertNotNull(auth.authenticate(second.session().secret()));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE event_type='LOGIN_SUCCEEDED' AND actor_id=?",Integer.class,id));
        login.complete(registration,email,"Different");assertEquals("Name",auth.me(second.session().principal()).displayName());
    }
    @Test void missingProfileAndUnverifiedEmailNeedOwnActivationBeforeLogin() {
        String subject=UUID.randomUUID().toString(),email=email();var result=finish(context(ExternalProviders.Provider.GOOGLE,subject,null,false,null,false),null);
        assertNull(result.session());assertNull(login.profile(result.registrationSecret()).email());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_identity WHERE subject=?",Integer.class,subject));
        login.complete(result.registrationSecret(),email,"Completed");
        assertEquals("PENDING_EMAIL",jdbc.queryForObject("SELECT status FROM user_account WHERE id=?",String.class,account(subject)));
        assertThrows(AuthFailure.class,()->finish(context(ExternalProviders.Provider.GOOGLE,subject,email,true,"Name",false),null));
        String activation=mail.token(email,"EMAIL_VERIFICATION");auth.verifyEmail(activation);assertThrows(AuthFailure.class,()->auth.verifyEmail(activation));
        assertNotNull(finish(context(ExternalProviders.Provider.GOOGLE,subject,null,false,null,false),null).session());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_identity WHERE account_id=? AND provider='LOCAL'",Integer.class,account(subject)));
    }
    @Test void changingProviderVerifiedEmailStillRequiresOwnVerification() {
        String subject=UUID.randomUUID().toString(),newEmail=email();var result=finish(context(ExternalProviders.Provider.GOOGLE,subject,email(),true,"Name",false),null);
        login.complete(result.registrationSecret(),newEmail,"Name");assertEquals("PENDING_EMAIL",jdbc.queryForObject("SELECT status FROM user_account WHERE id=?",String.class,account(subject)));
        auth.verifyEmail(mail.token(newEmail,"EMAIL_VERIFICATION"));assertNotNull(finish(context(ExternalProviders.Provider.GOOGLE,subject,newEmail,true,null,false),null).session());
    }
    @Test void emailCollisionDoesNotLinkOrOverwriteLocalPassword() {
        String email=email(),subject=UUID.randomUUID().toString();auth.register(email,"local-password","Local");auth.verifyEmail(mail.token(email,"EMAIL_VERIFICATION"));
        var local=auth.login(email,"local-password",false);
        var result=finish(context(ExternalProviders.Provider.GOOGLE,subject,email,true,"External",false),null);
        login.complete(result.registrationSecret(),email,"External");login.complete(result.registrationSecret(),email,"External");
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_identity WHERE subject=?",Integer.class,subject));
        assertEquals("Local",auth.me(local.principal()).displayName());assertEquals(local.principal().accountId(),auth.login(email,"local-password",false).principal().accountId());
    }
    @Test void concurrentCompletionOfOneContextCreatesExactlyOneIdentity() throws Exception {
        String subject=UUID.randomUUID().toString(),email=email();var result=finish(context(ExternalProviders.Provider.GOOGLE,subject,email,false,null,false),null);
        var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> call=()->{barrier.await(5,TimeUnit.SECONDS);login.complete(result.registrationSecret(),email,"Name");return true;};
            var a=pool.submit(call);var b=pool.submit(call);assertTrue(a.get(10,TimeUnit.SECONDS));assertTrue(b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM user_identity WHERE subject=?",Integer.class,subject));
        assertEquals(1,mail.deliveries.stream().filter(d->d.email.equals(email) && d.purpose.equals("EMAIL_VERIFICATION")).count());
    }
    @Test void twoDifferentContextsForSameIdentityDoNotOverwriteAccount() throws Exception {
        String subject=UUID.randomUUID().toString(),email=email();var a=finish(context(ExternalProviders.Provider.GOOGLE,subject,email,true,"A",false),null);
        var b=finish(context(ExternalProviders.Provider.GOOGLE,subject,email,true,"B",false),null);var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var x=pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);login.complete(a.registrationSecret(),email,"A");return true;});
            var y=pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);login.complete(b.registrationSecret(),email,"B");return true;});x.get(10,TimeUnit.SECONDS);y.get(10,TimeUnit.SECONDS);
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM user_identity WHERE subject=?",Integer.class,subject));
        assertTrue(List.of("A","B").contains(jdbc.queryForObject("SELECT display_name FROM user_account WHERE id=?",String.class,account(subject))));
    }
    @Test void exactFifteenMinuteExpiryAndInvalidTokenNeverCreateAccount() {
        var result=finish(context(ExternalProviders.Provider.GOOGLE,UUID.randomUUID().toString(),email(),true,"A",false),null);
        var expiry=login.profile(result.registrationSecret()).expiresAt();var previous=clock.instant();
        try {clock.advance(Duration.between(previous,expiry));assertThrows(AuthFailure.class,()->login.complete(result.registrationSecret(),email(),"A"));}
        finally {clock.advance(Duration.between(clock.instant(),previous));}
        assertThrows(AuthFailure.class,()->login.profile(tokens.create()));
    }
    @Test void disabledIdentityCannotLoginEvenIfProviderClaimsAreValid() {
        String subject=UUID.randomUUID().toString(),email=email();signup(subject,email,true,"A");jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?",account(subject));
        assertThrows(AuthFailure.class,()->finish(context(ExternalProviders.Provider.GOOGLE,subject,email,true,"A",false),null));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_session WHERE account_id=?",Integer.class,account(subject)));
    }
    @Test void auditFailureRollsBackSessionAndStateConsumption() {
        String subject=UUID.randomUUID().toString(),email=email();signup(subject,email,true,"A");UUID account=account(subject);
        var context=context(ExternalProviders.Provider.GOOGLE,subject,email,true,"A",false);
        doThrow(new IllegalStateException("injected")).when(audit).append(any(),any(),eq(account),eq(AuditService.Event.LOGIN_SUCCEEDED),eq(account));
        try {assertThrows(RuntimeException.class,()->finish(context,null));}
        finally {doCallRealMethod().when(audit).append(any(),any(),eq(account),eq(AuditService.Event.LOGIN_SUCCEEDED),eq(account));}
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_session WHERE account_id=?",Integer.class,account));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM external_auth_flow WHERE state_hash=?",java.sql.Timestamp.class,tokens.hash(context.state())));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE event_type='LOGIN_SUCCEEDED' AND actor_id=?",Integer.class,account));
    }
    @Test void appleRelayNameAndEncryptedCredentialSurviveRepeatedLoginAndCancellation() {
        String subject=UUID.randomUUID().toString(),email=UUID.randomUUID()+"@privaterelay.appleid.com";
        var first=finish(context(ExternalProviders.Provider.APPLE,subject,email,true,null,false),"Apple Name");
        login.complete(first.registrationSecret(),email,"Apple Name");UUID account=account(subject);
        var next=finish(context(ExternalProviders.Provider.APPLE,subject,null,false,null,false),null);
        assertEquals("Apple Name",auth.me(next.session().principal()).displayName());assertEquals(email,auth.me(next.session().principal()).email());
        var row=jdbc.queryForMap("SELECT * FROM apple_revocation_credential WHERE account_id=? ORDER BY created_at,id LIMIT 1",account);
        var sealed=new AppleTokenVault.Sealed((String)row.get("key_id"),(byte[])row.get("iv"),(byte[])row.get("ciphertext"));
        String aad=ExternalLoginService.credentialAad((UUID)row.get("id"),(String)row.get("client_id"));
        assertTrue(vault.open(sealed,aad).startsWith("synthetic-refresh-"));
        var unfinished=finish(context(ExternalProviders.Provider.APPLE,UUID.randomUUID().toString(),email,true,null,false),null);
        cancellation.administrator(root,account,UUID.randomUUID());assertNull(auth.authenticate(next.session().secret()));
        assertThrows(AuthFailure.class,()->login.profile(unfinished.registrationSecret()));
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM apple_revocation_credential WHERE account_id=?",Integer.class,account)>0);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_account WHERE id=?",Integer.class,account));
    }
    @Test void completionFailureRollsBackAccountIdentityAndContextAndCanRetry() {
        String subject=UUID.randomUUID().toString(),email=email();var result=finish(context(ExternalProviders.Provider.GOOGLE,subject,email,false,"A",false),null);
        // Configure the spy behind the MANDATORY proxy without invoking that proxy outside a transaction.
        ExternalAccountStore target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(external);
        assertTrue(mockingDetails(target).isSpy());
        var injected=new IllegalStateException("injected completion failure");
        doAnswer(call->{
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            call.callRealMethod();
            throw injected;
        }).when(target).consume(any(),any());
        try {
            RuntimeException failure=assertThrows(RuntimeException.class,()->login.complete(result.registrationSecret(),email,"A"));
            Throwable cause=failure;
            while(cause.getCause()!=null) cause=cause.getCause();
            assertSame(injected,cause);
        }
        finally {doCallRealMethod().when(target).consume(any(),any());}
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_identity WHERE subject=?",Integer.class,subject));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_account WHERE normalized_email=?",Integer.class,email));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM external_registration WHERE token_hash=?",java.sql.Timestamp.class,tokens.hash(result.registrationSecret())));
        assertEquals(email,jdbc.queryForObject("SELECT proposed_email FROM external_registration WHERE token_hash=?",String.class,tokens.hash(result.registrationSecret())));
        assertEquals("A",jdbc.queryForObject("SELECT display_name FROM external_registration WHERE token_hash=?",String.class,tokens.hash(result.registrationSecret())));
        assertEquals(0,mail.deliveries.stream().filter(d->d.email.equals(email)).count());
        login.complete(result.registrationSecret(),email,"A");assertNotNull(account(subject));
        assertEquals(1,mail.deliveries.stream().filter(d->d.email.equals(email) && d.purpose.equals("EMAIL_VERIFICATION")).count());
    }
    @Test void upgradeV8PreservesExistingAccountAndSession() {
        String upgrade=SCHEMA+"_upgrade";
        try {
            var source=jdbc.getDataSource();org.flywaydb.core.Flyway.configure().dataSource(source).schemas(upgrade).defaultSchema(upgrade).target("8").load().migrate();
            UUID account=UUID.randomUUID(),session=UUID.randomUUID();
            jdbc.update("INSERT INTO "+upgrade+".user_account(id,email,normalized_email,display_name,status,created_at,updated_at) VALUES (?,'a@example.test','a@example.test','A','PENDING_EMAIL',now(),now())",account);
            jdbc.update("INSERT INTO "+upgrade+".user_session(id,account_id,token_hash,remember_me,created_at,last_used_at,expires_at) VALUES (?,?,?,false,now(),now(),now()+interval '24 hours')",session,account,tokens.hash("synthetic-session"));
            org.flywaydb.core.Flyway.configure().dataSource(source).schemas(upgrade).defaultSchema(upgrade).load().migrate();
            assertEquals(account,jdbc.queryForObject("SELECT id FROM "+upgrade+".user_account",UUID.class));
            assertEquals(session,jdbc.queryForObject("SELECT id FROM "+upgrade+".user_session",UUID.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM "+upgrade+".external_registration",Integer.class));
        } finally {guard();assertTrue(upgrade.matches("it_external_web_[0-9a-f]{32}_upgrade"));jdbc.execute("DROP SCHEMA IF EXISTS \""+upgrade+"\" CASCADE");}
    }
    @Test void appleAndGoogleWithSameEmailRemainSeparateWithoutAutomaticLink() {
        String email=email(),google=UUID.randomUUID().toString(),apple=UUID.randomUUID().toString();signup(google,email,true,"Google");UUID owner=account(google);
        var second=finish(context(ExternalProviders.Provider.APPLE,apple,email,true,null,false),"Apple");
        login.complete(second.registrationSecret(),email,"Apple");
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM user_identity WHERE account_id=?",Integer.class,owner));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_identity WHERE subject=?",Integer.class,apple));
        assertEquals(owner,finish(context(ExternalProviders.Provider.GOOGLE,google,email,true,"Google",false),null).session().principal().accountId());
    }
    @Test void reviewedV9AcceptedAndUnknownNameFailsClosed() {
        var facts=new PreRaceAccountParticipationFacts(jdbc);
        new TransactionTemplate(manager).executeWithoutResult(s->{assertFalse(facts.forAccount(UUID.randomUUID()).historical());
            jdbc.update("UPDATE flyway_schema_history SET script='V9__unknown.sql' WHERE version='9'");
            assertThrows(AuthFailure.class,()->facts.forAccount(UUID.randomUUID()));s.setRollbackOnly();});
    }
    @Test void reviewedV10AcceptedAndUnknownV10NameFailsClosed() {
        var facts=new PreRaceAccountParticipationFacts(jdbc);
        new TransactionTemplate(manager).executeWithoutResult(s->{assertFalse(facts.forAccount(UUID.randomUUID()).historical());
            jdbc.update("UPDATE flyway_schema_history SET script='V10__unknown.sql' WHERE version='10'");
            assertThrows(AuthFailure.class,()->facts.forAccount(UUID.randomUUID()));s.setRollbackOnly();});
    }
    @AfterAll void cleanup(){guard();assertTrue(SCHEMA.matches("it_external_web_[0-9a-f]{32}"));jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");}
}
