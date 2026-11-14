package io.github.wlmosv_png.tgautosign;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动补前置命令的判定测试（2026-11-14）。
 *
 * 为什么把判定抽成纯函数：补 /start 是有外部副作用的动作（会给 bot 发消息）。
 * 判据只要错一条，就会变成"每天往用户所有 bot 里刷 /start"——
 * 这种错在真机上很难复现，只能靠不变量钉住。
 */
public final class AutoPrestartTest {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<String>();

    private static void ok(String tag, boolean cond) {
        if (cond) passed++; else failed.add(tag);
    }

    public static void main(String[] args) {
        // 唯一允许补的情况：开关开、没自带前置命令、面板不新鲜、今天还没补过
        ok("全满足才补", SignLogic.shouldAutoPrestart(true, false, false, false));

        // 任一条件不满足都不补
        ok("开关关闭不补", !SignLogic.shouldAutoPrestart(false, false, false, false));
        ok("自带前置命令不补", !SignLogic.shouldAutoPrestart(true, true, false, false));
        ok("面板新鲜不补", !SignLogic.shouldAutoPrestart(true, false, true, false));
        ok("今天补过不补", !SignLogic.shouldAutoPrestart(true, false, false, true));

        // 关闭开关必须压过一切（用户显式表态优先）
        ok("关闭压过其它条件", !SignLogic.shouldAutoPrestart(false, false, false, false));

        System.out.println("----------------------------------------");
        if (failed.isEmpty()) {
            System.out.println("通过 " + passed + " / 失败 0");
            System.out.println("ALL OK");
            return;
        }
        System.out.println("通过 " + passed + " / 失败 " + failed.size());
        for (String f : failed) System.out.println("  FAIL: " + f);
        System.exit(1);
    }
}
