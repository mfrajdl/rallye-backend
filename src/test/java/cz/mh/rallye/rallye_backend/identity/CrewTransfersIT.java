package cz.mh.rallye.rallye_backend.identity;

import java.time.Duration;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import cz.mh.rallye.rallye_backend.crews.application.*;
import cz.mh.rallye.rallye_backend.crews.domain.CrewTransfer;
import cz.mh.rallye.rallye_backend.crews.persistence.CrewTransferStore;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.support.TestDatabaseInitializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest
@ActiveProfiles("it")
@ContextConfiguration(initializers=TestDatabaseInitializer.class)
@Import(IdentityIT.Fixtures.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class CrewTransfersIT {
    static final String SCHEMA="it_transfers_"+UUID.randomUUID().toString().replace("-","");
    // Synthetic digest, never a usable authentication token.
    static final String HASH="a".repeat(64);
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry properties) {
        properties.add("spring.flyway.schemas",()->SCHEMA);
        properties.add("spring.flyway.default-schema",()->SCHEMA);
        properties.add("spring.datasource.hikari.connection-init-sql",()->"SET search_path TO "+SCHEMA);
    }
    @Autowired CrewTransferLifecycle lifecycle;
    @Autowired CrewTransferService service;
    @Autowired CrewService crews;
    @Autowired AuthService auth;
    @Autowired IdentityIT.TestMail mail;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean CrewTransferStore transfers;
    @MockitoSpyBean cz.mh.rallye.rallye_backend.shared.persistence.CommandStore commands;
    @MockitoSpyBean cz.mh.rallye.rallye_backend.audit.persistence.AuditStore audit;
    AuthService.LoginSession administrator;
    AuthService.LoginSession owner,target;
    AuthService.LoginSession account() {
        String email=UUID.randomUUID()+"@example.test";
        auth.register(email,"test-password","Captain");
        auth.verifyEmail(mail.token(email,"EMAIL_VERIFICATION"));
        return auth.login(email,"test-password",true);
    }
    @BeforeEach void fixtures() {owner=account();target=account();}
    @BeforeAll void bootstrapAdministrator() throws Exception {
        administrator=account();
        String sql=AdministrationIT.bootstrapBody(administrator.principal().accountId());
        tx(()->{jdbc.execute(sql);return null;});
    }
    <T> T tx(Supplier<T> work) {return new TransactionTemplate(manager).execute(status->work.get());}
    CrewService.View crew() {return crews.create(owner.principal(),UUID.randomUUID(),"Crew",null,null,false,null);}
    String hash() {return UUID.randomUUID().toString().replace("-","").repeat(2);}
    CrewTransfer request(UUID crew,String digest) {
        return tx(()->lifecycle.create(owner.principal(),crew,target.principal().accountId(),0,digest)).transfer();
    }
    void expired(CrewTransfer transfer) {
        jdbc.update("UPDATE crew_transfer SET created_at=?,expires_at=? WHERE id=?",
                Timestamp.from(clock.instant().minus(Duration.ofHours(168))),Timestamp.from(clock.instant()),transfer.id());
    }
    void failure(Runnable work,RuntimeException marker) {
        Throwable thrown=assertThrows(RuntimeException.class,work::run);
        while(thrown.getCause()!=null) thrown=thrown.getCause();
        assertSame(marker,thrown,"Must fail at the injected persistence write");
    }
    String targetEmail() {return jdbc.queryForObject("SELECT email FROM user_account WHERE id=?",String.class,target.principal().accountId());}
    long messages() {return mail.deliveries.stream().filter(item->item.purpose.equals("CREW_TRANSFER")).count();}
    @Test void restFoundationCreatesOneDigestAndOneMailForSameIdempotentCommand() {
        var crew=crew();UUID key=UUID.randomUUID();long before=messages();String email=targetEmail();
        var first=service.create(owner.principal(),crew.id(),key,email,0);
        String secret=mail.token(email,"CREW_TRANSFER");
        var tokens=new SecretTokens();assertTrue(tokens.hash(secret).equals(transfers.find(first.id()).tokenHash()));
        assertFalse(secret.equals(transfers.find(first.id()).tokenHash()));
        assertEquals(first,service.create(owner.principal(),crew.id(),key,email,0));assertEquals(before+1,messages());
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AuthFailure.class,
                ()->service.create(owner.principal(),crew.id(),key,email,1)).code());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM crew_transfer WHERE crew_id=?",Integer.class,crew.id()));
        String ledger=jdbc.queryForObject("SELECT result_data::text FROM admin_role_command WHERE actor_id=? AND command_key=?",
                String.class,owner.principal().accountId(),key);
        assertFalse(ledger.contains(secret));assertFalse(ledger.contains("token_hash"));assertFalse(ledger.contains("tokenHash"));
        assertEquals(0,crews.detail(owner.principal(),crew.id()).version());
    }
    @Test void resendRotatesTokenDoesNotExtendLifetimeAndReplaysWithoutMail() {
        var crew=crew();String email=targetEmail();var first=service.create(owner.principal(),crew.id(),UUID.randomUUID(),email,0);
        String old=mail.token(email,"CREW_TRANSFER");UUID key=UUID.randomUUID();long before=messages();
        var resent=service.resend(owner.principal(),crew.id(),first.id(),key);String fresh=mail.token(email,"CREW_TRANSFER");
        assertFalse(old.equals(fresh));assertEquals(first.expiresAt(),resent.expiresAt());
        assertEquals(before+1,messages());
        assertEquals(resent,service.resend(owner.principal(),crew.id(),first.id(),key));assertEquals(before+1,messages());
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AuthFailure.class,
                ()->service.create(owner.principal(),crew.id(),key,email,0)).code());
        var tokens=new SecretTokens();
        assertEquals("INVALID_OR_EXPIRED_TOKEN",assertThrows(AuthFailure.class,
                ()->tx(()->lifecycle.accept(target.principal(),first.id(),tokens.hash(old)))).code());
        assertEquals(1,tx(()->lifecycle.accept(target.principal(),first.id(),tokens.hash(fresh))).crewVersion());
    }
    @Test void changedVersionWithUsedKeyConflictsBeforeCheckingCurrentCrewVersion() {
        var crew=crew();String email=targetEmail();UUID key=UUID.randomUUID();
        var original=service.create(owner.principal(),crew.id(),key,email,0);long before=messages();
        crews.update(owner.principal(),crew.id(),0,"Edited",null,null);
        assertEquals(original,service.create(owner.principal(),crew.id(),key,email,0));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AuthFailure.class,
                ()->service.create(owner.principal(),crew.id(),key,email,2)).code());
        assertEquals("CREW_VERSION_CONFLICT",assertThrows(AuthFailure.class,
                ()->service.create(owner.principal(),crew.id(),UUID.randomUUID(),email,2)).code());
        assertEquals(before,messages());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM crew_transfer WHERE crew_id=?",Integer.class,crew.id()));
        assertEquals(1,crews.detail(owner.principal(),crew.id()).version());
    }
    @Test void failedCommandWriteRollsBackRequestAndPreventsEmailThenAllowsSameKey() {
        var crew=crew();UUID key=UUID.randomUUID();String email=targetEmail();long before=messages();
        var marker=new IllegalStateException("injected command failure");
        doAnswer(call->{call.callRealMethod();throw marker;}).when(commands).save(any(),eq(key),any(),any(),any(),any(),any());
        try {failure(()->service.create(owner.principal(),crew.id(),key,email,0),marker);}finally {reset(commands);}
        assertEquals(before,messages());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM crew_transfer WHERE crew_id=?",Integer.class,crew.id()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM admin_role_command WHERE actor_id=? AND command_key=?",Integer.class,owner.principal().accountId(),key));
        assertNotNull(service.create(owner.principal(),crew.id(),key,email,0));assertEquals(before+1,messages());
    }
    @Test void failedResendRollsBackRotationAndLedgerWithoutMail() {
        var crew=crew();String email=targetEmail();var first=service.create(owner.principal(),crew.id(),UUID.randomUUID(),email,0);
        String digest=transfers.find(first.id()).tokenHash();long before=messages();UUID key=UUID.randomUUID();
        var marker=new IllegalStateException("injected resend command failure");
        doAnswer(call->{call.callRealMethod();throw marker;}).when(commands).save(any(),eq(key),any(),any(),any(),any(),any());
        try {failure(()->service.resend(owner.principal(),crew.id(),first.id(),key),marker);}finally {reset(commands);}
        assertTrue(digest.equals(transfers.find(first.id()).tokenHash()));assertEquals(before,messages());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM admin_role_command WHERE actor_id=? AND command_key=?",Integer.class,owner.principal().accountId(),key));
        assertNotNull(service.resend(owner.principal(),crew.id(),first.id(),key));assertEquals(before+1,messages());
    }
    @Test void missingDisabledUnverifiedAndSelfTargetsAndStaleVersionNeverSendMail() {
        var crew=crew();String email=targetEmail();long before=messages();
        assertEquals("CAPTAIN_UNAVAILABLE",assertThrows(AuthFailure.class,
                ()->service.create(owner.principal(),crew.id(),UUID.randomUUID(),"absent-"+UUID.randomUUID()+"@example.test",0)).code());
        jdbc.update("UPDATE user_account SET email_verified_at=NULL WHERE id=?",target.principal().accountId());
        assertEquals("CAPTAIN_UNAVAILABLE",assertThrows(AuthFailure.class,()->service.create(owner.principal(),crew.id(),UUID.randomUUID(),email,0)).code());
        jdbc.update("UPDATE user_account SET email_verified_at=now(),status='DISABLED' WHERE id=?",target.principal().accountId());
        assertEquals("CAPTAIN_UNAVAILABLE",assertThrows(AuthFailure.class,()->service.create(owner.principal(),crew.id(),UUID.randomUUID(),email,0)).code());
        jdbc.update("UPDATE user_account SET status='ACTIVE' WHERE id=?",target.principal().accountId());
        String own=jdbc.queryForObject("SELECT email FROM user_account WHERE id=?",String.class,owner.principal().accountId());
        assertEquals("CAPTAIN_UNCHANGED",assertThrows(AuthFailure.class,()->service.create(owner.principal(),crew.id(),UUID.randomUUID(),own,0)).code());
        assertEquals("CREW_VERSION_CONFLICT",assertThrows(AuthFailure.class,()->service.create(owner.principal(),crew.id(),UUID.randomUUID(),email,1)).code());
        assertEquals(404,assertThrows(AuthFailure.class,()->service.create(target.principal(),crew.id(),UUID.randomUUID(),own,0)).status());
        assertEquals(before,messages());
    }
    @Test void pendingAndExpiredRequestsCannotBeResentByWrongActorOrAfterDeadline() {
        var crew=crew();String email=targetEmail();var first=service.create(owner.principal(),crew.id(),UUID.randomUUID(),email,0);long before=messages();
        assertEquals("TRANSFER_ALREADY_PENDING",assertThrows(AuthFailure.class,
                ()->service.create(owner.principal(),crew.id(),UUID.randomUUID(),email,0)).code());
        assertEquals(404,assertThrows(AuthFailure.class,()->service.resend(target.principal(),crew.id(),first.id(),UUID.randomUUID())).status());
        expired(transfers.find(first.id()));
        assertEquals("TRANSFER_NOT_PENDING",assertThrows(AuthFailure.class,
                ()->service.resend(owner.principal(),crew.id(),first.id(),UUID.randomUUID())).code());assertEquals(before,messages());
    }
    @Test void concurrentIdempotentCreationCommitsOneRequestAndOneMail() throws Exception {
        var crew=crew();String email=targetEmail();UUID key=UUID.randomUUID();long before=messages();
        var results=race(()->service.create(owner.principal(),crew.id(),key,email,0),()->service.create(owner.principal(),crew.id(),key,email,0));
        assertInstanceOf(CrewTransferService.View.class,results[0]);assertEquals(results[0],results[1]);assertEquals(before+1,messages());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM crew_transfer WHERE crew_id=?",Integer.class,crew.id()));
    }
    String secret(UUID crew) {
        service.create(owner.principal(),crew,UUID.randomUUID(),targetEmail(),0);
        return mail.token(targetEmail(),"CREW_TRANSFER");
    }
    UUID pendingId(UUID crew) {return jdbc.queryForObject("SELECT id FROM crew_transfer WHERE crew_id=? AND status='PENDING'",UUID.class,crew);}
    long transferAudits(UUID id,String event) {return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE transfer_id=? AND event_type=?",Long.class,id,event);}
    @Test void acceptedDecisionChangesAccessAndVersionAuditsAndReplaysSafely() {
        var crew=crew();String secret=secret(crew.id());UUID id=pendingId(crew.id()),key=UUID.randomUUID();
        crews.update(owner.principal(),crew.id(),0,"Edited",null,null);
        var result=service.decide(target.principal(),id,key,"accept",secret);assertEquals(2,result.crewVersion());
        assertEquals(404,assertThrows(AuthFailure.class,()->crews.detail(owner.principal(),crew.id())).status());
        assertEquals(target.principal().accountId(),crews.detail(target.principal(),crew.id()).captainAccountId());
        assertEquals(result,service.decide(target.principal(),id,key,"accept",secret));
        assertEquals("TRANSFER_NOT_PENDING",assertThrows(AuthFailure.class,
                ()->service.decide(target.principal(),id,UUID.randomUUID(),"accept",secret)).code());
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AuthFailure.class,
                ()->service.decide(target.principal(),id,key,"accept",new SecretTokens().create())).code());
        assertEquals(1,transferAudits(id,"CREW_TRANSFER_REQUESTED"));assertEquals(1,transferAudits(id,"CREW_TRANSFER_ACCEPTED"));
        var row=jdbc.queryForMap("SELECT * FROM audit_event WHERE transfer_id=? AND event_type='CREW_TRANSFER_ACCEPTED'",id);
        assertEquals(crew.id(),row.get("crew_id"));assertEquals(owner.principal().accountId(),row.get("previous_captain_id"));
        assertEquals(target.principal().accountId(),row.get("new_captain_id"));
        String request=jdbc.queryForObject("SELECT request_data::text FROM admin_role_command WHERE actor_id=? AND command_key=?",String.class,target.principal().accountId(),key);
        assertFalse(request.contains(secret));assertFalse(request.contains(new SecretTokens().hash(secret)));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"reject","cancel"})
    void rejectionAndCancellationPreserveOwnerVersionAndReplayWithoutAuditDuplicates(String operation) {
        var crew=crew();String secret=secret(crew.id());UUID id=pendingId(crew.id()),key=UUID.randomUUID();
        var actor=operation.equals("cancel")?owner.principal():target.principal();
        var result=service.decide(actor,id,key,operation,secret);assertEquals(0,result.crewVersion());
        assertEquals(result,service.decide(actor,id,key,operation,secret));
        assertEquals("TRANSFER_NOT_PENDING",assertThrows(AuthFailure.class,()->service.decide(actor,id,UUID.randomUUID(),operation,secret)).code());
        assertEquals(owner.principal().accountId(),crews.detail(owner.principal(),crew.id()).captainAccountId());
        assertEquals(1,transferAudits(id,operation.equals("cancel")?"CREW_TRANSFER_CANCELLED":"CREW_TRANSFER_REJECTED"));
        crews.delete(owner.principal(),crew.id(),0);assertNotNull(transfers.find(id));
    }
    @Test void oldTokenAndWrongUserCannotDecideAndLiveTransferBlocksDeletion() {
        var crew=crew();String old=secret(crew.id());UUID id=pendingId(crew.id());
        service.resend(owner.principal(),crew.id(),id,UUID.randomUUID());
        assertEquals("INVALID_REQUEST",assertThrows(AuthFailure.class,()->service.decide(target.principal(),id,UUID.randomUUID(),"accept",old)).code());
        assertEquals(404,assertThrows(AuthFailure.class,()->service.decide(owner.principal(),id,UUID.randomUUID(),"accept",old)).status());
        assertEquals("CREW_TRANSFER_PENDING",assertThrows(AuthFailure.class,()->crews.delete(owner.principal(),crew.id(),0)).code());
        expired(transfers.find(id));crews.delete(owner.principal(),crew.id(),0);assertNotNull(transfers.find(id));
    }
    @Test void adminCaptainChangeOrRemovalCancelsPendingAndInvalidatesToken() {
        for(boolean remove:new boolean[]{false,true}) {
            var crew=crew();String secret=secret(crew.id());UUID id=pendingId(crew.id());
            var updated=crews.captain(administrator.principal(),crew.id(),0,remove?null:administrator.principal().accountId(),UUID.randomUUID());
            assertEquals(1,updated.version());assertEquals(CrewTransfer.Status.CANCELLED,transfers.find(id).status());
            assertEquals("ADMIN_CAPTAIN_CHANGED",transfers.find(id).resolutionReason());
            assertEquals(1,transferAudits(id,"CREW_TRANSFER_CANCELLED"));
            assertEquals("TRANSFER_NOT_PENDING",assertThrows(AuthFailure.class,()->service.decide(target.principal(),id,UUID.randomUUID(),"accept",secret)).code());
        }
    }
    @Test void failedTransferAuditRollsBackCaptainStateAndCommandAndRetrySucceeds() {
        var crew=crew();String secret=secret(crew.id());UUID id=pendingId(crew.id()),key=UUID.randomUUID();
        var marker=new IllegalStateException("injected transfer audit failure");
        doAnswer(call->{call.callRealMethod();throw marker;}).when(audit).appendTransfer(any(),any(),any(),any(),eq(id),any(),any(),any(),any());
        try {failure(()->service.decide(target.principal(),id,key,"accept",secret),marker);}finally {reset(audit);}
        assertEquals(CrewTransfer.Status.PENDING,transfers.find(id).status());
        assertEquals(0,crews.detail(owner.principal(),crew.id()).version());assertEquals(0,transferAudits(id,"CREW_TRANSFER_ACCEPTED"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM admin_role_command WHERE actor_id=? AND command_key=?",Integer.class,target.principal().accountId(),key));
        assertEquals(1,service.decide(target.principal(),id,key,"accept",secret).crewVersion());
    }
    @Test void requestedAuditFailureDoesNotCreateRequestCommandOrMail() {
        var crew=crew();String email=targetEmail();UUID key=UUID.randomUUID();long before=messages();
        var marker=new IllegalStateException("requested audit failure");
        doAnswer(call->{call.callRealMethod();throw marker;}).when(audit).appendTransfer(any(),any(),any(),
                eq(cz.mh.rallye.rallye_backend.audit.application.AuditService.Event.CREW_TRANSFER_REQUESTED),any(),eq(crew.id()),any(),any(),any());
        try {failure(()->service.create(owner.principal(),crew.id(),key,email,0),marker);}finally {reset(audit);}
        assertEquals(before,messages());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM crew_transfer WHERE crew_id=?",Integer.class,crew.id()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE crew_id=?",Integer.class,crew.id()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM admin_role_command WHERE actor_id=? AND command_key=?",Integer.class,owner.principal().accountId(),key));
        assertNotNull(service.create(owner.principal(),crew.id(),key,email,0));
    }
    @Test void decisionCommandFailureRollsBackAlreadyWrittenAuditAndOwnership() {
        var crew=crew();String secret=secret(crew.id());UUID id=pendingId(crew.id()),key=UUID.randomUUID();
        var marker=new IllegalStateException("decision command failure");
        doAnswer(call->{call.callRealMethod();throw marker;}).when(commands).save(any(),eq(key),any(),any(),any(),any(),any());
        try {failure(()->service.decide(target.principal(),id,key,"accept",secret),marker);}finally {reset(commands);}
        assertEquals(CrewTransfer.Status.PENDING,transfers.find(id).status());assertEquals(0,crews.detail(owner.principal(),crew.id()).version());
        assertEquals(0,transferAudits(id,"CREW_TRANSFER_ACCEPTED"));assertEquals(1,transferAudits(id,"CREW_TRANSFER_REQUESTED"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM admin_role_command WHERE actor_id=? AND command_key=?",Integer.class,target.principal().accountId(),key));
        assertEquals(1,service.decide(target.principal(),id,key,"accept",secret).crewVersion());
    }
    @Test void failedAdminCrewAuditRollsBackCancellationAndCaptainTogether() {
        var crew=crew();String secret=secret(crew.id());UUID id=pendingId(crew.id()),key=UUID.randomUUID();
        var marker=new IllegalStateException("injected captain audit failure");
        doAnswer(call->{call.callRealMethod();throw marker;}).when(audit).appendCrew(any(),any(),any(),eq(cz.mh.rallye.rallye_backend.audit.application.AuditService.Event.CREW_CAPTAIN_CHANGED),eq(crew.id()),any(),any());
        try {failure(()->crews.captain(administrator.principal(),crew.id(),0,administrator.principal().accountId(),key),marker);}finally {reset(audit);}
        assertEquals(CrewTransfer.Status.PENDING,transfers.find(id).status());assertEquals(0,crews.detail(owner.principal(),crew.id()).version());
        assertEquals(0,transferAudits(id,"CREW_TRANSFER_CANCELLED"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM admin_role_command WHERE actor_id=? AND command_key=?",Integer.class,administrator.principal().accountId(),key));
        assertEquals(1,service.decide(target.principal(),id,UUID.randomUUID(),"accept",secret).crewVersion());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"accept","reject","cancel"})
    void concurrentDecisionOperationsHaveOneResult(String competing) throws Exception {
        var crew=crew();String secret=secret(crew.id());UUID id=pendingId(crew.id());
        var results=race(()->service.decide(target.principal(),id,UUID.randomUUID(),"accept",secret),
                ()->service.decide(competing.equals("cancel")?owner.principal():target.principal(),id,UUID.randomUUID(),competing,secret));
        assertEquals(1,java.util.Arrays.stream(results).filter(CrewTransferService.Decision.class::isInstance).count());
        assertEquals(1,java.util.Arrays.stream(results).filter("TRANSFER_NOT_PENDING"::equals).count());
        boolean accepted=transfers.find(id).status()==CrewTransfer.Status.ACCEPTED;
        assertEquals(accepted?1:0,crews.detail(accepted?target.principal():owner.principal(),crew.id()).version());
    }
    @Test void acceptanceRacingAdministratorHasCoherentFinalState() throws Exception {
        var crew=crew();String secret=secret(crew.id());UUID id=pendingId(crew.id());
        var results=race(()->service.decide(target.principal(),id,UUID.randomUUID(),"accept",secret),
                ()->crews.captain(administrator.principal(),crew.id(),0,administrator.principal().accountId(),UUID.randomUUID()));
        boolean accepted=transfers.find(id).status()==CrewTransfer.Status.ACCEPTED;
        assertEquals(accepted?target.principal().accountId():administrator.principal().accountId(),crews.detail(administrator.principal(),crew.id()).captainAccountId());
        assertEquals(1,crews.detail(administrator.principal(),crew.id()).version());
        assertTrue(java.util.Arrays.asList(results).contains(accepted?"CREW_VERSION_CONFLICT":"TRANSFER_NOT_PENDING"));
    }
    @Test void creationRacingDeletionNeverLeavesLiveOrphan() throws Exception {
        var crew=crew();String email=targetEmail();
        var results=race(()->service.create(owner.principal(),crew.id(),UUID.randomUUID(),email,0),
                ()->{crews.delete(owner.principal(),crew.id(),0);return "DELETED";});
        if(java.util.Arrays.asList(results).contains("DELETED")) {
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM crew_transfer WHERE crew_id=?",Integer.class,crew.id()));
            assertTrue(java.util.Arrays.asList(results).contains("CREW_NOT_FOUND"));
        } else {assertTrue(java.util.Arrays.asList(results).contains("CREW_TRANSFER_PENDING"));assertNotNull(pendingId(crew.id()));}
    }
    @Test void expirationAfterLocksAreAcquiredPreventsDecision() {
        var crew=crew();String secret=secret(crew.id());UUID id=pendingId(crew.id());
        doAnswer(call->{clock.advance(Duration.ofHours(168));return call.callRealMethod();}).when(transfers).lock(id);
        try {assertEquals("TRANSFER_NOT_PENDING",assertThrows(AuthFailure.class,()->service.decide(target.principal(),id,UUID.randomUUID(),"accept",secret)).code());}
        finally {reset(transfers);}
        assertEquals(CrewTransfer.Status.PENDING,transfers.find(id).status());assertEquals(0,crews.detail(owner.principal(),crew.id()).version());
    }
    @Test void acceptanceUsesCurrentVersionAndCannotBeRepeated() {
        var crew=crew();var transfer=request(crew.id(),hash());
        assertEquals(0,crews.detail(owner.principal(),crew.id()).version());
        crews.update(owner.principal(),crew.id(),0,"Edited",null,null);
        var result=tx(()->lifecycle.accept(target.principal(),transfer.id(),transfer.tokenHash()));
        assertEquals(CrewTransfer.Status.ACCEPTED,result.transfer().status());assertEquals(2,result.crewVersion());
        var detail=crews.detail(target.principal(),crew.id());
        assertEquals("Edited",detail.name());assertEquals(2,detail.version());
        assertEquals(target.principal().accountId(),detail.captainAccountId());
        assertEquals("TRANSFER_NOT_PENDING",assertThrows(AuthFailure.class,
                ()->tx(()->lifecycle.accept(target.principal(),transfer.id(),transfer.tokenHash()))).code());
        assertEquals(2,crews.detail(target.principal(),crew.id()).version());
    }
    @Test void rejectAndCancelKeepVersionAndTerminalHistorySurvivesCrewDeletion() {
        var crew=crew();var first=request(crew.id(),hash());
        assertEquals(0,tx(()->lifecycle.reject(target.principal(),first.id(),first.tokenHash())).crewVersion());
        var second=request(crew.id(),hash());
        assertEquals(0,tx(()->lifecycle.cancel(owner.principal(),second.id())).crewVersion());
        crews.delete(owner.principal(),crew.id(),0);
        assertEquals(CrewTransfer.Status.REJECTED,transfers.find(first.id()).status());
        assertEquals(CrewTransfer.Status.CANCELLED,transfers.find(second.id()).status());
        assertEquals("TRANSFER_NOT_PENDING",assertThrows(AuthFailure.class,
                ()->tx(()->lifecycle.cancel(owner.principal(),second.id()))).code());
    }
    @Test void exactExpiryIsInvalidAndNextCreationClosesPreviousRequest() {
        var crew=crew();var first=request(crew.id(),hash());expired(first);
        assertEquals("TRANSFER_NOT_PENDING",assertThrows(AuthFailure.class,
                ()->tx(()->lifecycle.accept(target.principal(),first.id(),first.tokenHash()))).code());
        var next=request(crew.id(),hash());
        assertEquals(CrewTransfer.Status.EXPIRED,transfers.find(first.id()).status());
        assertEquals(CrewTransfer.Status.PENDING,next.status());
        assertEquals(Duration.ofHours(168),Duration.between(next.createdAt(),next.expiresAt()));
        assertEquals(0,crews.detail(owner.principal(),crew.id()).version());
    }
    @Test void databaseEnforcesPartialUniqueIndexAndConsistentStates() {
        var crew=crew();var first=request(crew.id(),hash());
        assertThrows(DataIntegrityViolationException.class,()->tx(()->transfers.insert(UUID.randomUUID(),crew.id(),
                owner.principal().accountId(),target.principal().accountId(),hash(),clock.instant(),clock.instant().plus(Duration.ofHours(168)))));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE crew_transfer SET status='ACCEPTED' WHERE id=?",first.id()));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE crew_transfer SET expires_at=created_at WHERE id=?",first.id()));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE crew_transfer SET to_account_id=from_account_id WHERE id=?",first.id()));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE crew_transfer SET token_hash='invalid' WHERE id=?",first.id()));
        assertEquals(CrewTransfer.Status.PENDING,transfers.find(first.id()).status());
        tx(()->lifecycle.cancel(owner.principal(),first.id()));
        assertNotEquals(first.id(),request(crew.id(),hash()).id());
    }
    @Test void wrongActorOrDigestCannotConsumeRequest() {
        var crew=crew();var first=request(crew.id(),hash());
        assertEquals("TRANSFER_FORBIDDEN",assertThrows(AuthFailure.class,
                ()->tx(()->lifecycle.accept(owner.principal(),first.id(),first.tokenHash()))).code());
        assertEquals("INVALID_OR_EXPIRED_TOKEN",assertThrows(AuthFailure.class,
                ()->tx(()->lifecycle.accept(target.principal(),first.id(),HASH))).code());
        assertEquals(CrewTransfer.Status.PENDING,transfers.find(first.id()).status());
        assertEquals(owner.principal().accountId(),crews.detail(owner.principal(),crew.id()).captainAccountId());
    }
    @Test void failedResolutionRollsBackBothCaptainAndTransferThenAllowsRetry() {
        var crew=crew();var first=request(crew.id(),hash());RuntimeException marker=new IllegalStateException("injected resolution failure");
        doAnswer(call->{call.callRealMethod();throw marker;}).when(transfers).resolve(eq(first.id()),eq(CrewTransfer.Status.ACCEPTED),any(),any(),any());
        try {failure(()->tx(()->lifecycle.accept(target.principal(),first.id(),first.tokenHash())),marker);}
        finally {reset(transfers);}
        assertEquals(owner.principal().accountId(),crews.detail(owner.principal(),crew.id()).captainAccountId());
        assertEquals(0,crews.detail(owner.principal(),crew.id()).version());
        var unchanged=transfers.find(first.id());assertEquals(CrewTransfer.Status.PENDING,unchanged.status());
        assertNull(unchanged.resolvedAt());assertNull(unchanged.resolvedBy());assertNull(unchanged.resolutionReason());
        assertTrue(first.tokenHash().equals(unchanged.tokenHash()));
        assertEquals(1,tx(()->lifecycle.accept(target.principal(),first.id(),first.tokenHash())).crewVersion());
    }
    @Test void failedCreationRollsBackExpirationAndInsertedRequest() {
        var crew=crew();var first=request(crew.id(),hash());expired(first);
        RuntimeException marker=new IllegalStateException("injected insert failure");
        doAnswer(call->{call.callRealMethod();throw marker;}).when(transfers).insert(any(),eq(crew.id()),any(),any(),any(),any(),any());
        try {failure(()->request(crew.id(),hash()),marker);} finally {reset(transfers);}
        assertEquals(CrewTransfer.Status.PENDING,transfers.find(first.id()).status());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM crew_transfer WHERE crew_id=?",Integer.class,crew.id()));
        assertEquals(CrewTransfer.Status.PENDING,request(crew.id(),hash()).status());
        assertEquals(CrewTransfer.Status.EXPIRED,transfers.find(first.id()).status());
    }
    Object outcome(Supplier<?> work) {try {return work.get();}catch(AuthFailure failure){return failure.code();}}
    Object[] race(Supplier<?> first,Supplier<?> second) throws Exception {
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Future<?> a=pool.submit(()->{start.await();return outcome(first);});
            Future<?> b=pool.submit(()->{start.await();return outcome(second);});
            start.countDown();return new Object[]{a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS)};
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @Test void concurrentCreationHasExactlyOnePendingWinner() throws Exception {
        var crew=crew();Object[] results=race(()->request(crew.id(),hash()),()->request(crew.id(),hash()));
        assertEquals(1,java.util.Arrays.stream(results).filter(CrewTransfer.class::isInstance).count());
        assertEquals(1,java.util.Arrays.stream(results).filter("TRANSFER_ALREADY_PENDING"::equals).count());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM crew_transfer WHERE crew_id=? AND status='PENDING'",Integer.class,crew.id()));
    }
    @Test void concurrentAcceptanceAndCancellationHaveExactlyOneTerminalWinner() throws Exception {
        var crew=crew();var first=request(crew.id(),hash());
        Object[] results=race(()->tx(()->lifecycle.accept(target.principal(),first.id(),first.tokenHash())),
                ()->tx(()->lifecycle.cancel(owner.principal(),first.id())));
        assertEquals(1,java.util.Arrays.stream(results).filter(CrewTransferLifecycle.Transition.class::isInstance).count());
        assertEquals(1,java.util.Arrays.stream(results).filter("TRANSFER_NOT_PENDING"::equals).count());
        boolean accepted=transfers.find(first.id()).status()==CrewTransfer.Status.ACCEPTED;
        var detail=crews.detail(accepted?target.principal():owner.principal(),crew.id());
        assertEquals(accepted?1:0,detail.version());
        assertEquals(accepted?target.principal().accountId():owner.principal().accountId(),detail.captainAccountId());
    }
    @AfterAll void cleanupOwnSchema() {
        assertTrue(SCHEMA.matches("it_transfers_[0-9a-f]{32}"));
        assertEquals("rallye_test",jdbc.queryForObject("SELECT current_database()",String.class));
        assertEquals("rallye_test",jdbc.queryForObject("SELECT current_user",String.class));
        jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");
    }
}
