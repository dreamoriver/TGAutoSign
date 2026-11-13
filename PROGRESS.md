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
