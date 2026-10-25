package cz.mh.rallye.rallye_backend.identity;

import java.util.*;
import java.util.concurrent.*;
import java.sql.SQLException;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.AccountCancellationStore;
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
class SingleIdentityIT {
    static final String SCHEMA="it_single_identity_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void schema(DynamicPropertyRegistry p) {
        p.add("spring.flyway.schemas",()->SCHEMA);p.add("spring.flyway.default-schema",()->SCHEMA);
        p.add("spring.datasource.hikari.connection-init-sql",()->"SET search_path TO "+SCHEMA);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired AuthService auth;
    @Autowired IdentityIT.TestMail mail;
    @Autowired AccountCancellationService cancellation;
    @Autowired AccountCancellationStore cancellations;
    TransactionTemplate tx(){return new TransactionTemplate(manager);}
    void guard(){assertEquals("rallye_test",jdbc.queryForObject("SELECT current_database()",String.class));assertEquals("rallye_test",jdbc.queryForObject("SELECT current_user",String.class));assertEquals(SCHEMA,jdbc.queryForObject("SELECT current_schema()",String.class));}
    @BeforeEach void checked(){guard();}
    UUID account(String status){
        UUID id=UUID.randomUUID();String email=id+"@example.test";
        jdbc.update("INSERT INTO user_account(id,email,normalized_email,display_name,status,created_at,updated_at) VALUES (?,?,?,'A',?,now(),now())",id,email,email,status);return id;
    }
    void identity(UUID account,String provider){jdbc.update("INSERT INTO user_identity(id,account_id,provider,issuer,subject,password_hash,created_at) VALUES (?,?,?,?,?,?,now())",UUID.randomUUID(),account,provider,provider.equals("LOCAL")?"local":ExternalProviders.issuer(ExternalProviders.Provider.valueOf(provider)),UUID.randomUUID().toString(),provider.equals("LOCAL")?"synthetic-hash":null);}
    UUID valid(String provider){return tx().execute(s->{UUID id=account("ACTIVE");identity(id,provider);return id;});}
    void violation(String state,Runnable action){
        RuntimeException error=assertThrows(RuntimeException.class,action::run);
        SQLException sql=null;for(Throwable t=error;t!=null;t=t.getCause())if(t instanceof SQLException e)sql=e;
        assertNotNull(sql,"Must fail because of the database invariant");assertEquals(state,sql.getSQLState());
    }
    @Test void activeAccountWithoutIdentityCannotCommit(){
        long before=jdbc.queryForObject("SELECT count(*) FROM user_account",Long.class);
        violation("23514",()->tx().executeWithoutResult(s->account("ACTIVE")));
        assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM user_account",Long.class));
    }
    @Test void activeRegistrationMayInsertAccountBeforeItsOnlyIdentity(){
        for(String provider:List.of("LOCAL","GOOGLE","APPLE")) {
            UUID id=valid(provider);assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM user_identity WHERE account_id=?",Integer.class,id));
        }
    }
    @Test void pendingAccountCanCompleteButCannotActivateWithoutIdentity(){
        UUID id=account("PENDING_EMAIL");
        violation("23514",()->jdbc.update("UPDATE user_account SET status='ACTIVE' WHERE id=?",id));
        tx().executeWithoutResult(s->{identity(id,"GOOGLE");jdbc.update("UPDATE user_account SET status='ACTIVE' WHERE id=?",id);});
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM user_account WHERE id=?",String.class,id));
    }
    @Test void secondIdentityOfSameOrDifferentProviderIsRejected(){
        UUID id=valid("GOOGLE");for(String provider:List.of("GOOGLE","APPLE","LOCAL"))violation("23505",()->identity(id,provider));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM user_identity WHERE account_id=?",Integer.class,id));
    }
    @Test void identifyingColumnsCannotBeChanged(){
        UUID id=valid("GOOGLE"),other=account("PENDING_EMAIL");
        for(String column:List.of("provider","issuer","subject","account_id","id")) {
            Object value=switch(column){case "account_id"->other;case "id"->UUID.randomUUID();case "provider"->"APPLE";default->"changed";};
            violation("23514",()->jdbc.update("UPDATE user_identity SET "+column+"=? WHERE account_id=?",value,id));
        }
    }
    @Test void deleteInsertCannotReplaceEvenOnDisabledOrPendingAccounts(){
        UUID id=valid("GOOGLE");UUID original=jdbc.queryForObject("SELECT id FROM user_identity WHERE account_id=?",UUID.class,id);
        for(String status:List.of("ACTIVE","DISABLED","PENDING_EMAIL")) {
            jdbc.update("UPDATE user_account SET status=? WHERE id=?",status,id);
            violation("23514",()->tx().executeWithoutResult(s->{jdbc.update("DELETE FROM user_identity WHERE account_id=?",id);identity(id,"APPLE");}));
            assertEquals(original,jdbc.queryForObject("SELECT id FROM user_identity WHERE account_id=?",UUID.class,id));
        }
    }
    @Test void deletingAndReinsertingSameIdentityIdAlsoFails(){
        UUID id=valid("GOOGLE");var row=jdbc.queryForMap("SELECT * FROM user_identity WHERE account_id=?",id);
        violation("23514",()->tx().executeWithoutResult(s->{jdbc.update("DELETE FROM user_identity WHERE account_id=?",id);
            jdbc.update("INSERT INTO user_identity(id,account_id,provider,issuer,subject,created_at) VALUES (?,?,'APPLE','https://appleid.apple.com','replacement',now())",row.get("id"),id);}));
        assertEquals("GOOGLE",jdbc.queryForObject("SELECT provider FROM user_identity WHERE account_id=?",String.class,id));
    }
    @Test void deletionWithoutCancellationCannotCommit(){
        UUID id=valid("LOCAL");violation("23514",()->jdbc.update("DELETE FROM user_identity WHERE account_id=?",id));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM user_identity WHERE account_id=?",Integer.class,id));
    }
    @Test void completeFixtureCleanupCommitsAndLeavesUnrelatedAccountUntouched(){
        UUID own=valid("LOCAL"),other=valid("GOOGLE");
        String email=jdbc.queryForObject("SELECT normalized_email FROM user_account WHERE id=?",String.class,own);
        UUID session=UUID.randomUUID();
        jdbc.update("INSERT INTO user_session(id,account_id,token_hash,remember_me,created_at,last_used_at,expires_at) VALUES (?,?,?,false,now(),now(),now()+interval '24 hours')",session,own,new SecretTokens().hash("fixture-session"));
        jdbc.update("INSERT INTO user_action_token(id,account_id,purpose,token_hash,created_at,expires_at) VALUES (?,?,'EMAIL_VERIFICATION',?,now(),now()+interval '24 hours')",UUID.randomUUID(),own,new SecretTokens().hash("fixture-action"));
        var otherIdentity=jdbc.queryForMap("SELECT * FROM user_identity WHERE account_id=?",other);
        tx().executeWithoutResult(status->{
            var ids=jdbc.queryForList("SELECT id FROM user_account WHERE normalized_email=? FOR UPDATE",UUID.class,email);
            assertEquals(List.of(own),ids);
            for(UUID id:ids){
                for(String table:List.of("user_session","user_action_token","user_identity"))jdbc.update("DELETE FROM "+table+" WHERE account_id=?",id);
                assertEquals(1,jdbc.update("DELETE FROM user_account WHERE id=?",id));
            }
        });
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_account WHERE id=?",Integer.class,own));
        for(String table:List.of("user_session","user_action_token","user_identity"))assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE account_id=?",Integer.class,own));
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM user_account WHERE id=?",String.class,other));
        assertEquals(otherIdentity,jdbc.queryForMap("SELECT * FROM user_identity WHERE account_id=?",other));
    }
    @Test void failedFixtureCleanupRollsBackIdentityAndAccountTogether(){
        UUID own=valid("LOCAL");var before=jdbc.queryForMap("SELECT * FROM user_identity WHERE account_id=?",own);
        var marker=new IllegalStateException("injected cleanup failure");
        assertSame(marker,assertThrows(IllegalStateException.class,()->tx().executeWithoutResult(s->{
            jdbc.update("DELETE FROM user_identity WHERE account_id=?",own);
            jdbc.update("DELETE FROM user_account WHERE id=?",own);
            throw marker;
        })));
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM user_account WHERE id=?",String.class,own));
        assertEquals(before,jdbc.queryForMap("SELECT * FROM user_identity WHERE account_id=?",own));
    }
    @Test void localPasswordChangeAndCancellationStillWork(){
        String email=UUID.randomUUID()+"@example.test";auth.register(email,"test-password","A");auth.verifyEmail(mail.token(email,"EMAIL_VERIFICATION"));
        var session=auth.login(email,"test-password",false);UUID identity=jdbc.queryForObject("SELECT id FROM user_identity WHERE account_id=?",UUID.class,session.principal().accountId());
        auth.changePassword(session.principal(),"test-password","new-password");
        assertEquals(identity,jdbc.queryForObject("SELECT id FROM user_identity WHERE account_id=?",UUID.class,session.principal().accountId()));
        cancellation.self(session.principal(),"new-password",UUID.randomUUID());assertNull(auth.authenticate(session.secret()));
    }
    @Test void anonymizationMayRemoveIdentityButCannotBeReactivated(){
        UUID id=valid("APPLE");
        tx().executeWithoutResult(s->{cancellations.deleteIdentities(id);cancellations.anonymize(id,java.time.Instant.now());});
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_identity WHERE account_id=?",Integer.class,id));
        violation("23514",()->identity(id,"GOOGLE"));
        violation("23514",()->jdbc.update("UPDATE user_account SET status='PENDING_EMAIL',email='x@example.test',normalized_email='x@example.test',display_name='X',cancelled_at=NULL WHERE id=?",id));
    }
    @Test void concurrentSecondIdentityAttemptsCannotChangeOwner() throws Exception {
        UUID id=valid("LOCAL");var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results=new ArrayList<>();
            for(String provider:List.of("GOOGLE","APPLE"))results.add(pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);violation("23505",()->identity(id,provider));return true;}));
            for(var result:results)assertTrue(result.get(15,TimeUnit.SECONDS));
        }
        assertEquals("LOCAL",jdbc.queryForObject("SELECT provider FROM user_identity WHERE account_id=?",String.class,id));
    }
    @Test void concurrentReplacementsBothRollback() throws Exception {
        UUID id=valid("GOOGLE");var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> replace=()->{barrier.await(5,TimeUnit.SECONDS);violation("23514",()->tx().executeWithoutResult(s->{jdbc.update("DELETE FROM user_identity WHERE account_id=?",id);identity(id,"APPLE");}));return true;};
            var a=pool.submit(replace);var b=pool.submit(replace);assertTrue(a.get(15,TimeUnit.SECONDS));assertTrue(b.get(15,TimeUnit.SECONDS));
        }
        assertEquals("GOOGLE",jdbc.queryForObject("SELECT provider FROM user_identity WHERE account_id=?",String.class,id));
    }
    record GrantFixture(UUID account,UUID session,UUID attempt,String challenge) {}
    GrantFixture grantFixture(){
        UUID account=valid("LOCAL"),session=UUID.randomUUID(),attempt=UUID.randomUUID();
        var secrets=new SecretTokens();String challenge="A".repeat(43);
        jdbc.update("INSERT INTO user_session(id,account_id,token_hash,remember_me,created_at,last_used_at,expires_at) VALUES (?,?,?,false,now(),now(),now()+interval '24 hours')",session,account,secrets.hash(UUID.randomUUID().toString()));
        jdbc.update("INSERT INTO mobile_login_attempt(id,provider,pkce_challenge,remember_me,return_id,return_uri,profile_uri,fallback_uri,ticket_hash,created_at,expires_at) VALUES (?,'GOOGLE',?,false,'android','https://example.test/mobile','https://example.test/profile','https://example.test/fallback',?,now(),now()+interval '10 minutes')",attempt,challenge,secrets.hash(UUID.randomUUID().toString()));
        return new GrantFixture(account,session,attempt,challenge);
    }
    void insertGrant(GrantFixture fixture,UUID attempt,String kind,String purpose){
        boolean mobile=kind.equals("MOBILE");
        jdbc.update("INSERT INTO external_auth_grant(id,account_id,session_id,attempt_id,kind,purpose,token_hash,pkce_challenge,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,now(),now()+(? * interval '1 second'))",
            UUID.randomUUID(),fixture.account(),mobile?null:fixture.session(),attempt,kind,purpose,new SecretTokens().hash(UUID.randomUUID().toString()),mobile?fixture.challenge():null,mobile?60:300);
    }
    @Test void removedContextsAndReauthGrantsCannotBeInserted(){
        var fixture=grantFixture();
        // All parent rows and the original V8 shape are valid: only V10's prohibition fails.
        violation("23514",()->insertGrant(fixture,fixture.attempt(),"REAUTH","LOGIN"));
        violation("23514",()->insertGrant(fixture,fixture.attempt(),"MOBILE","LINK"));
        violation("23514",()->insertGrant(fixture,fixture.attempt(),"REAUTH","LINK"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM external_auth_grant WHERE account_id=?",Integer.class,fixture.account()));
        // The same fixture permits the supported grant, proving no unrelated constraint masks V10.
        insertGrant(fixture,fixture.attempt(),"MOBILE","LOGIN");
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM external_auth_grant WHERE account_id=?",Integer.class,fixture.account()));
    }
    @Test void mobileGrantRequiresExistingAttempt(){
        var fixture=grantFixture();
        violation("23502",()->insertGrant(fixture,null,"MOBILE","LOGIN"));
        violation("23503",()->insertGrant(fixture,UUID.randomUUID(),"MOBILE","LOGIN"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM external_auth_grant WHERE account_id=?",Integer.class,fixture.account()));
        insertGrant(fixture,fixture.attempt(),"MOBILE","LOGIN");
        assertEquals(fixture.attempt(),jdbc.queryForObject("SELECT attempt_id FROM external_auth_grant WHERE account_id=?",UUID.class,fixture.account()));
    }
    @Test void v10MigrationPreflightPreservesConflictingData(){
        for(String conflict:List.of("MULTIPLE","MISSING","ANONYMIZED","FLOW","GRANT"))upgrade(conflict);
    }
    @Test void cleanV9UpgradePreservesIdentityAndAllowsOnlyReviewedV10(){upgrade("CLEAN");}
    void upgrade(String conflict){
        String schema=SCHEMA+"_"+conflict.toLowerCase();
        try {
            var source=jdbc.getDataSource();org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("9").load().migrate();
            UUID id=UUID.randomUUID();jdbc.update("INSERT INTO "+schema+".user_account(id,email,normalized_email,display_name,status,created_at,updated_at) VALUES (?,'a@example.test','a@example.test','A','ACTIVE',now(),now())",id);
            if(!conflict.equals("MISSING"))jdbc.update("INSERT INTO "+schema+".user_identity(id,account_id,provider,issuer,subject,password_hash,created_at) VALUES (?,?,'LOCAL','local',?,'synthetic',now())",UUID.randomUUID(),id,id.toString());
            if(conflict.equals("ANONYMIZED"))jdbc.update("UPDATE "+schema+".user_account SET status='ANONYMIZED',email=NULL,normalized_email=NULL,display_name=NULL,email_verified_at=NULL,cancelled_at=now() WHERE id=?",id);
            if(conflict.equals("MULTIPLE"))jdbc.update("INSERT INTO "+schema+".user_identity(id,account_id,provider,issuer,subject,created_at) VALUES (?,?,'GOOGLE','https://accounts.google.com','synthetic',now())",UUID.randomUUID(),id);
            if(conflict.equals("FLOW") || conflict.equals("GRANT")) {
                UUID session=UUID.randomUUID();jdbc.update("INSERT INTO "+schema+".user_session(id,account_id,token_hash,remember_me,created_at,last_used_at,expires_at) VALUES (?,?,?,false,now(),now(),now()+interval '24 hours')",session,id,new SecretTokens().hash("session"));
                if(conflict.equals("FLOW"))jdbc.update("INSERT INTO "+schema+".external_auth_flow(id,provider,purpose,account_id,session_id,state_hash,nonce_hash,correlation_hash,callback_uri,return_uri,remember_me,created_at,expires_at) VALUES (?,'GOOGLE','LINK',?,?,?,?,?,'https://example.test/cb','https://example.test/return',false,now(),now()+interval '10 minutes')",UUID.randomUUID(),id,session,new SecretTokens().hash("state"),new SecretTokens().hash("nonce"),new SecretTokens().hash("correlation"));
                else jdbc.update("INSERT INTO "+schema+".external_auth_grant(id,account_id,session_id,kind,purpose,token_hash,created_at,expires_at) VALUES (?,?,?,'REAUTH','LINK',?,now(),now()+interval '5 minutes')",UUID.randomUUID(),id,session,new SecretTokens().hash("grant"));
            }
            long before=jdbc.queryForObject("SELECT count(*) FROM "+schema+".user_identity",Long.class);
            Runnable migrate=()->org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
            if(conflict.equals("CLEAN")){migrate.run();assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM "+schema+".flyway_schema_history WHERE version='10' AND success",Integer.class));}
            else {violation("23514",migrate);
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM "+schema+".flyway_schema_history WHERE version='10' AND success",Integer.class));}
            assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM "+schema+".user_identity",Long.class));
            assertEquals(id,jdbc.queryForObject("SELECT id FROM "+schema+".user_account",UUID.class));
        } finally {guard();assertTrue(schema.matches("it_single_identity_[0-9a-f]{32}_[a-z]+"));jdbc.execute("DROP SCHEMA IF EXISTS \""+schema+"\" CASCADE");}
    }
    @AfterAll void cleanup(){guard();assertTrue(SCHEMA.matches("it_single_identity_[0-9a-f]{32}"));jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");}
}
