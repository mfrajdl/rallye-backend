package cz.mh.rallye.rallye_backend.identity;

import java.util.*;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import cz.mh.rallye.rallye_backend.identity.persistence.PreRaceAccountParticipationFacts;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PreRaceAccountParticipationFactsTest {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    PreRaceAccountParticipationFacts facts=new PreRaceAccountParticipationFacts(jdbc);
    @Test void reviewedV26ReadsRealHistoricalAndRunningFacts(){
        UUID account=UUID.randomUUID();
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(java.util.stream.IntStream.rangeClosed(1,26).mapToObj(Integer::toString).toList());
        var expected=new cz.mh.rallye.rallye_backend.identity.application.AccountParticipationFacts.Facts(true,false);
        when(jdbc.queryForObject(anyString(),org.mockito.ArgumentMatchers.<RowMapper<cz.mh.rallye.rallye_backend.identity.application.AccountParticipationFacts.Facts>>any(),eq(account),eq(account))).thenReturn(expected);
        assertEquals(expected,facts.forAccount(account));
    }
    @Test void unavailableRegistrationFactsFailClosedInReviewedV26(){
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(java.util.stream.IntStream.rangeClosed(1,26).mapToObj(Integer::toString).toList());
        when(jdbc.queryForObject(anyString(),org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),any(),any())).thenThrow(new DataAccessResourceFailureException("Unavailable"));
        assertEquals("PARTICIPATION_CHECK_UNAVAILABLE",assertThrows(AuthFailure.class,()->facts.forAccount(UUID.randomUUID())).code());
    }
    @Test void onlyExplicitPreRaceMigrationSequenceAllowsEmptyFacts() {
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(List.of("1","2","3","4","5","6"));
        var result=facts.forAccount(UUID.randomUUID());assertFalse(result.historical());assertFalse(result.running());
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(List.of("1","2","3","4","5","6","7"));
        assertFalse(facts.forAccount(UUID.randomUUID()).historical());
    }
    @Test void reviewedV8SequenceIsAccepted() {
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(List.of("1","2","3","4","5","6","7","8"));
        assertFalse(facts.forAccount(UUID.randomUUID()).historical());
    }
    @Test void reviewedV9SequenceIsAccepted() {
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(List.of("1","2","3","4","5","6","7","8","9"));
        assertFalse(facts.forAccount(UUID.randomUUID()).historical());
    }
    @Test void reviewedV10SequenceIsAccepted() {
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(List.of("1","2","3","4","5","6","7","8","9","10"));
        assertFalse(facts.forAccount(UUID.randomUUID()).historical());
    }
    @Test void reviewedV11SequenceIsAccepted() {
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(List.of("1","2","3","4","5","6","7","8","9","10","11"));
        assertFalse(facts.forAccount(UUID.randomUUID()).historical());
    }
    @Test void reviewedV12SequenceIsAccepted() {
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(List.of("1","2","3","4","5","6","7","8","9","10","11","12"));
        assertFalse(facts.forAccount(UUID.randomUUID()).historical());
    }
    @Test void reviewedV13SequenceIsAccepted() {
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(List.of("1","2","3","4","5","6","7","8","9","10","11","12","13"));
        assertFalse(facts.forAccount(UUID.randomUUID()).historical());
    }
    @Test void reviewedV14SequenceIsAccepted() {
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(List.of("1","2","3","4","5","6","7","8","9","10","11","12","13","14"));
        assertFalse(facts.forAccount(UUID.randomUUID()).historical());
    }
    @Test void reviewedV15SequenceIsAccepted() {
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(List.of("1","2","3","4","5","6","7","8","9","10","11","12","13","14","15"));
        assertFalse(facts.forAccount(UUID.randomUUID()).historical());
    }
    @Test void reviewedV16SequenceIsAccepted() {
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(java.util.stream.IntStream.rangeClosed(1,16).mapToObj(Integer::toString).toList());
        assertFalse(facts.forAccount(UUID.randomUUID()).historical());
    }
    @Test void reviewedV17SequenceIsAccepted() {
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(java.util.stream.IntStream.rangeClosed(1,17).mapToObj(Integer::toString).toList());
        assertFalse(facts.forAccount(UUID.randomUUID()).historical());
    }
    @Test void reviewedV18SequenceIsAccepted() {
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(java.util.stream.IntStream.rangeClosed(1,18).mapToObj(Integer::toString).toList());
        assertFalse(facts.forAccount(UUID.randomUUID()).historical());
    }
    @Test void reviewedV19SequenceIsAccepted(){when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(java.util.stream.IntStream.rangeClosed(1,19).mapToObj(Integer::toString).toList());assertFalse(facts.forAccount(UUID.randomUUID()).historical());}
    @Test void reviewedV20SequenceIsAccepted(){when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(java.util.stream.IntStream.rangeClosed(1,20).mapToObj(Integer::toString).toList());assertFalse(facts.forAccount(UUID.randomUUID()).historical());}
    @Test void reviewedV21SequenceIsAccepted(){when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(java.util.stream.IntStream.rangeClosed(1,21).mapToObj(Integer::toString).toList());assertFalse(facts.forAccount(UUID.randomUUID()).historical());}
    @Test void reviewedV22SequenceIsAccepted(){when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(java.util.stream.IntStream.rangeClosed(1,22).mapToObj(Integer::toString).toList());assertFalse(facts.forAccount(UUID.randomUUID()).historical());}
    @Test void futureMissingFailedAndRepeatableVersionsAreRejected() {
        for(var versions:List.of(List.of("1","2","3","4","5","6","7","8","9","10","11","12","13","14","15","16","17","18","19","20","21","22","23","24","25","26","27","28","29","30"),List.of("1","2","3","4","5"),List.of("1","2","3","4","5","unsupported"),List.<String>of())) {
            when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(versions);
            var error=assertThrows(AuthFailure.class,()->facts.forAccount(UUID.randomUUID()));assertEquals(503,error.status());assertEquals("PARTICIPATION_CHECK_UNAVAILABLE",error.code());
        }
    }
    @Test void databaseFailureIsUnavailableWithoutLeakingDiagnostics() {
        var cause=new DataAccessResourceFailureException("private diagnostic");
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenThrow(cause);
        var error=assertThrows(AuthFailure.class,()->facts.forAccount(UUID.randomUUID()));assertEquals(503,error.status());assertEquals("PARTICIPATION_CHECK_UNAVAILABLE",error.code());
        assertSame(cause,error.getCause());assertFalse(error.toString().contains("private diagnostic"));
    }
 @Test void reviewedV23SequenceIsAccepted(){when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(java.util.stream.IntStream.rangeClosed(1,23).mapToObj(Integer::toString).toList());assertFalse(facts.forAccount(UUID.randomUUID()).historical());}

    @Test void reviewedV25SequenceIsAccepted(){when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(java.util.stream.IntStream.rangeClosed(1,25).mapToObj(Integer::toString).toList());assertFalse(facts.forAccount(UUID.randomUUID()).historical());}
    @Test void reviewedV24SequenceIsAccepted(){
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(java.util.stream.IntStream.rangeClosed(1,24).mapToObj(Integer::toString).toList());
        assertFalse(facts.forAccount(UUID.randomUUID()).historical());
    }
    @Test void reviewedV27ReadsSameRegistrationFactsAndFutureVersionRemainsClosed(){
        UUID account=UUID.randomUUID();when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(java.util.stream.IntStream.rangeClosed(1,27).mapToObj(Integer::toString).toList());
        var expected=new cz.mh.rallye.rallye_backend.identity.application.AccountParticipationFacts.Facts(true,true);
        when(jdbc.queryForObject(anyString(),org.mockito.ArgumentMatchers.<RowMapper<cz.mh.rallye.rallye_backend.identity.application.AccountParticipationFacts.Facts>>any(),eq(account),eq(account))).thenReturn(expected);assertEquals(expected,facts.forAccount(account));
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(java.util.stream.IntStream.rangeClosed(1,30).mapToObj(Integer::toString).toList());assertEquals(503,assertThrows(AuthFailure.class,()->facts.forAccount(account)).status());
    }
 @Test void reviewedV28KeepsRegistrationFacts(){UUID account=UUID.randomUUID();when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(java.util.stream.IntStream.rangeClosed(1,28).mapToObj(Integer::toString).toList());var expected=new cz.mh.rallye.rallye_backend.identity.application.AccountParticipationFacts.Facts(false,false);when(jdbc.queryForObject(anyString(),org.mockito.ArgumentMatchers.<RowMapper<cz.mh.rallye.rallye_backend.identity.application.AccountParticipationFacts.Facts>>any(),eq(account),eq(account))).thenReturn(expected);assertEquals(expected,facts.forAccount(account));}
    @Test void reviewedV29UsesRealCurrentRunFacts(){UUID account=UUID.randomUUID();when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any())).thenReturn(java.util.stream.IntStream.rangeClosed(1,29).mapToObj(Integer::toString).toList());var expected=new cz.mh.rallye.rallye_backend.identity.application.AccountParticipationFacts.Facts(true,false);when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("JOIN race_run"),org.mockito.ArgumentMatchers.<RowMapper<cz.mh.rallye.rallye_backend.identity.application.AccountParticipationFacts.Facts>>any(),eq(account),eq(account))).thenReturn(expected);assertEquals(expected,facts.forAccount(account));}
}
