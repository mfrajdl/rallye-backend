package cz.mh.rallye.rallye_backend.races.application;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
@Profile("!it")
public class LifecycleScheduler {
 private final LifecycleService lifecycle;
 public LifecycleScheduler(LifecycleService lifecycle){this.lifecycle=lifecycle;}
 @Scheduled(initialDelay=30000,fixedDelay=30000) public void run(){lifecycle.processDue();}
}
