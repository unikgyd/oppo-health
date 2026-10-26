package dev.local.stepcontroller;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.os.Handler;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import java.lang.ref.WeakReference;
import java.time.ZoneId;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** All hooks are installed only in the two explicitly allowed packages. */
public final class HookEntry implements IXposedHookLoadPackage {
    private static final String ROOT = "com.heytap.sports.step.stepdaemon.";
    private static final String LISTENER = ROOT + "sensor.StepCounterListener";
    private static final String MODE = ROOT + "store.DefaultMode";
    private static final String REPOSITORY = ROOT + "store.DefaultModeRepository";
    private static final String EXTEND = "com.heytap.health.sport.StepProviderUtils.ExtendStepCounterUtil";

    private ClassLoader loader;
    private String process;
    private Handler handler;
    private XSharedPreferences prefs;
    private Context targetContext;
    private SharedPreferences runtime;
    private volatile StepPlan plan = Settings.disabled();
    private volatile boolean ready, permissionsAllowed;
    private boolean ticking, wasActive, firstConfig = true;
    private WeakReference<Object> listener = new WeakReference<>(null);
    private WeakReference<Object> mode = new WeakReference<>(null);
    private volatile Reading lastReading;
    private long lastWriteTime, lastWriteRevision = -1;
    private int lastWrittenSteps = -1;
    private int consecutiveErrors;
    private final ThreadLocal<Boolean> recoveryWrite = new ThreadLocal<>();
    private static final String SNAPSHOT_TAG = "dev.local.stepcontroller.snapshot_revision";

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (Settings.PACKAGE.equals(lp.packageName)) {
            XposedHelpers.findAndHookMethod(MainActivity.class.getName(), lp.classLoader,
                    "frameworkActive", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(true); }
                    });
            return;
        }
        if (!Settings.TARGET.equals(lp.packageName)) return;
        if (!Settings.TARGET.equals(lp.processName) && !(Settings.TARGET + ":SportDaemonService").equals(lp.processName)) return;
        loader = lp.classLoader;
        process = lp.processName;
        try {
            prefs = new XSharedPreferences(Settings.PACKAGE, Settings.PREFS);
            installHooks();
            ready = true;
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    targetContext = (Context) p.thisObject;
                    runtime = targetContext.getSharedPreferences("step_controller_runtime", Context.MODE_PRIVATE);
                    startTicker();
                }
            });
            log("hooks installed; target=6.9.19; process=" + process);
        } catch (Throwable e) {
            // Every callback checks ready, so an incompatible build cannot leave
            // just one of the data paths overridden.
            ready = false;
            log("disabled: required hook not found: " + e);
        }
        // Each feature has its own compatibility boundary. A missing local
        // counter method must not prevent independent WeChat customization.
        try { new WechatHooks(loader, process).install(); }
        catch (Throwable e) { log("WeChat feature initialization failed: " + e); }
    }

    private void installHooks() {
        Class<?> lc = XposedHelpers.findClass(LISTENER, loader);
        Class<?> mc = XposedHelpers.findClass(MODE, loader);
        Class<?> rc = XposedHelpers.findClass(REPOSITORY, loader);
        Class<?> displayClass = XposedHelpers.findClass("com.heytap.health.sport.model.SportsDisplayData", loader);
        Class<?> cpClass = XposedHelpers.findClass(ROOT + "store.ContentProviderOperate", loader);

        // Validate the methods used by periodic injection before allowing activation.
        XposedHelpers.findMethodExact(lc, "changedEvent", SensorEvent.class);
        XposedHelpers.findMethodExact(mc, "update", int.class, long.class);
        XposedHelpers.findMethodExact(rc, "updateDisplayData", long.class, double.class, double.class);
        XposedHelpers.findMethodExact(rc, "insertStepStat");
        XposedHelpers.findMethodExact(rc, "updateCurDataFromDB", long.class);
        for (String field : new String[]{"mOnStepCounterListener", "sOffsetSensorStep"})
            XposedHelpers.findField(lc, field);
        XposedHelpers.findMethodExact(cpClass, "updateSportProvider", displayClass);
        for (String field : new String[]{"mDisplayData", "mRepository", "mCpOperate", "mLastSensorStep", "mLastSensorTime", "handler"})
            XposedHelpers.findField(mc, field);

        XposedBridge.hookAllConstructors(displayClass, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!p.hasThrowable() && active()) XposedHelpers.setAdditionalInstanceField(p.thisObject, SNAPSHOT_TAG, plan.revision);
            }
        });
        XposedHelpers.findAndHookMethod(cpClass, "updateSportProvider", displayClass, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (Boolean.TRUE.equals(recoveryWrite.get())) return;
                Object tag = XposedHelpers.getAdditionalInstanceField(p.args[0], SNAPSHOT_TAG);
                // A queued coroutine from an old simulation must not rewrite the
                // provider after disabling or after saving a different plan.
                if (tag instanceof Long && (!active() || ((Long) tag).longValue() != plan.revision)) {
                    p.setResult(false);
                } else if (!active() && runtime != null && runtime.getBoolean("dirty", false)) {
                    p.setResult(false);
                }
            }
        });

        XposedBridge.hookAllConstructors(lc, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!p.hasThrowable()) listener = new WeakReference<>(p.thisObject);
            }
        });
        XposedBridge.hookAllConstructors(mc, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!p.hasThrowable()) mode = new WeakReference<>(p.thisObject);
            }
        });
        XposedHelpers.findAndHookMethod(lc, "changedEvent", SensorEvent.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                SensorEvent event = (SensorEvent) p.args[0];
                if (event != null) lastReading = new Reading(event);
                if (!active()) return;
                try {
                    dispatch(p.thisObject);
                    p.setResult(null);
                } catch (Throwable e) { fail(e); }
            }
        });
        XposedHelpers.findAndHookMethod(mc, "update", int.class, long.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!active()) return;
                try {
                    render(p.thisObject);
                    p.setResult(null);
                } catch (Throwable e) { fail(e); }
            }
        });
        XposedHelpers.findAndHookMethod(rc, "updateDisplayData", long.class, double.class, double.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!active()) return;
                        int n = currentSteps();
                        p.args[0] = (long) n;
                        p.args[1] = distanceKm(n);
                        p.args[2] = calories(n);
                    }
                });
        // Pause the competing app-side reads. The system provider and other apps
        // are never hooked, and no system sensor counts are changed.
        suppress(EXTEND, "getHistoryDataOnlyOnce");
        suppress(EXTEND, "getPeriodStepCounts");
        suppress(MODE, "getStepFromAssScreen");
        suppress(MODE, "intervalUpdateDataToDB");
        XposedHelpers.findAndHookMethod(mc, "getReadDataFromCP", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                // An old simulated display cache must not be reused after a killed
                // process. The real daily database is read separately by the app.
                if (ready && runtime != null && runtime.getBoolean("dirty", false))
                    p.setResult(XposedHelpers.getStaticObjectField(XposedHelpers.findClass("kotlin.Unit", loader), "INSTANCE"));
            }
        });
    }

    private void suppress(String type, String method) {
        XposedHelpers.findAndHookMethod(type, loader, method, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { if (active()) p.setResult(null); }
        });
    }

    private boolean active() {
        return ready && permissionsAllowed && plan.active(System.currentTimeMillis(), ZoneId.systemDefault());
    }
    private int currentSteps() { return plan.steps(System.currentTimeMillis(), SystemClock.elapsedRealtime()); }
    private double distanceKm(int n) {
        try {
            return ((Number) XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                    "com.heytap.health.sport.algorithm.StepTransformUtils", loader), "perMinuteDistance", (long) n, 0)).doubleValue();
        } catch (Throwable ignored) { return n * 0.0007; }
    }
    private double calories(int n) {
        try {
            return ((Number) XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                    "com.heytap.health.sport.algorithm.StepTransformUtils", loader), "perMinuteCalorie", (long) n, 0, 0.0)).doubleValue();
        } catch (Throwable ignored) { return n * 0.021; }
    }

    private void startTicker() {
        if (ticking) return;
        ticking = true;
        handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                try { tick(); }
                catch (Throwable e) { fail(e); }
                if (ready) handler.postDelayed(this, 5000L);
            }
        }, 1500L);
    }

    private void tick() {
        int errorsBefore = consecutiveErrors;
        prefs.reload();
        StepPlan newPlan = Settings.read(prefs);
        if (firstConfig) {
            firstConfig = false;
            log("settings schema=" + prefs.getInt("schema", 0) + "; readable=" + prefs.getFile().canRead());
        }
        plan = newPlan;
        permissionsAllowed = Boolean.TRUE.equals(XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("com.heytap.health.base.tourist.TouristStatus", loader), "hasAgreeHealth"))
                && !Boolean.TRUE.equals(XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("com.heytap.health.base.tourist.TouristHelper", loader), "notAllowStepFunc"));
        boolean on = active();
        if (!on && (wasActive || (mode.get() != null && runtime != null && runtime.getBoolean("dirty", false)))) restore();
        if (on) {
            Object l = listener.get();
            Object m = mode.get();
            if (l != null) dispatch(l);
            else if (m != null) render(m);
            if (active() && m != null && l != null && plan.persist &&
                    (plan.revision != lastWriteRevision ||
                            (currentSteps() != lastWrittenSteps && SystemClock.elapsedRealtime() - lastWriteTime >= 60000L))) {
                XposedHelpers.callMethod(XposedHelpers.getObjectField(m, "mRepository"), "insertStepStat");
                lastWriteTime = SystemClock.elapsedRealtime();
                lastWriteRevision = plan.revision;
                lastWrittenSteps = currentSteps();
                log("requested local day-stat write; steps=" + lastWrittenSteps);
            }
        }
        if (on != wasActive) log(on ? "simulation enabled" : "simulation disabled/expired");
        wasActive = on;
        if (consecutiveErrors == errorsBefore) consecutiveErrors = 0;
    }

    private void dispatch(Object l) {
        Object callback = XposedHelpers.getObjectField(l, "mOnStepCounterListener");
        if (callback != null) XposedHelpers.callMethod(callback, "onChangeStepCounter", currentSteps(), SystemClock.elapsedRealtime() / 1000L);
    }

    private void render(Object m) {
        if (runtime != null && !runtime.getBoolean("dirty", false)) {
            if (!runtime.edit().putBoolean("dirty", true).commit())
                throw new IllegalStateException("Cannot save simulation recovery marker");
        }
        int n = currentSteps();
        XposedHelpers.setIntField(m, "mLastSensorStep", n);
        XposedHelpers.setLongField(m, "mLastSensorTime", SystemClock.elapsedRealtime() / 1000L);
        XposedHelpers.callMethod(XposedHelpers.getObjectField(m, "mRepository"), "updateDisplayData", (long) n, distanceKm(n), calories(n));
        XposedHelpers.callMethod(XposedHelpers.getObjectField(m, "mCpOperate"), "sendDataToCP", true);
    }

    private void restore() {
        Object m = mode.get();
        if (m == null) return;
        Object repo = XposedHelpers.getObjectField(m, "mRepository");
        XposedHelpers.setIntField(m, "mLastSensorStep", 0);
        XposedHelpers.setLongField(m, "mLastSensorTime", 0L);
        XposedHelpers.callMethod(repo, "updateDisplayData", 0L, 0.0, 0.0);
        Object l = listener.get();
        Reading reading = lastReading;
        if (l != null && reading != null) {
            SensorEvent event = (SensorEvent) XposedHelpers.newInstance(SensorEvent.class, reading.values.length);
            event.sensor = reading.sensor;
            event.accuracy = reading.accuracy;
            event.timestamp = SystemClock.elapsedRealtimeNanos();
            System.arraycopy(reading.values, 0, event.values, 0, reading.values.length);
            XposedHelpers.callMethod(l, "onSensorChanged", event);
        }
        // Synchronize and read back the display cache BEFORE clearing the durable
        // recovery marker. Scheduling the original coroutine is not sufficient.
        Object data = XposedHelpers.getObjectField(m, "mDisplayData");
        XposedHelpers.removeAdditionalInstanceField(data, SNAPSHOT_TAG);
        Object cp = XposedHelpers.getObjectField(m, "mCpOperate");
        recoveryWrite.set(true);
        try {
            if (!Boolean.TRUE.equals(XposedHelpers.callMethod(cp, "updateSportProvider", data)))
                throw new IllegalStateException("Real display cache write rejected");
            Bundle readback = (Bundle) XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                    "com.heytap.health.core.provider.adapter.open.SportDataAdapter", loader), "querySportData", targetContext);
            long expected = ((Number) XposedHelpers.callMethod(data, "getStep")).longValue();
            if (readback == null || readback.getLong("step", -1L) != expected)
                throw new IllegalStateException("Real display cache readback did not match");
        } finally { recoveryWrite.remove(); }
        // The app may have stopped its fallback timer while the extended source
        // was present. Resume exactly one timer after leaving simulation.
        Handler originalHandler = (Handler) XposedHelpers.getObjectField(m, "handler");
        originalHandler.removeMessages(1);
        originalHandler.sendEmptyMessageDelayed(1, 60000L);
        if (permissionsAllowed) {
            XposedHelpers.callStaticMethod(XposedHelpers.findClass(EXTEND, loader), "getHistoryDataOnlyOnce");
            XposedHelpers.callStaticMethod(XposedHelpers.findClass(EXTEND, loader), "getPeriodStepCounts");
        }
        if (runtime != null && !runtime.edit().putBoolean("dirty", false).commit())
            throw new IllegalStateException("Cannot clear simulation recovery marker");
        XposedHelpers.callMethod(repo, "updateCurDataFromDB", 0L);
        lastWriteRevision = -1;
        log("real display cache verified; database refresh requested; prior explicit day-stat writes retained");
    }

    private void fail(Throwable error) {
        if (++consecutiveErrors <= 3) log("runtime error: " + error);
        if (consecutiveErrors >= 3) {
            ready = false;
            permissionsAllowed = false;
            try { if (wasActive) restore(); } catch (Throwable ignored) { }
            log("hooks deactivated after repeated errors; restart health app and inspect compatibility");
        }
    }
    private void log(String text) { XposedBridge.log("[StepController] " + process + " " + text); }

    private static final class Reading {
        final Sensor sensor;
        final int accuracy;
        final float[] values;
        Reading(SensorEvent event) {
            sensor = event.sensor;
            accuracy = event.accuracy;
            values = event.values.clone();
        }
    }
}
