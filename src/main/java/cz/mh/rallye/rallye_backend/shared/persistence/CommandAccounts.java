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
            if(operation.equals("TEMPLATE_CREATE") || operation.equals("TEMPLATE_STAGE_CREATE") || operation.equals("TEMPLATE_IMAGE_CREATE") || operation.equals("TEMPLATE_DUPLICATE") || operation.equals("TEMPLATE_STAGE_DUPLICATE")) {
                if(operation.equals("TEMPLATE_CREATE")){size(req,3);if(!req.get(0).isString() || !req.get(2).isIntegralNumber())throw invalid();textOrNull(req.get(1));}
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
