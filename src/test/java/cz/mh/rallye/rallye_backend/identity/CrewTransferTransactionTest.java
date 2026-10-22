package cz.mh.rallye.rallye_backend.identity;

import java.time.Clock;
import java.util.UUID;
import cz.mh.rallye.rallye_backend.crews.application.CrewTransferLifecycle;
import cz.mh.rallye.rallye_backend.crews.domain.CrewTransfer;
import cz.mh.rallye.rallye_backend.crews.domain.CrewTransfer.Status;
import cz.mh.rallye.rallye_backend.crews.persistence.*;
import cz.mh.rallye.rallye_backend.identity.application.AuthService;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CrewTransferTransactionTest {
    @TestConfiguration(proxyBeanMethods=false) @EnableTransactionManagement
    static class Fixtures {
        @Bean CrewTransferStore transfers() {return mock(CrewTransferStore.class);}
        @Bean CrewStore crews() {return mock(CrewStore.class);}
        @Bean AccountStore accounts() {return mock(AccountStore.class);}
        @Bean AdminStore roles() {return mock(AdminStore.class);}
        @Bean Clock clock() {return new MutableClock();}
        @Bean AuditTransactionTest.RecordingTransactions transactionManager() {return new AuditTransactionTest.RecordingTransactions();}
        @Bean CrewTransferLifecycle lifecycle(CrewTransferStore transfers,CrewStore crews,AccountStore accounts,AdminStore roles,Clock clock) {
            return new CrewTransferLifecycle(transfers,crews,accounts,roles,clock);
        }
    }
    @Test void internalOperationsCannotRunOutsideTheFutureOrchestratorsTransaction() {
        try(var context=new AnnotationConfigApplicationContext(Fixtures.class)) {
            var service=context.getBean(CrewTransferLifecycle.class);
            var actor=new AuthService.Principal(UUID.randomUUID(),UUID.randomUUID(),false);UUID id=UUID.randomUUID();
            assertThrows(IllegalTransactionStateException.class,()->service.create(actor,id,UUID.randomUUID(),0,CrewTransferLifecycleTest.HASH));
            assertThrows(IllegalTransactionStateException.class,()->service.accept(actor,id,CrewTransferLifecycleTest.HASH));
            assertThrows(IllegalTransactionStateException.class,()->service.reject(actor,id,CrewTransferLifecycleTest.HASH));
            assertThrows(IllegalTransactionStateException.class,()->service.cancel(actor,id));
            verifyNoInteractions(context.getBean(CrewTransferStore.class),context.getBean(CrewStore.class),
                    context.getBean(AccountStore.class),context.getBean(AdminStore.class));
        }
    }
    @Test void failureAfterCaptainWriteRollsBackSameOuterTransaction() {
        try(var context=new AnnotationConfigApplicationContext(Fixtures.class)) {
            var transfers=context.getBean(CrewTransferStore.class);var crews=context.getBean(CrewStore.class);
            var roles=context.getBean(AdminStore.class);var accounts=context.getBean(AccountStore.class);
            var manager=context.getBean(AuditTransactionTest.RecordingTransactions.class);
            UUID from=UUID.randomUUID(),to=UUID.randomUUID(),id=UUID.randomUUID(),crew=UUID.randomUUID();
            var actor=new AuthService.Principal(to,UUID.randomUUID(),false);var now=context.getBean(Clock.class).instant();
            var pending=new CrewTransfer(id,crew,from,to,CrewTransferLifecycleTest.HASH,Status.PENDING,now,now.plus(CrewTransferLifecycle.LIFETIME),null,null,null);
            when(roles.activeVerified(to)).thenReturn(true);when(accounts.isActiveSession(any(),any(),any())).thenReturn(true);
            when(transfers.find(id)).thenReturn(pending);when(transfers.lock(id)).thenReturn(pending);
            when(crews.find(crew)).thenReturn(new CrewStore.Crew(crew,from,"Crew",null,null,null,null,null,null,null,now,now,4));
            var marker=new IllegalStateException("Test transition write failure");
            when(transfers.resolve(any(),any(),any(),any(),any())).thenThrow(marker);
            assertSame(marker,assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).executeWithoutResult(status->
                    context.getBean(CrewTransferLifecycle.class).accept(actor,id,CrewTransferLifecycleTest.HASH))));
            verify(crews).captain(crew,to,now);assertEquals(1,manager.rollbacks);assertEquals(0,manager.commits);
        }
    }
}
