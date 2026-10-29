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
@Import({IdentityIT.Fixtures.class,AccountCancellationServiceIT.Fixtures.class})
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
    @Autowired AccountCancellationServiceIT.ControlledFacts participation;
    @MockitoBean ExternalOidcVerifier verifier;
    @MockitoSpyBean AuditStore audit;
    @MockitoSpyBean ExternalAccountStore external;
    @MockitoSpyBean AppleRevocationStore revocations;
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
    /** Only for tests whose prerequisite is an already registered GOOGLE account. No callback,
     * registration-completion or Apple lifecycle assertion uses this shortcut. Session issuance
     * still goes through context()/finish(), including real flow correlation/consumption and audit.
     */
    UUID existingGoogleAccount(String subject,String email,String name) {
        return new TransactionTemplate(manager).execute(s->{
            external.lock();var at=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);UUID id=UUID.randomUUID();
            var identity=new ExternalAccountStore.Registration(UUID.randomUUID(),"GOOGLE",ExternalProviders.issuer(ExternalProviders.Provider.GOOGLE),
                    subject,email,true,name,false,"https://app.example.test/complete",at.plus(ExternalLoginService.COMPLETION_LIFE),null);
            assertTrue(external.create(id,identity,email,name,true,at));
            return id;
        });
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
        String subject=UUID.randomUUID().toString(),email=email();existingGoogleAccount(subject,email,"A");jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?",account(subject));
        assertThrows(AuthFailure.class,()->finish(context(ExternalProviders.Provider.GOOGLE,subject,email,true,"A",false),null));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_session WHERE account_id=?",Integer.class,account(subject)));
    }
    @Test void auditFailureRollsBackSessionAndStateConsumption() {
        String subject=UUID.randomUUID().toString(),email=email();existingGoogleAccount(subject,email,"A");UUID account=account(subject);
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
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM apple_revocation_credential WHERE case_id IN (SELECT id FROM apple_authorization_case WHERE origin_account_id=?)",Integer.class,account)>0);
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
    List<Map<String,Object>> material(UUID account) {
        return jdbc.queryForList("SELECT * FROM apple_revocation_credential WHERE account_id=? ORDER BY id",account);
    }
    void assertMaterialUnchanged(List<Map<String,Object>> before,UUID account) {
        var after=jdbc.queryForList("SELECT * FROM apple_revocation_credential WHERE case_id IN (SELECT id FROM apple_authorization_case WHERE origin_account_id=?) ORDER BY id",account);assertEquals(before.size(),after.size());assertFalse(before.isEmpty());
        for(int i=0;i<before.size();i++)for(String column:before.get(i).keySet()) {
            Object expected=before.get(i).get(column),actual=after.get(i).get(column);
            if(expected instanceof byte[] bytes)assertArrayEquals(bytes,(byte[])actual);
            else if(column.equals("account_id")){assertTrue(actual==null || actual.equals(expected));}
            else assertEquals(expected,actual,column);
        }
    }
    AuthService.LoginSession externalAccount(ExternalProviders.Provider provider) {
        String subject=UUID.randomUUID().toString(),email=email();
        if(provider==ExternalProviders.Provider.GOOGLE)existingGoogleAccount(subject,email,"D10");
        else {
            // Apple fixtures must retain both real token captures and the registration -> account binding.
            var registration=finish(context(provider,subject,email,true,"D10",false),"D10");
            login.complete(registration.registrationSecret(),email,"D10");
        }
        return finish(context(provider,subject,email,true,null,false),null).session();
    }
    @Test void existingGoogleFixturesHaveOneIdentityNoSessionAndKeepRealCallbackSecurity() {
        String subject=UUID.randomUUID().toString(),email=email(),otherSubject=UUID.randomUUID().toString();
        UUID id=existingGoogleAccount(subject,email,"Existing"),other=existingGoogleAccount(otherSubject,email(),"Other");
        assertNotEquals(id,other);assertEquals(id,account(subject));assertEquals(other,account(otherSubject));
        assertEquals(1,count("SELECT count(*) FROM user_account WHERE id=? AND status='ACTIVE' AND email_verified_at IS NOT NULL",id));
        assertEquals(1,count("SELECT count(*) FROM user_identity WHERE account_id=? AND provider='GOOGLE' AND issuer=? AND subject=?",id,ExternalProviders.issuer(ExternalProviders.Provider.GOOGLE),subject));
        assertEquals(0,count("SELECT count(*) FROM user_session WHERE account_id IN (?,?)",id,other));
        assertEquals(0,count("SELECT count(*) FROM audit_event WHERE actor_id=? AND event_type='LOGIN_SUCCEEDED'",id));
        var ctx=context(ExternalProviders.Provider.GOOGLE,subject,email,true,"Changed",false);
        assertEquals("EXTERNAL_STATE_INVALID",assertThrows(AuthFailure.class,()->login.callback(ctx.provider(),ctx.state(),tokens.create(),"code",null)).code());
        assertEquals(0,count("SELECT count(*) FROM user_session WHERE account_id=?",id));
        var result=finish(ctx,null);assertEquals(id,result.session().principal().accountId());
        assertNotNull(auth.authenticate(result.session().secret()));assertEquals("Existing",auth.me(result.session().principal()).displayName());
        assertThrows(AuthFailure.class,()->finish(ctx,null));
        assertEquals(1,count("SELECT count(*) FROM audit_event WHERE actor_id=? AND event_type='LOGIN_SUCCEEDED'",id));
        assertEquals(0,count("SELECT count(*) FROM user_session WHERE account_id=?",other));
    }
    @Test void googleD10CancellationRevokesAllSessionsAndAuditsOnce() {
        var session=externalAccount(ExternalProviders.Provider.GOOGLE);UUID id=session.principal().accountId();
        String subject=jdbc.queryForObject("SELECT subject FROM user_identity WHERE account_id=?",String.class,id);
        var other=finish(context(ExternalProviders.Provider.GOOGLE,subject,null,false,null,false),null).session();
        cancellation.self(session.principal(),null,true,UUID.randomUUID());
        assertNull(auth.authenticate(session.secret()));assertNull(auth.authenticate(other.secret()));
        for(String table:List.of("user_session","user_identity","user_action_token"))assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE account_id=?",Integer.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_account WHERE id=?",Integer.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE event_type='ACCOUNT_DELETED' AND actor_id=?",Integer.class,id));
    }
    @Test void externalAccountsCannotCancelWithoutExplicitConfirmationEvenWithPassword() {
        for(var provider:List.of(ExternalProviders.Provider.GOOGLE,ExternalProviders.Provider.APPLE)) {
            var session=externalAccount(provider);UUID id=session.principal().accountId();
            for(String password:Arrays.asList(null,"irrelevant-password")) {
                var error=assertThrows(AuthFailure.class,()->cancellation.self(session.principal(),password,false,UUID.randomUUID()));
                assertEquals("ACCOUNT_CANCELLATION_CONFIRMATION_REQUIRED",error.code());
            }
            assertNotNull(auth.authenticate(session.secret()));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM user_identity WHERE account_id=?",Integer.class,id));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE event_type IN ('ACCOUNT_DELETED','ACCOUNT_ANONYMIZED') AND actor_id=?",Integer.class,id));
        }
    }
    @Test void confirmationCannotBypassRevokedExternalSession() {
        for(var provider:List.of(ExternalProviders.Provider.GOOGLE,ExternalProviders.Provider.APPLE)) {
            var session=externalAccount(provider);auth.logout(session.principal());
            assertEquals(401,assertThrows(AuthFailure.class,()->cancellation.self(session.principal(),null,true,UUID.randomUUID())).status());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM user_account WHERE id=?",Integer.class,session.principal().accountId()));
        }
    }
    @Test void appleD10DeletionPreservesExactEncryptedMaterialAndClientBinding() {
        var session=externalAccount(ExternalProviders.Provider.APPLE);UUID id=session.principal().accountId();
        var before=material(id);
        cancellation.self(session.principal(),null,true,UUID.randomUUID());
        assertMaterialUnchanged(before,id);assertRevocation(id,before.size());
        var sealed=new AppleTokenVault.Sealed((String)before.getFirst().get("key_id"),(byte[])before.getFirst().get("iv"),(byte[])before.getFirst().get("ciphertext"));
        assertTrue(vault.open(sealed,ExternalLoginService.credentialAad((UUID)before.getFirst().get("id"),(String)before.getFirst().get("client_id"))).startsWith("synthetic-refresh-"));
        assertNull(auth.authenticate(session.secret()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_account WHERE id=?",Integer.class,id));
    }
    @Test void appleHistoricalD10CancellationAnonymizesWithoutLosingRevocationMaterial() {
        var session=externalAccount(ExternalProviders.Provider.APPLE);UUID id=session.principal().accountId();
        var before=material(id);
        participation.overrides.put(id,new AccountParticipationFacts.Facts(true,false));
        try {cancellation.self(session.principal(),null,true,UUID.randomUUID());}
        finally {participation.overrides.remove(id);}
        var account=jdbc.queryForMap("SELECT * FROM user_account WHERE id=?",id);
        assertEquals("ANONYMIZED",account.get("status"));assertNull(account.get("email"));assertNull(account.get("display_name"));assertNull(account.get("normalized_email"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_identity WHERE account_id=?",Integer.class,id));
        assertMaterialUnchanged(before,id);assertRevocation(id,before.size());assertNull(auth.authenticate(session.secret()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE event_type='ACCOUNT_ANONYMIZED' AND actor_id=?",Integer.class,id));
    }
    @Test void missingAppleMaterialBlocksBothSelfAndAdminBeforeDeletion() {
        UUID id=UUID.randomUUID(),sessionId=UUID.randomUUID();String secret=tokens.create(),email=email();
        new TransactionTemplate(manager).executeWithoutResult(s->{
            jdbc.update("INSERT INTO user_account(id,email,normalized_email,display_name,status,created_at,updated_at,email_verified_at) VALUES (?,?,?,'Missing material','ACTIVE',now(),now(),now())",id,email,email);
            jdbc.update("INSERT INTO user_identity(id,account_id,provider,issuer,subject,created_at) VALUES (?,?,'APPLE','https://appleid.apple.com',?,now())",UUID.randomUUID(),id,UUID.randomUUID().toString());
            jdbc.update("INSERT INTO user_session(id,account_id,token_hash,remember_me,created_at,last_used_at,expires_at) VALUES (?,?,?,false,?,?,?)",sessionId,id,tokens.hash(secret),java.sql.Timestamp.from(clock.instant()),java.sql.Timestamp.from(clock.instant()),java.sql.Timestamp.from(clock.instant().plusSeconds(86400)));
        });
        var session=new AuthService.LoginSession(secret,new AuthService.Principal(id,sessionId,false));
        assertEquals("APPLE_REVOCATION_MATERIAL_UNAVAILABLE",assertThrows(AuthFailure.class,()->cancellation.self(session.principal(),null,true,UUID.randomUUID())).code());
        assertEquals("APPLE_REVOCATION_MATERIAL_UNAVAILABLE",assertThrows(AuthFailure.class,()->cancellation.administrator(root,id,UUID.randomUUID())).code());
        assertNotNull(auth.authenticate(session.secret()));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM user_identity WHERE account_id=?",Integer.class,id));
    }
    @Test void concurrentExternalD10CancellationHasOneWinnerAndOneAudit() throws Exception {
        var session=externalAccount(ExternalProviders.Provider.GOOGLE);var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> call=()->{barrier.await(5,TimeUnit.SECONDS);
                try {cancellation.self(session.principal(),null,true,UUID.randomUUID());return true;}
                catch(AuthFailure rejected){assertEquals(401,rejected.status());return false;}
            };
            var first=pool.submit(call);var second=pool.submit(call);assertNotEquals(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE event_type='ACCOUNT_DELETED' AND actor_id=?",Integer.class,session.principal().accountId()));
    }
    @Test void externalD10AuditFailureRollsBackAccountIdentitySessionsAndMaterial() {
        var session=externalAccount(ExternalProviders.Provider.APPLE);UUID id=session.principal().accountId();
        var before=material(id);
        var marker=new IllegalStateException("injected audit failure");
        doThrow(marker).when(audit).append(any(),any(),eq(id),eq(AuditService.Event.ACCOUNT_DELETED),eq(id));
        try {
            Throwable failure=assertThrows(RuntimeException.class,()->cancellation.self(session.principal(),null,true,UUID.randomUUID()));
            while(failure.getCause()!=null)failure=failure.getCause();assertSame(marker,failure);
        }
        finally {doCallRealMethod().when(audit).append(any(),any(),eq(id),eq(AuditService.Event.ACCOUNT_DELETED),eq(id));}
        assertNotNull(auth.authenticate(session.secret()));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM user_identity WHERE account_id=?",Integer.class,id));
        assertMaterialUnchanged(before,id);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE event_type='ACCOUNT_DELETED' AND actor_id=?",Integer.class,id));
        cancellation.self(session.principal(),null,true,UUID.randomUUID());assertNull(auth.authenticate(session.secret()));
    }
    @AfterAll void cleanup(){guard();assertTrue(SCHEMA.matches("it_external_web_[0-9a-f]{32}"));jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");}
    int count(String sql,Object... args){return jdbc.queryForObject(sql,Integer.class,args);}
    void purge(){new TransactionTemplate(manager).executeWithoutResult(s->external.purgeExpired(clock.instant()));}
    void assertRevocation(UUID account,int expected){
        assertEquals(0,count("SELECT count(*) FROM apple_authorization_case WHERE account_id=?",account));
        assertEquals(expected,count("SELECT count(*) FROM apple_revocation_job j JOIN apple_authorization_case c ON c.id=j.case_id WHERE c.origin_account_id=? AND j.status='PENDING' AND j.reason='ACCOUNT_CANCELLATION'",account));
        assertEquals(expected,count("SELECT count(*) FROM apple_revocation_credential m JOIN apple_authorization_case c ON c.id=m.case_id WHERE c.origin_account_id=? AND m.account_id IS NULL AND m.registration_id IS NULL",account));
    }
    @Test void appleCaptureHasVerifiedSubjectClientFlowAndNoUnboundMaterial() {
        String subject=UUID.randomUUID().toString();var ctx=context(ExternalProviders.Provider.APPLE,subject,email(),true,null,false);var out=finish(ctx,"Apple");
        var row=jdbc.queryForMap("SELECT c.*,m.case_id FROM apple_authorization_case c JOIN apple_revocation_credential m ON m.case_id=c.id WHERE c.subject=?",subject);
        assertEquals("APPLE-synthetic-client",row.get("client_id"));assertEquals("REGISTRATION_PENDING",row.get("status"));assertNotNull(row.get("registration_id"));
        assertEquals(jdbc.queryForObject("SELECT id FROM external_auth_flow WHERE state_hash=?",UUID.class,tokens.hash(ctx.state())),row.get("origin_flow_id"));
        assertNotNull(out.registrationSecret());assertEquals(0,count("SELECT count(*) FROM apple_revocation_credential WHERE case_id IS NULL"));
    }
    @Test void appleCompletionBindsAllSameSubjectContextsAtomically() {
        String subject=UUID.randomUUID().toString(),email=email();
        var a=finish(context(ExternalProviders.Provider.APPLE,subject,email,true,null,false),"A");
        var b=finish(context(ExternalProviders.Provider.APPLE,subject,email,true,null,false),"B");
        login.complete(a.registrationSecret(),email,"Apple");UUID id=account(subject);
        assertEquals(2,count("SELECT count(*) FROM apple_authorization_case WHERE account_id=? AND status='ACCOUNT_BOUND'",id));
        assertEquals(2,material(id).size());login.complete(b.registrationSecret(),email,"Apple");assertEquals(1,count("SELECT count(*) FROM user_identity WHERE subject=?",subject));
    }
    @Test void appleCompletionFailureRollsBackBindingAndRetainsCapturedTokenForRetry() {
        String subject=UUID.randomUUID().toString(),email=email();var out=finish(context(ExternalProviders.Provider.APPLE,subject,email,true,null,false),"Apple");
        ExternalAccountStore target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(external);
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("injected after binding");}).when(target).consume(any(),any());
        try {assertThrows(RuntimeException.class,()->login.complete(out.registrationSecret(),email,"Apple"));}
        finally {doCallRealMethod().when(target).consume(any(),any());}
        assertEquals(0,count("SELECT count(*) FROM user_identity WHERE subject=?",subject));
        assertEquals(1,count("SELECT count(*) FROM apple_authorization_case WHERE subject=? AND status='REGISTRATION_PENDING'",subject));
        assertEquals(1,count("SELECT count(*) FROM apple_revocation_credential m JOIN apple_authorization_case c ON c.id=m.case_id WHERE c.subject=? AND m.registration_id IS NOT NULL",subject));
        login.complete(out.registrationSecret(),email,"Apple");assertEquals(1,material(account(subject)).size());
    }
    @Test void abandonedAppleRegistrationEnqueuesBeforePurgeWithoutLosingCiphertext() {
        String subject=UUID.randomUUID().toString();var out=finish(context(ExternalProviders.Provider.APPLE,subject,email(),true,null,false),"Apple");
        UUID credential=jdbc.queryForObject("SELECT m.id FROM apple_revocation_credential m JOIN apple_authorization_case c ON c.id=m.case_id WHERE c.subject=?",UUID.class,subject);
        byte[] before=jdbc.queryForObject("SELECT ciphertext FROM apple_revocation_credential WHERE id=?",byte[].class,credential);Instant previous=clock.instant();
        try {clock.advance(Duration.ofMinutes(15));purge();purge();}
        finally {clock.advance(Duration.between(clock.instant(),previous));}
        assertEquals(0,count("SELECT count(*) FROM external_registration WHERE token_hash=?",tokens.hash(out.registrationSecret())));
        assertEquals(1,count("SELECT count(*) FROM apple_revocation_job WHERE credential_id=? AND reason='REGISTRATION_ABANDONED'",credential));
        assertArrayEquals(before,jdbc.queryForObject("SELECT ciphertext FROM apple_revocation_credential WHERE id=?",byte[].class,credential));
    }
    @Test void liveSiblingPreventsPrematureD14Revocation() {
        String subject=UUID.randomUUID().toString(),email=email();Instant previous=clock.instant();
        try {
            finish(context(ExternalProviders.Provider.APPLE,subject,email,true,null,false),"A");clock.advance(Duration.ofMinutes(5));
            var live=finish(context(ExternalProviders.Provider.APPLE,subject,email,true,null,false),"B");clock.advance(Duration.ofMinutes(10));purge();
            assertEquals(0,count("SELECT count(*) FROM apple_revocation_job j JOIN apple_authorization_case c ON c.id=j.case_id WHERE c.subject=?",subject));
            assertEquals(2,count("SELECT count(*) FROM apple_authorization_case WHERE subject=? AND status='REGISTRATION_PENDING'",subject));
            login.complete(live.registrationSecret(),email,"Apple");assertEquals(2,material(account(subject)).size());purge();
        } finally {clock.advance(Duration.between(clock.instant(),previous));}
    }
    @Test void pendingEmailAccountIsNotAnAbandonedRegistration() {
        String subject=UUID.randomUUID().toString(),email=email();var out=finish(context(ExternalProviders.Provider.APPLE,subject,null,false,null,false),"Apple");
        login.complete(out.registrationSecret(),email,"Apple");UUID id=account(subject);Instant previous=clock.instant();
        try {clock.advance(Duration.ofMinutes(16));purge();}finally{clock.advance(Duration.between(clock.instant(),previous));}
        assertEquals("PENDING_EMAIL",jdbc.queryForObject("SELECT status FROM user_account WHERE id=?",String.class,id));
        assertEquals(1,material(id).size());assertEquals(0,count("SELECT count(*) FROM apple_revocation_job j JOIN apple_authorization_case c ON c.id=j.case_id WHERE c.account_id=?",id));
    }
    @Test void selfAndAdminCancellationDetachAndEnqueueAllAppleCasesWithReplay() {
        var own=externalAccount(ExternalProviders.Provider.APPLE);UUID id=own.principal().accountId();int expected=material(id).size();
        cancellation.self(own.principal(),null,true,UUID.randomUUID());assertRevocation(id,expected);
        var other=externalAccount(ExternalProviders.Provider.APPLE);UUID target=other.principal().accountId();int all=material(target).size();UUID key=UUID.randomUUID();
        cancellation.administrator(root,target,key);cancellation.administrator(root,target,key);assertRevocation(target,all);
        assertEquals(1,count("SELECT count(*) FROM audit_event WHERE event_type='ACCOUNT_DELETED' AND target_id=?",target));
    }
    @Test void cancellationEnqueueFailureRollsBackOwnerIdentitySessionsAndCiphertext() {
        var session=externalAccount(ExternalProviders.Provider.APPLE);UUID id=session.principal().accountId();var before=material(id);
        AppleRevocationStore target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(revocations);
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("injected enqueue failure");}).when(target).enqueue(any(),any(),any());
        try {assertThrows(RuntimeException.class,()->cancellation.self(session.principal(),null,true,UUID.randomUUID()));}
        finally {doCallRealMethod().when(target).enqueue(any(),any(),any());}
        assertNotNull(auth.authenticate(session.secret()));assertMaterialUnchanged(before,id);
        assertEquals(before.size(),count("SELECT count(*) FROM apple_authorization_case WHERE account_id=? AND status='ACCOUNT_BOUND'",id));
        assertEquals(0,count("SELECT count(*) FROM apple_revocation_job j JOIN apple_authorization_case c ON c.id=j.case_id WHERE c.origin_account_id=?",id));
        assertEquals(0,count("SELECT count(*) FROM apple_revocation_barrier WHERE identity_digest IN (SELECT identity_digest FROM apple_authorization_case WHERE account_id=?)",id));
        cancellation.self(session.principal(),null,true,UUID.randomUUID());assertRevocation(id,before.size());
    }
    @Test void replayedAndConcurrentAppleCallbackCaptureAtMostOneCase() throws Exception {
        String subject=UUID.randomUUID().toString();var ctx=context(ExternalProviders.Provider.APPLE,subject,email(),true,null,false);var gate=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<Boolean> call=()->{gate.await(5,TimeUnit.SECONDS);try{finish(ctx,"Apple");return true;}catch(AuthFailure e){assertEquals("EXTERNAL_STATE_INVALID",e.code());return false;}};
            var a=pool.submit(call);var b=pool.submit(call);assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
        assertEquals(1,count("SELECT count(*) FROM apple_authorization_case WHERE subject=?",subject));assertThrows(AuthFailure.class,()->finish(ctx,"Apple"));
    }
    @Test void concurrentAppleCompletionsAndPurgeKeepOneIdentityAndBothTokens() throws Exception {
        String subject=UUID.randomUUID().toString(),email=email();var a=finish(context(ExternalProviders.Provider.APPLE,subject,email,true,null,false),"A");var b=finish(context(ExternalProviders.Provider.APPLE,subject,email,true,null,false),"B");var gate=new CyclicBarrier(3);
        try(var pool=Executors.newFixedThreadPool(3)){
            var x=pool.submit(()->{gate.await(5,TimeUnit.SECONDS);login.complete(a.registrationSecret(),email,"A");return true;});
            var y=pool.submit(()->{gate.await(5,TimeUnit.SECONDS);login.complete(b.registrationSecret(),email,"B");return true;});
            var z=pool.submit(()->{gate.await(5,TimeUnit.SECONDS);purge();return true;});x.get(15,TimeUnit.SECONDS);y.get(15,TimeUnit.SECONDS);z.get(15,TimeUnit.SECONDS);
        }
        assertEquals(1,count("SELECT count(*) FROM user_identity WHERE subject=?",subject));assertEquals(2,material(account(subject)).size());
    }
    @Test void cancelledAppleIdentityCannotRegisterAgainWhileRevocationIsPending() {
        var session=externalAccount(ExternalProviders.Provider.APPLE);UUID id=session.principal().accountId();String subject=jdbc.queryForObject("SELECT subject FROM user_identity WHERE account_id=?",String.class,id);
        cancellation.self(session.principal(),null,true,UUID.randomUUID());
        var attempt=context(ExternalProviders.Provider.APPLE,subject,email(),true,null,false);
        assertEquals("APPLE_AUTHORIZATION_PENDING_REVOCATION",assertThrows(AuthFailure.class,()->finish(attempt,"Apple")).code());
        assertEquals(0,count("SELECT count(*) FROM user_identity WHERE subject=?",subject));
        assertEquals(1,count("SELECT count(*) FROM apple_authorization_case WHERE subject=? AND status='REGISTRATION_PENDING'",subject));
    }
    @Test void v14RejectsUnboundMaterialAndUnknownMigrationName() {
        var failure=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO apple_revocation_credential(id,client_id,key_id,iv,ciphertext,created_at) VALUES (?,'client','test',?,?,now())",UUID.randomUUID(),new byte[12],new byte[32]));
        java.sql.SQLException sql=null;for(Throwable t=failure;t!=null;t=t.getCause())if(t instanceof java.sql.SQLException e)sql=e;assertNotNull(sql);assertEquals("23502",sql.getSQLState());
        assertEquals(1,count("SELECT count(*) FROM flyway_schema_history WHERE version='14' AND script='V14__apple_authorization_lifecycle.sql' AND success"));
        var facts=new PreRaceAccountParticipationFacts(jdbc);new TransactionTemplate(manager).executeWithoutResult(s->{jdbc.update("UPDATE flyway_schema_history SET script='V14__unknown.sql' WHERE version='14'");assertThrows(AuthFailure.class,()->facts.forAccount(UUID.randomUUID()));s.setRollbackOnly();});
    }

    @Test void concurrentAppleCancellationAndCallbackCannotLeaveOwnedCasesOrValidSessions() throws Exception {
        var session=externalAccount(ExternalProviders.Provider.APPLE);UUID id=session.principal().accountId();String subject=jdbc.queryForObject("SELECT subject FROM user_identity WHERE account_id=?",String.class,id);
        var ctx=context(ExternalProviders.Provider.APPLE,subject,email(),true,null,false);var gate=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{gate.await(5,TimeUnit.SECONDS);cancellation.self(session.principal(),null,true,UUID.randomUUID());return true;});
            var b=pool.submit(()->{gate.await(5,TimeUnit.SECONDS);try{return finish(ctx,"Apple");}catch(AuthFailure rejected){return null;}});
            a.get(15,TimeUnit.SECONDS);var outcome=b.get(15,TimeUnit.SECONDS);if(outcome!=null && outcome.session()!=null)assertNull(auth.authenticate(outcome.session().secret()));
        }
        assertEquals(0,count("SELECT count(*) FROM user_account WHERE id=?",id));assertEquals(0,count("SELECT count(*) FROM apple_authorization_case WHERE account_id=?",id));
        assertEquals(0,count("SELECT count(*) FROM apple_revocation_credential m JOIN apple_authorization_case c ON c.id=m.case_id WHERE c.origin_account_id=? AND NOT EXISTS(SELECT 1 FROM apple_revocation_job j WHERE j.credential_id=m.id)",id));
    }
    @Test void exactExpiryCompletionAndPurgeRaceCannotCreateAnAccount() throws Exception {
        String subject=UUID.randomUUID().toString(),email=email();var out=finish(context(ExternalProviders.Provider.APPLE,subject,email,true,null,false),"Apple");Instant previous=clock.instant();
        try {clock.advance(Duration.ofMinutes(15));var gate=new CyclicBarrier(2);
            try(var pool=Executors.newFixedThreadPool(2)){
                var a=pool.submit(()->{gate.await(5,TimeUnit.SECONDS);assertThrows(AuthFailure.class,()->login.complete(out.registrationSecret(),email,"Apple"));return true;});
                var b=pool.submit(()->{gate.await(5,TimeUnit.SECONDS);purge();return true;});a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);
            }
            assertEquals(0,count("SELECT count(*) FROM user_identity WHERE subject=?",subject));
            assertEquals(1,count("SELECT count(*) FROM apple_revocation_job j JOIN apple_authorization_case c ON c.id=j.case_id WHERE c.subject=?",subject));
        }finally{clock.advance(Duration.between(clock.instant(),previous));}
    }
    @Test void v14MigrationRefusesUnboundPreproductionMaterialWithoutDeletingIt() {
        String schema=SCHEMA+"_v14";
        try {
            var source=jdbc.getDataSource();org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("13").load().migrate();UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO "+schema+".apple_revocation_credential(id,client_id,key_id,iv,ciphertext,created_at) VALUES (?,'client','test',?,?,now())",id,new byte[12],new byte[32]);
            assertThrows(org.flywaydb.core.api.FlywayException.class,()->org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate());
            assertArrayEquals(new byte[32],jdbc.queryForObject("SELECT ciphertext FROM "+schema+".apple_revocation_credential WHERE id=?",byte[].class,id));
            assertEquals(0,count("SELECT count(*) FROM "+schema+".flyway_schema_history WHERE version='14' AND success"));
        }finally{guard();assertTrue(schema.matches("it_external_web_[0-9a-f]{32}_v14"));jdbc.execute("DROP SCHEMA IF EXISTS \""+schema+"\" CASCADE");}
    }

    @Test void captureInsertFailureRollsBackFlowRegistrationCaseAndMaterialTogether() {
        String subject=UUID.randomUUID().toString();var ctx=context(ExternalProviders.Provider.APPLE,subject,email(),true,null,false);
        AppleRevocationStore target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(revocations);
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("injected after material insert");}).when(target).material(any(),any(),any(),any());
        try{assertThrows(RuntimeException.class,()->finish(ctx,"Apple"));}
        finally{doCallRealMethod().when(target).material(any(),any(),any(),any());}
        assertEquals(0,count("SELECT count(*) FROM apple_authorization_case WHERE subject=?",subject));assertEquals(0,count("SELECT count(*) FROM external_registration WHERE subject=?",subject));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM external_auth_flow WHERE state_hash=?",java.sql.Timestamp.class,tokens.hash(ctx.state())));
        assertEquals(0,count("SELECT count(*) FROM apple_revocation_credential WHERE case_id IS NULL"));
    }
    @Test void purgeEnqueueFailureRollsBackContextsCiphertextAndBarrier() {
        String subject=UUID.randomUUID().toString();var out=finish(context(ExternalProviders.Provider.APPLE,subject,email(),true,null,false),"Apple");Instant previous=clock.instant();
        AppleRevocationStore target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(revocations);
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("injected purge enqueue failure");}).when(target).enqueue(any(),any(),any());
        try{clock.advance(Duration.ofMinutes(15));assertThrows(RuntimeException.class,this::purge);}
        finally{doCallRealMethod().when(target).enqueue(any(),any(),any());clock.advance(Duration.between(clock.instant(),previous));}
        assertEquals(1,count("SELECT count(*) FROM external_registration WHERE token_hash=?",tokens.hash(out.registrationSecret())));
        assertEquals(1,count("SELECT count(*) FROM apple_authorization_case WHERE subject=? AND status='REGISTRATION_PENDING'",subject));
        assertEquals(0,count("SELECT count(*) FROM apple_revocation_job j JOIN apple_authorization_case c ON c.id=j.case_id WHERE c.subject=?",subject));
        assertEquals(0,count("SELECT count(*) FROM apple_revocation_barrier WHERE identity_digest=?",tokens.hash("https://appleid.apple.com\n"+subject)));
    }

}
