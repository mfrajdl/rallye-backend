package cz.mh.rallye.rallye_backend.administration.application;

import java.lang.management.ManagementFactory;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import cz.mh.rallye.rallye_backend.administration.persistence.AboutStore;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;

@Service
public class AboutService {
    public record Application(String name,String backendVersion,Instant buildTime,String gitCommit){}
    public record RuntimeInfo(String javaVersion,String springBootVersion,Instant serverTime,String timezone,long uptimeSeconds){}
    public record View(Application application,AboutStore.Database database,RuntimeInfo runtime,AboutConfiguration.View configuration){}
    private final AdminStore roles;private final AccountStore accounts;private final AboutStore store;
    private final Environment environment;private final BuildProperties build;private final GitProperties git;
    private final AboutConfiguration configuration;private final Clock clock;
    public AboutService(AdminStore roles,AccountStore accounts,AboutStore store,Environment environment,ObjectProvider<BuildProperties> build,ObjectProvider<GitProperties> git,AboutConfiguration configuration,Clock clock){
        this.roles=roles;this.accounts=accounts;this.store=store;this.environment=environment;this.build=build.getIfAvailable();this.git=git.getIfAvailable();this.configuration=configuration;this.clock=clock;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public View read(AuthService.Principal actor){
        Instant at=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if(actor==null)throw new AuthFailure(401,"UNAUTHENTICATED");
        if(!roles.activeVerified(actor.accountId())||!accounts.isActiveSession(actor.sessionId(),actor.accountId(),at))throw AuthFailure.credentials();
        if(!roles.administrator(actor.accountId()))throw new AuthFailure(403,"ADMIN_REQUIRED");
        String commit=git==null?null:git.getCommitId();if(commit==null&&git!=null)commit=git.get("commit.id.full");
        if(commit!=null&&!commit.matches("[0-9a-fA-F]{40,64}"))commit=null;
        var application=new Application(environment.getProperty("spring.application.name"),build==null?null:build.getVersion(),build==null?null:build.getTime(),commit);
        return new View(application,store.read(),new RuntimeInfo(Runtime.version().toString(),SpringBootVersion.getVersion(),at,ZoneId.systemDefault().getId(),Math.max(0,ManagementFactory.getRuntimeMXBean().getUptime()/1000)),configuration.read());
    }
}
