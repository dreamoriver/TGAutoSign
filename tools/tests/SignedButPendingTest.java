package io.github.wlmosv_png.tgautosign;

import java.util.ArrayList;
import java.util.List;

/**
 * 「明明签过了却显示待签」回归测试（2026-11-14，用户实测反馈）。
 *
 * 真机现场（DDxianren / 8673710076_1，prefs 里 result_=replied_unknown）：
 *   bot 回的是「今天已经签过到啦 ✅ 日期：2026-11-14 累计签到：127 天 连续签到：69 天 …」，
 *   模块判「回复判不出」→ 目标列表显示「待签」。
 *
 * 两层根因：
 *   ① 非结论词「累计」先命中 → nonConclusionObject 里「已签/签到过」的历史统计
 *      判据不认识「累计签到：127 天」，返回「无法归属」→ 保守 UNKNOWN；
 *   ② 即使认识，「今天」（CURRENT_WORDS）也会把它判成"本次冷却" → UNKNOWN，
 *      而后面 S5 的「已经签」根本没机会跑。
 *
 * 修法：明确「今天已经签过」这类强表述优先于统计/冷却词，但**必须**守住既有保守基线
 *   （冷却语、统计尾缀、命令式重复提示），所以这个测试同时锁这两个方向。
 */
public final class SignedButPendingTest {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<String>();

    private static void ok(String tag, boolean cond) {
        if (cond) passed++; else failed.add(tag);
    }
    private static void eq(String tag, Object got, Object want) {
        boolean b = got == null ? want == null : got.equals(want);
        if (b) passed++; else failed.add(tag + " → 得到 " + got + "，期望 " + want);
    }

    /** 生产形态：内置表 + 用户全局词「签到」+ bot 级「累计签到」。 */
    private static final String[] OK = merge(SignLogic.OK_WORDS_DEFAULT,
            new String[]{"签到"}, new String[]{"累计签到"});

    private static String[] merge(String[]... parts) {
        List<String> out = new ArrayList<String>();
        for (String[] p : parts) {
            if (p == null) continue;
            for (String s : p) if (s != null && !out.contains(s)) out.add(s);
        }
        return out.toArray(new String[0]);
    }

    private static SignLogic.StageResult judge(String reply, String[] ok) {
        SignLogic.StageInput in = new SignLogic.StageInput();
        in.reply = reply;
        in.okWords = ok;
        return SignLogic.decideByStage(in);
    }

    public static void main(String[] args) {
        // ── 真机原文：必须判成功 ──
        String real = "今天已经签过到啦 ✅\n日期：2026-11-14\n累计签到：127 天\n连续签到：69 天\n"
                + "换绑资格：可用 3 次\n邀请资格：可用 5 个\n规则：累计 66 天 / 连续 33 天，"
                + "可获得一次私聊换绑机器码资格。\n邀请规则：累计 20 天可获得 1 个邀请入群资格，无封顶。";
        SignLogic.StageResult r = judge(real, OK);
        eq("真机长文本 verdict", Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_SIGNED));
        eq("真机长文本 stage", Integer.valueOf(r.stage), Integer.valueOf(SignLogic.STAGE_DUP));

        // 单行版本（实测单行更容易被「今天」带到"本次冷却"分支）
        r = judge("今天已经签过到啦 ✅ 日期：2026-11-14 累计签到：127 天", OK);
        eq("真机单行 verdict", Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_SIGNED));

        // 内置表就够（不依赖用户 bot 词）
        r = judge("今天已经签过到啦 ✅ 日期：2026-11-14 累计签到：127 天", SignLogic.OK_WORDS_DEFAULT);
        eq("内置表也能认", Integer.valueOf(r.verdict), Integer.valueOf(SignLogic.V_SIGNED));

        // ── 守恒：这些仍然不得判成功 ──
        ok("冷却 + 历史统计不得判成功",
                judge("签到冷却中，请8小时后再试，已签到 5 次", OK).verdict != SignLogic.V_SIGNED);
        ok("纯统计不得判成功",
                judge("今日已有 123 人签到", OK).verdict != SignLogic.V_SIGNED);
        ok("统计尾缀压回非结论",
                judge("签到成功\n您已经获得 1 积分。\n📝 已有 123 人签到：详情请点菜单", OK).verdict
                        != SignLogic.V_SIGNED);
        ok("否定句式不得判成功",
                judge("今天还没有签到", OK).verdict != SignLogic.V_SIGNED);
        ok("累计但未签到不得判成功",
                judge("累计签到：0 天，今天还没签到", OK).verdict != SignLogic.V_SIGNED);

        // 命令式重复提示必须留在 REMINDER 阶段（既有阶段表基线）
        r = judge("请勿重复签到，您已经签过了", OK);
        eq("重复提示仍是 REMINDER", Integer.valueOf(r.stage), Integer.valueOf(SignLogic.STAGE_REMINDER));

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
