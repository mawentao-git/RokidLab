# HUD DSL 框架化方案（v2 提案 · 实施依据）

> 状态：**已评审待实施**（2026-10-21 用户拍板「直接全量上」）。
> 本文是实施的单一事实源；实施时与 docs/hud-dsl.md（v1 规范）、双端 HudDsl 同步推进。
> 目标：效果从「每加一个效果改双端渲染代码」变为「AI 组装配置」——type 表冻结。

## 0. 三块积木与范围

| 积木 | 维度 | 一句话 |
|---|---|---|
| A. 宏实例参数 | 空间 | use 宏传 `p:{...}`，宏内 `p.x` 引用 —— 1 个定义派生 N 个变体 |
| B. 属性表达式 | 时间 | 属性写 `"expr:..."`，AI 写时间函数（t/i/r01） |
| C. 位移动画 fall | 快捷 | 匀速循环位移，常用运动不必写表达式 |

附带小改：text 加 `dir:"down"` 纵向逐字符排版（通用排版能力，非单点效果）。

**明确不做（取舍留痕）**：
- ❌ 字符串域表达式（`content` 不能 expr）—— 字符随机变化类效果属于程序化批量纹理，留给专用 type（rain 是最后一个手写 type）。重开条件：出现「AI 需要任意字符内容动画」的真实需求。
- ✅ 三角函数（sin/cos/pow）—— 2026-10-21 开 sin（ StrictMath fdlibm 固定算法，JDK 规范保证全平台同结果，解除「跨设备 libm 差异」的原始顾虑）；2026-10-25 同案开 cos/pow（pow 的 NaN/Inf 归 0）。教训：原「明确不做」的否决理由（ intrinsic 快路径）已被 StrictMath 化解，否决项要随依据变化重审。
- ❌ rain 改用表达式拼装 —— 14 列实例化成本是专用 type 的 15 倍。type 冻结原则：只有「批量实例化成本 > 封装成本」的字符/粒子类批量纹理才进 type 表。

## 1. 积木 A：宏实例参数

```json
"macros": { "rainCol": [
    {"type":"text","content":"7","x":"p.x","y":"expr:(t*v0)%h","size":14,"opacity":"p.dim"}
]},
"scene": [
    {"type":"use","use":"rainCol","p":{"x":32,"v0":60,"dim":0.8}},
    {"type":"use","use":"rainCol","p":{"x":64,"v0":110,"dim":0.5}}
]
```

- 引用语法：属性值字符串以 `"p."` 开头 = **解析期静态替换**（零运行时开销）；`"p.x|20"` = 缺省值（宏调用没给 p.x 时用 20）。
- 参数值仅数字（int/float）。替换发生在 expand 阶段，之后与普通元素无异。
- 护栏（解析期，报错回模型）：
  - 每宏定义参数引用处数 ≤ 12；每个 use 的 p 对象键数 ≤ 6；p 值必须为数字
  - 宏内引用了 `p.*` 而 use 未提供且无 `|` 缺省 → 拒绝（报错列出名）
  - 参数名 `[a-z][a-zA-Z0-9]{0,11}`
- 与嵌套宏兼容：内层 use 继承外层参数（同名覆盖，内层优先），替换统一发生在最外层展开时。

## 2. 积木 B：属性表达式

```json
{"type":"circle","x":"expr:60+40*sin?no","y":"expr:(t*90+i*97)%h"}   // 示意
{"type":"dot","y":"expr:h-((t*120+i*53)%h)","opacity":"expr:0.4+0.6*r01(i*7)"}
```

- 语法：属性值字符串以 `"expr:"` 开头 → **运行时每帧求值**（t 变化才重算，静态帧只算一次）。
- 变量白名单：

| 变量 | 含义 | 类型 |
|---|---|---|
| `t` | 场景播放秒数（渲染端时钟） | float |
| `i` | 元素序号（宏实例 = 实例展开后序号，0 起） | int |
| `w`,`h` | 元素所在 zone 宽/高（px） | float |
| `sw`,`sh` | 屏宽高（480/640） | float |
| `r01(n)` | 确定性伪随机 [0,1)：`splitmix32(n*2654435761u + floor(t*10))` 纯整数散列，双端一致 | fn(int)->float |
| `p.*` | 宏参数（解析期已替换成数字，运行时不可见） | — |

- 函数白名单：`min max floor abs`（单参/双参数值域）。运算符 `+ - * / % ( )`，数字字面量。
- 适用属性：`x y x2 y2 r rx ry w h opacity size start sweep`（几何 + 透明度 + 弧角）。
  **不适用**：content/align/dash/path d/zone/type（结构性字段用 expr → 解析期拒绝）。
- 护栏（全部解析期检查，报错回模型）：
  - 表达式 ≤ 48 字符；每元素 ≤ 3 个 expr 属性；全场景含 expr 元素 ≤ 48（逐帧 CPU 预算）
  - 求值失败/除零/模零 → 该属性取 0（不炸场景）；求值结果钳制：x/y/x2/y2 为「相对区域中心的偏移」[-320,320]×[-400,400]（2026-10-21 中心原点翻转后量纲，加回 zone 基准即画布坐标）、opacity [0,1]
  - 表达式内数字字面量位数 ≤ 6；嵌套括号深度 ≤ 4
- 求值器实现：手写递归下降（tokenize → parse → eval），约 150 行，**放在 HudDsl.kt 内** → 自动纳入 `checkProtocolSynced` 双端逐行同源校验。求值纯函数：`(expr, env) -> Float`，env = {t,i,w,h,sw,sh}，无任何系统调用。

## 3. 积木 C：fall 位移动画

- `{"type":"fall","speed":90}`：y 方向匀速循环位移（zone 顶 → 底，出底回顶），speed px/s 钳 [10,300]。
- 语义 = `expr:(t*speed)%h` 的快捷方式（内部实现可直接复用表达式结果，或独立画法——实施取简单者）。
- 与现有动画可组合（≤2 个/元素）；`hasAnim` 计入（跑 ticker）。

## 4. text 纵排（附带小改）

- `{"type":"text","dir":"down",...}`：逐字符纵向排列（每字符步进 = size*1.2），`content`/bind 同现有规则（≤20 字符）。
- align 语义：down 时 align 控制横向对齐；x,y 为首字符锚点。
- 双端同源（解析 + 画法）。

## 5. 双端一致性与测试闭环

1. **求值器单测**（新 `HudExprTest.kt`，phone 侧）：语法、护栏、钳制、除零、r01 确定性（同输入恒同输出）、变量 env。
2. **等价验证**：fall vs `expr:(t*speed)%h` 帧采样对比（t=0,0.5,1.0,...）误差 ≤ 0.5px——验证求值器正确性的锚点。
3. **双端同源**：求值器/宏参数/fall/纵排全部落在 HudDsl.kt 与 HudOverlayController 的既有同源面内，`checkProtocolSynced` 自动把关。
4. **宏参数用例**：替换、缺省 `|`、未提供拒绝、嵌套继承、p 非数字拒绝。
5. **护栏一致性单测**（照 HudBindDslTest 既有模式）：expr 限额三查（长度/每元素/全场景）、不适用属性拒绝。

## 6. 文件清单（~9 文件）

| 文件 | 改动 |
|---|---|
| `phone-app/.../glasses/HudDsl.kt` | Expr 求值器 + 宏参数替换 + fall + dir:down + 护栏（同源主战场） |
| `RokidLink/.../HudOverlayController.kt` | 全量同源（解析 + fall 位移 + 纵排画法 + 每帧求值挂钩） |
| `phone-app/.../glasses/HudRenderer.kt` | 求值调用（t 变化重算）+ fall + 纵排画法 |
| `phone-app/.../ai/tools/GlassHudToolProvider.kt` | cheatsheet：三积木用法 + expr 示例 + 限额说明 |
| `docs/hud-dsl.md` | v2 章节（宏参数/表达式/fall/dir 语法与护栏表） |
| `phone-app/src/test/.../HudExprTest.kt` | 新建：求值器 + 等价验证 + 护栏 |
| `phone-app/src/test/.../HudBindDslTest.kt` | 追加宏参数/纵排用例 |

版本：v4.9 未发布，本改造并入 v4.9 开发周期。

## 7. 实施顺序（每步可编译）

1. HudDsl.kt：求值器 + 单测（纯函数，先行验证）→
2. HudDsl.kt：宏参数替换 + fall/dir 解析 + 护栏 →
3. HudRenderer.kt：求值挂钩 + fall/dir 画法 →
4. HudOverlayController.kt：全量同源 →
5. GlassHudToolProvider cheatsheet + docs v2 →
6. 全量测试 + 双端 assembleDebug + checkProtocolSynced →
7. AI 验证脚本（cheatsheet 示例场景走 preview 链路人工验收）
