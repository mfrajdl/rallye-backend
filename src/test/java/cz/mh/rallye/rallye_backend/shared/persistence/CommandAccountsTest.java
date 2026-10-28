package cz.mh.rallye.rallye_backend.shared.persistence;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class CommandAccountsTest {
    UUID actor=UUID.randomUUID(),target=UUID.randomUUID(),owner=UUID.randomUUID();
    @Test void adminCancellationStoresOnlyIdsAndKnownOutcome() {
        var request="[\""+target+"\"]";
        assertEquals(Set.of(actor,target),CommandAccounts.inspect(actor,target,"ADMIN_ACCOUNT_CANCEL",request,
                "{\"id\":\""+target+"\",\"outcome\":\"DELETED\"}").accounts());
        assertThrows(IllegalStateException.class,()->CommandAccounts.inspect(actor,target,"ADMIN_ACCOUNT_CANCEL",request,
                "{\"id\":\""+target+"\",\"outcome\":\"DELETED\",\"password\":\"must-not-persist\"}"));
    }
    @Test void roleTargetIsAccountButCrewTargetIsNot() {
        assertEquals(Set.of(actor,target),CommandAccounts.inspect(actor,target,"ADMIN_ROLE",null,null).accounts());
        var crew=CommandAccounts.inspect(actor,target,"CREW_CREATE","[\"Crew\",null,null,\""+owner+"\"]",
                "{\"id\":\""+target+"\",\"captainAccountId\":\""+owner+"\"}");
        assertEquals(Set.of(actor,owner),crew.accounts());assertFalse(crew.accounts().contains(target));
    }
    @Test void orphanHasOnlyActor() {
        assertEquals(Set.of(actor),CommandAccounts.inspect(actor,target,"CREW_CREATE","[\"Crew\",null,null,null]",
                "{\"id\":\""+target+"\",\"captainAccountId\":null}").accounts());
    }
    @Test void emailSearchTraversesValuesLiterallyAndCaseInsensitively() {
        var json=JsonMapper.builder().build();
        assertTrue(CommandAccounts.containsEmail(json.readTree("{\"description\":[\"Contact A_%@Example.test now\"]}"),"a_%@example.test"));
        assertFalse(CommandAccounts.containsEmail(json.readTree("{\"a@example.test\":\"unrelated\"}"),"a@example.test"));
    }
    @Test void unknownAndMalformedPayloadsFailWithoutDisclosingContent() {
        for(String operation:List.of("FUTURE","CREW_CREATE","CREW_TRANSFER_ACCEPT")) {
            var failure=assertThrows(IllegalStateException.class,()->CommandAccounts.inspect(actor,target,operation,"secret-payload","{}"));
            assertFalse(failure.toString().contains("secret-payload"));assertNull(failure.getCause());
        }
    }
    @Test void rawTokenCannotBeStoredAsTransferFingerprint() {
        assertThrows(IllegalStateException.class,()->CommandAccounts.inspect(actor,target,"CREW_TRANSFER_ACCEPT",
                "[\""+target+"\",\"raw-token\"]","{\"id\":\""+target+"\",\"crewId\":\""+owner+"\"}"));
    }
    @Test void mismatchedTargetIsRejected() {
        assertThrows(IllegalStateException.class,()->CommandAccounts.inspect(actor,target,"CREW_CREATE","[\"Crew\",null,null,null]",
                "{\"id\":\""+owner+"\",\"captainAccountId\":null}"));
    }
    @Test void transferCreationAndResendIncludeBothAccounts() {
        String result="{\"id\":\""+target+"\",\"crewId\":\""+UUID.randomUUID()+"\",\"fromAccountId\":\""+actor+"\",\"toAccountId\":\""+owner+"\"}";
        assertEquals(Set.of(actor,owner),CommandAccounts.inspect(actor,target,"CREW_TRANSFER_CREATE",
                "[\""+UUID.randomUUID()+"\",\"recipient@example.test\",0]",result).accounts());
        assertEquals(Set.of(actor,owner),CommandAccounts.inspect(actor,target,"CREW_TRANSFER_RESEND",
                "[\""+UUID.randomUUID()+"\",\""+target+"\"]",result).accounts());
    }
    @Test void unknownResponseFieldsCannotHideNewAccountReferences() {
        assertThrows(IllegalStateException.class,()->CommandAccounts.inspect(actor,target,"CREW_CREATE","[\"Crew\",null,null,null]",
                "{\"id\":\""+target+"\",\"captainAccountId\":null,\"futureAccountId\":\""+owner+"\"}"));
    }
    @Test void templateCommandsRelateOnlyActorAndRejectUnknownResultFields() {
        UUID template=UUID.randomUUID();
        String result="{\"id\":\""+target+"\",\"templateId\":\""+template+"\",\"version\":1}";
        String request="[\""+template+"\",0,null,null,0]";
        assertEquals(Set.of(actor),CommandAccounts.inspect(actor,target,"TEMPLATE_STAGE_CREATE",request,result).accounts());
        assertThrows(IllegalStateException.class,()->CommandAccounts.inspect(actor,target,"TEMPLATE_STAGE_CREATE",request,result.replace("\"version\":1","\"code\":\"secret\",\"version\":1")));
        assertEquals(Set.of(actor),CommandAccounts.inspect(actor,template,"TEMPLATE_CREATE","[\"Name\",null,-3]","{\"id\":\""+template+"\",\"templateId\":\""+template+"\",\"version\":0}").accounts());
    }

    @Test void imageCommandContainsOnlyDigestAndNoBinaryOrAccountTargets(){
        UUID template=UUID.randomUUID();String result="{\"id\":\""+target+"\",\"templateId\":\""+template+"\",\"version\":1}";
        String request="[\""+template+"\",1,\""+"a".repeat(64)+"\"]";
        assertEquals(Set.of(actor),CommandAccounts.inspect(actor,target,"TEMPLATE_IMAGE_CREATE",request,result).accounts());
        assertThrows(IllegalStateException.class,()->CommandAccounts.inspect(actor,target,"TEMPLATE_IMAGE_CREATE",request.replace("a".repeat(64),"raw-image"),result));
    }
    @Test void duplicationCommandsUseOnlyResourceIdsAndNeverIncludeCodeMaterial(){
        UUID template=UUID.randomUUID(),stage=UUID.randomUUID();String created="{\"id\":\""+target+"\",\"templateId\":\""+target+"\",\"version\":0}";
        assertEquals(Set.of(actor),CommandAccounts.inspect(actor,target,"TEMPLATE_DUPLICATE","[\""+template+"\"]",created).accounts());
        String request="[\""+template+"\",\""+stage+"\",7]";String result="{\"id\":\""+target+"\",\"templateId\":\""+template+"\",\"version\":8}";
        assertEquals(Set.of(actor),CommandAccounts.inspect(actor,target,"TEMPLATE_STAGE_DUPLICATE",request,result).accounts());
        assertThrows(IllegalStateException.class,()->CommandAccounts.inspect(actor,target,"TEMPLATE_STAGE_DUPLICATE",request,result.replace("\"version\":8","\"codeHash\":\"secret\",\"version\":8")));
    }

    @Test void namedImageUploadUsesOnlyActorNameAndDigest(){
        UUID template=UUID.randomUUID();String request="[\""+template+"\",0,\"mapa.png\",\""+"a".repeat(64)+"\"]";
        String response="{\"fileName\":\"mapa.png\",\"templateId\":\""+template+"\",\"url\":\"/api/v1/race-templates/"+template+"/images/mapa.png\",\"version\":0}";
        assertEquals(Set.of(actor),CommandAccounts.inspect(actor,target,"TEMPLATE_IMAGE_UPLOAD",request,response).accounts());
        assertThrows(IllegalStateException.class,()->CommandAccounts.inspect(actor,target,"TEMPLATE_IMAGE_UPLOAD",request.replace("a".repeat(64),"raw-image"),response));
        assertThrows(IllegalStateException.class,()->CommandAccounts.inspect(actor,target,"TEMPLATE_IMAGE_UPLOAD",request,response.replace("mapa.png","other.png")));
    }

    @Test void fileUploadCommandHasClosedMetadataAndNoSourceMaterial(){
        UUID template=UUID.randomUUID();String request="[\""+template+"\",7,\"guide.md\",\""+"a".repeat(64)+"\"]";String result="{\"fileName\":\"guide.md\",\"templateId\":\""+template+"\",\"url\":\"/api/v1/race-templates/"+template+"/files/guide.md\",\"version\":7,\"status\":\"DRAFT\",\"missingReferences\":[]}";
        assertEquals(Set.of(actor),CommandAccounts.inspect(actor,target,"TEMPLATE_FILE_UPLOAD",request,result).accounts());assertThrows(IllegalStateException.class,()->CommandAccounts.inspect(actor,target,"TEMPLATE_FILE_UPLOAD",request,result.replace("\"missingReferences\":[]","\"source\":\"private body\",\"missingReferences\":[]")));assertThrows(IllegalStateException.class,()->CommandAccounts.inspect(actor,target,"TEMPLATE_FILE_UPLOAD",request.replace("a".repeat(64),"private body"),result));
    }
}
