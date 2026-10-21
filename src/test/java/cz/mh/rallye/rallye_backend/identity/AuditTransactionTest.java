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
        @Bean AuditStore store(JdbcTemplate jdbc) { return new AuditStore(jdbc); }
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
}
