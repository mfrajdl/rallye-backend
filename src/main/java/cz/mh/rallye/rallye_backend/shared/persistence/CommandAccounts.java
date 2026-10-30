package cz.mh.rallye.rallye_backend.shared.persistence;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Closed list of persisted command formats. Never include payloads in diagnostics. */
final class CommandAccounts {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    record Content(Set<UUID> accounts, JsonNode request, JsonNode result) { }
    static Content inspect(UUID actor,UUID target,String operation,String request,String result) {
        try {
            var ids=new HashSet<UUID>();ids.add(actor);
            if(operation.equals("ADMIN_ROLE")) {
                if(request!=null || result!=null) throw invalid();
                ids.add(target);return new Content(ids,null,null);
            }
            JsonNode req=JSON.readTree(request),res=JSON.readTree(result);
            if(!req.isArray() || !res.isObject()) throw invalid();
            if(Set.of("RACE_EVENT_STARTED","RACE_EVENT_ENDED","RACE_EVENT_RESUMED").contains(operation)){
                size(req,3);if(!target.equals(requiredId(req.get(0)))||!req.get(1).isIntegralNumber()||!req.get(1).canConvertToLong()||req.get(1).longValue()<0)throw invalid();textOrNull(req.get(2));
                if(!new HashSet<>(res.propertyNames()).equals(Set.of("raceEventId","version","transition"))||!target.equals(requiredId(res.get("raceEventId")))||!res.get("version").isIntegralNumber())throw invalid();
                var h=res.get("transition");
                if(!h.isObject()||!new HashSet<>(h.propertyNames()).equals(Set.of("id","raceEventId","sequence","kind","type","fromStatus","toStatus","effectiveAt","processedAt","trigger","cause","actorKind","actorAccountId","versionBefore","versionAfter","scheduledStartAtBefore","scheduledStartAtAfter","endAtBefore","endAtAfter","periodId","closureRevision","invalidatedClosureRevision"))||!target.equals(requiredId(h.get("raceEventId")))||!operation.equals("RACE_EVENT_"+h.get("type").asString())||!actor.equals(requiredId(h.get("actorAccountId"))))throw invalid();
                if(!h.get("kind").asString().equals("TRANSITION")||!h.get("trigger").asString().equals("ADMIN")||!h.get("cause").asString().equals("MANUAL")||!h.get("actorKind").asString().equals("USER"))throw invalid();
                String type=h.get("type").asString(),from=h.get("fromStatus").asString(),to=h.get("toStatus").asString();
                if(!(type.equals("STARTED")&&from.equals("PLANNED")&&to.equals("RUNNING")||type.equals("ENDED")&&Set.of("PLANNED","RUNNING").contains(from)&&to.equals("ENDED")||type.equals("RESUMED")&&from.equals("ENDED")&&to.equals("RUNNING")))throw invalid();
                if(!h.get("sequence").isIntegralNumber()||!h.get("sequence").canConvertToLong()||h.get("sequence").longValue()<1||!res.get("version").canConvertToLong()||!h.get("versionBefore").canConvertToLong()||!h.get("versionAfter").canConvertToLong())throw invalid();
                for(String field:List.of("effectiveAt","processedAt","scheduledStartAtBefore","scheduledStartAtAfter","endAtBefore","endAtAfter")){textOrNull(h.get(field));if(!h.get(field).isNull())java.time.Instant.parse(h.get(field).asString());}
                if(h.get("effectiveAt").isNull()||h.get("processedAt").isNull()||!h.get("effectiveAt").equals(h.get("processedAt")))throw invalid();
                if(h.get("periodId").isNull()){if(!type.equals("ENDED")||!from.equals("PLANNED"))throw invalid();}else requiredId(h.get("periodId"));
                for(String field:List.of("closureRevision","invalidatedClosureRevision")){var value=h.get(field);boolean expected=field.equals("closureRevision")?type.equals("ENDED"):type.equals("RESUMED");if(expected?(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<1):!value.isNull())throw invalid();}
                requiredId(h.get("id"));if(!h.get("versionBefore").isIntegralNumber()||h.get("versionBefore").longValue()!=req.get(1).longValue()||!h.get("versionAfter").isIntegralNumber()||h.get("versionAfter").longValue()!=res.get("version").longValue()||h.get("versionAfter").longValue()!=Math.addExact(h.get("versionBefore").longValue(),1))throw invalid();
                return new Content(ids,req,res);
            }
            if(operation.equals("CREW_REGISTRATION_CREATE")){
                size(req,4);requiredId(req.get(0));requiredId(req.get(1));
                if(!req.get(2).isString()||!req.get(2).asString().matches("[0-9a-f]{64}")||!req.get(3).isIntegralNumber()||!req.get(3).canConvertToInt()||req.get(3).intValue()<0)throw invalid();
                if(!new HashSet<>(res.propertyNames()).equals(Set.of("id","version"))||!target.equals(requiredId(res.get("id")))||!res.get("version").isIntegralNumber()||!res.get("version").canConvertToLong()||res.get("version").longValue()!=0)throw invalid();
                return new Content(ids,req,res); // Only the actor is an account. No open member names in the ledger.
            }
            if(operation.equals("TEMPLATE_IMPORT")){
                size(req,1);if(!req.get(0).isString()||!req.get(0).asString().matches("[0-9a-f]{64}"))throw invalid();
                if(!new HashSet<>(res.propertyNames()).equals(Set.of("id","templateId","version"))||!target.equals(requiredId(res.get("id")))||!target.equals(requiredId(res.get("templateId")))||!res.get("version").isIntegralNumber())throw invalid();
                return new Content(ids,req,res);
            }
            if(operation.equals("SIMULATION_CREATE")){
                size(req,3);requiredId(req.get(0));if(!req.get(1).isIntegralNumber()||req.get(1).longValue()<0)throw invalid();textOrNull(req.get(2));
                if(!new HashSet<>(res.propertyNames()).equals(Set.of("id","version"))||!target.equals(requiredId(res.get("id")))||!res.get("version").isIntegralNumber())throw invalid();
                return new Content(ids,req,res);
            }
            if(operation.equals("RACE_EVENT_CREATE")){
                size(req,6);requiredId(req.get(0));if(!req.get(1).isIntegralNumber()||req.get(1).longValue()<0||!req.get(2).isString())throw invalid();
                for(int i=3;i<6;i++)textOrNull(req.get(i));
                if(!new HashSet<>(res.propertyNames()).equals(Set.of("id","version"))||!target.equals(requiredId(res.get("id")))||!res.get("version").isIntegralNumber())throw invalid();
                return new Content(ids,req,res); // source/target are template/event UUIDs, never accounts.
            }
            if(operation.equals("TEMPLATE_FILE_UPLOAD")){
                size(req,4);UUID template=requiredId(req.get(0));
                if(!req.get(1).isIntegralNumber()||!req.get(2).isString()||!req.get(3).isString()||!req.get(3).asString().matches("[0-9a-f]{64}"))throw invalid();
                if(!new HashSet<>(res.propertyNames()).equals(Set.of("fileName","templateId","url","version","status","missingReferences"))||!template.equals(requiredId(res.get("templateId")))||!req.get(2).asString().equals(res.get("fileName").asString())||!res.get("url").isString()||!res.get("version").isIntegralNumber()||!Set.of("DRAFT","READY").contains(res.get("status").asString())||!res.get("missingReferences").isArray())throw invalid();
                for(var warning:res.get("missingReferences")){if(!new HashSet<>(warning.propertyNames()).equals(Set.of("code","sourceType","sourceStageId","sourceFileName","targetFileName","referenceType"))||!warning.get("code").asString().equals("FILE_NOT_FOUND"))throw invalid();}
                return new Content(ids,req,res);
            }
            if(operation.equals("TEMPLATE_IMAGE_UPLOAD")){
                size(req,4);UUID template=requiredId(req.get(0));
                if(!req.get(1).isIntegralNumber()||!req.get(2).isString()||!req.get(3).isString()||!req.get(3).asString().matches("[0-9a-f]{64}"))throw invalid();
                if(!new HashSet<>(res.propertyNames()).equals(Set.of("fileName","templateId","url","version"))||!template.equals(requiredId(res.get("templateId")))||!res.get("fileName").isString()||!res.get("url").isString()||!res.get("version").isIntegralNumber()||!req.get(2).asString().equals(res.get("fileName").asString()))throw invalid();
                return new Content(ids,req,res);
            }
            if(operation.equals("TEMPLATE_CREATE") || operation.equals("TEMPLATE_STAGE_CREATE") || operation.equals("TEMPLATE_IMAGE_CREATE") || operation.equals("TEMPLATE_DUPLICATE") || operation.equals("TEMPLATE_STAGE_DUPLICATE")) {
                if(operation.equals("TEMPLATE_CREATE")){if(req.size()!=3&&req.size()!=4)throw invalid();if(req.size()==4&&!req.get(3).isIntegralNumber())throw invalid();if(!req.get(0).isString() || !req.get(2).isIntegralNumber())throw invalid();textOrNull(req.get(1));}
                else if(operation.equals("TEMPLATE_DUPLICATE")){size(req,1);requiredId(req.get(0));}
                else if(operation.equals("TEMPLATE_STAGE_DUPLICATE")){size(req,3);requiredId(req.get(0));requiredId(req.get(1));if(!req.get(2).isIntegralNumber() || req.get(2).longValue()<0)throw invalid();}
                else if(operation.equals("TEMPLATE_IMAGE_CREATE")){size(req,3);requiredId(req.get(0));if(!req.get(1).isIntegralNumber() || !req.get(2).isString() || !req.get(2).asString().matches("[0-9a-f]{64}"))throw invalid();}
                else {size(req,5);requiredId(req.get(0));if(!req.get(1).isIntegralNumber())throw invalid();textOrNull(req.get(2));for(int i=3;i<5;i++)if(!req.get(i).isNull()&&!req.get(i).isIntegralNumber())throw invalid();}
                if(!new HashSet<>(res.propertyNames()).equals(Set.of("id","templateId","version")) || !target.equals(requiredId(res.get("id"))) || !res.get("version").isIntegralNumber())throw invalid();
                UUID template=requiredId(res.get("templateId"));
                if((operation.equals("TEMPLATE_CREATE") || operation.equals("TEMPLATE_DUPLICATE"))?!template.equals(target):!template.equals(requiredId(req.get(0))))throw invalid();
                return new Content(ids,req,res); // Target IDs are templates/stages, never user accounts.
            }
            switch(operation) {
                case "ADMIN_ACCOUNT_CANCEL" -> {size(req,1);ids.add(requiredId(req.get(0)));
                    if(!target.equals(requiredId(req.get(0))) || res.get("outcome")==null || !Set.of("DELETED","ANONYMIZED").contains(res.get("outcome").asString()))throw invalid();}
                case "CREW_CREATE" -> {size(req,4);if(!req.get(0).isString())throw invalid();textOrNull(req.get(1));textOrNull(req.get(2));id(ids,req.get(3));id(ids,res.get("captainAccountId"));}
                case "CREW_CAPTAIN" -> {size(req,3);requiredId(req.get(0));if(!req.get(1).isIntegralNumber())throw invalid();id(ids,req.get(2));id(ids,res.get("captainAccountId"));}
                case "CREW_TRANSFER_CREATE" -> {size(req,3);requiredId(req.get(0));if(!req.get(1).isString() || !req.get(2).isIntegralNumber())throw invalid();transfer(ids,res);}
                case "CREW_TRANSFER_RESEND" -> {size(req,2);requiredId(req.get(0));requiredId(req.get(1));transfer(ids,res);}
                case "CREW_TRANSFER_ACCEPT","CREW_TRANSFER_REJECT","CREW_TRANSFER_CANCEL" -> {
                    size(req,2);requiredId(req.get(0));
                    if(!req.get(1).isString() || !(operation.endsWith("CANCEL") ? req.get(1).asString().isEmpty() : req.get(1).asString().matches("[0-9a-f]{64}")))throw invalid();
                    requiredId(res.get("crewId"));
                }
                default -> throw invalid();
            }
            Set<String> fields=operation.equals("ADMIN_ACCOUNT_CANCEL")?Set.of("id","outcome")
                    :operation.equals("CREW_CREATE") || operation.equals("CREW_CAPTAIN")
                    ?Set.of("id","captainAccountId","name","registrationPlate","description","hasPhoto","createdAt","updatedAt","version")
                    :operation.equals("CREW_TRANSFER_CREATE") || operation.equals("CREW_TRANSFER_RESEND")
                    ?Set.of("id","crewId","fromAccountId","toAccountId","status","createdAt","expiresAt")
                    :Set.of("id","crewId","status","crewVersion","resolvedAt");
            for(String field:res.propertyNames())if(!fields.contains(field))throw invalid();
            if(!target.equals(requiredId(res.get("id"))))throw invalid();
            return new Content(ids,req,res);
        } catch(RuntimeException failure) {throw invalid();}
    }
    private static void transfer(Set<UUID> ids,JsonNode res) {
        requiredId(res.get("crewId"));ids.add(requiredId(res.get("fromAccountId")));ids.add(requiredId(res.get("toAccountId")));
    }
    private static void size(JsonNode node,int size) {if(node.size()!=size)throw invalid();}
    private static void textOrNull(JsonNode node) {if(!node.isString() && !node.isNull())throw invalid();}
    private static UUID requiredId(JsonNode node) {if(node==null || !node.isString())throw invalid();return UUID.fromString(node.asString());}
    private static void id(Set<UUID> ids,JsonNode node) {if(node==null)throw invalid();if(!node.isNull())ids.add(requiredId(node));}
    static boolean containsEmail(JsonNode node,String email) {
        if(node==null || email==null || email.isBlank())return false;
        if(node.isString())return node.asString().toLowerCase(Locale.ROOT).contains(email.toLowerCase(Locale.ROOT));
        for(JsonNode child:node)if(containsEmail(child,email))return true;
        return false;
    }
    static IllegalStateException invalid() {return new IllegalStateException("Unrecognized idempotency record format; cancellation aborted");}
}
