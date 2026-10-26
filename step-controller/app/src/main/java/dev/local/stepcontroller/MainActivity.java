package dev.local.stepcontroller;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.time.ZoneId;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private EditText steps, cadence, wechatSteps;
    private Switch automatic, persist;
    private TextView preview, status, wechatStatus;
    private boolean shared;
    private final Runnable ticker = new Runnable() {
        @Override public void run() { renderStatus(); handler.postDelayed(this, 1000L); }
    };

    public static boolean frameworkActive() { return false; }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(245, 248, 246));
        getWindow().setNavigationBarColor(Color.rgb(245, 248, 246));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        try {
            prefs = getSharedPreferences(Settings.PREFS, Context.MODE_WORLD_READABLE);
            shared = true;
        } catch (SecurityException e) {
            prefs = getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(245, 248, 246));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(34), dp(24), dp(30));
        scroll.addView(root);
        addText(root, "今日，按你的节奏", 27, true, 0xff173e34);
        addText(root, "运动健康 · LSPosed 步数控制器", 14, false, 0xff63796f);
        status = addText(root, "", 14, false, 0xff63796f);
        preview = addText(root, "—", 46, true, 0xff167d68);
        addText(root, "本地今日步数", 18, true, 0xff173e34);
        StepPlan plan = Settings.read(prefs);
        steps = numberField(root, "起始 / 固定步数（0–99999）", plan.baseSteps);
        cadence = numberField(root, "自动步频（1–300 步/分钟）", plan.cadence);
        automatic = toggle(root, "自动模拟行走", plan.automatic);
        persist = toggle(root, "写入本地当日统计", plan.persist);
        addText(root, "自动模式从保存的起始值按步频增加。设置只在当天有效，跨日自动停止。", 14, false, 0xff63796f);
        addText(root, "写入统计后，记录会保留，并可能由运动健康按其原有方式同步；关闭模块不会回滚已写记录。", 13, false, 0xff63796f);
        button(root, "保存并启用本地步数", true, v -> save());
        button(root, "暂停自动增长，保留当前步数", false, v -> pause());
        button(root, "关闭本地模拟，恢复真实计步", false, v -> disable());
        addText(root, "微信同步步数", 23, true, 0xff173e34);
        WechatPlan wx = Settings.readWechat(prefs);
        wechatStatus = addText(root, "", 14, false, 0xff63796f);
        wechatSteps = numberField(root, "独立微信目标（1–99999 步）", wx.steps);
        addText(root, "微信目标独立保存：不需要开启本地模拟，也不会修改本地显示或日统计。设置只在当天有效。", 14, false, 0xff63796f);
        button(root, "保存并启用微信独立目标", true, v -> saveWechat());
        button(root, "关闭微信独立目标，跟随原来源", false, v -> disableWechat());
        addText(root, "保存不会立即提交。请在运动健康原有的微信同步页面触发同步；原自动同步、绑定检查、限频和分段处理照常执行，目标可能分次提交。", 13, false, 0xff63796f);
        addText(root, "关闭微信独立目标后，原同步来源可能包含本地模拟步数。微信侧不保证接受调低或任意目标值。", 13, false, 0xff63796f);
        button(root, "打开运动健康", false, v -> openTarget());
        addText(root, "首次使用：在 LSPosed 启用模块，勾选“运动健康”和模块自身，重启手机一次。更改设置后，运行中的计步进程通常约 5 秒内读取；进程被冻结时，在打开运动健康后更新。", 13, false, 0xff63796f);
        addText(root, "适配 6.9.19_b95456c_260918。加载详情见 LSPosed 日志中的 [StepController]。", 12, false, 0xff63796f);
        setContentView(scroll);
    }

    private void save() {
        if (!canSave()) return;
        try {
            int n = Integer.parseInt(steps.getText().toString().trim());
            int rate = Integer.parseInt(cadence.getText().toString().trim());
            if (n < 0 || n > StepPlan.MAX_STEPS || rate < 1 || rate > 300)
                throw new IllegalArgumentException();
            write(n, rate, automatic.isChecked(), true);
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, "请输入 0–99999 步、1–300 步/分钟", Toast.LENGTH_LONG).show();
        }
    }

    private void write(int n, int rate, boolean auto, boolean enabled) {
        long now = System.currentTimeMillis();
        boolean ok = Settings.saveLocal(prefs, n, rate, auto, enabled, persist.isChecked(), now,
                SystemClock.elapsedRealtime(), StepPlan.today(now, ZoneId.systemDefault()));
        Toast.makeText(this, ok ? "设置已保存" : "保存失败，请重开模块设置", Toast.LENGTH_SHORT).show();
        renderStatus();
    }

    private void pause() {
        if (!canSave()) return;
        StepPlan p = Settings.read(prefs);
        int n = p.steps(System.currentTimeMillis(), SystemClock.elapsedRealtime());
        steps.setText(String.valueOf(n));
        automatic.setChecked(false);
        write(n, p.cadence, false, p.active(System.currentTimeMillis(), ZoneId.systemDefault()));
    }

    private void disable() {
        if (!canSave()) return;
        boolean ok = Settings.disableLocal(prefs, System.currentTimeMillis());
        renderStatus();
        Toast.makeText(this, ok ? "本地模拟已关闭；微信独立目标保持原设置" : "关闭本地模拟失败", Toast.LENGTH_LONG).show();
    }

    private void saveWechat() {
        if (!canSave()) return;
        try {
            int n = Integer.parseInt(wechatSteps.getText().toString().trim());
            if (n < 1 || n > StepPlan.MAX_STEPS) throw new IllegalArgumentException();
            long now = System.currentTimeMillis();
            // Only wx_* fields are touched: local auto timing, count and enabled
            // state must survive an unrelated WeChat edit unchanged.
            boolean ok = Settings.saveWechat(prefs, n, now, StepPlan.today(now, ZoneId.systemDefault()));
            Toast.makeText(this, ok ? "微信目标已保存；请在运动健康中同步" : "微信目标保存失败", Toast.LENGTH_LONG).show();
            renderStatus();
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, "微信目标需为 1–99999 步", Toast.LENGTH_LONG).show();
        }
    }

    private void disableWechat() {
        if (!canSave()) return;
        boolean ok = Settings.disableWechat(prefs, System.currentTimeMillis());
        Toast.makeText(this, ok ? "已恢复微信原同步来源；本地设置未更改" : "关闭失败", Toast.LENGTH_LONG).show();
        renderStatus();
    }

    private boolean canSave() {
        if (shared) return true;
        Toast.makeText(this, "LSPosed 设置共享尚未启用。勾选模块作用域并重启后，重新打开设置。", Toast.LENGTH_LONG).show();
        return false;
    }

    private void renderStatus() {
        if (prefs == null || preview == null) return;
        StepPlan p = Settings.read(prefs);
        boolean on = p.active(System.currentTimeMillis(), ZoneId.systemDefault());
        preview.setText(on ? String.format(java.util.Locale.getDefault(), "%,d", p.steps(System.currentTimeMillis(), SystemClock.elapsedRealtime())) : "—");
        String state = !shared ? "等待 LSPosed 启用设置共享" : on ? (p.automatic ? "自动行走中" : "固定步数已启用") : "已关闭 / 当天设置已过期";
        status.setText(state + (frameworkActive() ? " · 模块自身已加载" : " · 目标加载状态见 LSPosed 日志"));
        if (wechatStatus != null) {
            WechatPlan wx = Settings.readWechat(prefs);
            boolean wxOn = wx.active(System.currentTimeMillis(), ZoneId.systemDefault());
            wechatStatus.setText(wxOn ? "独立微信目标：" + wx.steps + " 步" : "独立目标已关闭 / 过期，跟随原同步来源");
        }
    }

    private void openTarget() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(Settings.TARGET);
        if (intent != null) startActivity(intent);
        else Toast.makeText(this, "未找到运动健康应用", Toast.LENGTH_LONG).show();
    }
    @Override protected void onResume() { super.onResume(); handler.post(ticker); }
    @Override protected void onPause() { handler.removeCallbacks(ticker); super.onPause(); }

    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }
    private TextView addText(LinearLayout root, String text, int size, boolean bold, int color) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(null, Typeface.BOLD);
        v.setPadding(0, dp(9), 0, dp(5)); root.addView(v); return v;
    }
    private EditText numberField(LinearLayout root, String label, int value) {
        addText(root, label, 14, true, 0xff173e34);
        EditText v = new EditText(this); v.setSingleLine(true);
        v.setInputType(InputType.TYPE_CLASS_NUMBER); v.setText(String.valueOf(value));
        v.setTextSize(21); root.addView(v); return v;
    }
    private Switch toggle(LinearLayout root, String label, boolean value) {
        Switch v = new Switch(this); v.setText(label); v.setTextSize(16); v.setChecked(value);
        v.setPadding(0, dp(12), 0, dp(12)); root.addView(v); return v;
    }
    private void button(LinearLayout root, String label, boolean primary, View.OnClickListener listener) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(15);
        b.setTextColor(primary ? Color.WHITE : 0xff167d68);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(16));
        bg.setColor(primary ? 0xff167d68 : 0xffe4eee8); b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52)); lp.topMargin = dp(12);
        root.addView(b, lp); b.setOnClickListener(listener);
    }
}
