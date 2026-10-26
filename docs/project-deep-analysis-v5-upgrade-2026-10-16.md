# RokidLab 深度分析与下个大版本（v5.0）升级建议

> 分析日期：2026-10-16 · 对象：RokidLab 全仓（phone-app + RokidLink，HEAD 之后另有 10 个文件未提交改动）
> 方法：文档对账（README / DEV_GUIDE / RULES / docs/ 全部评估报告）+ 代码勘察（工具接线门禁取权威清单）+ 工作区记忆（D1–D46 判据库）+ 测试规模实测。
> 所有数字均为当日实测，非转述。

---

## 一、现状盘点（先建立事实基线）

| 维度 | 实测值 | 备注 |
|---|---|---|
| 版本 | 手机端 **4.7**（versionCode 32）/ 眼镜端 RokidLink **4.7**（versionCode 25） | README 更新总览**停在 v4.4**，缺 v4.5–v4.7 三个章节（文档漂移） |
| 工具面 | **22 个 ToolProvider / 109 个静态工具 / 17 个域**，`check_tool_wiring.py` 三表一致 ✅ | 另有动态 MCP 工具；独立 `manage_memory` 通道不计入 |
| 代码规模 | phone-app + RokidLink 约 4.8 万→现已显著增长（`AiConversationService` 单文件 3100+ 行） | 六层架构 L0–L5 + 5 条能力接缝（approval / compaction / llm / session / subagent） |
| 测试 | 10-18 复测 **88 个测试类 / 1117 个 `@Test`**（phone-app 87 类/1108 方法；RokidLink 仅 1 类/9 方法） | README 写"60 类 / 666 用例"——也漂移了；眼镜端测试近乎为零（10-18 修正：原记 RokidLink 27 类有误） |
| 构建门禁 | 4 道 preBuild（协议同源 / i18n 同步 / 关键链路空 catch / 全仓空 catch 棘轮）+ 2 道发布闸门（git clean + 单测绿） | 门禁文化是本项目最强的工程资产 |
| SDK | 双端 compileSdk/targetSdk **34**，phone minSdk 29 / Link minSdk 28 | Google Play 已要求 targetSdk 35（2024-08 起），**落后一个大版本** |
| 工作区 | 10 个文件已改未提交：PhotoQuizService `readOnlyTools` 修复（10-16 拨号 bug）+ 续做轮观测域装配 Phase 1（`ToolRegistryObservedSeedTest` 未跟踪）+ ChatArtifactChip + 贡献者条目 | 有一批**已完工未入库**的工作 |

**一句话现状**：这是一个架构纪律处于移动端 Agent 第一梯队的项目——安全闸门、注入隔离、事件流可回放、三层 token 降载都已落地且被测试锁死；当前的矛盾已从"能力不够"转为"**闭环不可信、编排不可测、文档不同步**"。

---

## 二、深度分析：优势与缺口（按优先级，全部带证据）

### 2.1 结构性优势（下个版本必须保住，别改回去）

1. **审批与安全**：`ApprovalGate` 唯一入口 + 8 道 guard 顺序合成（Deny 短路 / 首个 Ask 胜出 / null≠表态）；注入隔离 4 条路径全覆盖（工具口 / 自动 RAG / 长期记忆教训 / 调用方 contextText）；外部内容轮只读化（`readOnlyTools=true` 双兜底，2026-10-16 修拍照拨号 bug 时验证有效）。
2. **成本治理**：三层降载（闲聊快路 → 工具意图路由 → 菜单探测）+ `TokenMeter` 压缩记账 + desc 头部集中（27 个 ≥200 字符工具吃掉 59%，压缩靶点明确）。
3. **可观测**：`agent_sessions/<id>.jsonl` 事件流全程落盘 + 六类上下文注入逐条记账 + 轨迹视图 / 对话记录图；取证不依赖 logcat（10-15「显示封面」幻觉 bug 就是拉事件流定位的）。
4. **判据沉淀**：RULES + MEMORY-DETAIL（D1–D46）+ 各 skill，踩过的坑全部变成可复用判据而非注释。

### 2.2 P0 缺口（安全 / 正确性，须进下个大版本）

#### P0-1 `AiConversationService`（3100+ 行）零单测——风险已多次变现

- 2026-10-07 两条注入高危（`:1319` RAG 绕过、`:2252` 记忆播种）、2026-10-16 拍照拨号（PhotoQuizService 漏传 readOnlyTools）**全部出在这一个文件及其直接调用方**。这不是巧合：全部编排逻辑集中在唯一无测试的文件里。
- 2026-10-07 评估已建议"抽可测接缝（注入装配逻辑）并补单测（1~2 天）"，**至今未做**。
- **建议**：v5.0 做一轮编排层拆分——把注入装配（system 头 / 记忆 / 教训 / RAG / 技能 / 预算六段拼装）、轮次状态机（只读 10 / 动作 12 分账、续做、打断）抽成**纯函数 + 小类**，每条不变式配断言。目标不是覆盖率数字，是"改主循环时编译器与测试能兜住"。可借 `kotlin-godclass-split` skill 的方法论。

#### P0-2 通用 agent 四层中「验证层」仍缺失（D14.3 结论的唯一遗留）

- 感知层已由 screen 域 5 工具（无障碍）补上；**验证层——"动作后断言状态变化"——没有结构化机制**，现在靠模型自己 `look_at_view` / `read_screen` 复盘。直接后果就是 10-15 那类 bug：**模型"觉得自己成功了"**（拿到 `get_cover_image` 数据即幻觉"封面显示了"）。
- **建议**：做轻量「动作-断言」接缝——`ToolEntry` 声明 `expectedEffect`（如 `launch_app` → 前台包名 = 目标包；`play_song` → 播放状态 = 播放中），执行后由**框架**用既有只读工具核验，结果如实回给模型（三态：确认生效 / 确认未生效 / 无法断言）。这是把"成功但业务失败是第三种状态"（D46 判据）从人工审查变成机制。
- 严格度需决策：断言未生效时「自动重试一次」还是「如实回报让模型决定」。默认建议后者（副作用工具重试=重复副作用，A3 教训）。

#### P0-3 传输层数据完整性残留（CODE_AUDIT C7 / C8，2026-09-13 标记"仍在"）

- **C7**：`AdbFileManagerClient.downloadFile`（332–487 行）/ `downloadFileShell`（489–512 行）**只写不校验**——不与远端文件大小对账。而对称的 `AdbShellClient.pullFile` 已在 2026-09-13 修好（expectedSize 对账 + 分片重组）。**同一条链路两种可靠性**，静默截断不易察觉（8.7MB APK 只落 64KB 却报"已保存"的事故就发生在这条路上）。
- **C8**：`pullFile` / `downloadFile` 两份大同小异的手写实现并存——A4 当年"一份对一份错"的温床仍在。
- **建议**：v5.0 统一为一份带完整性校验的实现（对账 + 失败删半成品 + 落 `LogCollector`），消 C7 + C8。

### 2.3 P1 缺口（能力 / 产品，价值明确）

| # | 缺口 | 证据 / 背景 | 建议形态 |
|---|---|---|---|
| P1-1 | **通知按需读取无工具出口** | `LabNotificationListenerService`/`NotificationDigest` 数据只喂 proactive 陪伴，agent 答不了"我有什么新通知"（2026-10-14 记录，隐私敏感待决策） | 只读工具 `read_notifications`，per-source 限流 + 隐私文案 + **默认关**（在「管理 AI 工具」页手动开）。口径需用户拍板。**✅ 已落地（2026-10-17 工作区，待随 v4.8 提交发布）**：`read_notifications`（READ_ONLY，只读不清缓冲） |
| P1-2 | **实时投屏启停无工具** | 眼镜→手机的屏幕镜像只能 `MainActivity` UI 拉起，agent 只有 `capture_screen` 一次性截图。⚠️ 与 closedloop 文档 R2（手机投屏，MediaProjection 拿不到授权、已关闭）**不是同一件事**——这条走 ADB scrcpy 通道，无授权障碍。⚠️ 与 R2 也已分野：R2 的**手机投屏**已由 `screen_mirror` 工具落地（2026-10-17，start 自带 MediaProjection 系统授权框=用户闸门），本条（眼镜→手机）仍开 | `start_screen_mirror` / `stop_screen_mirror` + 状态查询，LOCAL_SIDE_EFFECT；UI 入口保留。📌 已由 2026-10-18 审查列为 M5（已知未修，底层 `MirrorCoordinator`+scrcpy 全现成） |
| P1-3 | **Lab 只做 MCP client，不做 server** | D14.6 判定的 P1：能力声明双向化，对齐 Android 16 `AppFunctions` 方向（官方 private preview，现在对齐=提前卡位）；本项目已是成熟 MCP 宿主，这是既有资产的自然延伸 | 把 `ToolRegistry` 以 MCP server 形态暴露（建议先仅回环 / 仅局域网），外部 agent 可调用手机+眼镜联合能力——**这是若琪生态真正的差异化**（原生集成 vs VisionClaw 的第三方桥接） |
| P1-4 | **文档漂移** | README 停 v4.4（缺 4.5–4.7）、测试数字过时、部分 KDoc/DEV_GUIDE 口径陈旧 | 版本发布流程加轻量对账（哪怕一道 `grep versionName README` 的脚本门禁）；v5.0 发布前全量对账一次 |
| P1-5 | **targetSdk 34 落后** | Google Play 底线已 35；双端 compileSdk 34 | v5.0 借机升 35（或 36），重点验 CXR SDK（`cxr-service-bridge:1.0` pinned snapshot）与 R8 行为；**需先实测再定**，不盲升 |

### 2.4 P2 缺口（体验 / 性能 / 结构，有余力再做）

- **P2-1 schema desc 定向压缩**：27 个 ≥200 字符工具占 59% desc 体量，逐个人工收紧长尾描述，每轮输入 token 直接受益（全量 ≈13–14k 的大头在这里）。
- **P2-2 C1 时序 sleep**：`AiConversationService` 6 处固定等待合计 ~2.6s/轮（285/725/735/805/820/830 行），全仓 63 处 `Thread.sleep`。根itecture 问题（用墙钟对齐协议），建议改等眼镜端确认回执，改动面大，单独一轮。
- **P2-3 C2/C3 小优化**：`AiuiLinkActivity` 同一 JSON 解析 2~3 遍（433–441 行）、主线程读 assets（333–334 行）。
- **P2-4 电话拨号正则过松**：`PhoneTools.dialPhone` 的 `^[+\d][\d\s-]{2,}$` 把 "119" 也当号码（2026-10-16 记录残留）。
- **P2-5 垂直闭环评测集**（D14.5）：选 3–5 个眼镜高频任务（语音点歌 / 设提醒 / 查信息+操作手机）建 感知→规划→行动→验证 全环 golden case，把 `GoldenAgentEvalTest`（现 20 案例）扩成带验证断言的端到对集——**闭环成功率与误放率从此是可度量 KPI**（判据：覆盖率不是 KPI，误放率才是）。

---

## 三、下个大版本（v5.0）升级建议

### 3.1 版本定位

**v5.0 主题：从「能力扩张」转向「闭环可信」。**
v3.x–v4.x 把能力面（109 工具 / 17 域 / MCP / AIUI 桥）和执行层（proot / 无障碍）建到了第一梯队；v5.0 的核心叙事应该是**"它做的事你可以信，它没做的事它会说"**——验证层、编排可测性、数据完整性三件事全部指向"可信"这一个词。版本号跳 5.0 的正当性也在这里：这不是功能累积，是质量属性的代际切换。

### 3.2 版本构成建议

**v4.8（先行小版本，1–2 周量级）——「收口」**：
1. 收尾当前未提交工作：PhotoQuiz `readOnlyTools` 修复（已编译验证）+ 续做轮观测域装配 Phase 1（含 `ToolRegistryObservedSeedTest`）+ ChatArtifactChip，提交入库。
2. P0-3 传输完整性统一（C7 + C8 一并修，带对账单测）。
3. 文档对账：README 补 v4.5–v4.7 章节、测试数字刷新。
4. P2-4 拨号正则收紧（一行级，随安全修复语义进 4.8）。

**v5.0（大版本）——「可信 + 双向」三大支柱**：

| 支柱 | 内容 | 判据 / 验收 |
|---|---|---|
| **A. 可信执行** | ① P0-1 编排层抽可测接缝 + 不变式单测；② P0-2 验证层（`expectedEffect` 声明 + 框架核验 + 三态回报） | `AiConversationService` 行数显著下降且新增单测断言指向生产代码；3 个以上工具有验证闭环 golden case |
| **B. 数据完整** | （C7/C8 已在 4.8 完成，v5.0 补传输层整体复盘：ADB sync / 隧道 / AIUI 包三条链路校对账） | 三条链路全部"收到字节数 == 声明大小"，失败删半成品 + LogCollector |
| **C. 双向能力** | ① P1-1 `read_notifications`（默认关，**2026-10-17 已落地待发布**）；② P1-2 投屏启停工具（仍开，见 M5）；③ P1-3 Lab 兼作 MCP server（回环/局域网） | 新工具全过 `check_tool_wiring` + `ApprovalGateTest` 名单不变式；MCP server 有鉴权与限流，不裸奔 |

**v5.0 明确不做**（防范围蔓延，全部有前案）：
- 通用 GUI agent 泛化（D14.5：先垂直闭环，Google 自己都只做三类 beta）；
- 照搬 DSH 32 接缝（本项目 4+1 条已够，过度接缝化是负资产）；
- 手机投屏 MediaProjection 工具化（**R2 已反转落地**：2026-10-17 `screen_mirror` 工具 + `ScreenCaptureBroker`，start 自带 MediaProjection 系统授权框——授权框本身就是用户闸门，原"工具拿不到授权"的关闭理由不再成立；待随 v4.8 提交发布）；
- Termux 回环通道复活（D21 已退役）。

### 3.3 需要用户决策的四个点

1. **通知读取隐私口径**：默认关（设置页手动开）/ 默认开但每次确认 / 默认开静默。建议默认关。
2. **验证层严格度**：断言未生效时自动重试一次 / 如实回报让模型决定。建议后者。
3. **MCP server 暴露范围**：仅回环 / 仅局域网 / 可配。建议默认仅局域网 + 令牌鉴权。
4. **targetSdk 升级时机**：v5.0 借机升 35，还是等 CXR SDK 官方跟进后再升。建议先做兼容实测再定，不盲升。

---

## 四、给用户自行构建/验证的步骤

```powershell
# 日常编译校验（不产出 APK）
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.11.9-hotspot"
cd D:\rokidapp\cxrl\RokidLab
.\gradlew.bat :phone-app:compileDebugKotlin

# 工具接线回归（改任何 provider 后必跑）
python scripts/check_tool_wiring.py

# 双端单测
.\gradlew.bat :phone-app:testDebugUnitTest :RokidLink:testDebugUnitTest
```

按用户约定：本文只做分析，**未执行任何打包 / 安装 / 设备实测**；是否装机验证由用户决定。

---

*事实来源：git log（HEAD=3830640, v4.7）、`check_tool_wiring.py`（22 provider / 109 工具 / 三表一致）、单测实测（90 类 / 1105 例）、`docs/agent-full-assessment-2026-10-07.md`、`docs/CODE_AUDIT.md`、`docs/AGENT-ROADMAP-DSH-ALIGNMENT.md`、`.workbuddy/memory/`（2026-10-14/15/16 及 MEMORY-DETAIL D14）。*
