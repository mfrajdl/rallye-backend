package cz.mh.rallye.rallye_backend.shared.persistence;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CommandRetentionEvidenceTest {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    CommandStore commands=mock(CommandStore.class);
    CommandCleanupStore cleanup=new CommandCleanupStore(jdbc,commands);
    UUID actor=UUID.randomUUID(),key=UUID.randomUUID(),crew=UUID.randomUUID(),previous=UUID.randomUUID();

    void captain(Set<UUID> metadata) {
        var row=new CommandCleanupStore.Row(actor,key,crew,"CREW_CAPTAIN",
                "[\""+crew+"\",0,\""+actor+"\"]",
                "{\"id\":\""+crew+"\",\"captainAccountId\":\""+actor+"\"}",metadata);
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<CommandCleanupStore.Row>>any())).thenReturn(List.of(row));
    }
    @Test void retentionRejectsUnverifiableLegacyCaptainBeforeLookingForPlausibleAudit() {
        captain(null);
        assertThrows(IllegalStateException.class,cleanup::prepareForAuditDeletion);
        verifyNoInteractions(commands);
        verify(jdbc,never()).update(anyString(),org.mockito.ArgumentMatchers.<Object[]>any());
        verify(jdbc,never()).query(contains("audit_event"),org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),org.mockito.ArgumentMatchers.<Object[]>any());
    }
    @Test void completeCaptainReferencesRemainValidWithoutReadingAuditHistory() {
        captain(Set.of(actor,previous));
        assertEquals(0,cleanup.prepareForAuditDeletion());
        verifyNoInteractions(commands);
        verify(jdbc,never()).update(anyString(),org.mockito.ArgumentMatchers.<Object[]>any());
        verify(jdbc,never()).query(contains("audit_event"),org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),org.mockito.ArgumentMatchers.<Object[]>any());
    }
}
