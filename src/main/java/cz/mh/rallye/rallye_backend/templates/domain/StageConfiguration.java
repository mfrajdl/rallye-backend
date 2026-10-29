package cz.mh.rallye.rallye_backend.templates.domain;

import java.util.*;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;

/** Persisted configuration. Completion is always AND for the enabled GPS/CODE checks. */
public record StageConfiguration(Gps gps, Code code, TimePenalty timePenalty, Delayed delayed) {
    public record Gps(boolean enabled, Double latitude, Double longitude, Double radiusMeters,
                      Long outsidePenaltyPoints, Long spoofingPenaltyPoints) {}
    public record Code(boolean enabled, String value, Long wrongAttemptPenaltyPoints, boolean valueUnavailable) {
        public Code(boolean enabled,String value,Long wrongAttemptPenaltyPoints){this(enabled,value,wrongAttemptPenaltyPoints,false);}
        public boolean matches(String submitted){if(value==null||submitted==null)return false;try{return value.equals(normalizeCode(submitted));}catch(AuthFailure invalid){return false;}}
        @Override public String toString(){return "Code[redacted]";}
    }
    public record TimePenalty(boolean enabled, Long intervalMs, Long pointsPerInterval) {}
    public record Delayed(boolean enabled, Long minimumDurationMs, Long pointsPerSecond) {}
    public static String normalizeCode(String raw){if(raw==null)throw new AuthFailure(400,"INVALID_REQUEST");String value=raw.strip().toUpperCase(Locale.ROOT);if(value.isEmpty()||value.codePointCount(0,value.length())>128)throw new AuthFailure(400,"INVALID_REQUEST");return value;}
    public static StageConfiguration empty(){return new StageConfiguration(new Gps(false,null,null,null,null,null),new Code(false,null,null),new TimePenalty(false,null,null),new Delayed(false,null,null));}
    public StageConfiguration {
        Objects.requireNonNull(gps);Objects.requireNonNull(code);Objects.requireNonNull(timePenalty);Objects.requireNonNull(delayed);
    }
    @Override public String toString(){return "StageConfiguration[redacted]";}
    public List<String> invalidFields(){
        var fields=new ArrayList<String>();
        if(gps.latitude()!=null && (!Double.isFinite(gps.latitude()) || gps.latitude() < -90 || gps.latitude()>90))fields.add("gps.latitude");
        if(gps.longitude()!=null && (!Double.isFinite(gps.longitude()) || gps.longitude() < -180 || gps.longitude()>180))fields.add("gps.longitude");
        if(gps.radiusMeters()!=null && (!Double.isFinite(gps.radiusMeters()) || gps.radiusMeters()<=0))fields.add("gps.radiusMeters");
        if(code.value()!=null && code.value().isBlank())fields.add("code.codeConfigured");
        if(timePenalty.intervalMs()!=null && (timePenalty.intervalMs()<=0 || timePenalty.intervalMs()%1000!=0))fields.add("timePenalty.intervalSeconds");
        if(delayed.minimumDurationMs()!=null && delayed.minimumDurationMs()<0)fields.add("delayed.minimumDurationMs");
        return List.copyOf(fields);
    }
    public void validate(){if(!invalidFields().isEmpty())throw new AuthFailure(400,"INVALID_REQUEST");}
}
