package dev.local.stepcontroller;

import java.time.ZoneId;

/** An independent, day-scoped WeChat target; it never changes the local plan. */
public final class WechatPlan {
    public final boolean enabled;
    public final int steps;
    public final long revision;
    public final String date;

    public WechatPlan(boolean enabled, int steps, long revision, String date) {
        this.enabled = enabled && steps >= 1 && steps <= StepPlan.MAX_STEPS;
        this.steps = Math.max(1, Math.min(StepPlan.MAX_STEPS, steps));
        this.revision = revision;
        this.date = date == null ? "" : date;
    }

    public boolean active(long wall, ZoneId zone) {
        return enabled && date.equals(StepPlan.today(wall, zone));
    }

    public static WechatPlan disabled() { return new WechatPlan(false, 8000, 0L, ""); }
}
