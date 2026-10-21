package cz.mh.rallye.rallye_backend.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

public class MutableClock extends Clock {
    private final AtomicReference<Instant> time = new AtomicReference<>(Instant.parse("2026-10-21T12:00:00Z"));
    public void advance(Duration duration) { time.updateAndGet(value -> value.plus(duration)); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
    @Override public Instant instant() { return time.get(); }
}
