package cz.mh.rallye.rallye_backend.participation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import cz.mh.rallye.rallye_backend.participation.persistence.RegistrationStore;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OwnRegistrationsStoreTest {
 @Test void crossEventReadUsesExactlyOneBoundedQueryEvenForFullPage(){
  JdbcTemplate jdbc=mock(JdbcTemplate.class);UUID actor=UUID.randomUUID();Instant at=Instant.parse("2026-10-30T10:00:00Z");
  var row=new RegistrationStore.OwnRegistration(UUID.randomUUID(),UUID.randomUUID(),"Event",UUID.randomUUID(),"Crew",null,null,"PLANNED",0);
  when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<RegistrationStore.OwnRegistration>>any(),eq(Timestamp.from(at)),eq(actor),eq(3),eq(4L))).thenReturn(List.of(row,row,row));
  assertEquals(3,new RegistrationStore(jdbc).own(actor,at,2,2).size());
  verify(jdbc).query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<RegistrationStore.OwnRegistration>>any(),eq(Timestamp.from(at)),eq(actor),eq(3),eq(4L));verifyNoMoreInteractions(jdbc);
 }
}
