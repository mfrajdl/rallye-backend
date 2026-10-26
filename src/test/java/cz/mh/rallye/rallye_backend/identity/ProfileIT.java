package cz.mh.rallye.rallye_backend.identity;

import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import cz.mh.rallye.rallye_backend.audit.application.AuditService.Event;
import cz.mh.rallye.rallye_backend.audit.persistence.AuditStore;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.AccountStore;
import cz.mh.rallye.rallye_backend.support.TestDatabaseInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Only -Pit and TestDatabaseInitializer can run this isolated-schema suite. */
@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@ActiveProfiles("it")
@ContextConfiguration(initializers=TestDatabaseInitializer.class)
@Import(IdentityIT.Fixtures.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ProfileIT {
    static final String SCHEMA="it_profile_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void isolatedSchema(DynamicPropertyRegistry properties){
        properties.add("spring.flyway.schemas",()->SCHEMA);properties.add("spring.flyway.default-schema",()->SCHEMA);
        properties.add("spring.datasource.hikari.connection-init-sql",()->"SET search_path TO "+SCHEMA);
    }
    @Autowired AuthService auth;
    @Autowired AccountStore accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @MockitoSpyBean AuditStore audit;
    record Fixture(UUID id,String secret,AuthService.Principal principal){}
    Fixture fixture(String provider){
        UUID id=UUID.randomUUID();String email=id+"@example.test",secret=new SecretTokens().create();
        return new TransactionTemplate(manager).execute(status->{
            jdbc.queryForObject("SELECT id FROM admin_control WHERE id=1 FOR UPDATE",Integer.class);
            var time=Timestamp.from(clock.instant());
            jdbc.update("INSERT INTO user_account(id,email,normalized_email,display_name,status,created_at,updated_at,email_verified_at) VALUES (?,?,?,'Old','ACTIVE',?,?,?)",id,email,email,time,time,time);
            jdbc.update("INSERT INTO user_identity(id,account_id,provider,issuer,subject,password_hash,created_at) VALUES (?,?,?,?,?,?,?)",UUID.randomUUID(),id,provider,provider.equals("LOCAL")?"local":provider.equals("GOOGLE")?"https://accounts.google.com":"https://appleid.apple.com",id.toString(),provider.equals("LOCAL")?"test-only-hash":null,time);
            UUID session=accounts.insertSession(id,new SecretTokens().hash(secret),false,clock.instant(),clock.instant().plusSeconds(86400));
            return new Fixture(id,secret,new AuthService.Principal(id,session,false));
        });
    }
    long auditCount(UUID id){return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE event_type='PROFILE_NAME_CHANGED' AND target_id=?",Long.class,id);}
    @ParameterizedTest @ValueSource(strings={"LOCAL","GOOGLE","APPLE"})
    void patchGetRepeatAndUnrelatedAccountAreConsistent(String provider)throws Exception{
        var fixture=fixture(provider);var other=fixture("LOCAL");
        var before=jdbc.queryForMap("SELECT * FROM user_identity WHERE account_id=?",fixture.id());
        String email=auth.me(fixture.principal()).email();
        mvc.perform(patch("/api/v1/auth/me").header("Authorization","Bearer "+fixture.secret()).contentType("application/json").content("{\"displayName\":\"  Nové jméno  \"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("Nové jméno")).andExpect(jsonPath("$.id").value(fixture.id().toString())).andExpect(jsonPath("$.email").value(email)).andExpect(jsonPath("$.roles[0]").value("USER"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+fixture.secret())).andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("Nové jméno"));
        auth.changeDisplayName(fixture.principal(),"Nové jméno");assertEquals(1,auditCount(fixture.id()));
        assertEquals("Old",auth.me(other.principal()).displayName());assertNotNull(auth.authenticate(fixture.secret()));
        assertTrue(before.equals(jdbc.queryForMap("SELECT * FROM user_identity WHERE account_id=?",fixture.id())),"Identity fields must remain unchanged");
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM user_session WHERE account_id=? AND revoked_at IS NULL",Integer.class,fixture.id()));
        var event=jdbc.queryForMap("SELECT actor_id,target_id,metadata FROM audit_event WHERE event_type='PROFILE_NAME_CHANGED' AND target_id=?",fixture.id());assertEquals(fixture.id(),event.get("actor_id"));assertEquals(fixture.id(),event.get("target_id"));assertEquals("{}",event.get("metadata").toString());
    }
    @Test void auditFailureRollsBackNameAndUpdatedTimestamp(){
        var fixture=fixture("LOCAL");var before=jdbc.queryForMap("SELECT display_name,updated_at FROM user_account WHERE id=?",fixture.id());
        clock.advance(java.time.Duration.ofSeconds(1));
        var injected=new IllegalStateException("injected audit failure");
        doAnswer(call->{
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("Changed",jdbc.queryForObject("SELECT display_name FROM user_account WHERE id=?",String.class,fixture.id()));
            assertNotEquals(before.get("updated_at"),jdbc.queryForMap("SELECT updated_at FROM user_account WHERE id=?",fixture.id()).get("updated_at"));
            call.callRealMethod(); // Include the actual audit INSERT in the transaction that must roll back.
            assertEquals(1,auditCount(fixture.id()));
            throw injected;
        }).when(audit).append(any(),any(),eq(fixture.id()),eq(Event.PROFILE_NAME_CHANGED),eq(fixture.id()));
        try {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            var failure=assertThrows(org.springframework.dao.DataAccessException.class,
                    ()->auth.changeDisplayName(fixture.principal(),"Changed"));
            assertSame(injected,failure.getMostSpecificCause(),"Must be the injected failure, not another persistence error");
            verify(audit,times(1)).append(any(),any(),eq(fixture.id()),eq(Event.PROFILE_NAME_CHANGED),eq(fixture.id()));
            // Read committed state after the service transaction has finished, with no test outer transaction.
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(before,jdbc.queryForMap("SELECT display_name,updated_at FROM user_account WHERE id=?",fixture.id()));
            assertEquals(0,auditCount(fixture.id()));
        } finally { reset(audit); }
    }
    @Test void concurrentSameValueHasOneAuditAndKeepsSession()throws Exception{
        var fixture=fixture("LOCAL");var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)){
            var first=pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return auth.changeDisplayName(fixture.principal(),"Same");});
            var second=pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return auth.changeDisplayName(fixture.principal(),"Same");});
            assertEquals("Same",first.get(20,TimeUnit.SECONDS).displayName());assertEquals("Same",second.get(20,TimeUnit.SECONDS).displayName());
        }
        assertEquals(1,auditCount(fixture.id()));assertEquals("Same",auth.me(fixture.principal()).displayName());assertNotNull(auth.authenticate(fixture.secret()));
    }
    @Test void revokedSessionCannotWrite(){var fixture=fixture("LOCAL");auth.logout(fixture.principal());assertThrows(AuthFailure.class,()->auth.changeDisplayName(fixture.principal(),"Changed"));assertEquals("Old",jdbc.queryForObject("SELECT display_name FROM user_account WHERE id=?",String.class,fixture.id()));assertEquals(0,auditCount(fixture.id()));}
}
