package io.github.wlmosv_png.tgautosign;

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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 目标列表（只读快照）。
 *
 * 数据在 Telegram 进程里，这边只能通过在页面打开时请求一次快照来展示：
 * 发 REQUEST_TARGETS 广播 → 宿主侧 pushTargetsToWidget 推回来 → 读本地缓存渲染。
 * 签到、删除这类写操作仍在 /jmb 面板，避免两条写路径打架。
 */
public class TargetsActivity extends Activity {

    private int bgColor, cardColor, textColor, subColor, okColor, waitColor, warnColor, accentColor;
    private LinearLayout root;
    private TextView statusLine;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean refreshing = false;
    private final Runnable autoRefresh = new Runnable() {
        @Override public void run() {
            if (!isFinishing() && !isDestroyed()) {
                loadFromRoot(true);
                handler.postDelayed(this, 8000L);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean dark = (getResources().getConfiguration().uiMode & 0x30) == 0x20;
        bgColor = dark ? 0xFF000000 : 0xFFF2F3F5;
        cardColor = dark ? 0xFF1C1C1E : 0xFFFFFFFF;
        textColor = dark ? 0xFFF5F5F5 : 0xFF1A1A1A;
        subColor = dark ? 0xFF8E8E93 : 0xFF8A8A8E;
        okColor = 0xFF34C759;
        waitColor = 0xFFFF9F0A;
        warnColor = 0xFFFF453A;
        accentColor = dark ? 0xFF3B82F6 : 0xFF2563EB;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bgColor);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), statusBarHeight() + dp(8), dp(16), dp(32));
        scroll.addView(root);
        setContentView(scroll);
        render(null, "正在读取 Telegram 的目标数据…");
        loadFromRoot(false);
        handler.postDelayed(autoRefresh, 8000L);
    }

    private void requestTargets() {
        try {
            Intent intent = new Intent(RemoteConfig.ACTION_REQUEST_TARGETS);
            for (String pkg : new String[]{"fork.risin42.nagramx", "org.telegram.messenger",
                    "org.telegram.messenger.web", "org.telegram.messenger.beta"}) {
                intent.setPackage(pkg);
                sendBroadcast(intent);
            }
        } catch (Throwable ignored) {}
        // 给宿主 1.5 秒回推，然后渲染缓存（拿不到就显示提示）。
        handler.postDelayed(this::loadFromCache, 1500);
    }

    /** 直接用 root 读 Telegram 的 prefs，绕开包可见性限制。silent=true 时不显示加载态。 */
    private void loadFromRoot(boolean silent) {
        if (refreshing) return;
        refreshing = true;
        new Thread(() -> {
            List<RootConfig.Target> targets;
            String error = null;
            try {
                targets = RootConfig.readTargets();
            } catch (Throwable t) {
                targets = new ArrayList<>();
                error = t.getClass().getSimpleName();
            }
            final List<RootConfig.Target> result = targets;
            final String failure = error;
            runOnUiThread(() -> {
                refreshing = false;
                if (failure != null) {
                    if (!silent) render(null, "读取失败：" + failure + "。请确认已授予 root 权限。");
                } else if (result.isEmpty()) {
                    if (!silent) render(null, "没读到目标。确认 Telegram 里已经有签到目标，且已授予 root 权限。");
                } else {
                    renderTargets(result);
                }
            });
        }).start();
    }

    private void renderTargets(List<RootConfig.Target> targets) {
        int signed = 0;
        for (RootConfig.Target t : targets) if (t.signed) signed++;
        render(null, targets.size() + " 个目标 · 今日已签 " + signed
                + " · " + new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date()));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardColor);
        bg.setCornerRadius(dp(18));
        card.setBackground(bg);
        root.addView(card);
        for (RootConfig.Target t : targets) card.addView(targetRow(t));
    }

    private View targetRow(RootConfig.Target t) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(18), dp(14));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(t.title);
        name.setTextSize(16);
        name.setTextColor(textColor);
        texts.addView(name);

        TextView sub = new TextView(this);
        String detail = "bot " + t.botId + (t.kind.isEmpty() ? "" : " · " + t.kind);
        if (!t.signed && !t.lastDate.isEmpty()) detail += " · 上次 " + t.lastDate;
        sub.setText(detail);
        sub.setTextSize(12);
        sub.setTextColor(subColor);
        sub.setPadding(0, dp(2), 0, 0);
        texts.addView(sub);
        row.addView(texts);

        TextView badge = new TextView(this);
        badge.setText(t.signed ? "已签" : t.pending ? "待确认" : "待签");
        badge.setTextSize(13);
        badge.setTextColor(t.signed ? okColor : t.pending ? warnColor : waitColor);
        badge.setPadding(dp(8), 0, dp(10), 0);
        row.addView(badge);

        TextView action = new TextView(this);
        action.setText("签到");
        action.setTextSize(13);
        action.setTextColor(accentColor);
        action.setPadding(dp(10), dp(6), dp(2), dp(6));
        action.setOnClickListener(v -> {
            action.setText("已下发");
            action.setTextColor(subColor);
            sendCommand("sign|" + t.entryId);
        });
        row.addView(action);

        // 长按删除（二次确认）
        row.setOnLongClickListener(v -> {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("删除目标")
                    .setMessage("确定删除「" + t.title + "」？这个操作不可撤销。")
                    .setPositiveButton("删除", (d, w) -> sendCommand("del|" + t.entryId))
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        });
        return row;
    }

    /** 下发命令给宿主心跳执行。 */
    private void sendCommand(String line) {
        new Thread(() -> {
            String pkg = RootConfig.installedClient();
            boolean ok = pkg != null && RootConfig.pushCommand(pkg, line);
            runOnUiThread(() -> Toast.makeText(this,
                    ok ? "已下发，几秒内生效" : "下发失败，请先打开一次 Telegram",
                    Toast.LENGTH_SHORT).show());
        }).start();
    }

    private void loadFromCache() {
        SharedPreferences prefs = getSharedPreferences(MiuiSettingsProvider.PREFS, Context.MODE_PRIVATE);
        String json = prefs.getString("targets_json", null);
        long at = prefs.getLong("targets_at", 0L);
        String account = prefs.getString("targets_account", "0");
        if (json == null) {
            render(null, "还没有收到目标数据。请先在 Telegram 里打开一次 /jmb，再回来刷新。");
            return;
        }
        try {
            JSONArray arr = new JSONArray(json);
            render(arr, "账号 " + account + " · " + arr.length() + " 个目标 · 更新于 "
                    + new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(at)));
        } catch (Throwable t) {
            render(null, "目标数据解析失败：" + t.getClass().getSimpleName());
        }
    }

    private void render(JSONArray targets, String status) {
        root.removeAllViews();
        TextView title = new TextView(this);
        title.setText("签到目标");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(textColor);
        title.setPadding(dp(6), dp(24), dp(6), dp(2));
        root.addView(title);

        statusLine = new TextView(this);
        statusLine.setText(status);
        statusLine.setTextSize(12);
        statusLine.setTextColor(subColor);
        statusLine.setPadding(dp(6), 0, dp(6), dp(10));
        root.addView(statusLine);

        TextView refresh = new TextView(this);
        refresh.setText("刷新");
        refresh.setTextSize(14);
        refresh.setTextColor(0xFF2563EB);
        refresh.setPadding(dp(6), dp(6), dp(6), dp(14));
        refresh.setOnClickListener(v -> {
            statusLine.setText("正在刷新…");
            loadFromRoot(false);
        });
        root.addView(refresh);

        if (targets == null || targets.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText(targets == null ? "" : "目标列表是空的，先在 /jmb 里添加一个。");
            empty.setTextSize(14);
            empty.setTextColor(subColor);
            empty.setPadding(dp(6), dp(4), dp(6), dp(4));
            root.addView(empty);
            return;
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardColor);
        bg.setCornerRadius(dp(18));
        card.setBackground(bg);
        root.addView(card);

        for (int i = 0; i < targets.length(); i++) {
            JSONObject o = targets.optJSONObject(i);
            if (o == null) continue;
            card.addView(targetRow(o));
        }
    }

    private View targetRow(JSONObject o) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(18), dp(14));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(o.optString("title", o.optString("cmd", "未命名目标")));
        name.setTextSize(16);
        name.setTextColor(textColor);
        texts.addView(name);

        String cmd = o.optString("cmd", "");
        String note = o.optString("note", "");
        TextView sub = new TextView(this);
        sub.setText(note.isEmpty() ? "/" + cmd.replace("/", "") : note);
        sub.setTextSize(12);
        sub.setTextColor(subColor);
        sub.setPadding(0, dp(2), 0, 0);
        texts.addView(sub);
        row.addView(texts);

        TextView badge = new TextView(this);
        boolean signed = o.optBoolean("signed", false);
        boolean pending = o.optBoolean("pending", false);
        badge.setText(pending ? "待确认" : signed ? "已签" : "待签");
        badge.setTextSize(13);
        badge.setTextColor(pending ? warnColor : signed ? okColor : waitColor);
        row.addView(badge);
        return row;
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(autoRefresh);
        super.onDestroy();
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) return getResources().getDimensionPixelSize(id);
        return dp(24);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
