package dev.local.stepcontroller;

import android.content.SharedPreferences;

public final class Settings {
    public static final String PACKAGE = "dev.local.stepcontroller";
    public static final String PREFS = "step_config";
    public static final String TARGET = "com.heytap.health";
    private Settings() {}

    public static StepPlan read(SharedPreferences p) {
        if (p.getInt("schema", 0) != 1) return disabled();
        return new StepPlan(p.getBoolean("enabled", false), p.getBoolean("auto", false),
                p.getBoolean("persist", false), p.getInt("steps", 0), p.getInt("cadence", 100),
                p.getLong("wall", 0), p.getLong("elapsed", 0), p.getLong("revision", 0),
                p.getString("date", ""));
    }

    public static StepPlan disabled() {
        return new StepPlan(false, false, false, 0, 100, 0, 0, 0, "");
    }

    public static WechatPlan readWechat(SharedPreferences p) {
        if (p.getInt("schema", 0) != 1) return WechatPlan.disabled();
        return new WechatPlan(p.getBoolean("wx_enabled", false), p.getInt("wx_steps", 8000),
                p.getLong("wx_revision", 0L), p.getString("wx_date", ""));
    }

    public static boolean saveLocal(SharedPreferences p, int steps, int cadence, boolean automatic,
                                    boolean enabled, boolean persist, long wall, long elapsed, String date) {
        return p.edit().putInt("schema", 1).putBoolean("enabled", enabled)
                .putBoolean("auto", automatic).putBoolean("persist", persist)
                .putInt("steps", steps).putInt("cadence", cadence).putLong("wall", wall)
                .putLong("elapsed", elapsed).putLong("revision", wall).putString("date", date).commit();
    }

    public static boolean saveWechat(SharedPreferences p, int steps, long now, String date) {
        if (steps < 1 || steps > StepPlan.MAX_STEPS) throw new IllegalArgumentException("Invalid WeChat target");
        return p.edit().putInt("schema", 1).putBoolean("wx_enabled", true)
                .putInt("wx_steps", steps).putLong("wx_revision", now).putString("wx_date", date).commit();
    }

    public static boolean disableLocal(SharedPreferences p, long now) {
        return p.edit().putBoolean("enabled", false).putLong("revision", now).commit();
    }

    public static boolean disableWechat(SharedPreferences p, long now) {
        return p.edit().putBoolean("wx_enabled", false).putLong("wx_revision", now).commit();
    }
}
