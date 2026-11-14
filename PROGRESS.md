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
