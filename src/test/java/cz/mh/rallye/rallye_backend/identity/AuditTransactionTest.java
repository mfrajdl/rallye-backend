package cz.mh.rallye.rallye_backend.identity;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import cz.mh.rallye.rallye_backend.audit.application.AuditService.Event;
import cz.mh.rallye.rallye_backend.audit.persistence.AuditStore;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.dao.support.PersistenceExceptionTranslator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real Spring exception/transaction interceptors; no datasource or PostgreSQL access. */
class AuditTransactionTest {
    @TestConfiguration(proxyBeanMethods=false)
    @EnableTransactionManagement
    static class Fixtures {
        @Bean static PersistenceExceptionTranslationPostProcessor exceptionTranslation() {
            return new PersistenceExceptionTranslationPostProcessor();
        }
        @Bean PersistenceExceptionTranslator jpaExceptionTranslator() { return new HibernateJpaDialect(); }
        @Bean RecordingTransactions transactionManager() { return new RecordingTransactions(); }
        @Bean JdbcTemplate jdbc() { return mock(JdbcTemplate.class); }
        @Bean AuditStore store(JdbcTemplate jdbc) { return spy(new AuditStore(jdbc)); }
        @Bean AuditService service(AuditStore store) {
            return new AuditService(store,Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"),ZoneOffset.UTC));
        }
    }
    static class RecordingTransactions extends AbstractPlatformTransactionManager {
        static class State implements SmartTransactionObject {
            boolean active, rollbackOnly;
            @Override public boolean isRollbackOnly() { return rollbackOnly; }
            @Override public void flush() { }
        }
        final ThreadLocal<State> state=ThreadLocal.withInitial(State::new);
        int commits, rollbacks;
        @Override protected Object doGetTransaction() { return state.get(); }
        @Override protected boolean isExistingTransaction(Object transaction) { return ((State)transaction).active; }
        @Override protected void doBegin(Object transaction,TransactionDefinition definition) { ((State)transaction).active=true; }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
        @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) { ((State)status.getTransaction()).rollbackOnly=true; }
        @Override protected void doCleanupAfterCompletion(Object transaction) { state.remove(); }
    }
    @Test void repositoryTranslationPreservesInjectedCauseAndRollsBackOuterTransaction() {
        try(var context=new AnnotationConfigApplicationContext(Fixtures.class)) {
            var jdbc=context.getBean(JdbcTemplate.class);
            var transactionManager=context.getBean(RecordingTransactions.class);
            var service=context.getBean(AuditService.class);
            var simulated=new IllegalStateException("Test audit failure");
            doThrow(simulated).when(jdbc).update(anyString(),any(Object[].class));
            var translated=assertThrows(InvalidDataAccessApiUsageException.class,()->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status->
                            service.record(UUID.randomUUID(),Event.LOGIN_SUCCEEDED,UUID.randomUUID())));
            assertSame(simulated,translated.getMostSpecificCause());
            assertEquals(0,transactionManager.commits);
            assertEquals(1,transactionManager.rollbacks);
        }
    }
    @Test void successfulAuditJoinsOuterTransactionAndCommitsOnce() {
        try(var context=new AnnotationConfigApplicationContext(Fixtures.class)) {
            var jdbc=context.getBean(JdbcTemplate.class);
            var transactionManager=context.getBean(RecordingTransactions.class);
            new TransactionTemplate(transactionManager).executeWithoutResult(status->
                    context.getBean(AuditService.class).record(UUID.randomUUID(),Event.LOGIN_SUCCEEDED,UUID.randomUUID()));
            verify(jdbc).update(anyString(),any(Object[].class));
            assertEquals(1,transactionManager.commits);
            assertEquals(0,transactionManager.rollbacks);
        }
    }
    @Test void auditCannotRunOutsideBusinessTransaction() {
        try(var context=new AnnotationConfigApplicationContext(Fixtures.class)) {
            var jdbc=context.getBean(JdbcTemplate.class);
            clearInvocations(jdbc); // Ignore bean lifecycle initialization, observe only this operation.
            assertThrows(IllegalTransactionStateException.class,()->
                    context.getBean(AuditService.class).record(UUID.randomUUID(),Event.LOGIN_SUCCEEDED,UUID.randomUUID()));
            verifyNoInteractions(jdbc);
        }
    }
    @Test void crewAuditStillRequiresExistingBusinessTransaction() {
        try(var context=new AnnotationConfigApplicationContext(Fixtures.class)) {
            var jdbc=context.getBean(JdbcTemplate.class);
            clearInvocations(jdbc);
            assertThrows(IllegalTransactionStateException.class,()->context.getBean(AuditService.class)
                    .crew(UUID.randomUUID(),Event.CREW_CREATED,UUID.randomUUID(),null,UUID.randomUUID()));
            verifyNoInteractions(jdbc);
        }
    }
    @Test void transferAuditRequiresSameBusinessTransactionAndOmitsAuthenticationDigest() {
        try(var context=new AnnotationConfigApplicationContext(Fixtures.class)) {
            var jdbc=context.getBean(JdbcTemplate.class);var service=context.getBean(AuditService.class);
            UUID actor=UUID.randomUUID(),crew=UUID.randomUUID(),id=UUID.randomUUID(),target=UUID.randomUUID();
            var now=Instant.parse("2026-10-21T12:00:00Z");
            var transfer=new cz.mh.rallye.rallye_backend.crews.domain.CrewTransfer(id,crew,actor,target,"a".repeat(64),
                    cz.mh.rallye.rallye_backend.crews.domain.CrewTransfer.Status.PENDING,now,now.plusSeconds(604800),null,null,null);
            clearInvocations(jdbc);
            assertThrows(IllegalTransactionStateException.class,()->service.transfer(actor,Event.CREW_TRANSFER_REQUESTED,transfer,"REQUESTED"));
            verifyNoInteractions(jdbc);
            new TransactionTemplate(context.getBean(RecordingTransactions.class)).executeWithoutResult(status->
                    service.transfer(actor,Event.CREW_TRANSFER_REQUESTED,transfer,"REQUESTED"));
            var args=org.mockito.ArgumentCaptor.forClass(Object[].class);
            verify(jdbc).update(anyString(),args.capture());
            assertTrue(java.util.Arrays.asList(args.getValue()).contains(id));assertTrue(java.util.Arrays.asList(args.getValue()).contains(crew));
            assertFalse(java.util.Arrays.asList(args.getValue()).contains(transfer.tokenHash()));
        }
    }
    @Test void repositorySpyCanBePreparedWithoutTransactionAndFailsOnlyAfterCrewAuditInsert() {
        try(var context=new AnnotationConfigApplicationContext(Fixtures.class)) {
            var jdbc=context.getBean(JdbcTemplate.class);
            var store=context.getBean(AuditStore.class);
            var service=context.getBean(AuditService.class);
            var manager=context.getBean(RecordingTransactions.class);
            var marker=new IllegalStateException("Test crew audit persistence failure");
            clearInvocations(jdbc);
            // Same injection point as CrewsIT: no service method is called while configuring the spy.
            doAnswer(invocation->{invocation.callRealMethod();throw marker;})
                    .when(store).appendCrew(any(),any(),any(),eq(Event.CREW_CREATED),any(),any(),any());
            verifyNoInteractions(jdbc);
            var translated=assertThrows(InvalidDataAccessApiUsageException.class,()->
                    new TransactionTemplate(manager).executeWithoutResult(status->
                            service.crew(UUID.randomUUID(),Event.CREW_CREATED,UUID.randomUUID(),null,UUID.randomUUID())));
            assertSame(marker,translated.getMostSpecificCause());
            verify(jdbc,times(1)).update(anyString(),any(Object[].class));
            assertEquals(1,manager.rollbacks);assertEquals(0,manager.commits);
        }
    }
    @Test void raceAuditsBothRequireAndJoinBusinessTransaction() {
        try(var context=new AnnotationConfigApplicationContext(Fixtures.class)) {
            var jdbc=context.getBean(JdbcTemplate.class);var service=context.getBean(AuditService.class);
            var manager=context.getBean(RecordingTransactions.class);
            UUID actor=UUID.randomUUID(),event=UUID.randomUUID(),source=UUID.randomUUID(),stage=UUID.randomUUID();
            clearInvocations(jdbc);
            assertThrows(IllegalTransactionStateException.class,()->service.race(actor,Event.RACE_EVENT_UPDATED,event,source,1));
            assertThrows(IllegalTransactionStateException.class,()->service.raceInstructions(actor,event,stage));
            verifyNoInteractions(jdbc);
            new TransactionTemplate(manager).executeWithoutResult(status->{service.race(actor,Event.RACE_EVENT_UPDATED,event,source,1);service.raceInstructions(actor,event,stage);});
            verify(jdbc,times(2)).update(anyString(),any(Object[].class));
            assertEquals(1,manager.commits);assertEquals(0,manager.rollbacks);
        }
    }
}
