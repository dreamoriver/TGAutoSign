package io.github.wlmosv_png.tgautosign;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 独立设置页（MIUI 风格）。
 *
 * 只放「每天要碰的配置」：签到窗口、定时、间隔、补签、通知。
 * 目标管理与逐条状态仍在 Telegram 里的 /jmb 面板——那部分数据在 Telegram 进程。
 *
 * 写配置后向 Telegram 进程发广播，让它立刻重拉，不用重启微信/Telegram。
 */
public class SettingsHomeActivity extends Activity {

    private static final String PREFS = MiuiSettingsProvider.PREFS;

    private int bgColor, cardColor, textColor, subColor, accentColor, dividerColor;
    private LinearLayout root;
    private SharedPreferences prefs;
    /** Telegram 里的真实配置（su 读出来），设置页优先用它渲染。 */
    private final java.util.Map<String, String> remote = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean dark = (getResources().getConfiguration().uiMode & 0x30) == 0x20;
        bgColor = dark ? 0xFF000000 : 0xFFF2F3F5;
        cardColor = dark ? 0xFF1C1C1E : 0xFFFFFFFF;
        textColor = dark ? 0xFFF5F5F5 : 0xFF1A1A1A;
        subColor = dark ? 0xFF8E8E93 : 0xFF8A8A8E;
        accentColor = dark ? 0xFF3B82F6 : 0xFF2563EB;
        dividerColor = dark ? 0xFF2C2C2E : 0xFFEDEDED;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bgColor);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), statusBarHeight() + dp(8), dp(16), dp(32));
        scroll.addView(root);
        setContentView(scroll);
        remote.putAll(RootConfig.readAll());
        render();
    }

    private void render() {
        root.removeAllViews();
        header("签到设置", "改完自动同步到 Telegram，不用重启");
        addTargetsEntry();
        section("签到");
        switchRow("定时签到", "每天在窗口内自动签一次", Keys.timer(), defaultBool("jmb_timer", true));
        valueRow("签到窗口", "只在这个时间段内签", windowLabel(), v -> pickWindow());
        valueRow("错开间隔", "多个目标之间隔几秒", gapValue() + " 秒", v -> pickGap());
        section("补签与提醒");
        switchRow("错过补签", "错过窗口后当天还能补一次", Keys.missBack(), defaultBool("jmb_missback", true));
        valueRow("补签截止", "超过这个时间不再补", minuteLabel(deadlineValue()), v -> pickDeadline());
        switchRow("签到结果通知", "签完在通知栏提示", Keys.notifyOn(), defaultBool("jmb_notify", true));
        switchRow("只通知失败", "成功的不打扰", Keys.notifyFailOnly(), defaultBool("jmb_notify_fail_only", false));
        section("关于");
        valueRow("版本", appVersion() + " · 设置页由独立界面提供", "", null);
        valueRow("签到目标", "查看每个目标的今日状态", "›", v ->
                startActivity(new Intent(SettingsHomeActivity.this, TargetsActivity.class)));
    }

    // ───────── 行构建 ─────────

    private void header(String title, String subtitle) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(26);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(textColor);
        t.setPadding(dp(6), dp(24), dp(6), dp(2));
        root.addView(t);
        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextSize(12);
        s.setTextColor(subColor);
        s.setPadding(dp(6), 0, dp(6), dp(10));
        root.addView(s);
    }

    /** 顶部目标入口：省得再去 /jmb 里翻，也避免桌面上出现两个图标。 */
    private void addTargetsEntry() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardColor);
        bg.setCornerRadius(dp(18));
        card.setBackground(bg);
        card.setClickable(true);
        card.setOnClickListener(v ->
                startActivity(new Intent(SettingsHomeActivity.this, TargetsActivity.class)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView title = new TextView(this);
        title.setText("签到目标");
        title.setTextSize(16);
        title.setTextColor(textColor);
        texts.addView(title);
        TextView sub = new TextView(this);
        sub.setText("正在读取…");
        sub.setTextSize(12);
        sub.setTextColor(subColor);
        sub.setPadding(0, dp(2), 0, 0);
        texts.addView(sub);
        card.addView(texts);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(20);
        arrow.setTextColor(subColor);
        card.addView(arrow);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        root.addView(card, params);

        new Thread(() -> {
            java.util.List<RootConfig.Target> list = RootConfig.readTargets();
            int signed = 0;
            for (RootConfig.Target t : list) if (t.signed) signed++;
            final String label = list.isEmpty()
                    ? "点此查看（没读到数据，检查 root 授权）"
                    : list.size() + " 个目标 · 今日已签 " + signed;
            runOnUiThread(() -> sub.setText(label));
        }).start();
    }

    private void section(String name) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(13);
        t.setTextColor(subColor);
        t.setPadding(dp(6), dp(18), dp(6), dp(8));
        root.addView(t);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardColor);
        bg.setCornerRadius(dp(18));
        card.setBackground(bg);
        card.setTag("card_" + name);
        root.addView(card);
    }

    private LinearLayout currentCard() {
        View last = root.getChildAt(root.getChildCount() - 1);
        return last instanceof LinearLayout ? (LinearLayout) last : root;
    }

    private void switchRow(String title, String subtitle, String key, boolean def) {
        String raw = remote.get(key);
        boolean checked = raw != null ? Boolean.parseBoolean(raw)
                : (prefs.contains(key) ? prefs.getBoolean(key, def) : def);
        LinearLayout row = baseRow();
        addTexts(row, title, subtitle);
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((b, value) -> {
            prefs.edit().putBoolean(key, value).commit();
            notifyTelegram();
        });
        row.addView(sw);
        currentCard().addView(row);
    }

    private void valueRow(String title, String subtitle, String value, View.OnClickListener click) {
        LinearLayout row = baseRow();
        addTexts(row, title, subtitle);
        TextView val = new TextView(this);
        val.setText(value);
        val.setTextSize(14);
        val.setTextColor(subColor);
        if (click != null) val.setTextColor(accentColor);
        row.addView(val);
        if (click != null) {
            row.setOnClickListener(click);
            val.setOnClickListener(click);
        }
        currentCard().addView(row);
    }

    private LinearLayout baseRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(18), dp(14));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void addTexts(LinearLayout row, String title, String subtitle) {
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(textColor);
        texts.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(subtitle);
            s.setTextSize(12);
            s.setTextColor(subColor);
            s.setPadding(0, dp(2), 0, 0);
            texts.addView(s);
        }
        row.addView(texts);
    }

    // ───────── 配置读写 ─────────

    private boolean defaultBool(String key, boolean def) {
        return def;
    }

    private String windowLabel() {
        String window = remote.get(Keys.window());
        if (window == null || window.isEmpty()) {
            window = prefs.getString(Keys.window(), null);
        }
        if (window == null || window.isEmpty()) {
            window = prefs.getString("jmb_window", "08:00-23:00");
        }
        return window.replace("-", " → ");
    }

    private String minuteLabel(int minutes) {
        int h = Math.max(0, Math.min(23, minutes / 60));
        int m = Math.max(0, Math.min(59, minutes % 60));
        return String.format(java.util.Locale.US, "%02d:%02d", h, m);
    }

    private int gapValue() {
        String raw = remote.get(Keys.gap());
        try { return raw == null ? prefs.getInt(Keys.gap(), 30) : Integer.parseInt(raw.trim()); }
        catch (Throwable t) { return prefs.getInt(Keys.gap(), 30); }
    }

    private int deadlineValue() {
        String raw = remote.get(Keys.missDeadline());
        try { return raw == null ? prefs.getInt(Keys.missDeadline(), 23 * 60) : Integer.parseInt(raw.trim()); }
        catch (Throwable t) { return prefs.getInt(Keys.missDeadline(), 23 * 60); }
    }

    private int[] parseWindow() {
        String window = prefs.getString(Keys.window(), "08:00-23:00");
        try {
            String[] parts = window.split("-");
            int[] start = parseHm(parts[0]);
            int[] end = parseHm(parts[1]);
            return new int[]{start[0], start[1], end[0], end[1]};
        } catch (Throwable ignored) {
            return new int[]{8, 0, 23, 0};
        }
    }

    private int[] parseHm(String value) {
        String[] p = value.trim().split(":");
        return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])};
    }

    private void pickWindow() {
        int[] w = parseWindow();
        new TimePickerDialog(this, (view, sh, sm) -> {
            int[] end = parseWindow();
            new TimePickerDialog(this, (v2, eh, em) -> {
                String window = String.format(java.util.Locale.US, "%02d:%02d-%02d:%02d", sh, sm, eh, em);
                prefs.edit().putString(Keys.window(), window).commit();
                notifyTelegram();
                render();
            }, end[2], end[3], true).show();
        }, w[0], w[1], true).show();
    }

    private void pickGap() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        int current = prefs.getInt(Keys.gap(), 30);
        input.setText(String.valueOf(current));
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("错开间隔（秒）")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    int value = 5;
                    try {
                        value = Math.max(0, Math.min(3600, Integer.parseInt(input.getText().toString().trim())));
                    } catch (Throwable ignored) {}
                    prefs.edit().putInt(Keys.gap(), value).commit();
                    notifyTelegram();
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickDeadline() {
        int minutes = prefs.getInt(Keys.missDeadline(), 23 * 60);
        new TimePickerDialog(this, (view, h, m) -> {
            prefs.edit().putInt(Keys.missDeadline(), h * 60 + m).commit();
            notifyTelegram();
            render();
        }, minutes / 60, minutes % 60, true).show();
    }

    /** 通知 Telegram 进程重拉配置：进程可能没在跑，失败也无所谓，下次启动会自己拉。 */
    private void notifyTelegram() {
        try {
            Intent intent = new Intent(RemoteConfig.ACTION_CHANGED);
            intent.setPackage("fork.risin42.nagramx");
            sendBroadcast(intent);
            intent.setPackage("org.telegram.messenger");
            sendBroadcast(intent);
            intent.setPackage("org.telegram.messenger.web");
            sendBroadcast(intent);
        } catch (Throwable ignored) {}
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) return getResources().getDimensionPixelSize(id);
        return dp(24);
    }

    /** 从设置页直接跳回 Telegram，省得用户自己找图标。 */
    private void openTelegram() {
        for (String pkg : new String[]{"fork.risin42.nagramx", "org.telegram.messenger",
                "org.telegram.messenger.web", "org.telegram.messenger.beta"}) {
            try {
                Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
                if (intent != null) {
                    startActivity(intent);
                    return;
                }
            } catch (Throwable ignored) {}
        }
        Toast.makeText(this, "没找到 Telegram 客户端", Toast.LENGTH_SHORT).show();
    }

    /** 模块没开 buildConfig，版本号从 PackageManager 取。 */
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "1.7.2";
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
