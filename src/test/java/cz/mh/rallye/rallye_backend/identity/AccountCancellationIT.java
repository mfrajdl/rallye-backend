package cz.mh.rallye.rallye_backend.identity;

import java.time.Instant;
import java.sql.Timestamp;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.*;
import cz.mh.rallye.rallye_backend.identity.persistence.AccountCancellationStore;
import cz.mh.rallye.rallye_backend.shared.persistence.*;
import cz.mh.rallye.rallye_backend.support.TestDatabaseInitializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("it")
@ContextConfiguration(initializers=TestDatabaseInitializer.class)
@Import(IdentityIT.Fixtures.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AccountCancellationIT {
    static final String SCHEMA="it_cancellation_"+UUID.randomUUID().toString().replace("-","");
    static final Instant NOW=Instant.parse("2026-10-23T12:00:00Z");
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry properties) {
        properties.add("spring.flyway.schemas",()->SCHEMA);
        properties.add("spring.flyway.default-schema",()->SCHEMA);
        properties.add("spring.datasource.hikari.connection-init-sql",()->"SET search_path TO "+SCHEMA);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountCancellationStore accounts;
    @Autowired CommandStore commands;
    @Autowired CommandCleanupStore cleanup;
    @Autowired PlatformTransactionManager manager;
    @Autowired javax.sql.DataSource dataSource;
    <T> T tx(Supplier<T> work) {return new TransactionTemplate(manager).execute(status->work.get());}
    UUID account() {return account(UUID.randomUUID()+"@example.test");}
    UUID account(String email) {
        UUID id=UUID.randomUUID();return tx(()->{jdbc.update("""
                INSERT INTO user_account(id,email,normalized_email,display_name,status,created_at,updated_at,email_verified_at)
                VALUES (?,?,?,'Captain','ACTIVE',?,?,?)
                """,id,email,email,Timestamp.from(NOW),Timestamp.from(NOW),Timestamp.from(NOW));
        jdbc.update("INSERT INTO user_identity VALUES (?,?, 'LOCAL','local',?,'synthetic-password-hash',?)",UUID.randomUUID(),id,id.toString(),Timestamp.from(NOW));return id;});
    }
    UUID crew(UUID captain) {
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO crew(id,captain_account_id,name,created_at,updated_at) VALUES (?,?,'Crew',?,?)",
                id,captain,Timestamp.from(NOW),Timestamp.from(NOW));return id;
    }
    UUID transfer(UUID crew,UUID from,UUID to,boolean expired) {
        UUID id=UUID.randomUUID();Instant created=expired?NOW.minusSeconds(604800):NOW;
        jdbc.update("""
                INSERT INTO crew_transfer(id,crew_id,from_account_id,to_account_id,token_hash,status,created_at,expires_at)
                VALUES (?,?,?,?,?,'PENDING',?,?)
                """,id,crew,from,to,UUID.randomUUID().toString().replace("-","").repeat(2),Timestamp.from(created),Timestamp.from(created.plusSeconds(604800)));return id;
    }
    int count(String table,String column,UUID id) {return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE "+column+"=?",Integer.class,id);}
    @Test void migrationUpgradesV5WithoutRemovingExistingAccountsOrCommands() {
        String schema=SCHEMA+"_upgrade";UUID id=UUID.randomUUID(),key=UUID.randomUUID();
        assertEquals("rallye_test",jdbc.queryForObject("SELECT current_database()",String.class));
        assertEquals("rallye_test",jdbc.queryForObject("SELECT current_user",String.class));
        try {
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("5").load().migrate();
            jdbc.update("INSERT INTO "+schema+".user_account(id,email,normalized_email,display_name,status,created_at,updated_at) VALUES (?,'upgrade@example.test','upgrade@example.test','Upgrade','PENDING_EMAIL',?,?)",
                    id,Timestamp.from(NOW),Timestamp.from(NOW));
            jdbc.update("INSERT INTO "+schema+".admin_role_command(actor_id,command_key,target_id,grant_role,created_at,expires_at) VALUES (?,?,?,true,?,?)",
                    id,key,id,Timestamp.from(NOW),Timestamp.from(NOW.plusSeconds(86400)));
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("6").load().migrate();
            assertEquals("upgrade@example.test",jdbc.queryForObject("SELECT email FROM "+schema+".user_account WHERE id=?",String.class,id));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM "+schema+".admin_role_command WHERE command_key=? AND related_account_ids IS NULL",Integer.class,key));
        } finally {
            assertTrue(schema.matches("it_cancellation_[0-9a-f]{32}_upgrade"));
            jdbc.execute("DROP SCHEMA IF EXISTS \""+schema+"\" CASCADE");
        }
    }
    void credentials(UUID id) {
        jdbc.update("INSERT INTO user_session(id,account_id,token_hash,remember_me,created_at,last_used_at,expires_at) VALUES (?,?,?,false,?,?,?)",
                UUID.randomUUID(),id,UUID.randomUUID().toString().replace("-","").repeat(2),Timestamp.from(NOW),Timestamp.from(NOW),Timestamp.from(NOW.plusSeconds(3600)));
        jdbc.update("INSERT INTO user_action_token(id,account_id,purpose,token_hash,created_at,expires_at) VALUES (?,?,'PASSWORD_RESET',?,?,?)",
                UUID.randomUUID(),id,UUID.randomUUID().toString().replace("-","").repeat(2),Timestamp.from(NOW),Timestamp.from(NOW.plusSeconds(1800)));
    }
    @Test void mandatoryTransactionIsEnforced() {
        assertThrows(IllegalTransactionStateException.class,()->accounts.lock(UUID.randomUUID()));
        assertThrows(IllegalTransactionStateException.class,()->cleanup.deleteRelated(UUID.randomUUID(),null));
    }
    @Test void concurrentAccountLockSeesCommittedStateRatherThanOldSnapshot() throws Exception {
        UUID id=account();var executor=Executors.newSingleThreadExecutor();
        var started=new CountDownLatch(1);
        try {
            Future<AccountCancellationStore.State> result=tx(()->{
                accounts.lock(id);
                Future<AccountCancellationStore.State> future=executor.submit(()->{
                    started.countDown();return tx(()->accounts.lock(id));
                });
                try {
                    assertTrue(started.await(5,TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class,()->future.get(200,TimeUnit.MILLISECONDS));
                } catch(InterruptedException failure) {Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
                accounts.deleteIdentities(id);accounts.anonymize(id,NOW);return future;
            });
            assertEquals("ANONYMIZED",result.get(10,TimeUnit.SECONDS).status());
        } finally {executor.shutdownNow();}
    }
    @Test void physicalDeletionAlsoRollsBack() {
        UUID id=account();var marker=new IllegalStateException("Injected after deletion");
        assertSame(marker,assertThrows(IllegalStateException.class,()->tx(()->{accounts.deleteIdentities(id);accounts.deleteAccount(id);throw marker;})));
        assertEquals("ACTIVE",tx(()->accounts.lock(id)).status());
    }
    @Test void anonymizationRemovesIdentifiersAndReleasesEmailImmediately() {
        String email=UUID.randomUUID()+"@example.test";UUID id=account(email);
        tx(()->{accounts.deleteIdentities(id);return accounts.anonymize(id,NOW);});
        var state=tx(()->accounts.lock(id));assertEquals("ANONYMIZED",state.status());assertNull(state.email());
        assertNull(state.normalizedEmail());assertNull(state.displayName());assertEquals(NOW,state.cancelledAt());
        assertNotEquals(id,account(email));
        assertEquals(0,tx(()->accounts.anonymize(id,NOW.plusSeconds(1))));
    }
    @Test void databaseRejectsPartialAnonymizationAndMissingLiveIdentity() {
        UUID id=account();
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE user_account SET status='ANONYMIZED',cancelled_at=? WHERE id=?",Timestamp.from(NOW),id));
        for(String state:new String[]{"ACTIVE","PENDING_EMAIL","DISABLED"}) {
            for(String field:new String[]{"email","normalized_email","display_name"})
                assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE user_account SET status=?,"+field+"=NULL WHERE id=?",state,id));
            assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE user_account SET status=?,cancelled_at=? WHERE id=?",state,Timestamp.from(NOW),id));
        }
        tx(()->{accounts.deleteIdentities(id);return accounts.anonymize(id,NOW);});
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE user_account SET display_name='Personal' WHERE id=?",id));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE user_account SET cancelled_at=NULL WHERE id=?",id));
    }
    @Test void existingLastAdministratorGuardStillProtectsPrimitiveOperations() throws Exception {
        UUID first=account(),second=account();
        String bootstrap=AdministrationIT.bootstrapBody(first);
        tx(()->{jdbc.execute(bootstrap);return null;});
        var original=tx(()->accounts.lock(first));
        assertAdministratorGuard(()->tx(()->accounts.deleteRoles(first)),
                "Cannot remove the last active administrator","guard_admin_role");
        assertLastAdministratorUnchanged(first,original);
        assertAdministratorGuard(()->tx(()->accounts.anonymize(first,NOW)),
                "Cannot invalidate the last active administrator","guard_admin_account");
        assertLastAdministratorUnchanged(first,original);
        assertAdministratorGuard(()->tx(()->accounts.deleteAccount(first)),
                "Cannot invalidate the last active administrator","guard_admin_account");
        assertLastAdministratorUnchanged(first,original);
        tx(()->{jdbc.update("INSERT INTO user_account_role(account_id,role,granted_at) VALUES (?,'ADMIN',?)",second,Timestamp.from(NOW));return null;});
        // Anonymization with identity removal is valid when another active ADMIN remains.
        assertEquals(1,tx(()->{accounts.deleteIdentities(second);return accounts.anonymize(second,NOW);}));
        assertEquals("ANONYMIZED",tx(()->accounts.lock(second)).status());
        assertEquals(1,tx(()->accounts.deleteRoles(second)));
        assertLastAdministratorUnchanged(first,original);
    }
    void assertAdministratorGuard(Runnable work,String message,String function) {
        Throwable failure=assertThrows(RuntimeException.class,work::run);
        while(!(failure instanceof SQLException) && failure.getCause()!=null)failure=failure.getCause();
        var sql=assertInstanceOf(SQLException.class,failure);
        // PL/pgSQL RAISE EXCEPTION without ERRCODE uses raise_exception, not a class-23 constraint error.
        assertEquals("P0001",sql.getSQLState());
        var server=assertInstanceOf(org.postgresql.util.PSQLException.class,sql).getServerErrorMessage();
        assertNotNull(server);
        assertEquals(message,server.getMessage());
        assertNotNull(server.getWhere());
        assertTrue(server.getWhere().contains(function+"()"),"Must be rejected by the intended administrator guard");
    }
    void assertLastAdministratorUnchanged(UUID id,AccountCancellationStore.State original) {
        assertEquals(original,tx(()->accounts.lock(id)),"Rejected operation must roll back all account changes");
        assertEquals(1,count("user_account_role","account_id",id));
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM user_account_role r JOIN user_account a ON a.id=r.account_id
                WHERE r.role='ADMIN' AND a.status='ACTIVE' AND a.email_verified_at IS NOT NULL
                """,Integer.class));
        assertTrue(jdbc.queryForObject("SELECT bootstrapped FROM admin_control WHERE id=1",Boolean.class));
    }
    @Test void physicalRemovalRequiresExplicitCredentialCleanup() {
        UUID id=account();credentials(id);
        assertThrows(DataIntegrityViolationException.class,()->tx(()->accounts.deleteAccount(id)));
        tx(()->{assertEquals(1,accounts.deleteIdentities(id));assertEquals(1,accounts.deleteSessions(id));assertEquals(1,accounts.deleteTokens(id));accounts.deleteRoles(id);return accounts.deleteAccount(id);});
        assertNull(tx(()->accounts.lock(id)));
    }
    @Test void orphaningPreservesPhotoAndIncrementsEveryOwnedCrewVersion() {
        UUID id=account(),first=crew(id),second=crew(id),photo=UUID.randomUUID(),other=crew(account());
        jdbc.update("UPDATE crew SET photo_id=?,photo_type='image/png',photo_size=10,photo_width=1,photo_height=1 WHERE id=?",photo,first);
        assertEquals(2,tx(()->accounts.detachCrews(id,NOW)));
        assertEquals(photo,jdbc.queryForObject("SELECT photo_id FROM crew WHERE id=?",UUID.class,first));
        for(UUID crew:new UUID[]{first,second}) {
            assertNull(jdbc.queryForObject("SELECT captain_account_id FROM crew WHERE id=?",UUID.class,crew));
            assertEquals(1L,jdbc.queryForObject("SELECT version FROM crew WHERE id=?",Long.class,crew));
        }
        assertEquals(0L,jdbc.queryForObject("SELECT version FROM crew WHERE id=?",Long.class,other));
    }
    @Test void closesBothSidesAndKeepsTerminalHistory() {
        UUID id=account(),other=account();
        UUID outgoing=transfer(crew(id),id,other,false),incoming=transfer(crew(other),other,id,false),expired=transfer(crew(id),id,other,true);
        UUID history=transfer(crew(other),other,id,false);
        jdbc.update("UPDATE crew_transfer SET status='REJECTED',resolved_at=?,resolved_by=?,resolution_reason='REJECTED' WHERE id=?",Timestamp.from(NOW),id,history);
        assertEquals(3,tx(()->accounts.closePendingTransfers(id,id,NOW)));
        for(UUID transfer:new UUID[]{outgoing,incoming})assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM crew_transfer WHERE id=?",String.class,transfer));
        assertEquals("EXPIRED",jdbc.queryForObject("SELECT status FROM crew_transfer WHERE id=?",String.class,expired));
        tx(()->{accounts.detachCrews(id,NOW);accounts.deleteIdentities(id);return accounts.deleteAccount(id);});
        assertEquals("REJECTED",jdbc.queryForObject("SELECT status FROM crew_transfer WHERE id=?",String.class,history));
    }
    @Test void metadataCleanupIncludesOtherActorsExpiredAndLegacyRoles() {
        UUID victim=account(),actor=account(),unrelated=account();
        UUID fresh=UUID.randomUUID(),old=UUID.randomUUID(),legacy=UUID.randomUUID(),keep=UUID.randomUUID();
        tx(()->{commands.saveRole(actor,fresh,victim,true,NOW);commands.saveRole(actor,old,victim,false,NOW.minusSeconds(90000));commands.saveRole(actor,legacy,victim,true,NOW);commands.saveRole(actor,keep,unrelated,true,NOW);return null;});
        jdbc.update("UPDATE admin_role_command SET related_account_ids=NULL WHERE command_key=?",legacy);
        assertEquals(3,tx(()->cleanup.deleteRelated(victim,null)));
        assertEquals(1,count("admin_role_command","command_key",keep));
    }
    @Test void newCaptainMetadataIncludesPreviousOwnerAndRetentionRemainsOneDay() {
        UUID previous=account(),actor=account(),crew=crew(actor),key=UUID.randomUUID();
        tx(()->{commands.save(actor,key,crew,"CREW_CAPTAIN","[\""+crew+"\",0,\""+actor+"\"]",crewResult(crew,actor,""),NOW);
            commands.relate(actor,key,previous);return null;});
        assertTrue(jdbc.queryForObject("SELECT ?=ANY(related_account_ids) AND expires_at=created_at+interval '24 hours' FROM admin_role_command WHERE command_key=?",Boolean.class,previous,key));
        assertEquals(1,tx(()->cleanup.deleteRelated(previous,null)));
    }
    @Test void missingLegacyCaptainHistoryFailsClosed() {
        UUID actor=account(),crew=crew(actor),key=UUID.randomUUID();
        tx(()->{commands.save(actor,key,crew,"CREW_CAPTAIN","[\""+crew+"\",0,null]",crewResult(crew,null,""),NOW);return null;});
        jdbc.update("UPDATE admin_role_command SET related_account_ids=NULL WHERE command_key=?",key);
        try {Throwable failure=assertThrows(RuntimeException.class,()->tx(()->cleanup.deleteRelated(actor,null)));
            while(failure.getCause()!=null)failure=failure.getCause();
            assertInstanceOf(IllegalStateException.class,failure);
            assertTrue(failure.getMessage().contains("Unrecognized idempotency record format"));
            assertEquals(1,count("admin_role_command","command_key",key));
        } finally {jdbc.update("DELETE FROM admin_role_command WHERE command_key=?",key);}
    }
    String crewResult(UUID crew,UUID captain,String description) {
        return "{\"id\":\""+crew+"\",\"captainAccountId\":"+(captain==null?"null":"\""+captain+"\"")+",\"description\":\""+description+"\"}";
    }
    @Test void literalEmailInOtherActorsCrewResponseIsRemovedButCrewUuidIsNotAccount() {
        UUID victim=account(),actor=account(),crew=crew(actor),key=UUID.randomUUID();
        String email=jdbc.queryForObject("SELECT email FROM user_account WHERE id=?",String.class,victim);
        tx(()->{commands.save(actor,key,crew,"CREW_CREATE","[\"Crew\",null,null,\""+actor+"\"]",crewResult(crew,actor,"Contact "+email.toUpperCase()),NOW);return null;});
        assertEquals(0,tx(()->cleanup.deleteRelated(crew,null)));
        assertEquals(1,tx(()->cleanup.deleteRelated(victim,email)));
        assertEquals(1,count("crew","id",crew));
    }
    @Test void legacyCaptainUsesRetainedAuditAndPreservesThatAudit() {
        UUID previous=account(),actor=account(),crew=crew(actor),key=UUID.randomUUID();
        tx(()->{commands.save(actor,key,crew,"CREW_CAPTAIN","[\""+crew+"\",0,\""+actor+"\"]",crewResult(crew,actor,""),NOW);return null;});
        jdbc.update("UPDATE admin_role_command SET related_account_ids=NULL WHERE command_key=?",key);
        UUID audit=UUID.randomUUID();jdbc.update("""
                INSERT INTO audit_event(id,occurred_at,actor_kind,actor_id,event_type,target_id,target_type,previous_captain_id,new_captain_id)
                VALUES (?,?,'USER',?,'CREW_CAPTAIN_CHANGED',?,'CREW',?,?)
                """,audit,Timestamp.from(NOW),actor,crew,previous,actor);
        assertEquals(1,tx(()->cleanup.deleteRelated(previous,null)));
        assertEquals(1,count("audit_event","id",audit));
    }
    @Test void legacyTransferDecisionUsesRetainedTransferAfterCrewRemoval() {
        UUID from=account(),to=account(),crew=crew(from),transfer=transfer(crew,from,to,false),key=UUID.randomUUID();
        tx(()->{commands.save(to,key,transfer,"CREW_TRANSFER_ACCEPT","[\""+transfer+"\",\""+"d".repeat(64)+"\"]",
                "{\"id\":\""+transfer+"\",\"crewId\":\""+crew+"\"}",NOW);return null;});
        jdbc.update("UPDATE admin_role_command SET related_account_ids=NULL WHERE command_key=?",key);
        jdbc.update("DELETE FROM crew WHERE id=?",crew);
        assertEquals(1,tx(()->cleanup.deleteRelated(from,null)));assertEquals(1,count("crew_transfer","id",transfer));
    }
    @Test void unknownFormatAbortsWithoutPartialCleanup() {
        UUID victim=account(),actor=account(),key=UUID.randomUUID(),unknown=UUID.randomUUID();
        tx(()->{commands.saveRole(actor,key,victim,true,NOW);commands.saveRole(actor,unknown,actor,true,NOW);return null;});
        jdbc.update("UPDATE admin_role_command SET operation='UNKNOWN' WHERE command_key=?",unknown);
        try {Throwable failure=assertThrows(RuntimeException.class,()->tx(()->cleanup.deleteRelated(victim,null)));
            while(failure.getCause()!=null)failure=failure.getCause();
            assertInstanceOf(IllegalStateException.class,failure);
            assertTrue(failure.getMessage().contains("Unrecognized idempotency record format"));
            assertEquals(1,count("admin_role_command","command_key",key));
        } finally {jdbc.update("DELETE FROM admin_role_command WHERE command_key=?",unknown);}
    }
    @Test void rollbackRestoresCredentialsCrewTransferLedgerAndAccount() {
        UUID victim=account(),other=account(),crew=crew(victim),transfer=transfer(crew,victim,other,false),key=UUID.randomUUID();credentials(victim);
        tx(()->{commands.saveRole(other,key,victim,true,NOW);return null;});
        var marker=new IllegalStateException("Injected failure after persistent operations");
        assertSame(marker,assertThrows(IllegalStateException.class,()->tx(()->{
            accounts.closePendingTransfers(victim,other,NOW);accounts.detachCrews(victim,NOW);cleanup.deleteRelated(victim,null);
            accounts.deleteIdentities(victim);accounts.deleteSessions(victim);accounts.deleteTokens(victim);accounts.deleteRoles(victim);accounts.anonymize(victim,NOW);throw marker;
        })));
        assertEquals("ACTIVE",tx(()->accounts.lock(victim)).status());assertEquals(victim,jdbc.queryForObject("SELECT captain_account_id FROM crew WHERE id=?",UUID.class,crew));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM crew_transfer WHERE id=?",String.class,transfer));
        for(String table:new String[]{"user_identity","user_session","user_action_token"})assertEquals(1,count(table,"account_id",victim));
        assertEquals(1,count("admin_role_command","command_key",key));
    }
    @AfterAll void cleanupOwnSchema() {
        assertTrue(SCHEMA.matches("it_cancellation_[0-9a-f]{32}"));
        assertEquals("rallye_test",jdbc.queryForObject("SELECT current_database()",String.class));
        assertEquals("rallye_test",jdbc.queryForObject("SELECT current_user",String.class));
        jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");
    }
}
