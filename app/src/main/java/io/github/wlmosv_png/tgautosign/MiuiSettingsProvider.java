package io.github.wlmosv_png.tgautosign;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/**
 * 设置页与 Telegram 进程之间的配置通道。
 *
 * 为什么需要：签到模块本身跑在 Telegram 进程里，配置存在那边的私有目录，
 * 独立设置页读不到。这里把「签到窗口 / 定时 / 间隔 / 补签 / 通知」这类
 * jmb_* 配置放到模块自己的进程，由本 Provider 提供给 Telegram 侧读取。
 *
 * 读取不设限（都是非敏感配置）；写入只接受 Telegram 系客户端，避免任意应用改配置。
 */
public class MiuiSettingsProvider extends ContentProvider {

    public static final String AUTHORITY = "io.github.wlmosv_png.tgautosign.settings";
    public static final String PREFS = "miui_settings";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/settings");

    /** 与 Telegram 侧同步的配置键，都是 jmb_ 全局开关/数值。 */
    public static final String[] SYNCED_KEYS = {
            "jmb_window", "jmb_timer", "jmb_gap", "jmb_missback", "jmb_missdead",
            "jmb_notify", "jmb_notify_fail_only", "jmb_catchup", "jmb_sort"
    };

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context ctx = getContext();
        if (ctx == null) return null;
        if ("get_all".equals(method)) {
            Bundle out = new Bundle();
            SharedPreferences p = prefs(ctx);
            for (String key : SYNCED_KEYS) {
                Object value = p.getAll().get(key);
                if (value instanceof Boolean) out.putBoolean(key, (Boolean) value);
                else if (value instanceof Integer) out.putInt(key, (Integer) value);
                else if (value instanceof Long) out.putLong(key, (Long) value);
                else if (value instanceof String) out.putString(key, (String) value);
            }
            out.putLong("_updated", p.getLong("_updated", 0L));
            return out;
        }
        if ("sync_targets".equals(method)) {
            // Telegram 侧推送目标快照，只读缓存，供独立界面展示。
            if (!callerAllowed(ctx)) return denied();
            if (extras == null) return denied();
            SharedPreferences.Editor editor = prefs(ctx).edit();
            String json = extras.getString("json");
            if (json != null) editor.putString("targets_json", json);
            String account = extras.getString("account");
            if (account != null) editor.putString("targets_account", account);
            editor.putLong("targets_at", extras.getLong("at", System.currentTimeMillis()));
            Bundle out = new Bundle();
            out.putBoolean("saved", editor.commit());
            return out;
        }
        if ("put".equals(method)) {
            if (!callerAllowed(ctx)) return denied();
            if (extras == null || arg == null) return denied();
            SharedPreferences.Editor editor = prefs(ctx).edit();
            Object value = extras.get(arg);
            if (value instanceof Boolean) editor.putBoolean(arg, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(arg, (Integer) value);
            else if (value instanceof Long) editor.putLong(arg, (Long) value);
            else if (value instanceof String) editor.putString(arg, (String) value);
            else return denied();
            editor.putLong("_updated", System.currentTimeMillis());
            boolean saved = editor.commit();
            Bundle out = new Bundle();
            out.putBoolean("saved", saved);
            return out;
        }
        return null;
    }

    /** 只有 Telegram 系客户端能写。读取不设限，配置本身不含敏感信息。 */
    private boolean callerAllowed(Context ctx) {
        try {
            String[] packages = ctx.getPackageManager().getPackagesForUid(Binder.getCallingUid());
            if (packages == null) return false;
            for (String name : packages) {
                if (name == null) continue;
                String lower = name.toLowerCase();
                if (lower.contains("telegram") || lower.contains("nagram")
                        || lower.contains("nekogram") || lower.contains("telescope")) return true;
            }
        } catch (Throwable ignored) {}
        return Binder.getCallingUid() == android.os.Process.myUid();
    }

    private Bundle denied() {
        Bundle out = new Bundle();
        out.putBoolean("saved", false);
        return out;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
