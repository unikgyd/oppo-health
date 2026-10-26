package dev.local.stepcontroller;

import android.content.Context;
import android.os.Bundle;
import java.lang.reflect.Method;
import java.time.ZoneId;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Overrides only the input read made by the original synchronous sync worker. */
public final class WechatHooks {
    private static final String MANAGER = "com.heytap.health.settings.me.thirdpartbinding.wechat.MMStepSyncManager";
    private static final String ADAPTER = "com.heytap.health.core.provider.adapter.open.SportDataAdapter";
    private static final String FRAME_KEY = "dev.local.stepcontroller.wechat_frame";
    private final WechatScope scope = new WechatScope();
    private final Object configLock = new Object();
    private final ClassLoader loader;
    private final String process;
    private final XSharedPreferences prefs;
    private volatile boolean installed;

    public WechatHooks(ClassLoader loader, String process) {
        this.loader = loader;
        this.process = process;
        // Separate reader from the local-plan timer, so its reload cannot switch
        // this worker's settings halfway through reading one plan.
        prefs = new XSharedPreferences(Settings.PACKAGE, Settings.PREFS);
    }

    public void install() {
        try {
            Class<?> manager = XposedHelpers.findClass(MANAGER, loader);
            Class<?> emitter = XposedHelpers.findClass("io.reactivex.rxjava3.core.ObservableEmitter", loader);
            Method worker = findWorker(manager, emitter);
            Method query = XposedHelpers.findMethodExact(XposedHelpers.findClass(ADAPTER, loader), "querySportData", Context.class);
            Method submit = XposedHelpers.findMethodExact(manager, "setStep", long.class);
            if (query.getReturnType() != Bundle.class || submit.getReturnType() != int.class)
                throw new IllegalStateException("Unexpected WeChat method contract");

            XposedBridge.hookMethod(worker, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!installed) return;
                    try {
                        WechatPlan plan;
                        synchronized (configLock) {
                            prefs.reload();
                            plan = Settings.readWechat(prefs);
                        }
                        if (!plan.active(System.currentTimeMillis(), ZoneId.systemDefault()) || !permissionsAllowed()) return;
                        // The Rx worker runs on the IO thread. Scoping syncInner()
                        // itself would end before this asynchronous work begins.
                        WechatScope.Frame frame = scope.enter(plan.steps);
                        p.setObjectExtra(FRAME_KEY, frame);
                        log("WeChat worker target=" + plan.steps + "; original pacing retained");
                    } catch (Throwable e) { log("WeChat target skipped: " + e); }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object frame = p.getObjectExtra(FRAME_KEY);
                    if (frame instanceof WechatScope.Frame) {
                        try { ((WechatScope.Frame) frame).close(); }
                        catch (Throwable e) { scope.clear(); log("WeChat scope cleanup: " + e); }
                    }
                }
            });
            XposedBridge.hookMethod(query, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Integer target = scope.steps();
                    if (target == null || p.hasThrowable() || !(p.getResult() instanceof Bundle)) return;
                    Bundle original = (Bundle) p.getResult();
                    if (!original.containsKey("step")) return;
                    // Do not mutate the shared display cache, its original Bundle,
                    // other readers, timestamps, binding or status information.
                    Bundle copy = new Bundle(original);
                    copy.putLong("step", target.longValue());
                    p.setResult(copy);
                }
            });
            XposedBridge.hookMethod(submit, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Integer target = scope.steps();
                    if (target == null) return;
                    log("WeChat target=" + target + "; submitted=" + p.args[0]
                            + (p.hasThrowable() ? "; call threw=" + p.getThrowable() : "; retCode=" + p.getResult()));
                }
            });
            installed = true;
            log("WeChat hooks installed; worker=" + worker.getName());
        } catch (Throwable e) {
            installed = false;
            log("WeChat feature disabled: incompatible method contract: " + e);
        }
    }

    static Method findWorker(Class<?> manager, Class<?> emitter) {
        Method selected = null;
        for (Method method : manager.getDeclaredMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (method.getName().startsWith("lambda$syncInner$") && method.getReturnType() == void.class
                    && p.length == 3 && p[0] == boolean.class && p[1] == boolean.class && p[2] == emitter) {
                if (selected != null) throw new IllegalStateException("Ambiguous WeChat IO worker");
                selected = method;
            }
        }
        if (selected == null) throw new IllegalStateException("Missing WeChat IO worker");
        return selected;
    }

    private boolean permissionsAllowed() {
        return Boolean.TRUE.equals(XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                "com.heytap.health.base.tourist.TouristStatus", loader), "hasAgreeHealth"))
                && !Boolean.TRUE.equals(XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                "com.heytap.health.base.tourist.TouristHelper", loader), "notAllowStepFunc"));
    }
    private void log(String message) { XposedBridge.log("[StepController] " + process + " " + message); }
}
