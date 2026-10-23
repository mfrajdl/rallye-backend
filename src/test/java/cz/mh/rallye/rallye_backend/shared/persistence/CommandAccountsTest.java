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
}
