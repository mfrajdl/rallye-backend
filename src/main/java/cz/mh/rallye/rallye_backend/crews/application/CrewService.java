package cz.mh.rallye.rallye_backend.crews.application;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import cz.mh.rallye.rallye_backend.crews.persistence.CrewStore;
import cz.mh.rallye.rallye_backend.crews.persistence.CrewTransferStore;
import cz.mh.rallye.rallye_backend.crews.domain.CrewTransfer.Status;
import cz.mh.rallye.rallye_backend.crews.persistence.CrewStore.Crew;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import cz.mh.rallye.rallye_backend.identity.application.AuthService.Principal;
import cz.mh.rallye.rallye_backend.identity.persistence.AccountStore;
import cz.mh.rallye.rallye_backend.identity.persistence.AdminStore;
import cz.mh.rallye.rallye_backend.audit.application.AuditService;
import cz.mh.rallye.rallye_backend.audit.application.AuditService.Event;
import cz.mh.rallye.rallye_backend.shared.persistence.CommandStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CrewService {
    public record View(UUID id,UUID captainAccountId,String name,String registrationPlate,String description,
                       boolean hasPhoto,Instant createdAt,Instant updatedAt,long version) { }
    public record Page(List<View> items,int page,int size,boolean hasMore) { }
    public record Photo(byte[] bytes,String contentType) { }
    private final CrewStore crews;
    private final CrewTransferStore transfers;
    private final AccountStore accounts;
    private final AdminStore roles;
    private final CommandStore commands;
    private final AuditService audit;
    private final PhotoFiles files;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final JsonMapper json=JsonMapper.builder().build();
    public CrewService(CrewStore crews,AccountStore accounts,AdminStore roles,CommandStore commands,
                       AuditService audit,PhotoFiles files,Clock clock,PlatformTransactionManager manager,CrewTransferStore transfers) {
        this.crews=crews;this.accounts=accounts;this.roles=roles;this.commands=commands;
        this.audit=audit;this.files=files;this.clock=clock;this.tx=new TransactionTemplate(manager);
        this.transfers=transfers;
    }
    private <T> T transaction(Supplier<T> body) { return tx.execute(status->body.get()); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    private boolean authorize(Principal actor) {
        roles.lock();
        if(actor==null) throw AuthFailure.credentials();
        accounts.lockAccount(actor.accountId());
        if(!roles.activeVerified(actor.accountId())||!accounts.isActiveSession(actor.sessionId(),actor.accountId(),now()))
            throw AuthFailure.credentials();
        return roles.administrator(actor.accountId());
    }
    private void admin(boolean allowed) { if(!allowed) throw new AuthFailure(403,"ADMIN_REQUIRED"); }
    private Crew accessible(UUID id,Principal actor,boolean admin) {
        Crew crew=crews.find(id);
        if(crew==null||(!admin&&!actor.accountId().equals(crew.captainAccountId()))) throw new AuthFailure(404,"CREW_NOT_FOUND");
        return crew;
    }
    private void version(Crew crew,long expected) {
        if(expected<0) throw new AuthFailure(400,"INVALID_REQUEST");
        if(crew.version()!=expected) throw new AuthFailure(409,"CREW_VERSION_CONFLICT");
    }
    private void captainAvailable(UUID captain) {
        if(captain==null) return;
        if(!roles.activeVerified(captain)) throw new AuthFailure(409,"CAPTAIN_UNAVAILABLE");
        accounts.lockAccount(captain);
        if(!roles.activeVerified(captain)) throw new AuthFailure(409,"CAPTAIN_UNAVAILABLE");
    }
    public static void validate(String name,String plate,String description) {
        if(name==null||name.isBlank()||length(name)>100||length(plate)>32||length(description)>2000)
            throw new AuthFailure(400,"INVALID_REQUEST");
    }
    private static int length(String value) { return value==null?0:value.codePointCount(0,value.length()); }
    public static View view(Crew c) { return new View(c.id(),c.captainAccountId(),c.name(),c.registrationPlate(),c.description(),
            c.photoId()!=null,c.createdAt(),c.updatedAt(),c.version()); }
    private String encode(Object value) { return json.writeValueAsString(value); }
    private View decode(String value) { return json.readValue(value,View.class); }
    public View create(Principal actor,UUID key,String name,String plate,String description,boolean captainSpecified,UUID captain) {
        validate(name,plate,description);
        if(key==null) throw new AuthFailure(400,"INVALID_REQUEST");
        return transaction(()->{
            boolean isAdmin=authorize(actor);
            UUID owner=captainSpecified?captain:actor.accountId();
            if(!Objects.equals(owner,actor.accountId())) admin(isAdmin);
            String request=encode(Arrays.asList(name,plate,description,owner));
            String replay=commands.replay(actor.accountId(),key,"CREW_CREATE",request,now());
            if(replay!=null) return decode(replay);
            captainAvailable(owner);
            UUID id=UUID.randomUUID();
            crews.insert(id,owner,name,plate,description,now());
            audit.crew(actor.accountId(),Event.CREW_CREATED,id,null,owner);
            View result=view(crews.find(id));
            commands.save(actor.accountId(),key,id,"CREW_CREATE",request,encode(result),now());
            return result;
        });
    }
    public Page list(Principal actor,boolean all,UUID captain,boolean orphaned,int page,int size) {
        if(page<0||page>10000||size<1||size>100||(!all&&(captain!=null||orphaned))||(orphaned&&captain!=null))
            throw new AuthFailure(400,"INVALID_REQUEST");
        return transaction(()->{
            boolean isAdmin=authorize(actor);
            if(all) admin(isAdmin);
            var rows=crews.list(all?captain:actor.accountId(),all&&captain==null,orphaned,page,size);
            return new Page(rows.stream().limit(size).map(CrewService::view).toList(),page,size,rows.size()>size);
        });
    }
    public View detail(Principal actor,UUID id) {
        return transaction(()->view(accessible(id,actor,authorize(actor))));
    }
    public View update(Principal actor,UUID id,long expected,String name,String plate,String description) {
        validate(name,plate,description);
        return transaction(()->{
            Crew crew=accessible(id,actor,authorize(actor));version(crew,expected);
            crews.update(id,name,plate,description,now());
            audit.crew(actor.accountId(),Event.CREW_UPDATED,id,null,null);
            return view(crews.find(id));
        });
    }
    public View captain(Principal actor,UUID id,long expected,UUID captain,UUID key) {
        if(key==null) throw new AuthFailure(400,"INVALID_REQUEST");
        return transaction(()->{
            admin(authorize(actor));
            String request=encode(Arrays.asList(id,expected,captain));
            String replay=commands.replay(actor.accountId(),key,"CREW_CAPTAIN",request,now());
            if(replay!=null) return decode(replay);
            captainAvailable(captain);
            Crew crew=accessible(id,actor,true);version(crew,expected);
            if(Objects.equals(crew.captainAccountId(),captain)) throw new AuthFailure(409,"CAPTAIN_UNCHANGED");
            var pending=transfers.pending(id);
            if(pending!=null) {
                Instant at=now();
                if(pending.pendingAt(at)) {
                    var cancelled=transfers.resolve(pending.id(),Status.CANCELLED,at,actor.accountId(),"ADMIN_CAPTAIN_CHANGED");
                    audit.transfer(actor.accountId(),Event.CREW_TRANSFER_CANCELLED,cancelled,"ADMIN_CAPTAIN_CHANGED");
                } else transfers.resolve(pending.id(),Status.EXPIRED,at,null,"EXPIRED");
            }
            crews.captain(id,captain,now());
            audit.crew(actor.accountId(),Event.CREW_CAPTAIN_CHANGED,id,crew.captainAccountId(),captain);
            View result=view(crews.find(id));
            commands.save(actor.accountId(),key,id,"CREW_CAPTAIN",request,encode(result),now());
            commands.relate(actor.accountId(),key,crew.captainAccountId());
            return result;
        });
    }
    public void delete(Principal actor,UUID id,long expected) {
        files.exclusive(()->{
            UUID old=transaction(()->{
                Crew crew=accessible(id,actor,authorize(actor));version(crew,expected);
                var pending=transfers.pending(id);
                if(pending!=null&&pending.pendingAt(now())) throw new AuthFailure(409,"CREW_TRANSFER_PENDING");
                // Participation module must add its history restriction before writing participation references.
                crews.delete(id);audit.crew(actor.accountId(),Event.CREW_DELETED,id,crew.captainAccountId(),null);
                return crew.photoId();
            });
            files.remove(old);return null;
        });
    }
    public View upload(Principal actor,UUID id,long expected,InputStream input) {
        // Authorize before spending resources on image decoding, then recheck within the write transaction.
        detail(actor,id);
        var image=files.validate(input);
        return files.exclusive(()->{
            UUID photo=UUID.randomUUID();
            var previous=new UUID[1];
            View result;
            try {
                result=transaction(()->{
                    Crew crew=accessible(id,actor,authorize(actor));version(crew,expected);
                    previous[0]=crew.photoId();files.write(photo,image);
                    crews.photo(id,photo,image.type(),image.bytes().length,image.width(),image.height(),now());
                    audit.crew(actor.accountId(),Event.CREW_PHOTO_CHANGED,id,null,null);
                    return view(crews.find(id));
                });
            } catch(RuntimeException failure) {
                // An uncertain commit outcome must never delete a potentially referenced file.
                try { if(!transaction(()->crews.referenced(photo))) files.remove(photo); }
                catch(RuntimeException unavailable) { /* Leave for explicit cleanup once DB recovers. */ }
                throw failure;
            }
            files.remove(previous[0]);return result;
        });
    }
    public View deletePhoto(Principal actor,UUID id,long expected) {
        return files.exclusive(()->{
            var old=new UUID[1];
            View result=transaction(()->{
                Crew crew=accessible(id,actor,authorize(actor));version(crew,expected);old[0]=crew.photoId();
                if(old[0]!=null) {
                    crews.photo(id,null,null,null,null,null,now());
                    audit.crew(actor.accountId(),Event.CREW_PHOTO_DELETED,id,null,null);
                }
                return view(crews.find(id));
            });
            files.remove(old[0]);return result;
        });
    }
    public Photo photo(Principal actor,UUID id) {
        return files.exclusive(()->transaction(()->{
            Crew crew=accessible(id,actor,authorize(actor));
            if(crew.photoId()==null) throw new AuthFailure(404,"PHOTO_NOT_FOUND");
            return new Photo(files.read(crew.photoId()),crew.photoType());
        }));
    }
    public int cleanup(Principal actor) {
        return files.exclusive(()->{
            // Persist authorization and intent before irreversible removal of unreferenced files.
            transaction(()->{ admin(authorize(actor));audit.photoCleanup(actor.accountId());return null; });
            return files.cleanup(now().minus(24,ChronoUnit.HOURS),id->transaction(()->crews.referenced(id)));
        });
    }
}
