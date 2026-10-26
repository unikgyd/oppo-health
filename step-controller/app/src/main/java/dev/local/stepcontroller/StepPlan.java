package dev.local.stepcontroller;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Pure time model, shared by the settings preview and all hooked processes. */
public final class StepPlan {
    public static final int MAX_STEPS = 99999;
    public final boolean enabled, automatic, persist;
    public final int baseSteps, cadence;
    public final long startWall, startElapsed, revision;
    public final String date;

    public StepPlan(boolean enabled, boolean automatic, boolean persist, int baseSteps,
                    int cadence, long startWall, long startElapsed, long revision, String date) {
        this.enabled = enabled;
        this.automatic = automatic;
        this.persist = persist;
        this.baseSteps = Math.max(0, Math.min(MAX_STEPS, baseSteps));
        this.cadence = Math.max(1, Math.min(300, cadence));
        this.startWall = startWall;
        this.startElapsed = startElapsed;
        this.revision = revision;
        this.date = date == null ? "" : date;
    }

    public static String today(long wall, ZoneId zone) {
        return Instant.ofEpochMilli(wall).atZone(zone).toLocalDate().toString();
    }

    public boolean active(long wall, ZoneId zone) {
        return enabled && date.equals(today(wall, zone));
    }

    public int steps(long wall, long elapsed) {
        if (!automatic) return baseSteps;
        // Prefer the monotonic clock within the same boot. Across a reboot or a
        // large clock adjustment, use wall time. Clamp elapsed before multiplication.
        long wallDelta = Math.max(0L, wall - startWall);
        long monotonicDelta = elapsed - startElapsed;
        boolean sameClock = monotonicDelta >= 0L
                && Math.abs(wallDelta - monotonicDelta) <= 120000L;
        long duration = Math.min(86400000L, sameClock ? monotonicDelta : wallDelta);
        return (int) Math.min(MAX_STEPS, baseSteps + duration * cadence / 60000L);
    }

    public static int compactDate(long wall, ZoneId zone) {
        LocalDate d = Instant.ofEpochMilli(wall).atZone(zone).toLocalDate();
        return d.getYear() * 10000 + d.getMonthValue() * 100 + d.getDayOfMonth();
    }
}
