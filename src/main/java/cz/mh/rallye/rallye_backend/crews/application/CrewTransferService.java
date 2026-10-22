package cz.mh.rallye.rallye_backend.crews.application;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import cz.mh.rallye.rallye_backend.crews.domain.CrewTransfer;
import cz.mh.rallye.rallye_backend.crews.persistence.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.application.AuthService.Principal;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import cz.mh.rallye.rallye_backend.shared.persistence.CommandStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CrewTransferService {
    public record View(UUID id,UUID crewId,UUID fromAccountId,UUID toAccountId,String status,
                       Instant createdAt,Instant expiresAt) { }
    private final CrewTransferLifecycle lifecycle;
    private final CrewTransferStore transfers;
    private final CrewStore crews;
    private final AccountStore accounts;
    private final AdminStore roles;
    private final CommandStore commands;
    private final SecretTokens tokens;
    private final AccountMailSender mail;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final JsonMapper json=JsonMapper.builder().build();
    public CrewTransferService(CrewTransferLifecycle lifecycle,CrewTransferStore transfers,CrewStore crews,
            AccountStore accounts,AdminStore roles,CommandStore commands,SecretTokens tokens,
            AccountMailSender mail,Clock clock,PlatformTransactionManager manager) {
        this.lifecycle=lifecycle;this.transfers=transfers;this.crews=crews;this.accounts=accounts;this.roles=roles;
        this.commands=commands;this.tokens=tokens;this.mail=mail;this.clock=clock;this.tx=new TransactionTemplate(manager);
    }
    private Instant now() {return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
    private void actor(Principal actor) {
        roles.lock();if(actor==null) throw AuthFailure.credentials();
        accounts.lockAccount(actor.accountId());
        if(!roles.activeVerified(actor.accountId())||!accounts.isActiveSession(actor.sessionId(),actor.accountId(),now()))
            throw AuthFailure.credentials();
    }
    private CrewStore.Crew owned(Principal actor,UUID crewId) {
        var crew=crews.find(crewId);
        if(crew==null||!actor.accountId().equals(crew.captainAccountId())) throw new AuthFailure(404,"CREW_NOT_FOUND");
        return crew;
    }
    private View view(CrewTransfer transfer) {
        return new View(transfer.id(),transfer.crewId(),transfer.fromAccountId(),transfer.toAccountId(),
                transfer.status().name(),transfer.createdAt(),transfer.expiresAt());
    }
    private void sendAfterCommit(String email,String name,CrewTransfer transfer,String secret) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {mail.sendTransfer(email,name,transfer.id(),secret,transfer.expiresAt());}
                catch(RuntimeException failure) {
                    org.slf4j.LoggerFactory.getLogger(CrewTransferService.class)
                            .warn("Crew transfer email delivery failed; resend can be requested.");
                }
            }
        });
    }
    public View create(Principal actor,UUID crewId,UUID key,String email,long version) {
        if(key==null||crewId==null||email==null||email.isBlank()||email.length()>254||version<0)
            throw new AuthFailure(400,"INVALID_REQUEST");
        String normalized=AuthService.normalizeEmail(email);
        return tx.execute(status->{
            actor(actor);
            String request=json.writeValueAsString(List.of(crewId,normalized,version));
            String replay=commands.replay(actor.accountId(),key,"CREW_TRANSFER_CREATE",request,now());
            if(replay!=null) {owned(actor,crewId);return json.readValue(replay,View.class);}
            // Resolve and lock accounts before locking the crew, following the shared lock order.
            var candidate=accounts.byEmail(normalized);
            if(candidate!=null) accounts.lockAccount(candidate.id());
            var crew=owned(actor,crewId);
            if(candidate==null||!roles.activeVerified(candidate.id())) throw new AuthFailure(409,"CAPTAIN_UNAVAILABLE");
            String secret=tokens.create();
            var transfer=lifecycle.create(actor,crewId,candidate.id(),version,tokens.hash(secret)).transfer();
            View result=view(transfer);
            commands.save(actor.accountId(),key,transfer.id(),"CREW_TRANSFER_CREATE",request,json.writeValueAsString(result),now());
            sendAfterCommit(candidate.email(),crew.name(),transfer,secret);
            return result;
        });
    }
    public View resend(Principal actor,UUID crewId,UUID transferId,UUID key) {
        if(key==null||crewId==null||transferId==null) throw new AuthFailure(400,"INVALID_REQUEST");
        return tx.execute(status->{
            actor(actor);
            String request=json.writeValueAsString(List.of(crewId,transferId));
            String replay=commands.replay(actor.accountId(),key,"CREW_TRANSFER_RESEND",request,now());
            if(replay!=null) {owned(actor,crewId);return json.readValue(replay,View.class);}
            var initial=transfers.find(transferId);
            // Do not reveal request existence to another crew's caller.
            if(initial==null||!initial.crewId().equals(crewId)||!initial.fromAccountId().equals(actor.accountId()))
                throw new AuthFailure(404,"TRANSFER_NOT_FOUND");
            var recipient=accounts.lockAccount(initial.toAccountId());
            var crew=owned(actor,crewId);
            var pending=transfers.lock(transferId);pending.requirePending(now());
            if(recipient==null||!roles.activeVerified(recipient.id())) throw new AuthFailure(409,"CAPTAIN_UNAVAILABLE");
            String secret=tokens.create();var rotated=transfers.rotate(transferId,tokens.hash(secret));
            View result=view(rotated);
            commands.save(actor.accountId(),key,transferId,"CREW_TRANSFER_RESEND",request,json.writeValueAsString(result),now());
            sendAfterCommit(recipient.email(),crew.name(),rotated,secret);
            return result;
        });
    }
}
