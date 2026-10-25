package cz.mh.rallye.rallye_backend.identity;

import java.time.*;
import cz.mh.rallye.rallye_backend.identity.application.AppleRegistrationMaintenance;
import cz.mh.rallye.rallye_backend.identity.persistence.ExternalAccountStore;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AppleRegistrationMaintenanceTest {
    @Test void reconciliationCommitsOnlyDatabaseMaintenance(){
        var store=mock(ExternalAccountStore.class);var manager=mock(PlatformTransactionManager.class);var status=new SimpleTransactionStatus();when(manager.getTransaction(any())).thenReturn(status);
        Instant now=Instant.parse("2026-10-25T12:00:00Z");new AppleRegistrationMaintenance(store,Clock.fixed(now,ZoneOffset.UTC),manager).reconcile();
        var order=inOrder(store,manager);order.verify(store).purgeExpired(now);order.verify(manager).commit(status);verify(manager,never()).rollback(any());
    }
    @Test void failedMaintenanceRollsBackAndCanRunAgain(){
        var store=mock(ExternalAccountStore.class);var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenAnswer(c->new SimpleTransactionStatus());
        doThrow(new IllegalStateException("synthetic failure")).doNothing().when(store).purgeExpired(any());var schedule=new AppleRegistrationMaintenance(store,Clock.systemUTC(),manager);
        schedule.reconcile();schedule.reconcile();verify(manager).rollback(any());verify(manager).commit(any());verify(store,times(2)).purgeExpired(any());
    }
}
