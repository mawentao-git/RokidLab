# HUD DSL 规范（生成式眼镜 HUD，v1 语法 + v2 参数化/表达式）

> 手机端对话经 `glass_hud` 工具生成场景 DSL → 手机端渲染预览卡 → 下发
> `AiChannel.TOPIC_HUD_CTRL` → 眼镜端 `HudOverlayController` 解释渲染为悬浮层。
> 手机端解析器：`phone-app/.../glasses/HudDsl.kt`；眼镜端解释器：`RokidLink/.../HudOverlayController.kt`（两者逻辑同源，双副本维护）。

## 设计原则

1. **原子层足够低**：只有几何/发光原语，不固化任何风格词汇——风格全部由宏（macros）组合表达，宏随场景一起下发，眼镜端永不升级即可渲染新风格。
2. **单绿屏物理约束**：所有颜色收敛为 4 档「墨色」（ink 0–3），由绿屏物理特性决定，DSL 不允许出现任意色值。
3. **护栏优先**：解释器在解析期就拒绝超限场景，防止把眼镜拖死。
4. **风格多样性由提示词层负责**：DSL 与解释器不感知风格；**改风格策略不动本协议**。
   - 2026-10-21 定稿「cheatsheet 只给语法骨架不给视觉示例」——初衷防趋同，但 2026-10-24 用户实测「不管什么风格，出来的 HUD 都差不多」：零视觉锚点反而把产出拉向先验收均值（同心圆 + brackets + 扫描线的「默认科幻脸」），语法骨架里的 bg 默认值被照抄，且无反同构机制（「连续生成必须不同」一句话压不住 in-context 自我模仿）。
   - 2026-10-24 修订（cheatsheet 结构重排）：**创作契约前置**（立意三问 + 默认套路黑名单 + bg 按意象定制 + 构图范式轮换清单 + 多样性铁律）+ **正交构图示例矩阵**（三个极小片段：阵列重复/极简单线/角落栖息，只示范构图差异、明示禁止照抄）。防趋同的正确解法既不是「不给示例」（拉向均值）也不是「给唯一示例」（单一锚点），而是差异极大的多锚点 + 黑名单。
   - 2026-10-25 修订（用户四连实测反馈，全部落在提示词层与工具层，未动 DSL/协议/渲染）：
     - 「让做很多动画还是做不了/不会组合」⇒ 契约⑤的克制被模型当成硬顶。cheatsheet 新增【动画编排】段：预算明示（48 实例/128 元素/16000 字符）+ 组合模式（每元素≤2，spin+glow/orbit+pulse/dashFlow+fall…）+ 编排三法（分组错峰 phase/类型混搭/宏实例化）+ 明确「用户要求丰富时契约让位」。
     - 「不主动找答案/不主动看参考」⇒ 能力与提示双缺口：fetch_webpage 原先剥掉所有标签、模型拿不到页面图片 URL（`WebTools.extractImageUrls` 现按 ≤8 张 http/https 直链列出，懒加载属性优先于占位 src）；system prompt 新增【创作先找参考】条款（设计/创作任务+具体风格词 ⇒ 先 search_web，要不要检索取决于模型有没有把握、不取决于用户提没提）；cheatsheet 图片节补「search_web→fetch_webpage→hud_image」工作流。hud_image 的 URL 下载同步改走 NetGuard（引导模型传网页图 URL 后，裸连会成为内网探测旁路）。
     - 「预览/保存跟设计有出入」⇒ 根因是 glass_hud 无状态（scene 每次重传、模型重新生成即漂移）。新增 lastScene 记忆（内存 + `filesDir/hud_last_scene.json`）：try/apply/save 不传 scene 即零出入沿用上一次设计；`pickSceneJson` 纯函数由单测钉住（参数优先、缺省沿用、都没有返回 null）。
   - 2026-10-25 **skill 化改造**（用户定调：DSL 做框架，设计规范做成可升级 skill，代码与规范各改各的，参考 aiui-dev 的技能模式）：创作规范从 Kotlin 字符串常量（改一轮要编译+打包+装机，眼镜端还要 CXR 通道）迁入**内置技能 `hud-design`**（`phone-app/src/main/assets/skills/hud-design/SKILL.md`，经 `load_skill` 按需加载），上游 = gitee 独立仓库（dlover1314/rokidlab-hud-skill），设置页技能管理可「同步官方」拉新版。**三源分工**（谁是源，防双写漂移）：
     | 层 | 载体 | 拍什么 | 为什么在这 |
     |---|---|---|---|
     | 协议/语法 | `HudDsl.kt` + `glass_hud` 工具描述里的最小语法集 | 语法契约、限额、校验 | 与解析器同版本；上游 skill 与 App 解析器错配会写出被拒的 DSL ⇒ **版本强耦合，不进独立仓库** |
     | 风格/规范 | `assets/skills/hud-design/SKILL.md`（gitee 可升级） | 创作契约/黑名单/构图范式/多样性铁律/动画编排/参考工作流/正交示例 | 演进频率远高于协议；改规范不再需要改代码发版 |
     | 开发者文档 | 本文件 | 双端契约、字段语义 | 不随包进模型 |
   - 示例语法由 `HudCheatsheetTest` 防腐烂（改坏示例即测试红——模型会照抄示例，示例错了比没有示例更糟）；另钉住：技能正文 ≤12000 字符全文加载阈值、技能名与 glass_hud 描述的 `load_skill(name="hud-design")` 纪律互相咬合。

## 顶层结构

```json
{
  "v": 1,
  "bg":  { "scanline": 0.08, "grid": 48, "gridOpacity": 0.10 },
  "macros": { "宏名": [元素, ...], ... },
  "scene": [元素, ...]
}
```

- `v`：必须为 1，其他版本拒绝。
- `bg`：全屏背景质感层（可省略）。`scanline` = 扫描线强度 0–0.2（0 关闭）；`grid` = 网格间距 px（0 关闭）；`gridOpacity` = 网格线透明度，中央最亮向外径向渐隐。
- `macros`：可选。宏 = 元素数组，供 `{"type":"use","use":"宏名"}` 引用。
- `scene`：必填。元素列表，按顺序绘制。

## 区域锚点（zone）

元素坐标默认相对 `zone` 矩形**中心**；缺省 `x`/`y` 时取区域中心。区域矩形针对官方表盘页实测（480×640）：

| zone | left | top | width | height | 说明 |
|---|---|---|---|---|---|
| `top-center` | 128 | 8 | 224 | 133 | 自定义图盒子位 |
| `A` | 8 | 10 | 116 | 180 | 表盘左上空闲区 |
| `B` | 356 | 10 | 118 | 180 | 表盘右上空闲区 |
| `C` | 6 | 552 | 468 | 80 | 状态栏下方横带 |
| `full` | 0 | 0 | 480 | 640 | 全屏（默认） |

⚠️ **坐标空间 = 中心原点（2026-10-21 翻转，勿改回左上角）**：`x,y` 是元素定位点相对**区域中心**的偏移
（`full` 画布中心 = 0,0；+x 向右，+y 向下；偏移可正可负，-150 = 中心左 150px），不是左上角绝对值。
旧语义是「相对区域左上角的绝对值」，实测两轮模型都按中心原点思维写坐标（把 `x:0,y:0` 当画布中心、
四角装饰写 ±152/±236 负数偏移）⇒ 照片被扔到左上角、负数偏移被 clampCoord 压糊在角上
（设备截图 + 会话日志实锤）。翻成中心原点后，模型的自然写法即正确写法。

**定位规则所有元素一致**：`circle`/`dot`/`arc`/`polygon`/`ping` = 中心点；
`rect`/`image`/`brackets`/`ruler` = 框中心（极少数要左上角定位才加 `"anchor":"topleft"` opt-out）；
`line` = (x,y)→(x2,y2) 两端；`path` 的 `d` 是以元素 x,y 为原点的局部坐标；`text` = 基线锚点。
宏内部件缺省 x/y = use 定位点（2026-10-20 修复在此语义下自动保持）。

## 元素（type）

公共字段：`zone`、`x`、`y`、`opacity`(0–1)、`width`(线宽 px)、`stroke`(墨色档 0–3，默认 3)、
`fillInk`(填充墨色档，-1 = 不填充，默认)、`dash`([on, off] 虚线)、`anim`、`bindOn`("enum键:值" 条件显隐)。

| type | 专属字段 | 说明 |
|---|---|---|
| `circle` | `r` 或 `rx`/`ry` | 圆环（stroke）；`rx`/`ry` 定义椭圆（缺省 = r，斜视角轨道用） |
| `arc` | `r`、`start`(度,0=正上)、`sweep`(度) | 弧段 |
| `line` | `x2`、`y2` | 线段（(x,y) → (x2,y2)，两端同为相对区域中心的偏移） |
| `rect` | `w`、`h`、`radius`(圆角 px，缺省 0)、`anchor`(topleft) | 矩形（x,y = 框中心；`anchor:"topleft"` 时 x,y = 框左上角） |
| `dot` | `r` | 实心圆点 |
| `polygon` | `r`、`sides`(≥3) | 正多边形（外接圆半径，x,y = 圆心） |
| `path` | `d` | SVG 路径（支持 M/L/H/V/C/Q/Z；`d` 为以元素 x,y 为原点的**局部坐标**） |
| `use` | `use`(宏名)、`dx`/`dy`(可选偏移)、`rot`(整组旋转度)、`mirror`(整组水平镜像) | 展开宏（宏内元素带各自 zone/x/y，加 dx/dy 平移）；rot/mirror 绕 use 定位点（use 所在 zone 中心+x/y+dx/dy）做整组变换，不支持嵌套变换 |
| `brackets` | `w`/`h`(框大小，缺省=全屏)、`len`(臂长 px)、`inset`(框内缩 px) | 四角 L 形角标框（x,y = 框中心；旧实现固定屏幕四角且忽略 w/h/y，2026-10-21 重写） |
| `ruler` | `w`、`step`(刻度间距)、`major`(长刻度间隔) | 水平刻度尺（x,y = 刻度行中心，从 x-w/2 向右排） |
| `ping` | `r`(最大扩散半径)、`interval`(秒) | 扩散脉冲（必须带 anim 外的 interval） |
| `text` | `content`、`size`(px,8–48,默认24)、`align`(left/center,默认left)、`dir`(down=逐字符纵排,步进 size×1.2,x,y 为首字符锚点) | 文本（x,y = 基线锚点；content 或 bind 必有其一，≤20 字符；**P1-1：content 支持 `\n` 显式换行，≤4 行、每行 ≤20 字符，中文可用**——行距 size×1.2，align 逐行生效；不做自动 wrap：measureText 跨设备有 1 字级差异，双端不一致。字形以眼镜端系统字体为准，小字号中文可读性待实测） |
| `image` | `id`、`w`、`h`、`anchor`(topleft)、`mask`(circle)、`blend`(screen) | 位图主视觉（x,y = 显示框中心，图按 contain 居中适配 w×h 框；`anchor:"topleft"` 时 x,y = 框左上角；居中构图直接写 x:0,y:0，免手算；id 为 `hud_image` 工具登记的资源 id；不支持 stroke/fillInk/width/dash；mask/blend 见「图片主视觉」节） |

### 图片主视觉（hud_image 两步式）

位图不内嵌 DSL（16 000 字符护栏装不下图片），走**先资源后场景**两步：

1. 模型调 `hud_image` 工具传图片地址（http/https URL、**本地文件路径** `file://` 或绝对路径、或 base64）→ 手机端等比缩到长边 ≤240px、
   JPEG q80 编码，登记进 2 槽 LRU 表（id 自增 `img1`/`img2`…，新登记顶掉最旧 = 旧 id 过期），
   并经 `TOPIC_HUD_IMAGE` 先行下发眼镜端缓存；
2. 在 `glass_hud` 的 scene 里引用 `{"type":"image","id":"img1","x":0,"y":0,"w","h"}`
   （x,y = 框中心相对画面中心的偏移，居中就写 0,0）。实时下发点（preview/try/apply）解析时校验 id 已登记，未登记直接拒绝（报错回给模型）；
   主题库等离线场景不校验（保存时不检查，占位框兜底）。

**本地路径优先（2026-10-21）**：用户在对话里发过的图，其 `file://` 路径会随该轮用户消息
一起附带给模型（见 `ChatScreen.sendWithAttachments`）——模型直接把这个路径传给 `hud_image`，
手机端读文件解码（先量边界再 `inSampleSize` 采样防 OOM，>32MB 拒）。
不再需要用户重传 base64，也不需要把本地图改画成矢量图或去找外部 URL。

**锚点 anchor（2026-10-21 随中心原点翻转改版）**：所有元素 x,y 语义统一为「元素定位点相对
区域中心的偏移」，box 元素（rect/image/brackets/ruler）的定位点 = 框**中心**——这正是模型的
自然写法（居中写 0,0），2026-10-21 之前它是框左上角，导致两轮实测错位。极少数场景要按左上角
定位时，显式写 `"anchor":"topleft"`（此时 x,y = 框左上角）。约束：`anchor` 仅
image/rect/brackets/ruler 支持（其他 type 写即拒）；不能与 x/y/w/h 的 `expr:` 表达式同用
（每帧求值会覆盖解析期的锚点归一化）。两端解析器（手机 `HudDsl.parseEl` /
眼镜 `HudOverlayController.parseEl`）同源实现，必须双改。

**蒙版与混合（2026-10-25 P1-3，仅 image）**：
- `"mask":"circle"`：圆形蒙版，直径 = min(w,h)（随 scale 动画呼吸缩放）；缓存 miss 的占位框
  仍是矩形虚线框。头像/圆形主视觉用。
- `"blend":"screen"`：位图与已绘内容 Screen 叠印——位图黑区透出下层墨线、亮区加亮，
  做图文叠印层次（配 ink 0 暗轨/线稿底效果明显）。
- 两个字段仅 image 支持（其他 type 写即拒），值白名单外解析期拒绝；双端渲染同源。

⚠️ **存量主题影响**：2026-10-21 之前的已存主题按旧「左上角绝对值」语义保存，翻转后会整体位移
（如 full 区域 x=240,y=320 旧=画布中心、新=中心右下方 240px 外）——让模型重新生成，
或 `load` 后把坐标改为中心偏移量再 `save` 同名覆盖。

**主题持久化**：`save` 时场景引用的登记图随主题落盘为 `image_<id>.jpg` 副本（`filesDir/hud_themes/<id>/`）；
`use` / 主题管理页「应用」会先**按原 id 回灌**手机登记表并重推眼镜端缓存（先资源后场景），
再下发场景 DSL —— 重启、换机、登记表被顶掉后应用主题，图依然完整。
推送失败不阻塞场景下发（眼镜端画虚线占位框兜底，重新应用一次即可恢复）。
对话微调已存主题：`glass_hud` 加 `action=load`（返回主题 DSL 原文给模型）→ 改字段 → `save` 同名覆盖。

动画兼容：spin/scale/glow/pulse/fade 与 use 组变换天然生效；**orbit 与 dashFlow 拒绝**
（orbit 会以圆点替代元素本体、dashFlow 语义为描边流动，对位图无意义），解析期拒绝。
构图建议：image 放视觉重心，矢量元素做周围装饰与数据绑定。

### 主题包（.hudtheme）

本质 zip（`HudThemeStore.export` / `import`），与主题目录同构：

| 条目 | 说明 |
|---|---|
| `manifest.json` | {format:"hudtheme", v:1, name, createdAt, app} |
| `scene.json` | 场景 DSL 原文（唯一真相源） |
| `image_<imgId>.jpg` | 图片副本（可选；导入方按 `image_` 前缀解包回灌登记表，旧包没有 → 占位框兜底） |
| `preview.png` | 静态首帧（可选；缺省导入方自己渲染） |

导入为新 id 落库（重名自动加后缀）；旧版导入方显式按名读取、未知条目忽略 → 含图包向后兼容。

### 数据绑定（bind）

元素加 `"bind": { "key": "数据键", "as": "映射方式" }`，把实时数据映射到图形属性；数据经
`AiChannel.TOPIC_HUD_DATA` 事件驱动下行（小包+节流），眼镜端更新即重绘。无数据显示空态：
text 显示 `--`，sweep=0、count=0、enum 置暗（0.3 倍亮度、无呼吸）。

**key 白名单**（不在表内的键解析期拒绝）：

| key | 类型 | 值 | as=text 显示 | 备注 |
|---|---|---|---|---|
| `battery_glass` | percent | 0–100 | `87%` | ⚠️ 官方表盘已有电量，默认别用 |
| `battery_phone` | percent | 0–100 | `85%` | ⚠️ 同上 |
| `link` | enum | connected / disconnected | `ON` / `OFF` | |
| `ai_state` | enum | idle / busy | `IDLE` / `BUSY` | busy = 正在思考/调工具 |
| `music` | enum | playing / paused | `PLAY` / `PAUSE` | 10s 轮询；HUD 活动窗口内才采集（2026-10-25 P1-2） |
| `timer` | number | 剩余秒数（0=无） | `MM:SS`（本地走秒） | |
| `calendar_next` | number | 距下一日程秒数（空=2h 内无日程） | `45m` / `1h05`（本地走秒） | 30 分钟粒度刷新 + 走秒补齐；精确日程看 get_calendar |
| `unread` | number | 待摘要通知数（空=无） | 条数（不走秒） | 消息摘要开关开启才采集 |
| `gps_speed` | number | km/h（空=无定位） | 取整条数 | 5s/5m GPS 订阅；权限缺失/室内无 fix 显示 `--` |
| `gps_bearing` | number | 度 0–359（空=无定位） | 八方位 `N/NE/E/SE/S/SW/W/NW` | 同上 |
| `steps` | number | 今日步数（按本地日切基线） | 条数 | 需身体活动权限；不支持计步传感器显示 `--` |
| `tool_result` | text | 自由短文本（≤20 字符） | 原文 | `hud_display` 工具写入（AI 显式控制，非自动推送） |

**as 映射**（`as` 可省略，按元素类型推断：arc→sweep、dot→count、text→text、其他→enum）：

| as | 适用元素 | 映射规则 | key 类型限制 |
|---|---|---|---|
| `sweep` | arc | sweep = 值/满量程×360（钳 0–360） | percent 或 number+max |
| `count` | dot | 光点数 = 值/满量程×容量（向右排布，间距 `step`，暗点画轨道） | percent 或 number+max |
| `bar` | rect | 比例填充条：clip 裁剪填充宽 = w×值/满量程（`barFill` 墨色 0–3 默认 3；轨道用元素自身 stroke/fillInk 画） | percent 或 number+max |
| `progress` | line | 按比例描绘：从 (x,y) 画到 (x2,y2) 的值比例处（静态轨道另画一条 line） | percent 或 number+max |
| `enum` | 任意图形 | on（connected/busy/playing）= 正常亮度+呼吸；off = 0.3 倍亮度、无呼吸 | enum |
| `text` | text | 文本替换为显示值（见上表） | 全部 |

`bar`/`progress` 必须显式写 `as`（推断表不含它们）；`bar` 可配 `"barFill":0-3` 指定填充墨色
（仅 as=bar 的 rect 有效，越界钳制）。

**满量程 max（2026-10-25 P1-2）**：比例映射（sweep/count/bar/progress）的 100% 基准。percent 键
恒 100（显式写 `max` 拒绝）；number 键量程各异（km/h、度、步数）**必须显式** `"max": 2..999999`
（如 `{"key":"gps_speed","as":"bar","max":120}` = 120km/h 满量程）；as=text/enum 不按比例，给 max 拒绝。
缺 max 的 number 键比例映射解析期拒绝并提示「只要原值显示请用 as=text」。

护栏：单场景 bind 元素 ≤ 8；bind 元素建议配一条 ink 0 的静态轨道（弧/点阵底下），无数据时不至于空白。
文本短句优先（绿端美学；2026-10-25 P1-1 起放开中文，`\n` 最多 4 行）；设计准则同前——官方表盘已有时间/电量，勿重复做数据面板。

**条件显隐（bindOn）**：元素加 `"bindOn": "link:connected"`——enum 键当前值等于指定值时显示，
否则整元素隐藏（数据未到达时也隐藏）。与 `bind as=enum`（置暗）互补：bindOn 管「在不在」，
enum 管「亮不亮」。key 仅限 enum 键（link/ai_state/music），值任意状态串（`link:disconnected`、
`ai_state:idle`、`music:paused` 等反状态均合法）。

**bindOn 数值阈值（2026-10-25 P0-1）**：`"bindOn": "timer:<300"`——键后直接跟比较符与阈值
（写法 `key:比较符值`），条件满足才显示（无数据/非数值 → 隐藏）。比较符 `</<=/>/>=/==/!=`
（`<=` 优先于 `<` 匹配）；键限 percent/number 类（battery_glass/battery_phone/timer/calendar_next/
unread/gps_speed/gps_bearing/steps）。
典型用法：`battery_phone:<=20` 低电量告警弧、`unread:>0` 有新消息才亮、`timer:<300` 倒计时紧迫态、
`gps_speed:>60` 超速才亮、`music:playing` 只在放音乐时显示。
相等比较（`key:值`）仍仅 enum 键；数值键误写相等（如 `timer:300`）解析期拒绝并提示阈值写法。
求值逻辑 `HudBind.matchOn`（手机）↔ `bindOnMatch`（眼镜）同源双副本。

### 动画（anim）

`anim` 可为**单对象**或**数组（最多 2 个组合动画）**：
`"anim": { "type": "...", ... }` 或 `"anim": [{...}, {...}]`。

公共动画字段：`period`（秒，1–300，默认 10）、`phase`（0–360，周期内相位偏移——错开多元素的
呼吸/旋转节奏，点阵波浪/涟漪的关键）、`reverse`（反向）。

| type | 专属字段 | 说明 | 适用元素 |
|---|---|---|---|
| `spin` | — | 绕元素自身 (x,y) 旋转 | 任意图形 |
| `orbit` | `orbitR`(px，默认 100) | 元素作为光点绕 (x,y) 公转并画轨迹环 | 任意图形（image 拒绝） |
| `pulse` | — | 透明度呼吸（0.35–1） | 任意图形 |
| `fade` | — | 透明度摆动（0.2–1，慢于 pulse） | 任意图形 |
| `scale` | `amp`(0–0.9，默认 0.3) | 半径/尺寸 ±amp 正弦呼吸（rect 绕自身中心缩放） | circle/dot/rect/polygon |
| `glow` | `amp`(0–0.9，默认 0.5) | 亮度 ±amp 呼吸 | 除 ping 外 |
| `dashFlow` | `speed`(px/s，1–300，默认 30) | 虚线蚂蚁线沿路径流动（需元素带 `dash`:[on,off]） | 描边图形（非 dot/text/ping/image） |

护栏：动画实例总数 ≤ 48（数组每项算 1 个实例，每元素 ≤ 2）；同元素动画类型不可重复；
不适用组合与缺失前提（如 dashFlow 无 dash）解析期拒绝。

## 墨色（stroke / fillInk）

| 档 | 色值 | 语义 |
|---|---|---|
| 0 | `#1E5C36` | 暗轨（最底层的结构线） |
| 1 | `#2E7A4B` | 中轨 |
| 2 | `#9FE8B8` | 次亮（辅助文字级） |
| 3 | `#46FF86` | 亮绿（主强调，省着用） |

## 护栏（解析期强制，超限直接拒绝）

| 项 | 上限 |
|---|---|
| 展开后元素总数 | 128 |
| 动画实例数（每元素 ≤2 组合） | 48 |
| `use` 嵌套深度 | 2 |
| dslJson 字符数 | 16 000 |
| bind 元素数 | 8 |
| text 内容长度 | 20 字符 |
| image 元素数 | 2 |
| 坐标/半径 | 钳制到 [-50, 530]×[-50, 690] |

## 协议

`AiChannel.TOPIC_HUD_CTRL = "rokidlab_hud_ctrl"`，载荷 `[cmd, version, action, dslJson]`：

- `try`：眼镜端 60s 试戴（自动收起；2026-10-25 用户要求由 12s 调长，`TRY_ON_MS`）
- `apply`：常驻（直到 `hide`）
- `hide`：收起（dslJson 空串）

手机端 `preview` 动作只渲染本地预览卡，不下发。

预览卡时间轴（2026-10-25 P1-4，纯手机端）：预览画布下方一行「播放/暂停 + 0–30s 滑块 + 时刻
读数」——拖动滑块即暂停并把 t 钉到该时刻（`HudRenderer.renderFrame` 本来就按 t 画，核对任意
动画中间帧），点播放从当前 t 继续；播放超过 30s 滑块钉在末端（动画继续播，拖动即定位）。
对话预览卡 / 全屏 / 主题管理预览三个面共用同一画布组件，自动获得时间轴。协议零改、眼镜端零改。

数据下行：`AiChannel.TOPIC_HUD_DATA = "rokidlab_hud_data"`，载荷 `[cmd, version, count, k1, v1, …]`
（事件驱动小包+节流，只发变化的键；眼镜端存 dataMap，更新即重绘。键白名单见「数据绑定」表）。
重连时手机端全量重发（`HudDataHub.resendAll`），新会话的眼镜端 dataMap 不缺历史键。
`tool_result` 键由 `hud_display` 工具写入（AI 显式把工具结果/状态词上屏），其余键由手机端系统状态采集。

采集键启停（2026-10-25 P1-2）：gps_speed/gps_bearing/steps/music 由手机端按需采集——场景绑定这些键
⇒ 采集器常开；未绑定则活动窗口兜底（glass_hud 下发/hud_display 后续期 10 分钟），`hide` 或空闲超时即停
并清数据（HUD 显示空态，不留过期值）。定位/身体活动权限缺失时静默跳过对应采集器（不弹权限框，
授权后下次窗口自动续上）。

图片资源下行：`AiChannel.TOPIC_HUD_IMAGE = "rokidlab_hud_image"`，载荷 `[cmd, version, id, base64Jpeg]`
（`hud_image` 工具下发，**必须先于引用它的 hud_ctrl 场景到达**；眼镜端 2 槽 LRU 缓存，
miss 时画虚线占位框、不拒绝场景）。

## v2：参数化与表达式（2026-10-21）

框架化核心：type 表冻结，新效果不再加代码，而是三块积木组合——AI 拿到用户意象后组装配置。

### 宏实例参数（空间维）

宏定义内字段值可写 `"p.名字"`（use 未提供且无缺省 → 拒绝）或 `"p.名字|缺省数字"`；`use` 时用 `p` 对象逐实例注入，解析期静态替换，零运行时开销：

```json
{"macros": {"rain": [
     {"type": "text", "content": "1", "x": "p.x", "y": "expr:(t*p.v0)%h", "dir": "down"}
 ]},
 "scene": [
     {"type": "use", "use": "rain", "p": {"x": 32, "v0": 60}},
     {"type": "use", "use": "rain", "p": {"x": 64, "v0": 110}}
 ]}
```

- 可参数化字段：数值字段 + `dash` + `anim`（对象/数组整体）；其余字段出现 `p.*` 解析期拒绝
- expr 体内可引用宏参数 `p.名字`（解析期替换为数字字面量，如 `(t*p.v0)%h`）
- 护栏：单宏 `p.*` 引用 ≤12 处；单 use 的 p 键 ≤6；p 值必须数字；参数名 `[a-z][a-zA-Z0-9]{0,11}`；嵌套 use 作用域合并（外层继承、内层覆盖）

### 属性表达式 expr:（时间维）

几何字段值可写 `"expr:算式"`，每帧求值覆盖静态几何（copy-on-draw，不改解析本体）：适用 `x/y/x2/y2/r/rx/ry/w/h/opacity/size/start/sweep`，其余字段拒绝。`x/y/x2/y2` 的求值结果 = **相对区域中心的偏移**（与静态值同语义，运行时加回 zone 基准；2026-10-21 中心原点翻转后同步改）。

- 变量（env = [t, i, w, h, sw, sh]）：`t`(秒) `i`(元素序号) `w`/`h`(元素 zone 宽高) `sw`/`sh`(480/640)
- 函数：`min(a,b)` `max(a,b)` `floor(x)` `abs(x)` `sin(x)` `cos(x)`（平滑波形：漂浮/波浪/心跳类运动；均走 `StrictMath` fdlibm 固定算法，跨设备逐位一致）`pow(a,b)`（幂：加速落体/抛物线/缓动；NaN/Inf 归 0）`r01(n)`（确定性伪随机 0–1，100ms 时间粒度，双端一致；驱动明暗/位置的逐元素错落）。`sin`/`cos`/`pow` 禁用 `Math` intrinsic 快路径（libm 差异破坏铁律）；`cos(x)` 与 `sin(x+1.5708)` 等价（测试锚点）
- 运算：`+ - * / %`、一元负号、括号；优先级 `* / %` 高于 `+ -`
- 永不抛：除零/模零/求值错误一律归 0；语法错误解析期拒绝
- 护栏：每条 ≤48 字符、每元素 ≤3 个 expr 属性、全场景 ≤48 个、数字字面量 ≤6 位、嵌套深度 ≤4
- 求值结果钳制：x/x2 ∈ [-50,530]、y/y2 ∈ [-50,690]、r/rx/ry/w/h ∈ [0,690]、opacity ∈ [0,1]、size ∈ [8,48]、start ∈ [-360,720]、sweep ∈ [0,360]
- 带 expr 的元素计入 hasAnim（眼镜端 ticker 常跑）；同一表达式同帧同输出，静态缩略图（t=0）与逐帧预览共用解析结果

### fall 位移动画（快捷维）

`{"type":"fall","speed":90}`：y 向匀速循环位移 `(t*speed)%zoneH`，出底回顶（数字雨/飘落类）。`speed` 钳 [10,300]，单位 px/s，默认 90。任一元素可用（text/ping 独立分支内同样生效），可与 spin 等组合。fall 与 `y: expr:(t*speed)%h` 语义等价（测试锚点），只是更短。

### 双端同源守护

表达式求值器（递归下降，手写，零 import）在双端各持一份逐字节副本：`HudDsl.kt` ↔ `HudOverlayController.kt` 的
`// ══EXPR-SYNC-BEGIN══` / `// ══EXPR-SYNC-END══` 标记区间。`checkProtocolSynced`（preBuild 钩子）逐字节比对，区间内只准用 kotlin stdlib / `java.lang.Math`（保证双端都可编译）。改求值器必须两端同改，或只改一处后整段复制。

## 示例（贾维斯轨道风）

```json
{
  "v": 1,
  "bg": { "scanline": 0.08, "grid": 48, "gridOpacity": 0.1 },
  "macros": {
    "satellite": [
      { "type": "dot", "r": 2.4, "anim": { "type": "orbit", "period": 34, "orbitR": 148 } }
    ]
  },
  "scene": [
    { "type": "brackets", "len": 20, "inset": 8, "width": 2 },
    { "type": "circle", "zone": "top-center", "x": 112, "y": 66, "r": 52, "stroke": 1 },
    { "type": "arc", "zone": "top-center", "x": 112, "y": 66, "r": 44,
      "start": 0, "sweep": 140, "anim": { "type": "spin", "period": 50 } },
    { "type": "circle", "zone": "full", "x": 240, "y": 320, "r": 190, "stroke": 1, "opacity": 0.5 },
    { "type": "use", "use": "satellite", "zone": "full", "x": 240, "y": 320 },
    { "type": "ruler", "zone": "C", "x": 34, "y": 596, "w": 412, "step": 48, "major": 3, "stroke": 2 }
  ]
}
```
