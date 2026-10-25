package cz.mh.rallye.rallye_backend.identity;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.PreRaceAccountParticipationFacts;
import cz.mh.rallye.rallye_backend.support.TestDatabaseInitializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
@ActiveProfiles("it")
@ContextConfiguration(initializers=TestDatabaseInitializer.class)
@Import(IdentityIT.Fixtures.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MobileLoginIT {
    static final String SCHEMA="it_mobile_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        for(String provider:List.of("GOOGLE","APPLE")) {
            p.add("rallye.auth.external.providers."+provider+".enabled",()->"true");
            p.add("rallye.auth.external.providers."+provider+".client-id",()->"synthetic-client");
            p.add("rallye.auth.external.providers."+provider+".client-secret",()->"synthetic-secret");
            p.add("rallye.auth.external.providers."+provider+".callback-uri",()->"https://backend.example.test/api/v1/auth/external/"+provider+"/callback");
        }
        p.add("rallye.auth.external.returns.web",()->"https://web.example.test/complete");
        p.add("rallye.auth.external.mobile.launch-uri",()->"https://backend.example.test/api/v1/auth/external/mobile/launch");
        p.add("rallye.auth.external.mobile.returns.android.return-uri",()->"https://app.example.test/mobile/return");
        p.add("rallye.auth.external.mobile.returns.android.profile-uri",()->"https://web.example.test/profile");
        p.add("rallye.auth.external.mobile.returns.android.fallback-uri",()->"https://app.example.test/mobile/fallback");
        p.add("spring.flyway.schemas",()->SCHEMA);p.add("spring.flyway.default-schema",()->SCHEMA);
        p.add("spring.datasource.hikari.connection-init-sql",()->"SET search_path TO "+SCHEMA);
    }
    @Autowired MobileLoginService mobile;
    @Autowired ExternalFlowService flows;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager manager;
    @Autowired PreRaceAccountParticipationFacts facts;
    @MockitoBean ExternalOidcVerifier verifier;
    SecretTokens secrets=new SecretTokens();
    TransactionTemplate tx(){return new TransactionTemplate(manager);}
    void guard(){assertEquals("rallye_test",jdbc.queryForObject("SELECT current_database()",String.class));assertEquals("rallye_test",jdbc.queryForObject("SELECT current_user",String.class));assertEquals(SCHEMA,jdbc.queryForObject("SELECT current_schema()",String.class));}
    @BeforeEach void checked(){guard();}
    MobileLoginService.Started start(){return mobile.start(ExternalProviders.Provider.GOOGLE,ExternalFlowService.Purpose.LOGIN,"android",true,secrets.create());}
    String ticket(MobileLoginService.Started started){return java.net.URI.create(started.browserStartUrl()).getQuery().substring("ticket=".length());}
    int flowCount(UUID id){return jdbc.queryForObject("SELECT count(*) FROM external_auth_flow WHERE id=(SELECT flow_id FROM mobile_login_attempt WHERE id=?)",Integer.class,id);}
    @Test void startStoresOnlyHashWithoutFlowAccountSessionGrantOrAudit() {
        var before=jdbc.queryForMap("SELECT (SELECT count(*) FROM user_account) accounts,(SELECT count(*) FROM user_session) sessions,(SELECT count(*) FROM audit_event) audits,(SELECT count(*) FROM external_auth_grant) grants");
        var started=start();var row=jdbc.queryForMap("SELECT * FROM mobile_login_attempt WHERE id=?",started.requestId());
        assertEquals(secrets.hash(ticket(started)),row.get("ticket_hash"));assertFalse(row.containsValue(ticket(started)));assertNull(row.get("flow_id"));
        assertEquals(started.expiresAt(),((java.sql.Timestamp)row.get("created_at")).toInstant().plusSeconds(600));
        assertEquals(before,jdbc.queryForMap("SELECT (SELECT count(*) FROM user_account) accounts,(SELECT count(*) FROM user_session) sessions,(SELECT count(*) FROM audit_event) audits,(SELECT count(*) FROM external_auth_grant) grants"));
    }
    @Test void launchBindsChallengeAndOriginalDeadlineAndDoesNotAuthenticate() {
        var started=start();clock.advance(Duration.ofSeconds(90));var launched=mobile.launch(ticket(started));
        var row=jdbc.queryForMap("SELECT f.* FROM external_auth_flow f JOIN mobile_login_attempt a ON a.flow_id=f.id WHERE a.id=?",started.requestId());
        assertEquals(started.expiresAt(),((java.sql.Timestamp)row.get("expires_at")).toInstant());
        assertEquals(jdbc.queryForObject("SELECT pkce_challenge FROM mobile_login_attempt WHERE id=?",String.class,started.requestId()),row.get("mobile_challenge"));
        assertEquals(secrets.hash(launched.flow().correlation()),row.get("correlation_hash"));assertNull(row.get("account_id"));assertNull(row.get("session_id"));assertEquals(true,row.get("remember_me"));
        assertTrue(launched.flow().authorizationUrl().contains("code_challenge_method=S256"));verifyNoInteractions(verifier);
    }
    @Test void malformedUnknownAndReplayTicketsAreRejected() {
        for(String ticket:List.of("bad",secrets.create()))assertEquals("EXTERNAL_MOBILE_REQUEST_INVALID",assertThrows(AuthFailure.class,()->mobile.launch(ticket)).code());
        var started=start();mobile.launch(ticket(started));
        assertThrows(AuthFailure.class,()->mobile.launch(ticket(started)));assertEquals(1,flowCount(started.requestId()));
    }
    @Test void exactTenMinuteExpiryNeverCreatesFlowOrConsumesTicket() {
        var started=start();clock.advance(Duration.ofMinutes(10));
        assertThrows(AuthFailure.class,()->mobile.launch(ticket(started)));assertEquals(0,flowCount(started.requestId()));
        assertNull(jdbc.queryForObject("SELECT launch_consumed_at FROM mobile_login_attempt WHERE id=?",java.sql.Timestamp.class,started.requestId()));
    }
    @Test void concurrentLaunchHasExactlyOneWinnerAndOneFlow() throws Exception {
        var started=start();var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> call=()->{barrier.await(5,TimeUnit.SECONDS);try{mobile.launch(ticket(started));return true;}catch(AuthFailure e){assertEquals("EXTERNAL_MOBILE_REQUEST_INVALID",e.code());return false;}};
            var a=pool.submit(call);var b=pool.submit(call);assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
        assertEquals(1,flowCount(started.requestId()));
    }
    @Test void rollbackRemovesNewFlowAndRestoresTicketForRetry() {
        var started=start();int before=jdbc.queryForObject("SELECT count(*) FROM external_auth_flow",Integer.class);
        assertThrows(IllegalStateException.class,()->tx().executeWithoutResult(status->{mobile.launch(ticket(started));throw new IllegalStateException("injected after launch");}));
        assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM external_auth_flow",Integer.class));
        assertNull(jdbc.queryForObject("SELECT launch_consumed_at FROM mobile_login_attempt WHERE id=?",java.sql.Timestamp.class,started.requestId()));
        mobile.launch(ticket(started));assertEquals(1,flowCount(started.requestId()));
    }
    @Test void appleLaunchKeepsFormPostAndWebOnlyVerifierRejectsMobileFlow() {
        var started=mobile.start(ExternalProviders.Provider.APPLE,ExternalFlowService.Purpose.LOGIN,"android",false,secrets.create());
        var launched=mobile.launch(ticket(started));assertTrue(launched.flow().authorizationUrl().contains("response_mode=form_post"));
        String state=org.springframework.web.util.UriComponentsBuilder.fromUriString(launched.flow().authorizationUrl()).build().getQueryParams().getFirst("state");
        assertEquals("EXTERNAL_MOBILE_UNAVAILABLE",assertThrows(AuthFailure.class,()->flows.verifyWebCallback(ExternalProviders.Provider.APPLE,state,launched.flow().correlation(),"synthetic-code")).code());
        verifyNoInteractions(verifier);
    }
    @Test void reviewedV11AndUnknownNameOrFutureVersionFailClosed() {
        assertFalse(tx().execute(s->facts.forAccount(UUID.randomUUID())).historical());
        tx().executeWithoutResult(s->{jdbc.update("UPDATE flyway_schema_history SET script='V11__unknown.sql' WHERE version='11'");assertThrows(AuthFailure.class,()->facts.forAccount(UUID.randomUUID()));s.setRollbackOnly();});
        tx().executeWithoutResult(s->{jdbc.update("UPDATE flyway_schema_history SET version='16' WHERE version='11'");assertThrows(AuthFailure.class,()->facts.forAccount(UUID.randomUUID()));s.setRollbackOnly();});
    }
    @Test void databaseRejectsWrongLifetimeAndHalfConsumedAttempt() {
        var started=start();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE mobile_login_attempt SET expires_at=expires_at+interval '1 second' WHERE id=?",started.requestId()));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE mobile_login_attempt SET launch_consumed_at=created_at WHERE id=?",started.requestId()));
        assertEquals(0,flowCount(started.requestId()));
    }
    @Test void v10UpgradePreservesExistingWebFlow() {
        String upgrade=SCHEMA+"_upgrade";
        try {
            var source=jdbc.getDataSource();org.flywaydb.core.Flyway.configure().dataSource(source).schemas(upgrade).defaultSchema(upgrade).target("10").load().migrate();
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO "+upgrade+".external_auth_flow(id,provider,purpose,state_hash,nonce_hash,correlation_hash,callback_uri,return_uri,remember_me,created_at,expires_at) VALUES (?,'GOOGLE','LOGIN',?,?,?,'https://backend.example.test/callback','https://web.example.test/complete',false,now(),now()+interval '10 minutes')",id,secrets.hash("state"),secrets.hash("nonce"),secrets.hash("cookie"));
            org.flywaydb.core.Flyway.configure().dataSource(source).schemas(upgrade).defaultSchema(upgrade).load().migrate();
            assertEquals(id,jdbc.queryForObject("SELECT id FROM "+upgrade+".external_auth_flow",UUID.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM "+upgrade+".mobile_login_attempt",Integer.class));
        } finally {guard();assertTrue(upgrade.matches("it_mobile_[0-9a-f]{32}_upgrade"));jdbc.execute("DROP SCHEMA IF EXISTS \""+upgrade+"\" CASCADE");}
    }
    @AfterAll void cleanup(){guard();assertTrue(SCHEMA.matches("it_mobile_[0-9a-f]{32}"));jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");}
}
