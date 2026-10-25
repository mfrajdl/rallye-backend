package cz.mh.rallye.rallye_backend.identity;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.sql.SQLException;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import cz.mh.rallye.rallye_backend.identity.persistence.AppleRevocationStore.*;
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
class AppleRevocationIT {
    static final String SCHEMA="it_apple_revocation_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        p.add("spring.flyway.schemas",()->SCHEMA);p.add("spring.flyway.default-schema",()->SCHEMA);p.add("spring.datasource.hikari.connection-init-sql",()->"SET search_path TO "+SCHEMA);
        p.add("rallye.auth.apple-vault.active-key-id",()->"test");p.add("rallye.auth.apple-vault.keys.test",()->Base64.getEncoder().encodeToString(new byte[32]));
    }
    @Autowired AppleRevocationFoundation service;
    @Autowired AppleRevocationStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired AppleTokenVault vault;
    @Autowired PlatformTransactionManager manager;
    @Autowired PreRaceAccountParticipationFacts facts;
    final SecretTokens secrets=new SecretTokens();
    TransactionTemplate tx(){return new TransactionTemplate(manager);}
    void guard(){assertEquals("rallye_test",jdbc.queryForObject("SELECT current_database()",String.class));assertEquals("rallye_test",jdbc.queryForObject("SELECT current_user",String.class));assertEquals(SCHEMA,jdbc.queryForObject("SELECT current_schema()",String.class));}
    @BeforeEach void isolatePendingWorkWithoutBypassingProtection(){
        guard();clock.advance(Duration.ofDays(2));
        // Keep prior fixtures intact; move their due jobs to BLOCKED using legitimate state transitions.
        Job j;while((j=service.claim(Duration.ofMinutes(2)))!=null)assertTrue(service.reportFailure(j.id(),j.lease(),j.version(),AppleRevocationStore.Error.KEY,null,clock.instant(),true));
    }
    record Fixture(String subject,UUID owner,UUID registration,UUID flow) {}
    Fixture fixture(boolean registration) {
        String subject=UUID.randomUUID().toString();UUID account=UUID.randomUUID(),reg=UUID.randomUUID(),flow=UUID.randomUUID();
        tx().executeWithoutResult(s->{
            if(registration)jdbc.update("INSERT INTO external_registration(id,provider,issuer,subject,email_verified,remember_me,return_uri,token_hash,created_at,expires_at) VALUES (?,'APPLE','https://appleid.apple.com',?,true,false,'https://example.test/return',?,?,?)",reg,subject,secrets.hash(UUID.randomUUID().toString()),java.sql.Timestamp.from(clock.instant()),java.sql.Timestamp.from(clock.instant().plusSeconds(900)));
            else {jdbc.update("INSERT INTO user_account(id,email,normalized_email,display_name,status,created_at,updated_at,email_verified_at) VALUES (?,?,?,'Test','ACTIVE',?,?,?)",account,account+"@example.test",account+"@example.test",java.sql.Timestamp.from(clock.instant()),java.sql.Timestamp.from(clock.instant()),java.sql.Timestamp.from(clock.instant()));
                jdbc.update("INSERT INTO user_identity(id,account_id,provider,issuer,subject,created_at) VALUES (?,?,'APPLE','https://appleid.apple.com',?,?)",UUID.randomUUID(),account,subject,java.sql.Timestamp.from(clock.instant()));}
            jdbc.update("INSERT INTO external_auth_flow(id,provider,purpose,state_hash,nonce_hash,correlation_hash,callback_uri,return_uri,remember_me,created_at,expires_at) VALUES (?,'APPLE','LOGIN',?,?,?,'https://example.test/callback','https://example.test/return',false,?,?)",flow,secrets.hash(UUID.randomUUID().toString()),secrets.hash("nonce"),secrets.hash("correlation"),java.sql.Timestamp.from(clock.instant()),java.sql.Timestamp.from(clock.instant().plusSeconds(600)));
        });
        return new Fixture(subject,registration?null:account,registration?reg:null,flow);
    }
    AppleRevocationFoundation.NewCase input(Fixture f,UUID generation){return new AppleRevocationFoundation.NewCase("https://appleid.apple.com",f.subject(),"synthetic-client",generation,f.flow(),f.owner(),f.registration());}
    Authorization create(boolean registration){return service.create(input(fixture(registration),UUID.randomUUID()));}
    List<Job> enroll(Authorization c){return service.enqueue(c.id(),c.account()!=null?Reason.ACCOUNT_CANCELLATION:Reason.REGISTRATION_ABANDONED);}
    void violation(String expected,Runnable action){var error=assertThrows(RuntimeException.class,action::run);SQLException sql=null;for(Throwable t=error;t!=null;t=t.getCause())if(t instanceof SQLException e)sql=e;assertNotNull(sql,"Expected a database invariant");assertEquals(expected,sql.getSQLState());}
    @Test void v13MigrationAndPreciseGuardAcceptOnlyReviewedSchema(){
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='13' AND script='V13__apple_revocation_foundation.sql' AND success",Integer.class));
        assertFalse(tx().execute(s->facts.forAccount(UUID.randomUUID())).historical());
        tx().executeWithoutResult(s->{jdbc.update("UPDATE flyway_schema_history SET script='V13__unknown.sql' WHERE version='13'");assertThrows(AuthFailure.class,()->facts.forAccount(UUID.randomUUID()));s.setRollbackOnly();});
    }
    @Test void bothOwnerKindsAndAllRequiredIdentifiersAreStored(){
        for(boolean registration:List.of(false,true)){var c=create(registration);assertEquals(c,service.find(c.id()));assertEquals(secrets.hash(c.issuer()+"\n"+c.subject()),c.digest());}
        var c=create(false);violation("23514",()->jdbc.update("UPDATE apple_authorization_case SET client_id=NULL,version=version+1 WHERE id=?",c.id()));
        violation("23514",()->jdbc.update("UPDATE apple_authorization_case SET subject='changed',version=version+1 WHERE id=?",c.id()));
    }
    @Test void newCaseCannotOmitGenerationOrDigest(){
        var f=fixture(false);
        violation("23502",()->jdbc.update("INSERT INTO apple_authorization_case(id,issuer,subject,identity_digest,client_id,generation,origin_flow_id,account_id,origin_account_id,status,created_at,updated_at) VALUES (?,'https://appleid.apple.com',?,?,'client',NULL,?,?,?,'ACCOUNT_BOUND',now(),now())",UUID.randomUUID(),f.subject(),secrets.hash("https://appleid.apple.com\n"+f.subject()),f.flow(),f.owner(),f.owner()));
        violation("23502",()->jdbc.update("INSERT INTO apple_authorization_case(id,issuer,subject,identity_digest,client_id,generation,origin_flow_id,account_id,origin_account_id,status,created_at,updated_at) VALUES (?,'https://appleid.apple.com',?,NULL,'client',?,?,?,?,'ACCOUNT_BOUND',now(),now())",UUID.randomUUID(),f.subject(),UUID.randomUUID(),f.flow(),f.owner(),f.owner()));
    }
    @Test void wrongAccountRegistrationAndProviderBindingsAreRejected(){
        var a=fixture(false);var other=fixture(false);
        violation("23514",()->service.create(new AppleRevocationFoundation.NewCase("https://appleid.apple.com",a.subject(),"client",UUID.randomUUID(),a.flow(),other.owner(),null)));
        var r=fixture(true);violation("23514",()->service.create(new AppleRevocationFoundation.NewCase("https://appleid.apple.com",a.subject(),"client",UUID.randomUUID(),r.flow(),null,r.registration())));
        jdbc.update("UPDATE external_auth_flow SET provider='GOOGLE' WHERE id=?",a.flow());violation("23514",()->service.create(input(a,UUID.randomUUID())));
    }
    @Test void sameAuthorizationCannotBeCreatedTwice(){var input=input(fixture(false),UUID.randomUUID());service.create(input);violation("23505",()->service.create(input));}
    @Test void sealedMaterialIsBoundAndCannotBeMovedOrDeleted(){
        var c=create(false);UUID id=service.addMaterial(c.id(),"synthetic-refresh");var row=jdbc.queryForMap("SELECT * FROM apple_revocation_credential WHERE id=?",id);
        assertFalse(row.containsValue("synthetic-refresh"));assertEquals(c.id(),row.get("case_id"));
        assertEquals("synthetic-refresh",vault.open(new AppleTokenVault.Sealed((String)row.get("key_id"),(byte[])row.get("iv"),(byte[])row.get("ciphertext")),ExternalLoginService.credentialAad(id,c.clientId())));
        violation("23514",()->jdbc.update("DELETE FROM apple_revocation_credential WHERE id=?",id));
        violation("23514",()->jdbc.update("UPDATE apple_revocation_credential SET case_id=NULL WHERE id=?",id));
        violation("23514",()->jdbc.update("UPDATE apple_revocation_credential SET ciphertext=? WHERE id=?",new byte[32],id));
    }
    @Test void managedMaterialWithWrongOwnerCannotCommit(){
        var c=create(false);UUID id=service.addMaterial(c.id(),"synthetic");
        violation("23514",()->tx().executeWithoutResult(s->jdbc.update("UPDATE apple_revocation_credential SET account_id=NULL WHERE id=?",id)));
        assertEquals(c.account(),jdbc.queryForObject("SELECT account_id FROM apple_revocation_credential WHERE id=?",UUID.class,id));
    }
    @Test void pendingRegistrationCanBindToMatchingAccountAtomically(){
        var f=fixture(true);var c=service.create(input(f,UUID.randomUUID()));UUID material=service.addMaterial(c.id(),"synthetic");UUID account=UUID.randomUUID();
        tx().executeWithoutResult(s->{
            jdbc.update("INSERT INTO user_account(id,email,normalized_email,display_name,status,created_at,updated_at) VALUES (?,?,?,'Test','PENDING_EMAIL',now(),now())",account,account+"@example.test",account+"@example.test");
            jdbc.update("INSERT INTO user_identity(id,account_id,provider,issuer,subject,created_at) VALUES (?,?,'APPLE','https://appleid.apple.com',?,now())",UUID.randomUUID(),account,f.subject());
        });
        service.bindAccount(c.id(),account);assertEquals(CaseStatus.ACCOUNT_BOUND,service.find(c.id()).status());assertEquals(1,service.find(c.id()).version());
        assertEquals(account,jdbc.queryForObject("SELECT account_id FROM apple_revocation_credential WHERE id=?",UUID.class,material));assertNull(jdbc.queryForObject("SELECT registration_id FROM apple_revocation_credential WHERE id=?",UUID.class,material));
    }
    @Test void enqueueIsIdempotentAndIncludesAllMaterials(){
        var c=create(false);service.addMaterial(c.id(),"synthetic-1");service.addMaterial(c.id(),"synthetic-2");var jobs=enroll(c);assertEquals(2,jobs.size());assertEquals(jobs,enroll(c));
        assertEquals(CaseStatus.REVOCATION_REQUIRED,service.find(c.id()).status());assertNull(service.find(c.id()).account());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM apple_revocation_barrier WHERE identity_digest=?",Integer.class,c.digest()));
    }
    @Test void enqueueWithoutMaterialFailsWithoutChangingCase(){var c=create(false);assertThrows(IllegalStateException.class,()->enroll(c));assertEquals(CaseStatus.ACCOUNT_BOUND,service.find(c.id()).status());assertTrue(service.jobs(c.id()).isEmpty());}
    @Test void concurrentEnqueueCreatesOneSetOfJobs() throws Exception {
        var c=create(false);service.addMaterial(c.id(),"synthetic");var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)){Callable<List<Job>> task=()->{barrier.await(5,TimeUnit.SECONDS);return enroll(c);};var a=pool.submit(task);var b=pool.submit(task);assertEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));}
        assertEquals(1,service.jobs(c.id()).size());
    }
    @Test void pendingJobBarrierAndCaseCannotBeDeletedOrReassigned(){
        var c=create(false);UUID material=service.addMaterial(c.id(),"synthetic");var job=enroll(c).getFirst();
        violation("23514",()->jdbc.update("DELETE FROM apple_revocation_job WHERE id=?",job.id()));
        violation("23514",()->jdbc.update("UPDATE apple_revocation_job SET credential_id=?,version=version+1 WHERE id=?",UUID.randomUUID(),job.id()));
        violation("23514",()->jdbc.update("DELETE FROM apple_revocation_barrier WHERE identity_digest=?",c.digest()));
        violation("23514",()->jdbc.update("DELETE FROM apple_authorization_case WHERE id=?",c.id()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM apple_revocation_credential WHERE id=?",Integer.class,material));
    }
    @Test void unauthorizedCompletionIsRejectedByDatabase(){
        var c=create(false);service.addMaterial(c.id(),"synthetic");var job=enroll(c).getFirst();
        violation("23514",()->jdbc.update("UPDATE apple_revocation_job SET status='SUCCEEDED',completed_at=now(),version=version+1 WHERE id=?",job.id()));
        violation("23514",()->jdbc.update("UPDATE apple_authorization_case SET status='COMPLETED',version=version+1 WHERE id=?",c.id()));
        violation("23514",()->jdbc.update("UPDATE apple_revocation_job SET status='RETRY_WAIT',last_error='NETWORK',version=version+1 WHERE id=?",job.id()));
        assertEquals(Status.PENDING,service.jobs(c.id()).getFirst().status());
    }
    @Test void claimFailureRetryAndExpiredLeaseUseConditionalVersions(){
        var c=create(false);service.addMaterial(c.id(),"synthetic");enroll(c);Job first=service.claim(Duration.ofSeconds(60));assertNotNull(first);assertEquals(1,first.attempts());assertNull(service.claim(Duration.ofSeconds(60)));
        assertFalse(service.reportFailure(first.id(),UUID.randomUUID(),first.version(),AppleRevocationStore.Error.NETWORK,null,clock.instant().plusSeconds(10),false));
        clock.advance(Duration.ofSeconds(60));Job second=service.claim(Duration.ofSeconds(60));assertEquals(first.id(),second.id());assertNotEquals(first.lease(),second.lease());assertEquals(2,second.attempts());
        assertFalse(service.reportFailure(first.id(),first.lease(),first.version(),AppleRevocationStore.Error.NETWORK,null,clock.instant().plusSeconds(10),false));
        assertTrue(service.reportFailure(second.id(),second.lease(),second.version(),AppleRevocationStore.Error.NETWORK,503,clock.instant().plusSeconds(10),false));assertNull(service.claim(Duration.ofSeconds(60)));
        clock.advance(Duration.ofSeconds(10));assertEquals(first.id(),service.claim(Duration.ofSeconds(60)).id());
    }
    @Test void blockedRetryPreservesEncryptedMaterial(){
        var c=create(false);UUID material=service.addMaterial(c.id(),"synthetic");enroll(c);byte[] before=jdbc.queryForObject("SELECT ciphertext FROM apple_revocation_credential WHERE id=?",byte[].class,material);
        var lease=service.claim(Duration.ofMinutes(2));assertTrue(service.reportFailure(lease.id(),lease.lease(),lease.version(),AppleRevocationStore.Error.KEY,null,clock.instant(),true));
        var blocked=service.jobs(c.id()).getFirst();assertEquals(Status.BLOCKED,blocked.status());assertNull(service.claim(Duration.ofMinutes(2)));assertFalse(service.retryBlocked(blocked.id(),blocked.version()-1));assertTrue(service.retryBlocked(blocked.id(),blocked.version()));
        assertArrayEquals(before,jdbc.queryForObject("SELECT ciphertext FROM apple_revocation_credential WHERE id=?",byte[].class,material));
    }
    @Test void concurrentClaimsDoNotShareSameJob() throws Exception {
        var c=create(false);service.addMaterial(c.id(),"synthetic-1");service.addMaterial(c.id(),"synthetic-2");enroll(c);var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)){Callable<Job> task=()->{barrier.await(5,TimeUnit.SECONDS);return service.claim(Duration.ofMinutes(2));};var a=pool.submit(task);var b=pool.submit(task);assertNotEquals(a.get(20,TimeUnit.SECONDS).id(),b.get(20,TimeUnit.SECONDS).id());}
    }
    @Test void outerFailureRollsBackCaseJobsBarrierAndOwnerChanges(){
        var c=create(false);UUID material=service.addMaterial(c.id(),"synthetic");var marker=new IllegalStateException("injected rollback");
        assertSame(marker,assertThrows(IllegalStateException.class,()->tx().executeWithoutResult(s->{enroll(c);throw marker;})));
        assertEquals(CaseStatus.ACCOUNT_BOUND,service.find(c.id()).status());assertTrue(service.jobs(c.id()).isEmpty());assertEquals(c.account(),jdbc.queryForObject("SELECT account_id FROM apple_revocation_credential WHERE id=?",UUID.class,material));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM apple_revocation_barrier WHERE identity_digest=?",Integer.class,c.digest()));
    }
    @Test void mandatoryRepositoryRequiresTransaction(){assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->store.jobs(UUID.randomUUID()));}
    @Test void v12UpgradeLeavesOldWriterAndRegistrationCleanupUnchanged(){
        String schema=SCHEMA+"_upgrade";
        try {
            var source=jdbc.getDataSource();org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("12").load().migrate();
            UUID reg=UUID.randomUUID(),material=UUID.randomUUID();
            jdbc.update("INSERT INTO "+schema+".external_registration(id,provider,issuer,subject,email_verified,remember_me,return_uri,token_hash,created_at,expires_at) VALUES (?,'APPLE','https://appleid.apple.com','synthetic',true,false,'https://example.test',?,now(),now()+interval '15 minutes')",reg,secrets.hash("upgrade"));
            jdbc.update("INSERT INTO "+schema+".apple_revocation_credential(id,registration_id,client_id,key_id,iv,ciphertext,created_at) VALUES (?,?,'client','test',?,?,now())",material,reg,new byte[12],new byte[32]);
            org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
            jdbc.update("DELETE FROM "+schema+".external_registration WHERE id=?",reg);
            assertNull(jdbc.queryForObject("SELECT case_id FROM "+schema+".apple_revocation_credential WHERE id=?",UUID.class,material));assertNull(jdbc.queryForObject("SELECT registration_id FROM "+schema+".apple_revocation_credential WHERE id=?",UUID.class,material));
            assertArrayEquals(new byte[32],jdbc.queryForObject("SELECT ciphertext FROM "+schema+".apple_revocation_credential WHERE id=?",byte[].class,material));
        } finally {guard();assertTrue(schema.matches("it_apple_revocation_[0-9a-f]{32}_upgrade"));jdbc.execute("DROP SCHEMA IF EXISTS \""+schema+"\" CASCADE");}
    }
    @AfterAll void cleanup(){guard();assertTrue(SCHEMA.matches("it_apple_revocation_[0-9a-f]{32}"));jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");}
}
