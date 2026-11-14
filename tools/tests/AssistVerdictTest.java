package io.github.wlmosv_png.tgautosign;

import java.util.ArrayList;
import java.util.List;

/**
 * 辅助结论测试：需要验证 / 被限流（2026-11-14）。
 *
 * 为什么单列一个 runner
 * --------------------
 * 这两类文本原来的归宿都是「判不出」—— 用户看到的是「去补判定词」，
 * 而补词永远解决不了验证码，也解决不了限流。缺陷不在词表覆盖，
 * 而在**归类缺失**：一个需要人、一个需要等，却被压成同一个词。
 *
 * 断言纪律
 * --------
 *   · 每个用例同时断言 verdict + stage（只断言结果码会掩盖"结果对了阶段错了"）；
 *   · **必须**包含「成功不被覆盖」的用例 —— refineByAssist 是唯一允许覆盖判定链的入口，
 *     一旦它把已签改成需要验证，用户第二天会断签；
 *   · 必须包含误判样本（面板里的「输入验证码」、否定句「无需验证」、普通失败语）。
 */
public final class AssistVerdictTest {

    private static int passed = 0;
    private static final List<String> failed = new ArrayList<String>();

    private static void ok(String tag, boolean cond) {
        if (cond) passed++; else failed.add(tag);
    }
    private static void eq(String tag, Object got, Object want) {
        boolean b = got == null ? want == null : got.equals(want);
        if (b) passed++; else failed.add(tag + " → 得到 " + got + "，期望 " + want);
    }

    private static SignLogic.StageResult refine(String reply) {
        return SignLogic.refineByAssist(SignLogic.decideByStage(in(reply)), reply);
    }

    private static SignLogic.StageInput in(String reply) {
        SignLogic.StageInput i = new SignLogic.StageInput();
        i.reply = reply;
        return i;
    }

    public static void main(String[] args) {
        verifyCases();
        rateLimitCases();
        noFalsePositive();
        refineKeepsSuccess();
        refineOverrides();
        codeRoundTrip();
        attentionPolicy();
        pendingStateMapping();

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

    // ── 验证类 ──────────────────────────────────────────────
    private static void verifyCases() {
        eq("验证-请输入验证码", SignLogic.assistCodeOf("请输入验证码"), SignLogic.R_NEED_VERIFY);
        eq("验证-人机验证", SignLogic.assistCodeOf("请先完成人机验证后再签到"), SignLogic.R_NEED_VERIFY);
        eq("验证-滑块", SignLogic.assistCodeOf("🔐 安全验证：拖动滑块完成验证"), SignLogic.R_NEED_VERIFY);
        eq("验证-captcha", SignLogic.assistCodeOf("Please enter the captcha to continue"),
                SignLogic.R_NEED_VERIFY);
        eq("验证-验证码优先于限流", SignLogic.assistCodeOf("操作太频繁，请完成验证"),
                SignLogic.R_NEED_VERIFY);
    }

    // ── 限流类 ──────────────────────────────────────────────
    private static void rateLimitCases() {
        eq("限流-操作太频繁", SignLogic.assistCodeOf("操作太频繁，请稍后再试"), SignLogic.R_RATE_LIMITED);
        eq("限流-请求过于频繁", SignLogic.assistCodeOf("请求过于频繁，请 60 秒后再试"),
                SignLogic.R_RATE_LIMITED);
        eq("限流-FLOOD_WAIT", SignLogic.assistCodeOf("FLOOD_WAIT_30"), SignLogic.R_RATE_LIMITED);
        eq("限流-too many requests", SignLogic.assistCodeOf("Too Many Requests"), SignLogic.R_RATE_LIMITED);
    }

    // ── 不该误判 ────────────────────────────────────────────
    private static void noFalsePositive() {
        eq("不误判-空", SignLogic.assistCodeOf(""), -1);
        eq("不误判-null", SignLogic.assistCodeOf(null), -1);
        eq("不误判-签到成功", SignLogic.assistCodeOf("签到成功"), -1);
        eq("不误判-普通失败", SignLogic.assistCodeOf("签到失败，请明天再来"), -1);
        eq("不误判-否定句", SignLogic.assistCodeOf("无需验证，签到成功"), -1);
        // 面板/菜单形态：帮助页里的「输入验证码」是功能入口，不是当前要求
        eq("不误判-面板里的验证入口",
                SignLogic.assistCodeOf("用户中心 当前绑定：@abc 会员状态：已过期 输入验证码：有 积分：100"),
                -1);
    }

    // ── 成功结论绝不被覆盖 ──────────────────────────────────
    private static void refineKeepsSuccess() {
        SignLogic.StageResult r1 = refine("签到成功，如需改绑请完成验证");
        eq("成功-verdict 保留", Integer.valueOf(r1.verdict), Integer.valueOf(SignLogic.V_SIGNED));
        // 额度用尽同样视为今日了结，不能被改成「需要验证」
        SignLogic.StageResult r2 = refine("今日签到次数已达上限，请明日再试");
        eq("用尽-verdict 保留", Integer.valueOf(r2.verdict), Integer.valueOf(SignLogic.V_EXHAUSTED));
    }

    // ── 判不出 / 失败 被精炼 ────────────────────────────────
    private static void refineOverrides() {
        SignLogic.StageResult r1 = refine("请输入验证码后继续");
        eq("精炼-验证 verdict", Integer.valueOf(r1.verdict), Integer.valueOf(SignLogic.V_NEED_VERIFY));
        eq("精炼-验证 stage", Integer.valueOf(r1.stage), Integer.valueOf(SignLogic.STAGE_VERIFY));

        SignLogic.StageResult r2 = refine("操作太频繁，请 30 秒后再试");
        eq("精炼-限流 verdict", Integer.valueOf(r2.verdict), Integer.valueOf(SignLogic.V_RATE_LIMITED));
        eq("精炼-限流 stage", Integer.valueOf(r2.stage), Integer.valueOf(SignLogic.STAGE_RATE_LIMIT));

        // 失败词先命中也要被改成验证：否则模块会无脑重发
        SignLogic.StageResult base = SignLogic.decideByStage(in("签到失败，请完成人机验证"));
        eq("前置-基线是失败", Integer.valueOf(base.verdict), Integer.valueOf(SignLogic.V_FAILED));
        SignLogic.StageResult r3 = SignLogic.refineByAssist(base, "签到失败，请完成人机验证");
        eq("精炼-失败转验证", Integer.valueOf(r3.verdict), Integer.valueOf(SignLogic.V_NEED_VERIFY));

        // 与两类都无关时原样返回（不得凭空造结论）
        SignLogic.StageResult r4 = refine("今天天气不错");
        eq("精炼-无关保持 UNKNOWN", Integer.valueOf(r4.verdict), Integer.valueOf(SignLogic.V_UNKNOWN));

        // null 安全
        SignLogic.StageResult r5 = SignLogic.refineByAssist(null, null);
        eq("精炼-null", Integer.valueOf(r5.verdict), Integer.valueOf(SignLogic.V_UNKNOWN));
    }

    // ── 归类码往返 ──────────────────────────────────────────
    private static void codeRoundTrip() {
        eq("往返-需要验证", Integer.valueOf(SignLogic.resultOfCode(SignLogic.resultCode(SignLogic.R_NEED_VERIFY))),
                Integer.valueOf(SignLogic.R_NEED_VERIFY));
        eq("往返-限流", Integer.valueOf(SignLogic.resultOfCode(SignLogic.resultCode(SignLogic.R_RATE_LIMITED))),
                Integer.valueOf(SignLogic.R_RATE_LIMITED));
        eq("往返-标识串", "need_verify", SignLogic.resultCode(SignLogic.R_NEED_VERIFY));
        eq("往返-标识串2", "rate_limited", SignLogic.resultCode(SignLogic.R_RATE_LIMITED));
    }

    // ── 处置策略 ────────────────────────────────────────────
    private static void attentionPolicy() {
        ok("处置-验证需人介入", SignLogic.needsAttention(SignLogic.R_NEED_VERIFY));
        ok("处置-限流不打扰人", !SignLogic.needsAttention(SignLogic.R_RATE_LIMITED));
        ok("处置-验证停手", !SignLogic.autoRetryable(SignLogic.R_NEED_VERIFY));
        ok("处置-限流可退避", SignLogic.autoRetryable(SignLogic.R_RATE_LIMITED));
        ok("处置-成功不需要处置", !SignLogic.needsAttention(SignLogic.R_SIGNED));
    }

    // ── 界面归因映射 ────────────────────────────────────────
    private static void pendingStateMapping() {
        eq("归因-需要验证",
                Integer.valueOf(SignLogic.resolvePendingState(false, true, true, 1000L,
                        SignLogic.GRADE_NONE, SignLogic.R_NEED_VERIFY, true)),
                Integer.valueOf(SignLogic.PS_NEED_VERIFY));
        eq("归因-限流",
                Integer.valueOf(SignLogic.resolvePendingState(false, false, true, 1000L,
                        SignLogic.GRADE_NONE, SignLogic.R_RATE_LIMITED, true)),
                Integer.valueOf(SignLogic.PS_RATE_LIMIT));
        // 已签优先：签上了就不该再谈验证
        eq("归因-已签优先",
                Integer.valueOf(SignLogic.resolvePendingState(true, false, true, 1000L,
                        SignLogic.GRADE_NONE, SignLogic.R_NEED_VERIFY, true)),
                Integer.valueOf(SignLogic.PS_SIGNED));
    }
}
