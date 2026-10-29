package cz.mh.rallye.rallye_backend.simulation.application;
import org.springframework.stereotype.Component;import org.springframework.context.annotation.Profile;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.scheduling.annotation.Scheduled;
@Component @Profile("!it") @ConditionalOnProperty(name="rallye.simulation.cleanup-enabled",havingValue="true",matchIfMissing=true)
public class SimulationMaintenance {
 private final SimulationService service;public SimulationMaintenance(SimulationService service){this.service=service;}
 @Scheduled(initialDelay=600000,fixedDelayString="${rallye.simulation.cleanup-delay-ms:600000}") public void cleanup(){try{service.cleanup();}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).error("Simulation cleanup failed");cz.mh.rallye.rallye_backend.identity.api.ApiErrors.logServerFailure(failure);}}
}
