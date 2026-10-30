package cz.mh.rallye.rallye_backend.competition;

import cz.mh.rallye.rallye_backend.competition.domain.RadarMeasurement;
import cz.mh.rallye.rallye_backend.competition.domain.RaceEvaluator.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RadarMeasurementTest {
 private final UUID id=UUID.randomUUID(),run=UUID.randomUUID(),stage=UUID.randomUUID();
 private final Instant at=Instant.parse("2026-10-30T10:00:00Z");
 private Action action(String outcome,long delta,Double distance){return new Action(id,5,"RADAR",stage,outcome,delta,at,new GpsResult(51.123456789,15.987654321,0d,distance,outcome));}
 @Test void evidencePreservesCoordinatesInt64AndRoundedDistance(){
  var m=RadarMeasurement.from(id,run,stage,5,2,action("RADAR_OUTSIDE",9007199254740993L,123.6),at);
  assertEquals(51.123456789,m.latitude());assertEquals(15.987654321,m.longitude());assertEquals(124,m.distanceMeters());assertEquals(9007199254740993L,m.penaltyPoints());assertEquals(run,m.runId());assertEquals(stage,m.stageId());assertFalse(m.completedStage());
 }
 @Test void completingMeasurementDoesNotIncludeOtherCompletionPenalties(){
  var m=RadarMeasurement.from(id,run,stage,5,1,action("RADAR_ACCEPTED",99,1d),at);
  assertTrue(m.completedStage());assertEquals(0,m.penaltyPoints());assertEquals(at,m.measuredAt());
 }
 @Test void invalidOrUnsuccessfulEvidenceCannotBecomeMeasurement(){
  assertThrows(IllegalArgumentException.class,()->RadarMeasurement.from(id,run,stage,5,1,action("GPS_UNAVAILABLE",0,null),at));
  assertThrows(IllegalArgumentException.class,()->RadarMeasurement.from(id,run,UUID.randomUUID(),5,1,action("RADAR_OUTSIDE",7,2d),at));
  assertThrows(IllegalArgumentException.class,()->RadarMeasurement.from(id,run,stage,6,1,action("RADAR_OUTSIDE",7,2d),at));
  assertThrows(IllegalArgumentException.class,()->RadarMeasurement.from(id,run,stage,5,1,action("RADAR_OUTSIDE",7,Double.NaN),at));
 }
}
