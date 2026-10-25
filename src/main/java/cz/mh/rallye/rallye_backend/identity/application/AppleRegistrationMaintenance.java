package cz.mh.rallye.rallye_backend.identity.application;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import cz.mh.rallye.rallye_backend.identity.persistence.ExternalAccountStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** D14 database-only reconciliation. This is not the Apple API worker or its retry scheduler. */
@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="rallye.auth.apple-registration-maintenance.enabled",havingValue="true",matchIfMissing=true)
public class AppleRegistrationMaintenance {
    private final ExternalAccountStore store;
    private final Clock clock;
    private final TransactionTemplate tx;
    public AppleRegistrationMaintenance(ExternalAccountStore store,Clock clock,PlatformTransactionManager manager){this.store=store;this.clock=clock;this.tx=new TransactionTemplate(manager);}
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void reconcile(){
        try {tx.executeWithoutResult(status->store.purgeExpired(clock.instant().truncatedTo(ChronoUnit.MILLIS)));}
        catch(RuntimeException failure){cz.mh.rallye.rallye_backend.identity.api.ApiErrors.logServerFailure(failure);}
    }
}
