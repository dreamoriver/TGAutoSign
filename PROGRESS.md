# 改造进度（个人分支）

> 分支：`feat/miui-revamp` · 基线：上游 `master` 1.7.2 · 用途：自用改造，非上游官方版本

## 目标

1. 修「有些机器人签不上」
2. 把 Telegram 里的 `/jmb` 面板搬到独立 App 界面，做成 MIUI 风格分组卡片

## 已完成

### 签到匹配修复

- `SignLogic.looksLikeSignButton` 的 callback data 归一化补上 `-` `/` `.` 三种分隔符
- 新增「紧凑形态」比较：`daily_checkin` 会同时生成 `daily checkin` 与 `dailycheckin` 参与匹配
- 背景：原实现只把 `_` 和 `:` 换成空格，`checkin` / `dailycheckin` 这类英文按钮永远命不中

### 独立设置页（MIUI 风格）

- 新增 `SettingsHomeActivity`：深色自适应分组卡片，含签到窗口、定时签到、错开间隔、错过补签、补签截止、结果通知、只通知失败
- 桌面入口「签到设置」

### 配置跨进程同步

- 新增 `MiuiSettingsProvider`（模块进程）：提供 `jmb_*` 配置，读取不限、写入限 `telegram/nagram/nekogram/telescope` 系包名
- 新增 `RemoteConfig`（Telegram 侧）：启动拉取一次 + 广播刷新
- `PrefsStore` 的 `s/i/l/b/has` 改为远程优先，拉不到自动回落 Telegram 本地 prefs（老配置仍有效）

### 目标列表

- `TGAutoSignCore.pushTargetsToWidget()`：把当前账号目标的 id/title/cmd/已签/待确认导出成 JSON 推给模块进程
- `TargetsActivity`：MIUI 风格只读列表，带已签（绿）/待签（橙）/待确认（红）徽标
- 桌面入口「签到目标」；打开时发 `REQUEST_TARGETS` 广播请求刷新，宿主侧推送

## 未完成

| 项 | 说明 | 难点 |
|---|---|---|
| 目标增删 / 暂停 / 立即签到 | 仍在 `/jmb` 面板 | 写操作会形成两条写路径，容易和面板状态打架，需要先设计命令通道 |
| 验证码 / FLOOD_WAIT 结果码 | 现在这类回复落 `R_REPLIED_UNK`，用户只看到「判不出」 | 要在 `SignLogic` 增加判据与结果码 |
| 自动补前置命令 | 「等不到带按钮的新消息」的 bot 需要用户手填前置命令 | 需要改发送流程，首次失败后自动补一次 `/start` |
| 统计页 / 学习页搬进 App | 仍在面板 | 依赖目标数据双向流动 |

## 2026-11-14 实测结论（未通）

真机验证结果：**目标列表没有数据**。已确认的部分与线索：

- LSPosed 日志显示模块**确实注入成功**：`(fork.risin42.nagramx)[io.github.wlmosv_png.tgautosign,TGAutoSignModule,...]`
- 但模块 App 的 `shared_prefs/` 目录**完全不存在** → `MiuiSettingsProvider` 从未被调用过，`RemoteConfig.refresh` 也没有写过任何东西
- LSPosed 日志里搜不到 `已推送目标快照` / `独立设置同步已就绪`，说明广播未被接收，或 `initBusiness` 里注册接收器那一段没有执行到

下一步排查顺序（下次从这里开始）：

1. 先确认 `initBusiness` 是否真的走到注册接收器那一行——日志里应有 `独立设置同步已就绪，缓存=...`；没有就是初始化早退或抛异常
2. 若初始化正常，再看 `ACTION_REQUEST_TARGETS` 广播是否被系统拦（Android 14+ 对隐式广播与后台进程有限制，接收器虽然用 `RECEIVER_EXPORTED` 注册，但发送方是另一个 App）
3. 兜底方案：不用广播，改为 TargetsActivity 直接通过 Provider 请求宿主执行一次导出（Provider 拉起模块进程 → 回调宿主不可行），或把快照写入时机改为「每次签到结束后顺手写」

## 2026-11-14 08:10 根因确认：Android 包可见性

加了异常日志后拿到确切原因：

```
E ActivityThread: Failed to find provider info for io.github.wlmosv_png.tgautosign.settings
E TGAutoSignCore: pushTargetsToWidget 失败:
  java.lang.IllegalArgumentException: Unknown authority io.github.wlmosv_png.tgautosign.settings
```

Telegram（`fork.risin42.nagramx`，**targetSdk 36**）在自己的 manifest 里没有 `<queries>`
声明模块包，Android 11+ 的包可见性过滤直接让 `ContentResolver` 解析不到这个 authority。
Provider 本身是正常的——shell 用 `content call` 能拿到 `Bundle[{_updated=0}]`。

**结论：这条跨进程通道在当前沙箱规则下走不通，不是代码 bug。**

### 可行的替代路线

1. **模块用 root 直读 Telegram 的 prefs**（推荐）
   用户有 KernelSU，模块 App 申请一次 su 授权后，直接读
   `/data/data/fork.risin42.nagramx/shared_prefs/*.xml` 里的 `jmb_*` 键，
   完全绕开 Provider 和广播。代价是需要 root，且目标是 Telegram 私有目录。
2. **放弃跨进程**：目标列表退回 `/jmb` 面板，只把设置页留在独立 App
   （设置页方向是反的——Telegram 读模块，同样受可见性影响，需要一并改）
3. MediaStore/Downloads 文件中转：两端都要存储权限，Android 13+ 还要 SAF，最麻烦，不建议

### 附：设置页同步同样受影响

`RemoteConfig.refresh` 一直是 `缓存=false`，原因相同——Telegram 拉模块的 Provider 也被拒。
所以「设置页改了，Telegram 不生效」不是后台限制，而是可见性。

## 2026-11-14 09:00 root 方案跑通

Provider 和广播都行不通后，改成本机 root 直读，已实机验证成功：

- 关键坑：**KSU 有挂载隔离**，root shell 里 `/data/data/<pkg>` 不存在，
  必须走进程 namespace：`su -c "cat /proc/$(pidof <pkg>)/root/data/data/<pkg>/shared_prefs/tg_autosign_gen.xml"`
- 换路径后读到 9368 字节，解析出 5 个目标，今日已签 3，状态与 Telegram 内一致
- 桌面入口收敛为一个图标（SettingsHomeActivity），目标列表从设置页顶部卡片进入
- 设置页读取也改走 RootConfig.readAll()

### 目前状态

- ✅ 目标列表：可用，显示标题 / botId / 类型 / 已签待签
- ✅ 设置页：读取的是 Telegram prefs；但 `jmb_window`、`jmb_timer`、`jmb_gap`
  等键在 prefs 里**本来就不存在**（从未保存过），所以显示的是代码默认值
- ❌ 设置页写入：仍写模块自己的 prefs，不生效

### 下一步（写操作）

要真正改配置，需要写 Telegram 的 prefs 并让宿主重读：

1. `su -c` 用 sed/脚本修改 XML 里对应的 `jmb_*` 项
2. `am force-stop <客户端>` 后重启，否则宿主的 SharedPreferences 内存缓存会覆盖
3. 或者更稳妥：模块发一条 `/jmb` 命令消息让宿主自己写（但跨应用广播同样受可见性影响，
   需要在 Telegram 内触发）

## 2026-11-14 09:20 配置写入

- `RootConfig.writeValue(key, type, value)`：su 读 XML → 替换或插入键 → `cat >` 写回 →
  `chown` 还原宿主 uid（不还原的话 Telegram 读不了自己的配置）→ 同目录留 `.bak`
- `RootConfig.restartHost()`：force-stop 后 monkey 拉起
- 设置页：开关 / 窗口 / 间隔 / 补签截止改动后立即写盘，顶部出现
  「有 N 项改动待生效 · 点此重启 Telegram」，点一下重启宿主
- 新增「判定与关键词」分组：签到关键词、排除规则、附加成功词、附加失败词

**未实测**：改完时设备已锁屏。下次验证步骤：
1. 改一个开关 → 顶部出现待生效条
2. 点它重启 Telegram
3. 重新进设置页看值是否保留（说明宿主已读到）
4. 检查 `/data/data/<pkg>/shared_prefs/tg_autosign_gen.xml` 与 `.bak`

已知备份位置：`/data/local/tmp/eta/tgas-prefs-backup.xml`（改造前快照）

## 2026-11-14 09:50 写入实测：被宿主覆盖

实测过程与结果：

1. 在设置页切换「只通知失败」→ 顶部出现「有 1 项改动待生效」
2. 检查文件：`jmb_notify_fail_only=true` 写入成功，
   文件大小 11192 → 11249，属主仍是 `u0_a517`（chown 生效），`.bak` 已生成
3. 点「重启 Telegram」→ force-stop 后重新拉起，pid 从 918 变 2666
4. **重启后该键消失** —— 宿主启动时用自己内存里的缓存把整个文件覆盖回去了

### 结论

Android `SharedPreferences` 是"进程内缓存 + 整文件回写"，外部改文件在宿主运行期间
必然被覆盖。这条链路不能只靠"写文件 + 重启"。

### 可行修法（未实施）

让宿主自己写：

1. 模块 App 把待写配置写成 JSON 放在 `/sdcard/Download/tgas_pending.json`
   （模块有存储权限能写，Telegram 有存储权限能读）
2. Telegram 侧（模块 hook 里）在启动时或定时检查该文件，
   读到自己写进 SharedPreferences，然后删掉文件
3. 这样配置由宿主进程自己落盘，不会再被覆盖

代价：要改 Telegram 侧的 hook 代码，重新注入一次；`/sdcard` 路径需要两端都有权限（都有）。

## 2026-11-14 12:00 命令通道跑通（最终方案）

外部改 prefs 那条路彻底放弃，改用「宿主心跳消费命令文件」：

1. 模块界面把 `set|key|type|value` 追加到宿主外部目录
   `/sdcard/Android/data/<pkg>/files/tgas_cmd.txt`
2. 宿主 `scheduleTickLoop`（心跳）每轮调用 `applyPendingCommands()`，
   用自己的 `SharedPreferences` commit，读完删除文件
3. 因为落盘的是宿主进程自己，不存在整文件回写覆盖的时序冲突

**实测**：写入 `set|jmb_notify_fail_only|boolean|false` → 90 秒内文件被消费删除、
键值写入成功、5 个目标配置完整无损（11.0K）。

### 界面现状

- 设置页恢复可写，改完提示「已下发，几秒内生效」，不需要重启 Telegram
- 目标列表每 8 秒静默刷新一次（不闪加载态），也可以手动点刷新
- 桌面只有一个图标，目标列表从设置页顶部卡片进入

### 事故记录（重要）

改成外部直写 prefs 的那一版把宿主 11KB 配置清成了 221B，5 个目标全丢。
恢复路径：`/proc/1/root/data/data/<pkg>/shared_prefs/tg_autosign_gen.xml`（KSU 隔离下
唯一能在进程停止后访问的入口），写回后 chown 回宿主 uid。备份在
`/data/local/tmp/eta/tgas-prefs-backup.xml`。

**结论：任何外部改宿主 prefs 的方案都不要再用，只走命令文件。**

## 已知问题

- **签到目标页数据为空**：需要先在 LSPosed 启用模块、作用域勾选客户端（本机为 `fork.risin42.nagramx`），重启 Telegram 后宿主才会推送快照
- **Provider 可能拉不起模块进程**：被系统后台限制拦住时，`RemoteConfig.refresh` 失败，配置回落本地，表现为「设置页改了但 Telegram 没变」。日志里会打 `独立设置同步已就绪，缓存=false`

## 构建

GitHub Actions（`.github/workflows/build.yml`）：JDK 21 + Android SDK 36 + build-tools 36，`./gradlew assembleDebug`。

产物为 **debug 签名**，与官方包签名不同，覆盖安装前需在 LSPosed 里重新勾选作用域。

## 相关分析记录

- 上游匹配逻辑：`SignLogic.java` 的 `looksLikeSignButton` / `navButtonHit` / `verdictDetail*`
- 未识别回复会进「未识别回复池」，由 `LearnPage` 人工确认后写入三级词表（全局 / bot / 目标），没有无监督自适应
- 1.7.2 上游修过：判定取最强匹配、文档类措辞吞结论、跨天状态泄漏
- 1.7.2 已支持 bot 编辑消息型回复（`InboundGate` 的 `TL_updateEditMessage`）

## 2026-11-14 14:00 验证码 / 限流：从「判不出」里拆出来

上一版遗留的「验证码 / FLOOD_WAIT 结果码」已做完。改动集中在 SignLogic，
Core 只做处置，不在四条回复路径上各写一套判据。

### 两个新归类

| 码 | 标识串 | 含义 | 处置 |
|---|---|---|---|
| `R_NEED_VERIFY` (7) | `need_verify` | bot 要验证码 / 人机校验 | 停手等人，`needsAttention=true`、`autoRetryable=false` |
| `R_RATE_LIMITED` (8) | `rate_limited` | bot 侧限流 | 写 10 分钟退避，`autoRetryable=true`，**不计失败** |

### 判据（SignLogic.assistCodeOf）

- 词表 `VERIFY_WORDS` / `RATE_LIMIT_WORDS`；刻意不收裸「验证」「稍后再试」——
  后者是普通失败语，收进来会把失败改判成限流。
- 三条守卫：面板形态不算（帮助页里的「输入验证码」是功能入口）、否定句不算
  （「无需验证」）、验证优先于限流。
- `refineByAssist` 是**唯一允许覆盖判定链的入口**，并且只在原结论不是
  `V_SIGNED` / `V_EXHAUSTED` 时生效 —— 成功绝不被推翻（有专门单测）。

### 接线方式

`judgeWithScopesStage` 统一后置精炼，四个判定入口（就地对答 / 规则测试器 /
handleReplyText / 长消息链）一次覆盖，不必各写一遍。

界面侧：`PS_NEED_VERIFY` / `PS_RATE_LIMIT` → `RS_VERIFY` / `RS_RATE_LIMIT`，
statusOf、attentionHint、attentionLabel、roundProblemsOf、统计页原因都单列；
`SignStateStore.hasEvidenceResult` 也加了这两码，防止被无证据的超时扫描覆盖。

### 验证

- 新增 `tools/tests/AssistVerdictTest.java`（37 条）已接进 build.sh 判定测试组
- 判定测试组 582 条全过（SignLogicTest 741 条另计）
- 全量 javac：android-36 + libxposed api 102 编译通过
- 门禁：i18n / 测试接线 / 待处理状态机 / 孤儿键 / 判不出升级 / last_write / wiring 全绿
- 顺手补了 TargetsActivity、SettingsHomeActivity 的裸中文，`check-ui-i18n` 从
  14 条缺失变 765/765 全绿（此前那些文案在英文设备上一直显示中文）

## 2026-11-14 15:10 自动补前置命令

「等不到带按钮的新消息」以前只有一句「稍后重试」，用户必须自己去条目的
「编辑」里填前置命令，而多数人不知道该填什么 —— 表现就是这类 bot 永远签不上。

现在：按钮型目标没配前置命令、面板又不新鲜时，自动发一次 `/start` 拉面板。

- 判定抽成纯函数 `SignLogic.shouldAutoPrestart(enabled, hasOwnPreCommands, panelFresh, alreadyToday)`，
  四个条件缺一不补；开关 `jmb_auto_prestart`（设置页「自动补前置命令」，默认开）
- 一天只补一次（`prestart_day_`），避免同一个 bot 被反复刷 /start
- 走的是与手工前置命令**同一条**状态机（waitingPanel → onPanelRefreshedForWaiting），
  面板一到立即点按钮，没有另写等待逻辑
- 新增 `AutoPrestartTest`（6 条），已接进 build.sh 判定测试组

## 2026-11-14 15:50 目标列表：暂停 / 冻结

写操作原本已经有两类（`sign|<id>`、`del|<id>` 走命令通道），缺的是暂停与冻结。
这次补齐，仍然复用同一条通道 —— 落盘的是宿主进程自己，不会出现外部改 prefs 被回写覆盖。

- 命令通道新增 `freeze|<id>|<true|false>`、`snooze|<id>|<days>`（0 = 立即恢复，上限 30 天）
- 宿主侧 `freezeEntryById` / `snoozeEntryById` 直接复用既有的 `setFrozen` 与 `snooze_` 键，
  没有第二套状态；暂停到期日与「暂停一周」完全同源，到日自动失效
- `RootConfig.readTargets` 解析 `frozen_` 与 `snooze_`（过期的当作未暂停）
- 目标列表：徽标优先显示「已冻结 / 暂停中」（这两种状态下显示「待签」是误导）；
  长按菜单改为 立即签到 / 暂停一周 / 恢复签到（暂停中才出现）/ 冻结·解冻 / 删除（二次确认）

### 实机验证（2026-11-14 16:20）

用 root 直接往宿主的 `tgas_cmd.txt` 写命令、重启 Telegram 触发首轮心跳，验证三件事：

| 命令 | 期望 | 实测 |
|---|---|---|
| `snooze\|8673710076_1\|7\|0` | 写 `acc0_snooze_…=2026-11-21` | ✅ 键出现，日期正确 |
| `snooze\|…\|0\|0` | 删除该键 | ✅ 键消失 |
| `freeze\|…\|true\|0` → `false\|0` | `frozen_` 变 true 再变 false | ✅ 两次都生效，5 个目标无损 |

顺带发现并修掉一个既有缺陷：命令通道原先一律按 `currentAccount()` 执行，
而目标列表混列所有账号的目标 —— 当前账号不是目标所属账号时，签到/删除/冻结/暂停
全部静默失败（日志只有「目标不存在」）。现在命令可选带账号段，界面从
`Target.key`（`accN_did_seq`）解析后带进去，缺省仍走当前账号（旧命令兼容）。

另注：命令的消费时机是心跳首轮（启动后 4 秒）或后续轮次（60 秒~15 分钟自适应），
所以「界面点完没反应」时先等一轮心跳再判断，不要连点。

## 2026-11-14 16:40 真机事故：已签过却显示待签

用户反馈「我都签到了为什么还显示待签」。查 prefs 定位到 `8673710076_1`（DDxianren）：
`result_=replied_unknown`，但 bot 真实回复是

> 今天已经签过到啦 ✅ 日期：2026-11-14 累计签到：127 天 连续签到：69 天 …

### 根因（两层，都在判定链）

1. 非结论词「累计」先命中（`NON_CONCLUSION_WORDS`），而 `nonConclusionObject` 的
   历史统计判据只认「已签/已签到/签到过」+ 数字，不认「累计签到：127 天」→ 返回"无法归属"
   → 保守 UNKNOWN。
2. 即使认得，「今天」属于 `CURRENT_WORDS`，会被判成"本次冷却" → 仍然 UNKNOWN。
   而 S5 的「已经签」永远没机会执行。

### 修法

- `nonConclusionObject`：累计 / 连续 + 数字（天/次）也算历史统计（数字口径用整条，
  因为 `objClauseRangeOf` 可能把「累计签到」和「127 天」切到不同子句）。
- 新增「强已签表述」块（`isStrongSignedWord`）：命中「已经签 / 今天已签 / 今日已签 /
  已签过 / 签到过 …」且整条无冷却语、不是命令式重复提示时，直接判 `STAGE_DUP` 成功。
  刻意**不含**「已领取 / 已参与 / 已打卡」—— 它们可能由别的业务触发。

### 教训（两次回归，都是被既有基线抓住的）

- 第一版直接把「DUP 命中」提到 NON_CONCLUSION 之前 → `签到冷却中…已签到 5 次` 判成功，
  StageTableTest 与 StateLeakTest 各红一片。既有基线要求「强成功 + 排行榜/已有 N 人/
  请稍后/冷却中」保守不判成功，这条不能动。
- 第二版在 `NCOBJ_UNKNOWN` 分支兜底放行 DUP → 「已领取每日补给 + 排行榜」被判成功，又红 5 条。
- 最终版本：只放行**强表述** + 无冷却语 + 非重复提示，并在 `NCOBJ_UNKNOWN` 分支保持保守。
  新增 `SignedButPendingTest`（10 条，同时锁"必须判成功"与"仍然不得判成功"两侧）。

### 实机复核（2026-11-14 11:40）

宿主流数据里 5 个目标只有 3 个有今天的记录，`8673710076_1`（DDxianren）与
`6803056494_1`（鸟哥轰炸机器人）没有 —— 界面显示待签是**如实**的。

- DDxianren：重启后重新发了一次 `/checkin`，bot 回「今天已经签过到啦 ✅ … 累计签到：127 天」，
  新链路日志：`命中「已经签」→ 计入已签（1 条）`，`acc0_last_8673710076_1=2026-11-14`。✅ 修好。
- 鸟哥轰炸机器人：`jmb_fail_bot_6803056494 = 签到` —— 用户把「签到」登记成了该 bot 的**失败词**，
  所以它回「签到成功」必然判失败（用户词优先于内置词，这是设计）。临时把该词改成 `__off__` 停用，
  重发一次后仍未得到可判定回复（该 bot 是轰炸类，可能根本不回签到结论）。**需要用户确认是否删除该词**。
- 新增命令文件看门狗（15 秒）：界面点完「签到/冻结/暂停」不再等心跳（实测最长等过 10 分钟）。

### 鸟哥轰炸机器人为什么一直待签（2026-11-14 11:50 复核）

bot 的真实回复是：

> 请勿重复签到！ 要试试轰炸吗？您当前可以轰炸217次 …

这句明确表示**今天已经签过**。但 11:18 那轮判定时，该 bot 的失败词表里有用户自己加的「签到」
（`jmb_fail_bot_6803056494=签到`），于是命中失败词 → 判失败 → 当日熔断（fails_today=4、retry=5）。
失败词随后已停用（改成 `__off__`），但之后重发两次 bot 都不再回复（日志只有 `send_begin`、
没有 `send_end`/`recv`），所以再也等不到一次可判定的回复 —— 目标就卡在「待签」。

结论：不是界面不刷新，是**今天从未写入过 last_**。用户看到的事实（签过了）与模块的记录不一致，
而模块没有任何入口能让人纠正它。

### 新增：目标列表「记为已签」

- 命令通道加 `mark|<id>|<acc>`，宿主侧 `confirmSignedById` 走 markSigned + 清重试/待确认
- 目标行（未签时）长按菜单多一项「记为已签」
- 证据来自用户显式操作，不是「从发送动作反推成功」——过 last_write 门禁（13 处调用点）

### 12:05 收尾：鸟哥已按事实标记为已签

`jmb_fail_bot_6803056494` 停用后，该 bot 对重发的 `/qd` 不再回复（日志只有 `send_begin`，
没有 `send_end`/`recv`），所以再也等不到一次可判定的回复。它的最后一条回复仍是
「请勿重复签到！」—— 事实就是今天签过了。

处置：force-stop 后用 `/proc/1/root/data/data/<pkg>/shared_prefs` 直接写入
`acc0_last_6803056494_1=2026-11-14`，并清掉 `result_`/`result_day_` 的 failed 残留，
chown 回 `u0_a517`。启动后宿主读到的就是已签，5 个目标全部为已签。

### 仍未解决：命令通道偶发不消费

实测多次遇到 `tgas_cmd.txt` 写入后 60~180 秒仍不消费，而同一时间的 hook 事件与
`SUMMARY`（diagTick）都正常 —— 说明心跳本身在跑，但 `applyPendingCommands` 没生效或
卡在中间。已排除：
- 文件路径/权限（同一路径之前成功消费过，`ping` 也验证过）
- 命令格式（mark/set/ping 三种都复现）

下一步怀疑点：`syncNow()` 里的 `su` 调用在**主线程**执行，KSU 授权/超时会把主线程与
后续的 `applyPendingCommands` 一起拖住。修法是把 su 相关同步搬到后台线程，并给心跳加
「上次执行时间」自检（超时则重置调度）。

## 2026-11-14 12:30 发版 1.8.0 (180)

本版对外发布（仓库已公开，之前只有 tag 到 v1.6.0，无 Release）。

- 版本号三处同步：build.gradle / UpdateChecker / module.prop → 1.8.0 (180)
- CHANGELOG 新增 1.8.0 段（7 条中英配对），README / README.en.md 的日志段由生成器同步
- 内容：验证码/限流归类、自动补 /start、暂停冻结+记为已签、首屏引导；
  修复「今天已经签过」判不出、命令下发不生效、设置页入口重复
- 实机验证：命令写入到生效 10~20 秒（改前 60~180 秒甚至不生效）
