package cz.mh.rallye.rallye_backend.identity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import cz.mh.rallye.rallye_backend.audit.application.*;
import cz.mh.rallye.rallye_backend.audit.persistence.*;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.*;
import cz.mh.rallye.rallye_backend.shared.persistence.CommandCleanupStore;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AuditMaintenanceTest {
    AdminStore roles=mock(AdminStore.class);AccountStore accounts=mock(AccountStore.class);
    AuditExportStore rows=mock(AuditExportStore.class);AuditService audit=mock(AuditService.class);
    AuditRetentionStore retention=mock(AuditRetentionStore.class);CommandCleanupStore commands=mock(CommandCleanupStore.class);
    AuditTransactionTest.RecordingTransactions manager=new AuditTransactionTest.RecordingTransactions();
    MutableClock clock=new MutableClock();
    AuthService.Principal actor=new AuthService.Principal(UUID.randomUUID(),UUID.randomUUID(),true);
    AuditExportFilter filter=new AuditExportFilter(null,null,null,null,null);
    AuditExportService exporter() {
        when(roles.administrator(actor.accountId())).thenReturn(true);when(accounts.isActiveSession(any(),any(),any())).thenReturn(true);
        return new AuditExportService(roles,accounts,rows,audit,clock,manager);
    }
    AuditRetentionService cleaner(long days) {return new AuditRetentionService(days,retention,commands,audit,clock,manager);}
    @Test void csvQuotesAndProtectsFormulasWithoutDroppingOriginalText() throws Exception {
        var writer=new StringWriter();
        AuditCsv.row(writer,"a;\"b\r\nc","=SUM(1;2)","  +cmd","\ufeff@cmd","normal");
        assertEquals("\"a;\"\"b\r\nc\";\"'=SUM(1;2)\";\"'  +cmd\";\"'\ufeff@cmd\";\"normal\"\r\n",writer.toString());
    }
    @Test void pragueTimesDistinguishSummerWinterAndRepeatedAutumnHour() {
        assertEquals("2026-01-15T13:00:00+01:00",AuditCsv.time(Instant.parse("2026-01-15T12:00:00Z")));
        assertEquals("2026-07-15T14:00:00+02:00",AuditCsv.time(Instant.parse("2026-07-15T12:00:00Z")));
        assertEquals("2026-10-25T02:30:00+02:00",AuditCsv.time(Instant.parse("2026-10-25T00:30:00Z")));
        assertEquals("2026-10-25T02:30:00+01:00",AuditCsv.time(Instant.parse("2026-10-25T01:30:00Z")));
        assertTrue(AuditCsv.metadata("{\"cutoff\":\"2026-01-15T12:00:00Z\",\"nested\":[\"český; text\\n\\\"\"]}").contains("2026-01-15T13:00:00+01:00"));
    }
    @Test void streamOwnsRepeatableReadAndRecordsCountInSeparateWriteTransaction() throws Exception {
        var service=exporter();
        when(rows.write(eq(filter),any())).thenAnswer(call->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            AuditCsv.row(call.getArgument(1),"row");return 1L;
        });
        doAnswer(call->{assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());return null;})
                .when(audit).maintenance(any(),any(),any());
        var output=new ByteArrayOutputStream();service.stream(actor,filter,output);
        assertTrue(output.toString(StandardCharsets.UTF_8).startsWith("\ufeff\"id\";"));
        verify(audit).maintenance(eq(actor.accountId()),eq(AuditService.Event.AUDIT_EXPORT),argThat(data->data.get("exportedCount").equals(1L)));
        assertEquals(2,manager.commits);verify(roles,never()).lock();
    }
    @Test void failedGenerationOrDisconnectedClientDoesNotWriteExportAudit() throws Exception {
        var service=exporter();
        OutputStream disconnected=new OutputStream(){public void write(int value)throws IOException{throw new IOException("Client disconnected");}};
        assertThrows(IOException.class,()->service.stream(actor,filter,disconnected));
        verifyNoInteractions(audit);assertEquals(1,manager.rollbacks);
    }
    @Test void exportAuditIsBestEffortAndDoesNotCorruptFinishedCsv() throws Exception {
        var service=exporter();doThrow(new IllegalStateException("private detail")).when(audit).maintenance(any(),any(),any());
        var bytes=new ByteArrayOutputStream();assertDoesNotThrow(()->service.stream(actor,filter,bytes));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("metadata"));assertEquals(1,manager.commits);assertEquals(1,manager.rollbacks);
    }
    @Test void authorizationIsRecheckedAtStartOfStreaming() throws Exception {
        var service=exporter();service.prepare(actor,filter);when(roles.administrator(actor.accountId())).thenReturn(false);
        var bytes=new ByteArrayOutputStream();assertEquals(403,assertThrows(AuthFailure.class,()->service.stream(actor,filter,bytes)).status());
        assertEquals(0,bytes.size());verifyNoInteractions(rows,audit);
    }
    @Test void invalidFiltersFailBeforeOpeningSnapshot() {
        var invalid=new AuditExportFilter(clock.instant(),clock.instant(),null,null,null);
        assertEquals(400,assertThrows(AuthFailure.class,invalid::validate).status());
    }
    @Test void retentionRejectsInvalidConfigurationBeforeAnyDatabaseAccess() {
        for(long days:new long[]{-1,0,89,400_000_000_000L,Long.MAX_VALUE})assertThrows(RuntimeException.class,()->cleaner(days));
        assertDoesNotThrow(()->cleaner(90));verifyNoInteractions(retention,commands,audit);
    }
    @Test void retentionBackfillsBeforeDeleteAndAuditsAtomically() {
        when(retention.claim(any())).thenReturn(true);when(retention.deleteBefore(any())).thenReturn(12L);
        assertTrue(cleaner(365).runScheduled());
        var order=inOrder(retention,commands,audit);
        order.verify(retention).claim(clock.instant().atZone(AuditCsv.ZONE).toLocalDate());
        order.verify(commands).prepareForAuditDeletion();
        order.verify(retention).deleteBefore(clock.instant().minus(Duration.ofDays(365)));
        order.verify(audit).maintenance(isNull(),eq(AuditService.Event.AUDIT_CLEANUP),argThat(data->data.get("deletedCount").equals(12L)));
        order.verify(retention).success(any());assertEquals(1,manager.commits);
    }
    @Test void busyLockOrCompletedDayProducesNoNewAudit() {
        assertFalse(cleaner(365).runScheduled());verifyNoInteractions(commands,audit);verify(retention,never()).deleteBefore(any());
    }
    @Test void unknownLegacyFormatOrAuditFailureRollsBackRetention() {
        when(retention.claim(any())).thenReturn(true);
        doThrow(new IllegalStateException("Unknown legacy record")).when(commands).prepareForAuditDeletion();
        assertThrows(IllegalStateException.class,()->cleaner(365).runScheduled());verify(retention,never()).deleteBefore(any());
        reset(commands);doThrow(new IllegalStateException("Audit failed")).when(audit).maintenance(any(),any(),any());
        assertThrows(IllegalStateException.class,()->cleaner(365).runScheduled());
        verify(retention,never()).success(any());assertEquals(2,manager.rollbacks);
    }
    @Test void dailyTriggerDoesNotRetryImmediatelyAndRunsAfterDstTransition() throws Exception {
        var service=mock(AuditRetentionService.class);when(service.runScheduled()).thenThrow(new IllegalStateException("Failure"));
        new AuditRetentionSchedule(service).daily();verify(service,times(1)).runScheduled();
        var annotation=AuditRetentionSchedule.class.getMethod("daily").getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertEquals("Europe/Prague",annotation.zone());
        var cron=org.springframework.scheduling.support.CronExpression.parse(annotation.cron());
        var before=ZonedDateTime.of(2026,3,29,0,0,0,0,AuditCsv.ZONE);
        assertEquals("2026-03-29T03:15+02:00[Europe/Prague]",cron.next(before).toString());
    }
}
