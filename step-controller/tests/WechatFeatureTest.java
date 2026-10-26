package dev.local.stepcontroller;

import android.content.SharedPreferences;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WechatFeatureTest {
    private static int checks;
    private static final long NOW = Instant.parse("2026-10-23T04:00:00Z").toEpochMilli();
    private static final ZoneId HKT = ZoneId.of("Asia/Hong_Kong");
    private static void check(boolean value, String label) {
        checks++; if (!value) throw new AssertionError(label);
    }
    private static void same(StepPlan a, StepPlan b, String label) {
        check(a.enabled==b.enabled && a.automatic==b.automatic && a.persist==b.persist
                && a.baseSteps==b.baseSteps && a.cadence==b.cadence && a.startWall==b.startWall
                && a.startElapsed==b.startElapsed && a.revision==b.revision && a.date.equals(b.date), label);
    }
    private static void same(WechatPlan a, WechatPlan b, String label) {
        check(a.enabled==b.enabled && a.steps==b.steps && a.revision==b.revision && a.date.equals(b.date), label);
    }

    public static void main(String[] ignored) throws Exception {
        Map<String,Object> state = new HashMap<>();
        SharedPreferences p = memoryPreferences(state);
        check(!Settings.readWechat(p).active(NOW,HKT), "unconfigured WeChat stays off");
        Settings.saveLocal(p,2000,100,true,true,false,NOW,500000,"2026-10-23");
        StepPlan local = Settings.read(p);
        check(!Settings.readWechat(p).enabled, "old schema-1 local settings migrate with WeChat off");
        Settings.saveWechat(p,15000,NOW+1000,"2026-10-23");
        same(local,Settings.read(p),"saving WeChat preserves local count, auto clock, revision and state");
        WechatPlan wx = Settings.readWechat(p);
        check(wx.active(NOW,HKT) && wx.steps==15000,"independent target loaded");
        Settings.disableLocal(p,NOW+2000);
        same(wx,Settings.readWechat(p),"disabling local keeps WeChat enabled and unchanged");
        check(!Settings.read(p).enabled && Settings.readWechat(p).enabled,"WeChat works with local off");
        Settings.saveLocal(p,3200,150,false,true,true,NOW+3000,503000,"2026-10-23");
        same(wx,Settings.readWechat(p),"saving local leaves WeChat target and revision unchanged");
        StepPlan newerLocal = Settings.read(p);
        Settings.disableWechat(p,NOW+4000);
        same(newerLocal,Settings.read(p),"disabling WeChat leaves local enabled and unchanged");
        check(!Settings.readWechat(p).enabled,"WeChat disable persisted");
        Map<String,Object> before = new HashMap<>(state);
        boolean rejected=false;
        try {Settings.saveWechat(p,0,NOW,"2026-10-23");} catch(IllegalArgumentException e){rejected=true;}
        check(rejected && before.equals(state),"zero target rejected without changing either plan");
        rejected=false;
        try {Settings.saveWechat(p,100000,NOW,"2026-10-23");} catch(IllegalArgumentException e){rejected=true;}
        check(rejected && before.equals(state),"too large target rejected atomically");
        SharedPreferences wxOnly = memoryPreferences(new HashMap<>());
        Settings.saveWechat(wxOnly,8888,NOW,"2026-10-23");
        check(!Settings.read(wxOnly).enabled && Settings.readWechat(wxOnly).enabled,"standalone WeChat save never enables local");
        long midnight = Instant.parse("2026-10-23T16:00:00Z").toEpochMilli();
        check(!new WechatPlan(true,15000,1,"2026-10-23").active(midnight,HKT),"WeChat expires at local midnight");
        check(!new WechatPlan(true,-1,1,"2026-10-23").active(NOW,HKT),"corrupted negative target fails closed");
        check(!new WechatPlan(true,Integer.MAX_VALUE,1,"2026-10-23").active(NOW,HKT),"corrupted large target fails closed");

        WechatScope scope = new WechatScope();
        check(scope.steps()==null,"ordinary readers have no override");
        WechatScope.Frame outer = scope.enter(15000);
        check(scope.steps()==15000,"worker captures target");
        WechatScope.Frame inner = scope.enter(7000);
        check(scope.steps()==7000,"nested worker has own target");
        inner.close();
        check(scope.steps()==15000,"nested close restores parent target");
        inner.close();
        check(scope.steps()==15000,"repeated close cannot pop parent");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            check(worker.submit(() -> scope.steps()==null).get(),"unrelated thread cannot see sync target");
            check(worker.submit(() -> {
                try(WechatScope.Frame f=scope.enter(1234)) {
                    if(scope.steps()!=1234) return false;
                    throw new IllegalStateException("simulated worker failure");
                } catch(IllegalStateException expected) {return scope.steps()==null;}
            }).get(),"exception path releases worker override");
            check(worker.submit(() -> scope.steps()==null).get(),"reused IO thread cannot leak target into next task");
            check(scope.steps()==15000,"other thread operations preserve outer target");
        } finally {worker.shutdownNow();}
        outer.close();
        check(scope.steps()==null,"sync completion restores ordinary reads");
        WechatScope.Frame a=scope.enter(100), b=scope.enter(200);
        rejected=false;
        try{a.close();}catch(IllegalStateException e){rejected=true;}
        check(rejected && scope.steps()==200,"out-of-order cleanup cannot silently corrupt scope");
        b.close(); a.close();
        check(scope.steps()==null,"scope recoverable after rejected close");
        Method method=WechatHooks.findWorker(MockManager.class,MockEmitter.class);
        check(method.getName().equals("lambda$syncInner$5"),"actual IO-worker signature selected");
        rejected=false;
        try{WechatHooks.findWorker(AmbiguousManager.class,MockEmitter.class);}catch(IllegalStateException e){rejected=true;}
        check(rejected,"ambiguous worker fails closed");
        rejected=false;
        try{WechatHooks.findWorker(MissingManager.class,MockEmitter.class);}catch(IllegalStateException e){rejected=true;}
        check(rejected,"missing worker fails closed");
        System.out.println("PASS: "+checks+" WeChat isolation and settings checks");
    }

    private interface MockEmitter {}
    private static class MockManager {
        private void lambda$syncInner$5(boolean a,boolean b,MockEmitter e) {}
        private void lambda$syncInner$2() {}
    }
    private static class AmbiguousManager {
        private void lambda$syncInner$5(boolean a,boolean b,MockEmitter e) {}
        private void lambda$syncInner$7(boolean a,boolean b,MockEmitter e) {}
    }
    private static class MissingManager {private void lambda$syncInner$2() {}}

    // Run the real Settings save/read methods against the Android interface without
    // invoking Android's stub implementations or requiring an emulator.
    private static SharedPreferences memoryPreferences(Map<String,Object> values) {
        return (SharedPreferences) Proxy.newProxyInstance(WechatFeatureTest.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
                    String name=method.getName();
                    if(name.equals("getAll")) return new HashMap<>(values);
                    if(name.equals("contains")) return values.containsKey(args[0]);
                    if(name.startsWith("get")) return values.getOrDefault(args[0],args[1]);
                    if(name.equals("edit")) {
                        Map<String,Object> changes=new HashMap<>();
                        return Proxy.newProxyInstance(WechatFeatureTest.class.getClassLoader(),
                                new Class<?>[]{SharedPreferences.Editor.class},(editor,editMethod,editArgs)->{
                                    String op=editMethod.getName();
                                    if(op.startsWith("put")){changes.put((String)editArgs[0],editArgs[1]);return editor;}
                                    if(op.equals("commit")||op.equals("apply")){values.putAll(changes);return op.equals("commit")?true:null;}
                                    throw new UnsupportedOperationException(op);
                                });
                    }
                    throw new UnsupportedOperationException(name);
                });
    }
}
