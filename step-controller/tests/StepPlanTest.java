import dev.local.stepcontroller.StepPlan;
import java.time.Instant;
import java.time.ZoneId;

public final class StepPlanTest {
    private static int checks;
    private static final ZoneId HKT = ZoneId.of("Asia/Hong_Kong");
    private static final long WALL = Instant.parse("2026-10-23T04:00:00Z").toEpochMilli();
    private static final long ELAPSED = 900000L;
    private static StepPlan plan(boolean auto, int base, int cadence) {
        return new StepPlan(true, auto, false, base, cadence, WALL, ELAPSED, 1, "2026-10-23");
    }
    private static void eq(long expected, long actual, String label) {
        checks++; if (expected != actual) throw new AssertionError(label + ": " + expected + " != " + actual);
    }
    public static void main(String[] ignored) {
        eq(8000, plan(false,8000,100).steps(WALL+3600000,ELAPSED+3600000), "fixed total stays fixed");
        eq(8050, plan(true,8000,100).steps(WALL+30000,ELAPSED+30000), "100 per minute adds 50 after 30 seconds");
        eq(8100, plan(true,8000,100).steps(WALL+60000,ELAPSED+60000), "automatic elapsed total");
        eq(8000, plan(true,8000,100).steps(WALL+599,ELAPSED+599), "fractional steps are not emitted");
        eq(8001, plan(true,8000,100).steps(WALL+600,ELAPSED+600), "whole step boundary");
        eq(99999, plan(true,99990,300).steps(WALL+60000,ELAPSED+60000), "display limit");
        eq(0, plan(false,-30,100).steps(WALL,ELAPSED), "negative input clamped");
        eq(99999, plan(false,Integer.MAX_VALUE,100).steps(WALL,ELAPSED), "huge input clamped");
        eq(8100, plan(true,8000,100).steps(WALL+60000,1000), "reboot falls back to wall time");
        eq(8000, plan(true,8000,100).steps(WALL-600000,1000), "clock rollback never subtracts steps");
        eq(8100, plan(true,8000,100).steps(WALL+30000,ELAPSED+60000), "small clock change uses monotonic time");
        eq(1, plan(true,0,100).active(WALL,HKT)?1:0, "today is enabled");
        eq(0, plan(true,0,100).active(Instant.parse("2026-10-23T16:00:00Z").toEpochMilli(),HKT)?1:0, "stops at local midnight");
        eq(20261023, StepPlan.compactDate(WALL,HKT), "database date representation");
        eq(99999, plan(true,90000,300).steps(Long.MAX_VALUE/2,Long.MAX_VALUE/2), "duration multiplication cannot overflow at long runtime");
        StepPlan paused = plan(false, plan(true,8000,100).steps(WALL+60000,ELAPSED+60000), 100);
        eq(8100, paused.steps(WALL+3600000,ELAPSED+3600000), "pause preserves the current total");
        System.out.println("PASS: " + checks + " behavioral checks");
    }
}
