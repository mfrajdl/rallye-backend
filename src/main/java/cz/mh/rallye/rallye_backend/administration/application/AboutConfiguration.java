package cz.mh.rallye.rallye_backend.administration.application;

import java.time.Duration;
import java.util.*;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.core.env.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import cz.mh.rallye.rallye_backend.audit.application.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.participation.domain.RegistrationRules;
import cz.mh.rallye.rallye_backend.participation.application.RegistrationService;
import cz.mh.rallye.rallye_backend.races.application.LifecycleScheduler;
import cz.mh.rallye.rallye_backend.shared.persistence.CommandStore;
import cz.mh.rallye.rallye_backend.simulation.application.SimulationMaintenance;
import cz.mh.rallye.rallye_backend.templates.application.*;

/** Explicit allowlist. Never enumerate properties, bean instances or configuration-property objects. */
@Component
public class AboutConfiguration {
    public record Job(boolean enabled,Long intervalMilliseconds,String cron,String timezone){}
    public record Schedulers(Job lifecycle,Job auditCleanup,Job simulationFileCleanup,Job appleRegistrationMaintenance,Job appleRevocation){}
    public record Limits(int registrationMembersTextCodePoints,int registrationMaxPageSize,int instructionsMaxBytes,TemplateFileContent.Limits files,long multipartMaxFileBytes,long multipartMaxRequestBytes){}
    public record Security(boolean secureCookies,long sessionIdleHours,long rememberedSessionIdleDays,boolean googleProviderEnabled,boolean appleProviderEnabled){}
    public record View(List<String> activeProfiles,List<String> defaultProfiles,Schedulers schedulers,long idempotencyHours,long auditRetentionDays,Limits limits,Security security){}
    private final Environment environment;private final AuditRetentionService audit;private final TemplateFileContent files;private final MultipartProperties multipart;private final ExternalProviders.Settings external;private final AppleClientSecrets.Settings apple;
    public AboutConfiguration(Environment environment,AuditRetentionService audit,TemplateFileContent files,MultipartProperties multipart,ExternalProviders.Settings external,AppleClientSecrets.Settings apple){this.environment=environment;this.audit=audit;this.files=files;this.multipart=multipart;this.external=external;this.apple=apple;}
    private boolean enabled(String key,boolean fallback){return "true".equalsIgnoreCase(environment.getProperty(key,Boolean.toString(fallback)));}
    private boolean provider(ExternalProviders.Provider provider){var c=external.providers().get(provider);return c!=null&&c.enabled();}
    private Job job(Class<?> owner,String method,boolean enabled){
        try{
            // Only these five named scheduling methods are inspected, once per diagnostic request.
            var schedule=owner.getMethod(method).getAnnotation(Scheduled.class);
            Long delay=schedule.fixedDelay()<0?null:schedule.timeUnit().toMillis(schedule.fixedDelay());
            if(!schedule.fixedDelayString().isEmpty())delay=DurationStyle.detectAndParse(environment.resolveRequiredPlaceholders(schedule.fixedDelayString())).toMillis();
            return new Job(enabled,delay,schedule.cron().isEmpty()?null:schedule.cron(),schedule.zone().isEmpty()?null:schedule.zone());
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Diagnostic schedule unavailable",failure);}
    }
    public View read(){
        boolean normalProfile=!environment.acceptsProfiles(Profiles.of("it"));
        return new View(List.of(environment.getActiveProfiles()),List.of(environment.getDefaultProfiles()),new Schedulers(
            job(LifecycleScheduler.class,"run",normalProfile),job(AuditRetentionSchedule.class,"daily",enabled("rallye.audit.cleanup-enabled",true)),
            job(SimulationMaintenance.class,"cleanup",normalProfile&&enabled("rallye.simulation.cleanup-enabled",true)),
            job(AppleRegistrationMaintenance.class,"reconcile",enabled("rallye.auth.apple-registration-maintenance.enabled",true)),
            job(AppleRevocationWorker.class,"run",apple.enabled())),CommandStore.RETENTION_HOURS,audit.retentionDays(),
            new Limits(RegistrationRules.MAX_MEMBERS_TEXT_LENGTH,RegistrationService.MAX_PAGE_SIZE,InstructionsMarkdown.MAX_BYTES,files.limits(),multipart.getMaxFileSize().toBytes(),multipart.getMaxRequestSize().toBytes()),
            new Security(environment.getProperty("rallye.auth.secure-cookies",Boolean.class,true),AuthService.idle(false).toHours(),AuthService.idle(true).toDays(),provider(ExternalProviders.Provider.GOOGLE),provider(ExternalProviders.Provider.APPLE)));
    }
}
