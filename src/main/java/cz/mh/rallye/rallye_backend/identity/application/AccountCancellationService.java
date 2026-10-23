package cz.mh.rallye.rallye_backend.identity.application;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import cz.mh.rallye.rallye_backend.shared.persistence.*;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import cz.mh.rallye.rallye_backend.audit.application.AuditService.Event;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountCancellationService {
    private final AdminStore roles;
    private final AccountStore accounts;
    private final AccountCancellationStore cancellation;
    private final AccountParticipationFacts participation;
    private final CancellationIdentityCheck identity;
    private final CommandStore commands;
    private final CommandCleanupStore cleanup;
    private final AuditService audit;
    private final Clock clock;
    private final cz.mh.rallye.rallye_backend.crews.persistence.CrewTransferStore transfers;
    public AccountCancellationService(AdminStore roles,AccountStore accounts,AccountCancellationStore cancellation,
            AccountParticipationFacts participation,CancellationIdentityCheck identity,CommandStore commands,
            CommandCleanupStore cleanup,AuditService audit,Clock clock,cz.mh.rallye.rallye_backend.crews.persistence.CrewTransferStore transfers) {
        this.roles=roles;this.accounts=accounts;this.cancellation=cancellation;this.participation=participation;
        this.identity=identity;this.commands=commands;this.cleanup=cleanup;this.audit=audit;this.clock=clock;this.transfers=transfers;
    }
    @Transactional
    public void self(AuthService.Principal actor,String password,UUID key) {
        requireKey(key);authorize(actor,false);
        var account=accounts.lockAccount(actor.accountId());
        identity.verify(account,password);
        commands.replay(actor.accountId(),key,"SELF_ACCOUNT_CANCEL","[\""+actor.accountId()+"\"]",clock.instant());
        cancel(actor.accountId(),actor.accountId(),false,key);
    }
    @Transactional
    public void administrator(AuthService.Principal actor,UUID target,UUID key) {
        requireKey(key);authorize(actor,true);
        if(actor.accountId().equals(target))throw new AuthFailure(403,"SELF_ACCOUNT_CANCELLATION_FORBIDDEN");
        String request="[\""+target+"\"]";
        if(commands.replay(actor.accountId(),key,"ADMIN_ACCOUNT_CANCEL",request,clock.instant())!=null)return;
        cancel(actor.accountId(),target,true,key);
    }
    private void authorize(AuthService.Principal actor,boolean admin) {
        roles.lock();
        if(actor==null)throw AuthFailure.credentials();
        var account=accounts.lockAccount(actor.accountId());
        if(account==null || !"ACTIVE".equals(account.status()) || !accounts.isActiveSession(actor.sessionId(),actor.accountId(),clock.instant()))
            throw AuthFailure.credentials();
        if(admin && !roles.administrator(actor.accountId()))throw new AuthFailure(403,"ADMIN_REQUIRED");
    }
    private void cancel(UUID actor,UUID target,boolean admin,UUID key) {
        var state=cancellation.lock(target);
        if(state==null || "ANONYMIZED".equals(state.status()))throw new AuthFailure(404,"ACCOUNT_NOT_FOUND");
        if(roles.administrator(target) && roles.count()<=1)throw new AuthFailure(409,"LAST_ADMIN_REQUIRED");
        AccountParticipationFacts.Facts facts;
        try {facts=participation.forAccount(target);}
        catch(RuntimeException failure) {
            if(failure instanceof AuthFailure unavailable && unavailable.status()==503
                    && "PARTICIPATION_CHECK_UNAVAILABLE".equals(unavailable.code()))throw unavailable;
            throw new AuthFailure(503,"PARTICIPATION_CHECK_UNAVAILABLE",failure);
        }
        if(facts==null)throw new AuthFailure(503,"PARTICIPATION_CHECK_UNAVAILABLE");
        if(facts.running())throw new AuthFailure(409,"ACCOUNT_PARTICIPATION_RUNNING");
        var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var pending=cancellation.pendingTransferIds(target);
        var owned=cancellation.ownedCrewIds(target);
        cancellation.closePendingTransfers(target,actor,now);
        for(UUID id:pending) {
            var transfer=transfers.find(id);
            if(transfer.status()==cz.mh.rallye.rallye_backend.crews.domain.CrewTransfer.Status.CANCELLED)
                audit.transfer(actor,Event.CREW_TRANSFER_CANCELLED,transfer,"ACCOUNT_CANCELLED");
        }
        cancellation.detachCrews(target,now);
        for(UUID id:owned)audit.crew(actor,Event.CREW_CAPTAIN_CHANGED,id,target,null);
        cancellation.deleteIdentities(target);cancellation.deleteSessions(target);cancellation.deleteTokens(target);
        if(cancellation.deleteRoles(target)>0)audit.record(actor,Event.ADMIN_REVOKED,target);
        cleanup.deleteRelated(target,state.email());
        if(facts.historical())cancellation.anonymize(target,now);else cancellation.deleteAccount(target);
        audit.record(actor,facts.historical()?Event.ACCOUNT_ANONYMIZED:Event.ACCOUNT_DELETED,target);
        // Only UUIDs and a nonpersonal outcome survive. Self cancellation stores no password or replay.
        if(admin)commands.save(actor,key,target,"ADMIN_ACCOUNT_CANCEL","[\""+target+"\"]",
                "{\"id\":\""+target+"\",\"outcome\":\""+(facts.historical()?"ANONYMIZED":"DELETED")+"\"}",now);
    }
    private static void requireKey(UUID key) {if(key==null)throw new AuthFailure(400,"INVALID_REQUEST");}
}
