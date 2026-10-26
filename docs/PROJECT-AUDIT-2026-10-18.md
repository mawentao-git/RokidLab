# RokidLab 全项目审查报告

> 审查日期：2026-10-18 · 对象：RokidLab 全仓（phone-app + RokidLink，HEAD = 3830640，工作区另有 55 个文件已改未提交、3 个未跟踪）
> 方法：权威脚本（check_tool_wiring.py）+ 三轴并行审计（功能↔工具缺口 / AIUI 工具桥链路 / 执行层与路由）+ 资源不变量与代码卫生扫描 + 构建配置审阅 + 高危结论人工复核
> 标注约定：【事实】= 代码/测试可直接证明；【推测】= 基于代码的推断；【待确认】= 需产品/作者拍板

---

## 一、总览

| 维度 | 实测值 |
|---|---|
| 版本 | 手机端 4.7（versionCode 32）/ 眼镜端 RokidLink 4.7（versionCode 25） |
| 工具面 | **23 个 ToolProvider / 112 个静态工具 / 18 个域**（check_tool_wiring.py 权威口径，接线完整 ✅）；另有 1 个动态 MCP provider + 9 个伪工具 |
| 代码规模 | 375 个 .kt 文件 / 约 3.55 万行；最大单文件 `AiConversationService.kt` 4300 行 |
| 测试 | 10-18 复测 88 个测试类 / 1117 个 `@Test`（phone-app 87 类 / 1108 方法；**RokidLink 仅 1 类 / 9 方法**，见 M10）（README 写 60/666，已漂移；v5 文档「RokidLink 27 个测试类」口径亦误） |
| 构建门禁 | 4 道 preBuild（协议同源 / i18n 同步 / 关键链路空 catch / 全仓空 catch棘轮 41）+ 2 道发布闸门（git clean + 单测绿） |
| 工作区 | 55 个文件已改未提交（+2066/−263）：10-16/17 两轮审计修复在修（read_notifications、screen_mirror 手机→眼镜、原子写备份层等）；3 个未跟踪（v5 文档 + 2 个新测试） |

**一句话结论**：这是一个工程纪律处于移动端 Agent 第一梯队的项目——门禁文化、原子写、审批闸门、注入隔离都已落地且被测试锁死。本次审查未发现新的架构级缺陷，但发现 **1 组安全事故（明文令牌）、1 个可被绕过的安全闸门（ShellStageAnalyzer）、1 个用户可感知的能力缺口（眼镜文件写操作）**，外加一批文档漂移。所有高危均有明确修法，改动量都不大。

---

## 二、高危（建议本版本内处理）

### S1. 三个明文 API 令牌入库并推送，违反项目自身规则【事实】

| 令牌 | 位置 | 用途 | 入库提交 |
|---|---|---|---|
| `GITEE_TOKEN = "f7957862…da8c"` | `phone-app/src/main/java/com/rokidlab/phone/settings/DeveloperScreen.kt:53` | Gitee PAT（projects 权限），「提交应用」页直接 push 商店注册表 `RokidBrew-Registry` 的 `apps.v1.json` | `b2a60fd`（docs: sync README and update Gitee token） |
| `IFDIAN_TOKEN = "Vd7nJXym…YEUM"` | `phone-app/src/main/java/com/rokidlab/phone/settings/SettingsScreen.kt:604` | 爱发电开放平台 token，拉赞助列表（MD5 签名） | `4e2a731` |
| `KUWO_API_TOKEN = "api-c3e79d…5d89"` | `phone-app/src/main/java/com/rokidlab/phone/util/AppConfig.kt:9` | 酷我音乐 API token（点歌/搜索） | `c65b45a`（v3.2 起） |

- 三个文件均被 git 跟踪，remote 为 GitHub（mdloverm/RokidLab）+ Gitee（dlover1314/RokidLab），**令牌已进入远端历史**；且都在 main 源集，**打进的 release APK 可直接反编译提取**。
- RULES.md §10 白纸黑字：「Gitee token……**禁止明文入库**；从环境变量 / 不入库的 local.properties 注入。2026-09-12 移除了此前明文写入的旧 token，旧 token 需到 Gitee 后台撤销轮换」——`b2a60fd` 之后又提交了新的，规则被二次违反。
- **影响**：GITEE_TOKEN 是三者中危害最大的——持有者可篡改商店注册表（所有用户的商店数据源）；KUWO token 可被耗尽额度；IFDIAN token 可读赞助数据。
- **修法**：① 三个令牌立即去各自后台作废轮换；② 改为 `local.properties` / 环境变量注入（可复用签名参数的 `optionalSigningProperty` 四选一模式，模板 `gradle.properties.example`）；③ 历史清理（`git filter-repo` 或接受历史泄露、以轮换止损）；④ 补一道构建期扫描门禁（仿 `checkNoBareCatch`，扫 `const val …TOKEN = "长十六进制/业务串"`），防止第三次。

### S2. ShellStageAnalyzer 被解释器包装完全绕过——对话路径无确认删除手机真实文件【事实，已人工读全文复核】

`run_shell` 定档 `LOCAL_SIDE_EFFECT`，对话路径不过 `RiskApprovalGuard`，唯一的问用户机会是 `ShellStageGuard`（`needsReview` 才 Ask）。而 `phone-app/src/main/java/com/rokidlab/phone/ai/approval/ShellStageAnalyzer.kt` 存在两条完整绕过路径：

1. **解释器包装**：`READ_ONLY` 白名单含 `python3/python/node/jq`（L134-138）⇒ `python3 -c "…shutil.rmtree('/mnt/lab/x')…"` 判 `AUTO_SAFE「python3 只读」`；`bash -c "rm -rf /mnt/lab/x"`（`sh/dash/eval` 同理）⇒ cmd=bash 不在任何表 ⇒ 落 L268-274 `AUTO_SAFE「触及 /mnt/lab 但不删除/覆盖数据」`——**文案本身失实**（它确实在删数据，动词藏在 `-c` 引号里）。
2. **管道拆分丢关联**：`echo /mnt/lab | xargs rm -rf` ⇒ stage1 `echo`（READ_ONLY）AUTO_SAFE；stage2 `xargs` 的 argv `[xargs, rm, -rf]` 不含 `/mnt/lab` ⇒ `touchesRealFs=false` ⇒ `AUTO_SAFE「容器内操作（可丢弃环境）」`。`env/nohup/timeout/sudo/nice` 同属参数穿透型，同样无分支。

`/mnt/lab` 是内核 `write()` 直连绑定到**手机「下载/Lab」**的真实挂载（类注释 L11-16 自述）。该 guard 的唯一存在理由就是拦「破坏性动词 × 真实文件系统」，上述两条让靶子被一层包装完全绕过；类注释 L43-45 的保守原则「静态看不透 ⇒ 一律 REVIEW_UNRESOLVED，绝不自动放行」被直接违背。`ShellStageAnalyzerTest` 对 bash/python/xargs **零用例**，RULES/docs 无此取舍记录。

- **影响**：模型生成「清理/重构」类指令（`bash -c "rm -rf …"`、`python3 -c "…rmtree(…)"` 是常见产物形态）时，用户手机真实文件被静默删除，无确认、无 Ask、fail-closed 也不触发。这正是 2026-10-09 引入该 guard 要防的事故的镜像版本。
- **修法**（任选其一，建议前者）：① `analyzeStage` 在 `cmd in READ_ONLY` 命中前识别「解释器 + 代码串」形态（`bash/sh/zsh/dash` 带 `-c`、`python/node` 带 `-c`、`jq` 带程序串）与「参数穿透型」命令（`xargs/env/nohup/timeout/sudo/nice`）⇒ 一律 `REVIEW_UNRESOLVED`（`argvComplete=false` 的语义正是为它准备的）；② 对 stage 原文全文扫描「破坏性动词与 REAL_FS_MOUNT 同现（不论是否在引号内）」⇒ `REVIEW_DANGEROUS`。无论哪种，必须补三钉测试：`bash -c "rm -rf /mnt/lab/x"` → REVIEW_DANGEROUS；`python3 -c "…rmtree('/mnt/lab/x')…"` → REVIEW_UNRESOLVED；`bash -c "ls /mnt/lab"` → AUTO_SAFE（防误伤回归）。

### S3. 眼镜文件「写操作」整域缺失——AI 只能看不能动眼镜文件【事实，新发现未登记】

- 底层全部现成：`adb/AdbFileManagerClient.kt` 的 `deleteFile`(:558) / `copyFile`(:617) / `renameFile`(:672) / `createFolder`(:726) / `uploadFile`(:888)；UI 侧 `filemanager/FileManagerActivity.kt:279-298` 五操作俱全。
- 工具侧只有只读三件：`ai/tools/GlassesToolProvider.kt:57-65`（`list_glasses_files` / `read_glasses_file` / `download_glasses_file`）。闭环 R3 的范围被明确裁成只读（`docs/AGENT-CLOSEDLOOP-TODO.md:39`），但 grep RULES §12 与两份闭环文档，**无「眼镜文件写操作刻意不暴露」的记载**。
- **影响**：「把眼镜里那个文件删了」「把这首歌传到眼镜上」「在眼镜建个文件夹」AI 全部做不到，只能让用户自己去文件管理器页；与手机侧 files 域（14 个工具，含删/改/移/压缩）严重不对称。
- **修法**：① `manage_glasses_file`（delete/rename/mkdir；删不可恢复 ⇒ 按 `delete_knowledge_doc` 先例定 `EXTERNAL_SIDE_EFFECT`+BLOCK，同步 `ApprovalGateTest` E1 名单）；② `upload_glasses_file`（手机→眼镜，LOCAL_SIDE_EFFECT）。【待确认】传输选路（shell push vs sync 协议）需先定——R3 特意「走共享 ADB 会话跑 shell，不用宿主特化的 AdbFileManagerClient」。

---

## 三、中危（有兜底或有记账，缺测试/口径统一/能力补齐）

| # | 发现 | 位置 | 现象与影响 | 修法 |
|---|---|---|---|---|
| M1 | AIUI 页面下行投递失败无页面侧兜底 | `glasses/CxrLHiRokidSession.kt:392-398`；`RokidLink/assets/ink/lab-page-bridge.js:33,56-79` | `sendAiuiHostMessage` 返回非 0（CXR 断开）时只双写日志、不回送 toolResult，注释称「页面将等到超时」——但页面桥**没有超时机制**，`pending[cbId]` 永挂、loading 永转、条目累积。链路抖动即静默死亡，零用户可见报错 | 失败分支补发 `{type:"toolResult", cbId, ok:false}`；同步修正失实注释 |
| M2 | `ToolGateway` 预检口径宽于闸门 | `ai/ToolGateway.kt:251,257-260` | 默认 `knownTools` 用全量 `toolList`（含 hidden），而闸门 `PageScopeGuard` 用 `PageScope.pageVisibleTools`；注释声称「与审批链完全一致」失实。当前无实害（hidden 工具仍被闸门拒），但属 2026-10-17 修复的漏改，双口径防线名存实亡 | 默认值改为 `PageScope.pageVisibleTools()`（惰性 lambda），同步改注释。**一行** |
| M3 | WebView 兜底通道缺 U+2028/2029 转义 | `RokidLink/.../AiuiLinkActivity.kt:876` vs 同文件 739 行 | 主通道 `dispatchHostMessage` 有 `safeJson` 替换，兜底通道裸 `$json` 插值。中文排版/歌词文本 + `activeActivity==null`（真机出现过）时 evaluateJavascript 静默失败，与 M1 叠加 ⇒ pending 永挂 | 提取 companion 级 `sanitizeForJs(json)` 两通道共用 |
| M4 | 审批 guard 完整顺序无测试钉住 | `ai/approval/ToolGuards.kt:35-45` vs `ApprovalGateTest.kt:984-986` | 顺序本身没错，但「限流第 5 位 ⇒ 被 6–9 拒掉的已扣配额」的决策理由（L20-31 注释）只有三段相对顺序断言；将来有人按直觉重排，全部单测仍绿、副作用静默回归 | 补一条 `ToolGuards.default().map{it.id}` 全量 `assertEquals`。**一行** |
| M5 | 眼镜→手机实时投屏启停无工具（v5 文档 P1-2，已知未修） | 唯一 UI 入口 `app/MainActivity.kt:834` | 用户「把眼镜画面投到手机」AI 只能口头指导；`capture_screen` 抓的是手机屏、`look_at_view` 是相机拍照，均不等价。底层 `MirrorCoordinator`+scrcpy 全现成且无授权障碍 | 新增 `screen_mirror_glasses`（start/stop/status，LOCAL_SIDE_EFFECT，须取 LONG_LIVED 租约） |
| M6 | P0 级已知未修三项（v5 文档 2026-10-16 仍列） | — | ① P0-1：`AiConversationService` 4300 行零单测，10-07 两条注入高危 + 10-16 拍照拨号 bug 全出在这一个文件；② P0-3：`AdbFileManagerClient.downloadFile/downloadFileShell` 只写不校验、不与远端大小对账（对称的 pullFile 已修），「8.7MB APK 只落 64KB 却报已保存」的事故路径仍在；③ P0-2：验证层（动作后状态断言）缺失，10-15「封面显示了」幻觉 bug 的根因 | 按 v5 文档 v4.8/v5.0 规划执行；P0-3 建议最先（有明确事故先例、改动面可控） |
| ↑ 纠偏 | **② P0-3 的描述已失实**（2026-10-18 复核）：`reconcileDownloadedSize` 对账逻辑**早已在 HEAD**，两条下载路径都过它，工作区无相关 diff。真实缺口是**零测试覆盖**（测了「成功」与「FAIL」，独独没测事故本体那条）——已在第二批补 3 例，见 §9.2 |
| M7 | `manage_proactive` schema 枚举缺 `notify` | `ai/tools/StatusToolProvider.kt:232,301,328` vs `store/ProactivePanel.kt:109-115` | 面板七格、schema 只列六个，「把消息摘要关了/打开」AI 做不到；而 `read_notifications` 的可用性恰依赖此开关 ⇒ 「AI 让用户开、用户让 AI 开」死循环 | schema enum 补 `notify`；或若有意不暴露，补注释登记理由 |
| M8 | 浏览器「关闭」缺失 | `browser/BrowserSession.kt:95-98` vs `ai/tools/BrowserToolProvider.kt:53-55` | `close()` 已实现（finish Activity）但无 `browser_close` 工具，「关掉浏览器」只能让用户自己按返回键 | 补 `browser_close`（READ_ONLY/LOCAL，零外部副作用） |
| M9 | 一组「无文档记载的不暴露」决定 | A 轴 H3/M3/M4/M7/M8 | RokidLink 重装（自伤性运维）、HID 输入通道、持续环境音、眼镜系统导航（回桌面）、眼镜 app disable/extract——底层现成但无工具，且**无任何「刻意不做」的记载**，后续审计会反复重判 | 逐项拍板；不做的在 RULES §12 补登记（判据 + 重开条件），做的进工具面 |
| M10 | 眼镜端测试近乎为零（10-18 复测新发现） | `RokidLink/src/test` 仅 `AiChannelProtocolTest` 1 类 / 9 方法；phone-app 87 类 / 1108 方法。而近 10 个提交全部在改眼镜端（过程时间线 / TTS / AIUI 桥 / `AiuiLinkActivity` 1196 行） | 眼镜端协议编解码、桥收发、时间线控制器补纯逻辑单测（AiChannelProtocolTest 是现成范例） |
| M11 | `sendAiTextViaLink` ~2885 行单函数（10-18 新发现） | `domain/AiConversationService.kt:1254-4139`，内部还嵌套 `sendTtsPlay` 等局部函数；比「4300 行文件」更硬的可测性障碍——无法按函数单测，只能整文件读 | P0-1 拆分时优先切这个函数（按「步骤 1→4.5」阶段边界切，边界注释已在） |
| M12 | 9 处位图解码无采样（10-18 新发现） | `VisionToolProvider:118`/`PhotoQuizFlow:367`（相机 JPEG）、`ChatScreen:632/663/2326`（用户选图）、`FileManagerActivity:222`、`MusicPlayerController:323/377`、`DisplayToolProvider:240`、`SettingsScreen:677`、`ChartGlassPush:196`、`AdbScreenMirrorClient:681`。ChatImageCache 已修过同款 OOM（华为 P20 案例），`util/ImageDecoder.kt` 有现成采样实现但无人调用 | 各点改调 `ImageDecoder`（一行级）；相机 / 用户选图路径优先 |
| M13 | 接线脚本不在仓库、CI 跑不到（10-18 新发现） | `check_tool_wiring.py`（唯一防「工具加了却调不到」的脚本）位于 `~/.workbuddy/skills/`，仓库与 CI 均无副本；4 道 preBuild 门禁里没有它，新人克隆后该防线不存在，只能靠人肉记得跑 | 移入 `scripts/` 并挂 preBuild 或 CI step（脚本已成熟，纯静态扫描无外部依赖） |
| M14 | ToolRegistry 四个热派生表每次访问全量重建（10-18 二轮新发现） | `ToolRegistry.kt:652` `toolList`（112 个 ToolMeta 重建）、`:459` `SIDE_EFFECT_TOOLS`、`:621` `GLASSES_REQUIRED_TOOLS`、`:593` `pageCallableToolNames` 全是 `get()` 现算。同类缓存基础设施 `DerivedTables`（tableVersion + refreshTools 失效）**已存在**，但只覆盖 entries/entryByName | 热路径：审批链每工具调用（`ToolGuard.requiresGlasses`:86、`PageScope.domainOf`:113）、`schemasFor` 每轮装配（AiConversationService:1720/1724）。每轮对话叠加每次工具执行约 3–6 次 × 112 项重建，纯浪费的对象分配 | 四个视图并入 `DerivedTables`（entries 变更 / MCP 上报时版本已 bump，零行为变化）；⚠️ enabled 快照必须保持每轮读 prefs——设置页开关不 bump 版本，缓存它会踩「关了却还发给模型」 |
| M15 | SessionLog 读路径全量重解析、写路径每事件开关文件（10-18 二轮新发现） | `SessionLog.kt:154` append 用 `file.appendText`（每事件一次开写关，一轮数十次）；`read(from)` 先全量 parse 再过滤；`SessionTrace.items:77` 对全量 records map 完才取尾 limit | 会话几百事件后，每次轨迹视图打开 / 增量读取都全文件重新解析（O(事件总数)）；UI 打开轨迹视图时有感 | 写侧持有 BufferedWriter（单写者已 synchronized，保持 JSONL 追加语义、不用 AtomicFileWrite）；读侧尾窗口裁剪（turnStats 需全量，map 可只做尾段） |

---

## 四、低危（文档/展示漂移，不影响现网行为）

1. **README 更新总览停在 v4.4**（缺 v4.5–v4.7 三章）；测试数字 60 类/666 用例 vs 10-16 实测 90 类/1105。
2. **工具数口径三方不一致**：README 写「103 工具/17 域/20 Provider」，脚本权威为 **112/18/23**；`ToolRegistry.kt:836-839` 注释同样停在 103。建议改为引用单一常量或每次发布随脚本刷新。
3. **错误代码示例（会主动制造 bug）**：`README.md:128` 与 `DEV_GUIDE.md:766` 写 `await globalThis.Lab.callTool('play_song', …)`——① `await` 在 ink 沙箱永不返回（页面当场卡死）；② `play_song` 是不存在的旧名（现名 `control_music`）。照抄即同时踩两个坑。
4. **e.data 形态描述矛盾**：`RULES.md:496` 与 `DEV_GUIDE.md:790-791` 称「e.data 是 JSON 字符串必须 JSON.parse」，而 `lab-page-bridge.js:84-86` 真机实测注释确认是对象、无条件 parse 会抛错。按文档写的页面会 TypeError 被 catch 吞掉 ⇒ 结果丢弃 ⇒ pending 永挂。
5. **章节引用错位**：`RULES.md:486` / `README.md:141` 引「lab-runtime.md 第 8 章」，实际链路约束在第 9 章（§9.0 起）。
6. **域政策 KDoc 停滞**：`ToolGateway.kt:30-31`、`ToolRegistry.kt:90-135` 仍写「摘除 shell 域」旧口径（2026-10-01 起为带闸门开放）。
7. `lab-runtime.md:184`「眼镜专属工具全清单（18 个）」实列 19 个名字，与 `requiresGlasses` 差 1。
8. `permission/AppPermission.kt:82-86` 注释引用旧工具名（`query_calendar`/`add_calendar_event` → 现 `manage_calendar`/`update_calendar_event`/`delete_calendar_event`）。
9. `ToolRegistry.PSEUDO_DISPLAY_RES` 缺 `ask_user`（7 vs `PseudoTools.BY_NAME` 9）⇒ 过程卡片显示 wire name。建议补一条「PseudoTools.names() ⊆ displayNameOf 可解析」兜底断言。
10. `ToolIntentRouter` 中文子串匹配固有噪声：music 关键词含单字「歌」、timer 含「叫我」⇒ 否定句误命中（只影响白名单装配、不影响执行；有三层保险）。可去掉单字关键词。
11. v5 文档 P1-1（通知读取）/ R2 已被 10-17 工作区修复（`read_notifications`、`ScreenCaptureBroker`+`screen_mirror`），文档未更新。
12. 手机演示宿主 `AiuiWebHost.kt:419-421` 超时只回 textResponse、不调 deliverToolResult（眼镜端已双路）。

---

## 五、审查确认无问题的面（证明检查到位，非遗漏）

- **工具接线**：`check_tool_wiring.py` 三表一致（toolNames ⊆ ToolEntry ⊆ execute），「加了工具却调不到」有机械门禁。
- **四道 preBuild + 两道发布闸门**全部在位且在跑（协议同源 / i18n / 空 catch 双门禁 / git clean + 单测绿）。
- **原子写基础设施**：`AtomicFileWrite`（tmp+ATOMIC_MOVE+无 delete 窗口）被 10+ 处调用方采用（FileWorkspace / WebTools / ScriptStore / ChatHistoryStore / AiuiAppRegistry / GoalStore / McpServerStore…），备份层与删除前备份齐备，`MAX_BACKUPS_PER_FILE=5` 有上限。
- **AIUI 工具桥五条已知坑**全部有在位防护：knownTools 惰性求值、ink 无事件循环（桥内无 await/.then）、globalThis.Lab 约定、收尾轮摘出图工具、页面路径 18s fail-closed；超时数值链自洽（网关 15s / 审批 18s / 页路径总预算 23s / 眼镜 sync 端点 25s）。
- **审批闸门不变式**：授权 seq<结果 seq、拒绝路径不写记录、fail-open 记 POLICY、双确认通道诚实（不可用如实返回 false）、abortAll 双语义分离、cbId 去重位于审批之前。
- **执行层**：未知名工具三重防线无静默成功；guard 顺序=求值顺序；`[NEED_TOOLS]` 泄漏三层终态防护；菜单探测轮不流式；限流数值有测试钉住。
- **代码卫生**：零 TODO/FIXME、零 `if(false)`、零 `GlobalScope`、所有 `CountDownLatch.await` 带超时、无 `by lazy` 包可变容器、无硬编码 `Color(0xFF…)`/裸 `RoundedCornerShape` 于 UI 违规项。
- **构建配置**：签名材料出库（无明文回退、缺材料硬失败）、依赖 pin（CXR bridge timestamped snapshot / okhttp 4.12.0 / jtokkit 1.1.0）、明文流量由 NSC 白名单收口、R8 + keep 规则有据。

---

## 六、建议处理顺序

> ✅ = 已在 2026-10-18 的三批里落地（记录见 §8 / §9 / §10）；「剩」= 仍待处理。

1. ~~**立即（安全）**：S1 三令牌作废轮换 + 改注入方式 + 补扫描门禁；S2 ShellStageAnalyzer 穿透判定 + 三钉测试。~~ ✅ 代码侧全落地（§10.1 / §9.1）；⚠️ **剩**：令牌作废轮换需用户去后台执行。
2. ~~**本版本（正确性/能力）**：M2、M1/M3、M4、M13、M12、M14。~~ ✅ 全落地（§8）；S3 已按用户拍板改为**登记不暴露**（§10.4）。
3. **随 v4.8/v5.0 规划**：
   - ✅ M5 投屏工具（§10.2）、M7/M8 小补齐（§8.1）、M10 眼镜端补测试（§9.3）、M15 事件流（§10.3，改法与原建议有出入，见该节）、文档对账（§8.2）；
   - **剩**：M6 的 P0-1 / P0-2 —— P0-1 的推进顺序已定（§10.5：先给步骤4/4.5 补录制回放用例，再逐段切）；P0-2 验证层未动。
4. ~~**拍板项**：M9 五个「不暴露」决定。~~ ✅ 已全部登记（§10.4）。

---

## 七、待用户拍板（策略类，不擅自改）

1. **S3 眼镜文件写操作**：是否做？传输选路（shell push vs sync 协议）？删除风险档（建议 EXTERNAL+BLOCK）？
2. **RokidLink 重装工具化**：自伤性运维动作（会卸眼镜端本地数据）是否交给模型，还是登记「刻意不暴露」？
3. **HID 输入通道**：「AI 操作眼镜上的游戏/输入」是否产品预期？
4. **持续环境音工具出口**：隐私面比一次性大，是否开放（或维持顶栏按钮手动）？
5. **通知读取语义**：10-17 已落 `read_notifications`，确认落地口径是否为「默认关、设置页手动开」（v5 文档建议值）。
6. **hearing/vision 域对 AIUI 页面开放** vs `ToolRegistry` 注释「第三方页面不该有静默开麦的能力」——两处口径漂移仍在（`PageScope.ALLOWED_DOMAINS` 开放、registry 注释收紧），需统一。

---

## 八、2026-10-18 修复记录（分两批，全部未提交）

### 8.0 第一批：「无风险项」

> 范围口径：**只做无风险项**——低危文档漂移、一行级口径对齐、零行为变化优化、有兜底的小修复。
> 需拍板/高风险项（S1/S3、P0、M5/M9/M11/M15）**未动**，留待后续轮次。
> 全部改动均未提交（工作区叠加在 v4.7 HEAD 上），发布前须先入库（否则 `checkGitClean` 挡 release）。

### 8.1 中危小修（有兜底/零行为变化）

| # | 修复 | 落点 |
|---|---|---|
| M1 | AIUI 下行失败补发短错误帧 + 修失实注释（页面桥无超时机制，pending 永挂） | `CxrLHiRokidSession.handleAiuiToolCall` 失败分支 `runCatching` 补发 `{type:toolResult,cbId,ok:false}`，双写日志 |
| M2 | 网关预检默认口径与审批链同源（`PageScope.pageVisibleTools()` / `PageScope::domainOf`），修 L30-32 过期域政策 KDoc；补 2 条回归测试（hidden 非 pageOnly 拒 / get_cover_image 不误伤） | `ToolGateway.kt` + `ToolGatewayLogicTest.kt` |
| M3 | 提取 companion 级 `sanitizeForJs`（U+2028/2029），静态兜底通道与实例通道共用 | `AiuiLinkActivity.dispatchMessageToActive` / `dispatchHostMessage` |
| M4 | guard 全序逐字锁定（9 道一个不少一个不错），挡「中间插新 guard」 | `ApprovalGateTest.I3b` |
| M7 | `manage_proactive` schema enum 补 `notify`（七格对齐面板），类注释补审计说明 | `StatusToolProvider.kt` |
| M8 | 新增 `browser_close` 工具（未打开时如实回复），双语字符串 | `BrowserToolProvider.kt` + strings.xml ×2 |
| M13 | 接线脚本新版（477 行）入仓 `scripts/`，挂 preBuild 第 5 道门禁（`checkToolWiring`，解释器缺失即失败不静默跳过）+ CI 快速失败步 | `scripts/check_tool_wiring.py` / `phone-app/build.gradle.kts` / `.github/workflows/ci.yml` |
| M14 | 四个热派生表（toolList/sideEffect/glassesRequired/pageCallable）并入 `DerivedTables` 版本缓存，零行为变化（enabled 快照仍每轮读 prefs） | `ToolRegistry.kt` |

### 8.2 低危项

| # | 修复 |
|---|---|
| 1/2/3/4/5/6 | README 补 v4.5–v4.7 三章；工具数 113/18/23、页面可调 104、测试数 88 类/1115 例、i18n 1892/24、棘轮 41、APK 名 v4.7 全部刷新；错误示例改回调式 `control_music`（await+play_song 双坑）；e.data 改实测口径（对象）；「第 8 章」→第 9 章两处；门禁表加 `checkToolWiring` 行 |
| 7 | `lab-runtime.md:184` 标题 18→19，注解 `stop_aiui_app`（`requiresGlasses=false`，眼镜离线 target=phone 可用） |
| 8 | `AppPermission.kt` 日历注释旧工具名 → `manage_calendar`/`update_calendar_event`/`delete_calendar_event` |
| 9 | `PSEUDO_DISPLAY_RES` 补 `ask_user` + `ToolRegistryPseudoDisplayTest` 双向防腐断言（已完成于 10-17 批次） |
| 10 | `ToolIntentRouter` 关键词消噪：music 删裸单字「歌」改收高置信说法；timer「叫我」否定后向断言；2 条回归测试 |
| 11 | v5 文档 P1-1 标「已落地待发布」；R2 标「已反转落地」（`screen_mirror`+`ScreenCaptureBroker` 系统授权框路径） |
| 12 | 手机演示宿主 `tool_call_sync` 超时路径补 `deliverToolResult`（双路，对齐眼镜端） |

### 8.3 代码口径顺手纠偏（同文件 KDoc，零行为变化）

- `ToolRegistry.kt`：`nameOf` KDoc 去写死「103 个」（指向脚本权威口径）；`schemasReadOnly` KDoc 旧工具名 `play_song` → `control_music`；DOMAIN_SHELL/SCREEN/BROWSER 三处「不对页面开放/只摘 shell」→ 2026-10-01 带闸门开放；toolMenu 注释去写死数字
- `ToolGateway.kt`：域政策 KDoc 同步带闸门开放口径

### 8.3 M12 位图采样（一行级，8 处改 / 4 处有据不改）

改法统一为调用既有 `util/ImageDecoder.kt` 的 `decodeSampledBitmap`（幂二采样，bounds 两遍解码）：

| 站点 | 上限 | 理由 |
|---|---|---|
| `ChatScreen.kt:632/663/2326`（待发送图缩略图） | 1024 | 相机/选图原图 4000×3000 ≈48MB，全量解码是 ChatImageCache 华为 P20 OOM 同款路径 |
| `SettingsScreen.kt:677`（赞助方头像） | 256 | 圆形头像显示只有几十 dp |
| `FileManagerActivity.kt:222`（图片预览） | 1600 | 预览区 400dp 高，大屏放大仍清晰 |
| `MusicPlayerController.kt:323`（封面 for page） | `maxPx*2` | 下游只缩到 maxPx（默认 256） |
| `MusicPlayerController.kt:377`（封面 async） | 640 | 下游限 320px（Binder ~1MB 事务上限） |
| `DisplayToolProvider.kt:240`（show_image 下载） | 1024 | 下游 compressToBase64 最大只到 480/320 |

**有据不改的 4 处**（防后续审计重判）：
- `VisionToolProvider:118` / `PhotoQuizFlow:367`：本地 OCR 取图，降采样直接掉识别率；
- `AdbScreenMirrorClient:681`：帧尺寸受眼镜屏幕约束且投屏支持缩放，需全分辨率；
- `ChartGlassPush:196`：**审计名单有误**——该位图是 480×640 的自渲染离屏快照（`SNAPSHOT_W/H`），不是外部图，采样到 1024 是 no-op（无 OOM 风险）。

### 8.4 明确**不做**（需拍板/高风险，留待后续轮次）

- S1（令牌轮换+扫描门禁）/ S3（眼镜文件写）：安全语义或产品边界变更，需先拍板；
- M5（投屏启停工具）/ M6 的 P0-1 与 P0-2（验证层）/ M9（五个「不暴露」决定）/ M11（2885 行函数拆分）/ M15（事件流读写改造）：见「建议处理顺序」，均未动。

---

## 九、第二批修复记录（2026-10-18 晚，安全与正确性）

> 范围：**安全闸门绕过（S2）+ 历史 bug 的测试补齐（M6-P0-3 / M10）**。
> 三项全部有明确判据、无需产品拍板；每项都先证伪测试（改坏实现看用例是否真的会红）。

### 9.1 S2 ShellStageAnalyzer 包装绕过（高危，本批次最重要）

**根因复述**：`analyzeStage` 的痕迹取样范围只到 `argv`。于是三层包装各绕过一次：

| 命令 | 旧判定 | 为什么错 |
|---|---|---|
| `python3 -c "…shutil.rmtree('/mnt/lab/x')"` | `AUTO_SAFE「python3 只读」` | `python3` 在 `READ_ONLY` 白名单里，而删除动作藏在 `-c` 的代码串中 |
| `bash -c "rm -rf /mnt/lab/x"` | `AUTO_SAFE「触及 /mnt/lab 但不删除/覆盖数据」` | `bash` 不在任何表，但 `rm` 是**带引号的参数**；**文案本身失实** |
| `echo /mnt/lab/x \| xargs rm -rf` | `AUTO_SAFE「容器内操作」` | `xargs` 的 argv 里根本没有 `/mnt/lab` —— 路径是从管道流进来的 |

三条都违反本类 L43-45 自己写的保守原则（「静态看不透 ⇒ 绝不自动放行」）。

**修法（判据不变，只补取样范围）**：
- 新增 `DESTRUCTIVE_IN_TEXT`：按**文本子串**扫破坏性痕迹（shell 动词 + `-delete/-exec` 开关 + 脚本语言删除 API `shutil.rmtree`/`os.remove`/`fs.rm`…）。判定条件仍是**破坏性痕迹 × 真实挂载点同现** ⇒ `REVIEW_DANGEROUS`；
- 新增 `PASSTHROUGH`：14 个参数穿透型命令（`xargs/env/nohup/timeout/sudo/nice/…` 与代码串型 `eval/exec/source`）。判据是「argv 里有删除动词」⇒ `REVIEW_UNRESOLVED`（路径不可知）；
- 两处判定都插在 `cmd in READ_ONLY` **之前**——否则白名单分支会先把它们放行。

**⚠️ 刻意不加的更粗规则**：审计建议的「解释器带 `-c` ⇒ 一律 REVIEW_UNRESOLVED」**会造成误伤**：`run_shell` 的 schema 自己就推荐 `python3 -c "print(sum(range(10)))"`（`ShellToolProvider` description 示例），而 `bash -c "rm -rf /tmp/build"`（容器内清理）是模型日常产物。按本类既有判据（靶子是真实用户数据，不是危险命令行）把它们拦掉会废掉功能。**路径在代码串里字面可见，所以"同现即危险"已经够用。**

**测试**：`ShellStageAnalyzerTest` 新增 E1–E4（解释器包装拦 / 容器内解释器不拦 / 参数穿透拦 / 参数穿透日常不拦）。**已做证伪**：把两处判定改成 `if (false)` 后恰好 E1、E3 变红，E2/E4 仍绿——证明四个用例分别锁住了修复的两侧，不是恒绿断言。

### 9.2 M6-P0-3 截断下载：**修复早已在 HEAD，缺的是测试**

- **审计描述已过时**：`AdbFileManagerClient.downloadFile/downloadFileShell` 的 `reconcileDownloadedSize`（落盘后按远端字节数对账、不符即删半成品 + 落 `LogCollector`）**已在 HEAD**，工作区无相关 diff；`pullFile` 同款修复也在。
- **真实缺口是零覆盖**：`AdbFileManagerSyncTest` 测了「正常成功」与「远端 FAIL」两条路，独独没测**事故本体**那条（下载成功但字节数不符）。按 RULES §12.15「历史真出过 bug 的文件必须有测试」，补 3 个用例（不符判失败且删半成品 / 相符保留 / `expectedSize=-1` 跳过对账）。

### 9.3 M10 眼镜端补测试（1 类 9 例 → 2 类 20 例）

眼镜端此前只有 `AiChannelProtocolTest`，而近 10 个提交全在改眼镜端。新增 `AiuiHostBridgeTest`（11 例），测**眼镜端独有、phone-app 无对应实现**的三样纯逻辑（所以同源覆盖帮不上忙）：

1. `sanitizeForJs` 的 U+2028/2029 转义 —— 即 M3 刚提的共用函数，纯函数却 `private` 无测试，失败模式是**静默**的（页面转圈到超时、零报错）。已提为 `internal` + 测试探针；
2. 工具结果缓存的判定前置（`type==toolResult`、`cbId` 非空）；
3. 缓存「取走即删」语义与**失败结果同样要入缓存**（曾有一版按 `ok` 过滤，失败结果全丢、页面永转）。

配套：`RokidLink/build.gradle.kts` 加 `testImplementation("org.json:json:20231013")`（与 phone-app 同版本，避免同一段 JSON 两端解析出不同结果这类假绿）。

### 9.4 第二批验证结果

| 检查 | 结果 |
|---|---|
| `ShellStageAnalyzerTest` | 24 例全绿（含新增 4 例），且**已证伪可红** |
| `AiuiHostBridgeTest`（新） | 11 例全绿 |
| `AdbFileManagerSyncTest` | 9 例全绿（含新增 3 例） |
| 双端全量单测 | phone-app **90 类 / 1122 例**；RokidLink **2 类 / 20 例**（原 1/9）；failures=0 errors=0 |
| `:phone-app:preBuild` 五道门禁 | `--rerun-tasks` 强制重跑全过（i18n / 关键链路空 catch / 空 catch 棘轮 / 协议同源 / 工具接线） |

---

## 十、第三批修复记录（2026-10-18 晚，S1 + M5 + M15 + S3/M9 登记）

> 用户拍板范围：**全做（含 P0-1/M11 大拆分）**；S3 与 M9 五项**登记为刻意不暴露**。
> 打包/装机仍未执行。

### 10.1 S1 三令牌：改注入方式 + 第六道门禁

| 令牌 | 原位置 | 现在 |
|---|---|---|
| Gitee PAT | `settings/DeveloperScreen.kt` `const val` | `BuildConfig.GITEE_TOKEN`（构建期注入） |
| 爱发电 token | `settings/SettingsScreen.kt` `const val` | `BuildConfig.IFDIAN_TOKEN` |
| 酷我 API token | `util/AppConfig.kt` `const val` | `BuildConfig.KUWO_API_TOKEN` |

- 注入复用签名参数的 `optionalSigningProperty`（gradle.properties / `ORG_GRADLE_PROJECT_*` 环境变量 / `~/.gradle/gradle.properties` 三选一），模板见 `gradle.properties.example`。
- **缺值不硬失败、编成空串**：三个令牌各有优雅降级（Gitee 报「未配置」、赞助列表空、点歌答「没找到」）；硬失败会连带打不出 debug 包。
- 值入库前做 `\"` / `\\` 转义 —— 不能假设本地文件里的值是干净的。
- ⚠️ **令牌作废轮换仍需用户去各自后台执行**（Gitee / 爱发电 / 云萌），改注入方式只解决「防第三次」，不改变已泄露的事实。

**新增第六道门禁 `checkNoHardcodedSecrets`**（挂 `preBuild`）：

- 判据是**形态**而非已知字面量 —— 「像凭据的标识符被赋了长字面量」，与具体值无关，新令牌自动纳入；
- ⚠️ 首版写成「行里出现凭据名 + 行里有长串」立刻**误报** `val fileUrl = "...?access_token=$GITEE_TOKEN"`（URL 模板）。收紧为**锚定赋值语句**（名字必须是被赋值的那个标识符，值必须是纯字面量、不含 `$`）；
- **已证伪可红**：埋 `private const val FAKE_TEST_TOKEN = "abc123def456ghi789jkl012"` → 门禁红并精确到行号，随后移除。当前实跑 375 个源文件 / 0 违规。

### 10.2 M5 眼镜→手机实时投屏工具（`screen_mirror_glasses`）

- ⚠️ 与既有 `screen_mirror` **方向相反**、不是重复：那个是手机→眼镜（MediaProjection），这个是眼镜→手机（ADB scrcpy，无授权障碍）。
- 新增 `mirror/GlassesMirrorSession.kt`：工具与投屏页之间的**单点桥**（弱引用 + `AtomicReference`），提供 `launch` / `requestClose` / `isPageOpen`；`ScreenMirrorActivity` 在 onCreate/onDestroy 登记与撤销。
- **边界如实收窄**：投屏页状态机是 Activity-handle 模式，工具拿不到实例 ⇒ `status` 只答「页面开着没有」，文案里明说这**不等于正在串流**；不把状态机改成单例（那是另一次重构）。
- 前置三条都如实说，不假装成功：ADB 不可达 / App 不在前台 / 眼镜端页拉不起，各自给可执行的下一步。
- 停在 `GlassesToolProvider`（眼镜域），`LOCAL_SIDE_EFFECT` + `sideEffect` + `requiresGlasses`；`GLASSES_MIRROR_LAUNCH_TIMEOUT_MS=15s`（不是包安装的 45s —— 这里只发一条 `am start`）。

### 10.3 M15 事件流读写：三处复核后只改两处

| 审计建议 | 复核结论 |
|---|---|
| 写侧持 `BufferedWriter` | **不改**。`SessionLog` 实例是**一次性**的（`StatusToolProvider` 每次工具调用 new 一个、`AgentSessionManager` 每会话一个）⇒ 持 writer 对多数实例收益为零，却对所有实例引入「未 flush 时进程被杀 ⇒ 尾部事件丢失」——而事件流是**取证凭据**。判据已写进 `append` 的 KDoc 防重判 |
| 读侧 `read(from)` 预筛 | **改**。解析前用行首 seq 跳过不相关行，比整行 `JSONObject` 便宜两个数量级 |
| `SessionTrace.items` 先 map 全量再取尾 | **改**。`toItem` 要跑 `oneLine` + 4000 字截断，是最重的加工；而 `turnStats()` 必须扫全量（轮耗时跨轮首尾相减）—— 两者代价不在一处，只裁前者 |

⚠️ **`read(from)` 预筛踩了一个隐蔽坑**（已写进代码注释）：首版锚定 `{"v":1,"seq":42,` 这个行首前缀，实测**单测永远不命中** —— `JSONObject` 键顺序无契约，Android 实现用 LinkedHashMap（保序），而单测用的 `org.json:json` 用 HashMap（顺序由哈希决定）。更糟的是**它是静默的**：预筛是优化，退化不改变任何返回值，只有断言「增量读 == 全量读后过滤」的测试能抓到。判据改成**顺序无关**：`"seq":` 出现在结构位置（前一非空白字符是 `{` 或 `,`，即它是顶层对象的键），这样被转义进字符串值里的 `\"seq\":999` 不会命中。

新增 3 例 `SessionLogTest`（坏行边界一致性 / 正文含 seq 字面量不漏行 / 全量读不受影响）。

### 10.4 S3 + M9：登记为「刻意不暴露」（用户 2026-10-18 拍板）

`RULES.md` 新增 §12.22，6 项逐条写判据 + 重开条件：

| # | 能力 | 判据要点 |
|---|---|---|
| S3 | 眼镜文件写操作整域 | 不可恢复（眼镜侧删除无回收站/备份层）+ 传输选路未收敛（R3 特意走共享 ADB shell，与 `AdbFileManagerClient` 是两条路） |
| M9-1 | RokidLink 重装 | 自伤性运维，会卸眼镜端本地数据，恢复手段不在手机上 |
| M9-2 | HID 输入通道 | 产品定位未定（Lab 是系统能力延伸还是通用遥控器）+ 输入注入无视觉反馈闭环 |
| M9-3 | 持续环境音 | 与一次性录音差一个隐私量级（等价长时间开麦且无自然结束点） |
| M9-4 | 眼镜系统导航（回桌面） | 对用户当前视觉焦点的**无参抢占**，与「打开 X」不同 |
| M9-5 | 眼镜 app disable / extract | disable 让应用凭空消失且不可逆；extract 涉第三方可执行文件 |

同时留了一条反向警戒：**不要用「危险」当判据往工具面加名字**（`DENY_TOOLS` 6 项就不是因为危险才禁，而是「一次调用 → 多次模型调用」的不可取消性）。

### 10.5 M11 / P0-1 大拆分：**本轮未动**，先记清约束与前置

用户拍板「全做，含 P0-1/M11」，侦察后结论是**这一项不适合在本轮硬切**，理由是客观的：

| 观测 | 数值 | 对拆分的含义 |
|---|---|---|
| `sendAiTextViaLink` 体量 | **3047 行**（1254 → 文件末尾 4300） | 它是文件的**整个后半段**，内部无顶层切分点，只有注释标记的阶段 |
| 局部 `val`/`var` 声明 | 16 处 | 切出去要逐个变成参数，且多数是**跨阶段共享的可变状态**（`reply` / `asrResult` / `endResult` / `glassSpoken` / `cloudPcm`） |
| 内嵌局部函数 | 2 个（`sendTtsPlay` / `sendTtsPlayWithRetry`，步骤4.5 内） | 局部函数捕获外层 4 个变量，搬走要改写成参数或提为成员 |
| 嵌套 lambda | 63 处 | 多数是 `launch { }` / `launchAiGeneration` 标签返回，**K2 禁对 val 赋值的 lambda 标签 return**，搬动极易改变控制流 |
| try 块 | 8 个 | 每个 catch 都有独立的复位语义（`onBusyChanged(false)` + `onResult(false, …)`），拆错一处 ⇒ 发送按钮永久转圈 |
| 验证手段 | **零单测** | 这是 P0-1 的核心问题本身；无测试 ⇒ 一次「零行为变化」的搬移无法证明零行为变化 |

⚠️ **为什么不能先搬后测**：`AiConversationService` 是全项目最高危的文件（3100+ 行、零单测，10-07 两条注入高危与 10-16 拍照拨号 bug 全出在这里）。在**没有回归网**的前提下做 3000 行搬移，等于把「改不坏」这件事交给运气。

**建议的推进顺序（前置依赖倒推）**：
1. 先给步骤 4/4.5（下行发送段，约 420 行）补 `SessionReplayTest` 式的**录制回放**用例 —— 它输入输出最封闭（`reply` + `glassSpoken` + `cloudPcm` → 一串 `sendCustomCmd`），是天然的单测夹具；
2. 再切这一段（边界最清晰：只依赖 5 个变量 + `link`/`session`）；
3. 用同款办法逐段推进「AI 生成段」（最重，跨 2500 行）。

⇒ **本轮把边界与顺序定下来（就是上表），代码不动。** 这样下一轮不需要重新侦察，且第一步是可验证的（补测试）而不是不可验证的（搬 3000 行）。

### 10.6 有据不改

- **M15 写侧**（见 10.3）；
- **M5 的串流状态暴露**：要把 `ScreenMirrorStateHolder` 从 Activity-handle 改成单例才能让工具读到 `isStreaming`，属另一次重构，本批只交付页面级启停 + 弱信号 status；
- **M11 / P0-1 大拆分**：见 10.5 —— 不是「不做」，是**先补回归网再动**（否则无法证明 3000 行搬移零行为变化）。

### 10.7 第三批验证结果

| 检查 | 结果 |
|---|---|
| `scripts/check_tool_wiring.py` | 23 provider / **114 工具** / 19 眼镜依赖 / 1 动态；`screen_mirror_glasses` 正确落进 SIDE_EFFECT + GLASSES_REQUIRED 两表 |
| `:phone-app:compileDebugKotlin` | BUILD SUCCESSFUL |
| 双端全量单测 | phone-app **90 类 / 1125 例**；RokidLink **2 类 / 20 例**；failures=0 errors=0 |
| `:phone-app:preBuild` 六道门禁 | `--rerun-tasks` 强制重跑全过（协议同源 / i18n / 关键链路空 catch / 空 catch 棘轮 / 工具接线 / **明文密钥**） |
| `checkNoHardcodedSecrets` 证伪 | 埋 `FAKE_TEST_TOKEN` → 红并精确到行号；移除后 375 文件 / 0 违规 |
| `checkToolWiring` 实证抓到一次真问题 | 工具名先进 `toolNames`、ToolEntry/execute 未写时立刻红 |

---

## 附：验证方式（用户自行执行）

```powershell
# 编译校验（本次审查已通过，复跑命令）
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.11.9-hotspot"
cd D:\rokidapp\cxrl\RokidLab
.\gradlew.bat :phone-app:compileDebugKotlin :RokidLink:compileDebugKotlin

# 工具接线回归（改任何 provider 后必跑；M13 起脚本随仓库分发，preBuild/CI 也会跑）
python scripts/check_tool_wiring.py .

# 单测
.\gradlew.bat :phone-app:testDebugUnitTest :RokidLink:testDebugUnitTest

# 六道构建期门禁（想强制重跑而不吃 UP-TO-DATE）
.\gradlew.bat :phone-app:preBuild --rerun-tasks
```

### 令牌配置（S1 落地后你需要做的事）

1. **去后台作废并轮换三个旧令牌**（Gitee PAT / 爱发电 / 云萌）——它们已在远端历史里，代码改动不改变这一点；
2. 新值填进 `gradle.properties`（从 `gradle.properties.example` 复制，**该文件不入库**），或用环境变量：
   ```powershell
   $env:ORG_GRADLE_PROJECT_GITEE_TOKEN = "<新 PAT>"
   $env:ORG_GRADLE_PROJECT_IFDIAN_TOKEN = "<新 token>"
   $env:ORG_GRADLE_PROJECT_KUWO_API_TOKEN = "<新 token>"
   ```
3. 不配置也能构建 —— 三个功能各自降级（Gitee 提交页报「未配置」、赞助列表空、点歌答「没找到」）。

### M5 投屏工具的真机自测步骤

1. 手机上打开乐奇 App 并保持前台，**先让乐奇手动开一次眼镜投屏页**，确认这条链路本来就通；
2. 对 AI 说「把眼镜画面投到手机上」→ 应打开投屏页并在约 5~15 秒内出画面；
3. 说「别投了」→ 投屏页应关闭（若系统拦了关闭请求，AI 会如实让你手动按返回键）；
4. 说「现在在投屏吗」→ 应答「投屏页当前是打开状态」并附带「这不等于正在串流」的说明；
5. ⚠️ 验证方向别搞反：`screen_mirror_glasses` 是**眼镜→手机**；`screen_mirror` 是**手机→眼镜**（要 MediaProjection 授权）。

> 打包/装机未执行（用户未授权）。工作区在修文件建议先提交入库，否则 `checkGitClean` 会挡 release 出包。
