package cz.mh.rallye.rallye_backend.audit.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="rallye.audit.cleanup-enabled",havingValue="true",matchIfMissing=true)
public class AuditRetentionSchedule {
    private final AuditRetentionService service;
    public AuditRetentionSchedule(AuditRetentionService service) {this.service=service;}
    @Scheduled(cron="0 15 3 * * *",zone="Europe/Prague")
    public void daily() {
        try {service.runScheduled();}
        catch(RuntimeException failure) {cz.mh.rallye.rallye_backend.identity.api.ApiErrors.logServerFailure(failure);}
    }
}
