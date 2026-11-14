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
        /** 宿主侧的目标 id，形如 <botId>_<entryId>，下发命令用这个。 */
        public String entryId = "";
        public String botId = "";
        public String title = "";
        public String kind = "";
        public boolean signed = false;
        public boolean pending = false;
        public String signedAt = "";
        /** 最后一次成功签到的日期，没成功过就是空。 */
        public String lastDate = "";
        /** 是否已冻结（不再自动签到，直到解冻）。 */
        public boolean frozen = false;
        /** 暂停到期日 yyyy-MM-dd；空 = 未暂停。 */
        public String snoozeUntil = "";
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
            t.entryId = m.group(2) + "_" + m.group(3);
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
                t.lastDate = value;
            }
        }
        // 待确认：模块发了请求但没判定成功
        Pattern pending = Pattern.compile("<boolean name=\"acc(\\d+)_pendcfm_(\\d+)_(\\d+)\" value=\"true\"");
        Matcher p = pending.matcher(xml);
        while (p.find()) {
            Target t = map.get("acc" + p.group(1) + "_" + p.group(2) + "_" + p.group(3));
            if (t != null) t.pending = true;
        }
        // 冻结：frozen_ 是布尔，不按天
        Pattern frozen = Pattern.compile("<boolean name=\"acc(\\d+)_frozen_(\\d+)_(\\d+)\" value=\"true\"");
        Matcher f = frozen.matcher(xml);
        while (f.find()) {
            Target t = map.get("acc" + f.group(1) + "_" + f.group(2) + "_" + f.group(3));
            if (t != null) t.frozen = true;
        }
        // 暂停：snooze_ 存的是到期日，过期的当作没暂停
        Pattern snooze = Pattern.compile("<string name=\"acc(\\d+)_snooze_(\\d+)_(\\d+)\">([^<]*)</string>");
        Matcher s = snooze.matcher(xml);
        while (s.find()) {
            Target t = map.get("acc" + s.group(1) + "_" + s.group(2) + "_" + s.group(3));
            if (t != null) {
                String until = unescape(s.group(4));
                if (until != null && until.compareTo(today) >= 0) t.snoozeUntil = until;
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
                t.entryId = d.group(2) + "_" + d.group(3);
                t.title = "bot " + d.group(2);
                map.put(key, t);
            }
        }
        out.addAll(map.values());
        return out;
    }

    /** 一次性读出所有键值，设置页渲染时用这个，避免每条 su 一次。 */
    public static Map<String, String> readAll() {
        String xml = readPrefs();
        Map<String, String> out = new LinkedHashMap<>();
        if (xml == null) return out;
        Matcher s = Pattern.compile("<string name=\"([^\"]+)\">([^<]*)</string>").matcher(xml);
        while (s.find()) out.put(s.group(1), unescape(s.group(2)));
        Matcher b = Pattern.compile("<boolean name=\"([^\"]+)\" value=\"(true|false)\"").matcher(xml);
        while (b.find()) out.put(b.group(1), b.group(2));
        Matcher i = Pattern.compile("<int name=\"([^\"]+)\" value=\"(-?\\d+)\"").matcher(xml);
        while (i.find()) out.put(i.group(1), i.group(2));
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

    /**
     * su cat 目标文件。
     *
     * 注意：KSU 有挂载隔离，root shell 里直接访问 /data/data/<pkg> 会 No such file。
     * 必须走进程自己的 namespace：/proc/<pid>/root/data/data/<pkg>/...
     */
    private static String readPrefs() {
        for (String pkg : CLIENTS) {
            // 先走 init 的 namespace：不要求 Telegram 正在运行
            String path = "/proc/1/root/data/data/" + pkg + "/shared_prefs/" + PREFS;
            String content = exec("cat " + path);
            if (content != null && content.contains("<map>")) return content;
            // 回退到进程自己的 namespace
            String pids = exec("pidof " + pkg);
            if (pids == null || pids.trim().isEmpty()) continue;
            String pid = pids.trim().split("\\s+")[0];
            content = exec("cat /proc/" + pid + "/root/data/data/" + pkg + "/shared_prefs/" + PREFS);
            if (content != null && content.contains("<map>")) return content;
        }
        return null;
    }

    /**
     * 写一个配置项到 Telegram 的 prefs。
     *
     * 步骤：读原文件 → 替换/插入 → su 写回 → 恢复属主（否则 Telegram 读不了自己的配置）。
     * 写之前会在同目录留一份 .bak，出问题能还原。
     *
     * 返回 true 只代表写进磁盘了，宿主还需要重启才会读到新值。
     */
    public static boolean writeValue(String key, String type, String value) {
        for (String pkg : CLIENTS) {
            String pids = exec("pidof " + pkg);
            if (pids == null || pids.trim().isEmpty()) continue;
            String pid = pids.trim().split("\\s+")[0];
            String base = "/proc/" + pid + "/root/data/data/" + pkg + "/shared_prefs/" + PREFS;
            String xml = exec("cat " + base);
            if (xml == null || !xml.contains("<map>")) continue;
            String updated = patch(xml, key, type, value);
            if (updated == null) return false;
            String owner = exec("stat -c %u:%g " + base).trim();
            if (owner.isEmpty() || !owner.contains(":")) owner = "1000:1000";
            exec("cp -f " + base + " " + base + ".bak");
            boolean ok = write(base, updated, owner);
            android.util.Log.i("RootConfig", "write " + key + "=" + value + " ok=" + ok + " owner=" + owner);
            return ok;
        }
        return false;
    }

    /** 在 XML 里替换或插入一个键，保持 SharedPreferences 的格式。 */
    private static String patch(String xml, String key, String type, String value) {
        String quoted = Pattern.quote(key);
        String escaped = escape(value);
        try {
            switch (type) {
                case "boolean": {
                    String existing = "<boolean name=\"" + quoted + "\" value=\"(true|false)\"";
                    if (Pattern.compile(existing).matcher(xml).find())
                        return xml.replaceAll(existing, "<boolean name=\"" + key + "\" value=\"" + value + "\"");
                    break;
                }
                case "int": {
                    String existing = "<int name=\"" + quoted + "\" value=\"-?\\d+\"";
                    if (Pattern.compile(existing).matcher(xml).find())
                        return xml.replaceAll(existing, "<int name=\"" + key + "\" value=\"" + value + "\"");
                    break;
                }
                default: {
                    String existing = "<string name=\"" + quoted + "\">[^<]*</string>";
                    if (Pattern.compile(existing).matcher(xml).find())
                        return xml.replaceAll(existing, "<string name=\"" + key + "\">" + escaped + "</string>");
                    break;
                }
            }
            // 不存在则插入到 </map> 之前
            String entry = "boolean".equals(type)
                    ? "    <boolean name=\"" + key + "\" value=\"" + value + "\" />\n"
                    : "int".equals(type)
                    ? "    <int name=\"" + key + "\" value=\"" + value + "\" />\n"
                    : "    <string name=\"" + key + "\">" + escaped + "</string>\n";
            int end = xml.lastIndexOf("</map>");
            if (end < 0) return null;
            return xml.substring(0, end) + entry + xml.substring(end);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 用 su 把内容写到目标路径，并把属主改回宿主自己的 uid。 */
    private static boolean write(String path, String content, String owner) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "cat > " + path + " && chown " + owner + " " + path + " && chmod 660 " + path});
            java.io.OutputStream out = process.getOutputStream();
            out.write(content.getBytes("UTF-8"));
            out.flush();
            out.close();
            return process.waitFor() == 0;
        } catch (Throwable t) {
            android.util.Log.e("RootConfig", "write 失败: " + t);
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    /**
     * 把待生效设置写到宿主自己的外部目录，由宿主进程自己落盘。
     *
     * 为什么不直接改 prefs：SharedPreferences 是"内存缓存 + 整文件回写"，
     * 外部改文件在宿主运行时必被覆盖。写到这里，宿主启动时读一次就自己写入。
     *
     * 每行格式：key|type|value
     */
    public static boolean pushPending(String pkg, java.util.List<String> lines) {
        if (lines == null || lines.isEmpty()) return false;
        String dir = "/storage/emulated/0/Android/data/" + pkg + "/files";
        StringBuilder body = new StringBuilder();
        for (String line : lines) body.append(line).append('\n');
        // /sdcard 走 FUSE，不支持 chown，也不需要对端改属主
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "mkdir -p " + dir + " && cat > " + dir + "/tgas_pending.txt"});
            java.io.OutputStream out = process.getOutputStream();
            out.write(body.toString().getBytes("UTF-8"));
            out.flush();
            out.close();
            boolean ok = process.waitFor() == 0;
            android.util.Log.i("RootConfig", "pushPending ok=" + ok + " lines=" + lines.size());
            return ok;
        } catch (Throwable t) {
            android.util.Log.e("RootConfig", "pushPending 失败: " + t);
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    /**
     * 往宿主外部目录追一条命令，由宿主心跳读取后用自己的 prefs 落盘。
     * 每行格式：set|key|type|value
     */
    public static boolean pushCommand(String pkg, String line) {
        String dir = "/storage/emulated/0/Android/data/" + pkg + "/files";
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "mkdir -p " + dir + " && cat >> " + dir + "/tgas_cmd.txt"});
            java.io.OutputStream out = process.getOutputStream();
            out.write((line + "\n").getBytes("UTF-8"));
            out.flush();
            out.close();
            boolean ok = process.waitFor() == 0;
            android.util.Log.i("RootConfig", "pushCommand ok=" + ok + " line=" + line);
            return ok;
        } catch (Throwable t) {
            android.util.Log.e("RootConfig", "pushCommand 失败: " + t);
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    /** 已安装的客户端（不要求正在运行），命令可以先存着等它启动。 */
    public static String installedClient() {
        for (String pkg : CLIENTS) {
            if (new java.io.File("/storage/emulated/0/Android/data/" + pkg + "/files").exists()) return pkg;
        }
        return runningClient();
    }

    /** 当前哪个客户端在运行。 */
    public static String runningClient() {
        for (String pkg : CLIENTS) {
            String pids = exec("pidof " + pkg);
            if (pids != null && !pids.trim().isEmpty()) return pkg;
        }
        return null;
    }

    /** 重启宿主，让新配置生效。 */
    public static boolean restartHost() {
        for (String pkg : CLIENTS) {
            String pids = exec("pidof " + pkg);
            if (pids == null || pids.trim().isEmpty()) continue;
            exec("am force-stop " + pkg);
            exec("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1");
            return true;
        }
        return false;
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
