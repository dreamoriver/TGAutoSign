package io.github.wlmosv_png.tgautosign;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;

/**
 * Telegram 进程侧的配置缓存。
 *
 * 模块跑在 Telegram 进程里，配置的真身现在放在模块自己的进程（独立设置页写）。
 * 这里在启动时拉一次全量，之后所有 jmb_* 读取先命中内存缓存，
 * 没拉到时才回落到 Telegram 本地 prefs（老用户升级前的老配置仍然有效）。
 *
 * 刷新时机：模块初始化、以及收到 SETTINGS_CHANGED 广播。
 */
public final class RemoteConfig {

    public static final String ACTION_CHANGED = "io.github.wlmosv_png.tgautosign.SETTINGS_CHANGED";
    /** 独立界面请求目标快照。 */
    public static final String ACTION_REQUEST_TARGETS = "io.github.wlmosv_png.tgautosign.REQUEST_TARGETS";
    private static final Uri URI = Uri.parse("content://" + MiuiSettingsProvider.AUTHORITY + "/settings");

    private static final Map<String, Object> CACHE = new HashMap<>();
    private static volatile boolean loaded = false;
    private static volatile long updatedAt = 0L;
    private static long lastAttempt = 0L;

    private RemoteConfig() {}

    /** 拉取全量配置。失败不做任何事，调用方继续用本地 prefs。 */
    public static synchronized void refresh(Context context) {
        if (context == null) return;
        long now = System.currentTimeMillis();
        // 一秒内重复调用直接复用上次结果，避免初始化阶段被反复触发。
        if (now - lastAttempt < 1000L) return;
        lastAttempt = now;
        try {
            ContentResolver resolver = context.getContentResolver();
            Bundle result = resolver.call(URI, "get_all", null, null);
            if (result == null) return;
            Map<String, Object> next = new HashMap<>();
            for (String key : MiuiSettingsProvider.SYNCED_KEYS) {
                if (!result.containsKey(key)) continue;
                Object value = result.get(key);
                if (value != null) next.put(key, value);
            }
            synchronized (CACHE) {
                CACHE.clear();
                CACHE.putAll(next);
            }
            updatedAt = result.getLong("_updated", 0L);
            loaded = !next.isEmpty();
        } catch (Throwable ignored) {
            // 模块进程没起来 / Provider 不可见：保持原来的本地行为即可。
        }
    }

    /** 收到广播时调用：强制重拉一次。 */
    public static synchronized void invalidate(Context context) {
        lastAttempt = 0L;
        loaded = false;
        synchronized (CACHE) { CACHE.clear(); }
        refresh(context);
    }

    public static boolean has(String key) {
        synchronized (CACHE) { return CACHE.containsKey(key); }
    }

    public static Boolean getBoolean(String key) {
        synchronized (CACHE) {
            Object value = CACHE.get(key);
            if (value instanceof Boolean) return (Boolean) value;
            if (value instanceof String) return Boolean.parseBoolean((String) value);
            return null;
        }
    }

    public static Integer getInt(String key) {
        synchronized (CACHE) {
            Object value = CACHE.get(key);
            if (value instanceof Integer) return (Integer) value;
            if (value instanceof Number) return ((Number) value).intValue();
            return null;
        }
    }

    public static Long getLong(String key) {
        synchronized (CACHE) {
            Object value = CACHE.get(key);
            if (value instanceof Long) return (Long) value;
            if (value instanceof Number) return ((Number) value).longValue();
            return null;
        }
    }

    public static String getString(String key) {
        synchronized (CACHE) {
            Object value = CACHE.get(key);
            return value == null ? null : String.valueOf(value);
        }
    }

    public static boolean isLoaded() { return loaded; }

    public static long updatedAt() { return updatedAt; }
}
