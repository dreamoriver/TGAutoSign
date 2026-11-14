package io.github.wlmosv_png.tgautosign;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通过 root 直接读 Telegram 进程的签到配置。
 *
 * 为什么不用 Provider：Telegram 是 targetSdk 36，Android 11+ 的包可见性让它
 * 根本解析不到模块的 authority（logcat: Failed to find provider info）。
 * 本机有 KernelSU，直接 su cat 那份 prefs 反而更简单可靠。
 *
 * 目标数据形如：acc0_title_<botId>_<entryId> / acc0_last_<botId>_<entryId>
 */
public final class RootConfig {

    /** 目前只读这个文件；packages 顺序即优先级。 */
    private static final String[] CLIENTS = {
            "fork.risin42.nagramx", "org.telegram.messenger",
            "org.telegram.messenger.web", "org.telegram.messenger.beta"
    };
    private static final String PREFS = "tg_autosign_gen.xml";

    public static final class Target {
        public String key = "";
        public String botId = "";
        public String title = "";
        public String kind = "";
        public boolean signed = false;
        public String signedAt = "";
    }

    private RootConfig() {}

    /** 读一次全部目标；失败返回空表，由界面提示。 */
    public static List<Target> readTargets() {
        String xml = readPrefs();
        List<Target> out = new ArrayList<>();
        if (xml == null || xml.isEmpty()) return out;
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        Map<String, Target> map = new LinkedHashMap<>();

        // <string name="acc0_title_5969411283_1">某个 bot</string>
        Pattern title = Pattern.compile("<string name=\"acc(\\d+)_title_(\\d+)_(\\d+)\">([^<]*)</string>");
        Matcher m = title.matcher(xml);
        while (m.find()) {
            Target t = new Target();
            t.key = "acc" + m.group(1) + "_" + m.group(2) + "_" + m.group(3);
            t.botId = m.group(2);
            t.title = unescape(m.group(4));
            map.put(t.key, t);
        }
        // kind：text / cb
        Pattern kind = Pattern.compile("<string name=\"acc(\\d+)_kind_(\\d+)_(\\d+)\">([^<]*)</string>");
        Matcher k = kind.matcher(xml);
        while (k.find()) {
            Target t = map.get("acc" + k.group(1) + "_" + k.group(2) + "_" + k.group(3));
            if (t != null) t.kind = unescape(k.group(4));
        }
        // last：今日已签日期
        Pattern last = Pattern.compile("<string name=\"acc(\\d+)_last_(\\d+)_(\\d+)\">([^<]*)</string>");
        Matcher l = last.matcher(xml);
        while (l.find()) {
            Target t = map.get("acc" + l.group(1) + "_" + l.group(2) + "_" + l.group(3));
            if (t != null) {
                String value = unescape(l.group(4));
                t.signed = today.equals(value);
                t.signedAt = value;
            }
        }
        // 没有 title 的目标也列出来，用 botId 兜底
        Pattern did = Pattern.compile("<string name=\"acc(\\d+)_did_(\\d+)_(\\d+)\">([^<]*)</string>");
        Matcher d = did.matcher(xml);
        while (d.find()) {
            String key = "acc" + d.group(1) + "_" + d.group(2) + "_" + d.group(3);
            if (!map.containsKey(key)) {
                Target t = new Target();
                t.key = key;
                t.botId = d.group(2);
                t.title = "bot " + d.group(2);
                map.put(key, t);
            }
        }
        out.addAll(map.values());
        return out;
    }

    /** 读一份配置项（jmb_*），给设置页展示用。 */
    public static String readValue(String key) {
        String xml = readPrefs();
        if (xml == null) return null;
        Matcher s = Pattern.compile("<string name=\"" + Pattern.quote(key) + "\">([^<]*)</string>").matcher(xml);
        if (s.find()) return unescape(s.group(1));
        Matcher b = Pattern.compile("<boolean name=\"" + Pattern.quote(key) + "\" value=\"(true|false)\"").matcher(xml);
        if (b.find()) return b.group(1);
        Matcher i = Pattern.compile("<int name=\"" + Pattern.quote(key) + "\" value=\"(-?\\d+)\"").matcher(xml);
        if (i.find()) return i.group(1);
        return null;
    }

    /** su cat 目标文件；哪个客户端装了就读哪个。 */
    private static String readPrefs() {
        for (String pkg : CLIENTS) {
            String path = "/data/data/" + pkg + "/shared_prefs/" + PREFS;
            String content = exec("cat " + path);
            if (content != null && content.contains("<map>")) return content;
        }
        return null;
    }

    private static String exec(String command) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            InputStream in = process.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            long deadline = System.currentTimeMillis() + 5000L;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
                if (System.currentTimeMillis() > deadline) break;
            }
            int code = process.waitFor();
            String out = buffer.toString("UTF-8");
            String err = "";
            try {
                InputStream es = process.getErrorStream();
                ByteArrayOutputStream eb = new ByteArrayOutputStream();
                byte[] c2 = new byte[2048];
                int r2;
                while ((r2 = es.read(c2)) > 0) eb.write(c2, 0, r2);
                err = eb.toString("UTF-8");
            } catch (Throwable ignored) {}
            android.util.Log.i("RootConfig", "exec exit=" + code + " outLen=" + out.length()
                    + " err=" + err.trim());
            return out;
        } catch (Throwable t) {
            android.util.Log.e("RootConfig", "exec 失败: " + t);
            return null;
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static String unescape(String value) {
        return value.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }
}
