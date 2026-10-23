package cz.mh.rallye.rallye_backend.audit.application;

import java.time.Instant;
import java.util.*;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;

public record AuditExportFilter(Instant from,Instant until,AuditService.Event eventType,UUID actorId,UUID targetAccountId) {
    public void validate() {
        if(from!=null && until!=null && !from.isBefore(until))throw new AuthFailure(400,"INVALID_AUDIT_FILTER");
    }
    public Map<String,Object> metadata() {
        var values=new LinkedHashMap<String,Object>();
        if(from!=null)values.put("from",from.toString());if(until!=null)values.put("until",until.toString());
        if(eventType!=null)values.put("eventType",eventType.name());if(actorId!=null)values.put("actorId",actorId.toString());
        if(targetAccountId!=null)values.put("targetAccountId",targetAccountId.toString());
        return values;
    }
}
