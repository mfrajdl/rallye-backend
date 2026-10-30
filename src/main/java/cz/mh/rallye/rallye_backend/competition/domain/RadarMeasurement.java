package cz.mh.rallye.rallye_backend.competition.domain;

import java.time.Instant;
import java.util.UUID;
import cz.mh.rallye.rallye_backend.competition.domain.RaceEvaluator.Action;

/** Verified measurement evidence shared by simulation and the future real race runtime.
 * Persistence, ownership and exactly-once processing belong to the calling transaction.
 * Contains the measured position only, never the target coordinates.
 */
public record RadarMeasurement(UUID measurementId, UUID runId, UUID stageId,
        long sequence, long measurementNumber, double latitude, double longitude,
        long distanceMeters, long penaltyPoints, Instant measuredAt, Instant recordedAt,
        boolean completedStage) {
    public static RadarMeasurement from(UUID id, UUID runId, UUID stageId, long sequence,
            long number, Action action, Instant recordedAt) {
        var gps = action == null ? null : action.gps();
        if (id == null || runId == null || stageId == null || sequence < 1 || number < 1
                || recordedAt == null || action == null || !id.equals(action.actionId())
                || sequence != action.sequence() || !stageId.equals(action.stageId())
                || !"RADAR".equals(action.type()) || action.virtualAt() == null
                || !("RADAR_ACCEPTED".equals(action.outcome()) || "RADAR_OUTSIDE".equals(action.outcome()))
                || gps == null || gps.latitude() == null || gps.longitude() == null
                || gps.distanceMeters() == null || !Double.isFinite(gps.distanceMeters())
                || gps.distanceMeters() < 0) {
            throw new IllegalArgumentException("Invalid radar measurement evidence");
        }
        GpsMath.validate(gps.latitude(), gps.longitude());
        boolean completed = "RADAR_ACCEPTED".equals(action.outcome());
        // Completion can also apply TIME/DELAYED penalties; those are not measurement penalties.
        long points = completed ? 0 : action.pointsDelta();
        if (points < 0) throw new IllegalArgumentException("Invalid radar measurement penalty");
        return new RadarMeasurement(id, runId, stageId, sequence, number,
                gps.latitude(), gps.longitude(), Math.round(gps.distanceMeters()), points,
                action.virtualAt(), recordedAt, completed);
    }
}
