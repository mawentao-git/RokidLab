package com.rokidlab.phone.ai.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.rokid.cxr.Caps
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.GlassToolConfirmChannel
import com.rokidlab.phone.ai.NetGuard
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.ToolRisk
import com.rokidlab.phone.glasses.AiChannel
import com.rokidlab.phone.glasses.GlassesHandshake
import com.rokidlab.phone.glasses.HudDataHub
import com.rokidlab.phone.glasses.HudDsl
import com.rokidlab.phone.glasses.HudThemeStore
import com.rokidlab.phone.glasses.LinkProtocol
import com.rokidlab.phone.store.ChatStateHolder
import org.json.JSONObject

/**
 * 生成式 HUD 工具（`glass_hud`）：对话里设计眼镜端悬浮 HUD。
 *
 * 三段式体验：
 *  - `preview`：解析 DSL → 离屏渲染 480x640 预览图 → 以图片气泡进对话流（ChatImageCache
 *    原生支持 file://，ChatBubble 可点击放大）；不下发眼镜。
 *  - `try` / `apply`：同一条 DSL 下发眼镜端（TOPIC_HUD_CTRL）：try = 60s 试戴自动收起，
 *    apply = 常驻直到 hide；下发同时也在对话里放一张预览图留档。
 *  - `hide`：收起眼镜端 HUD。
 *
 * 实现要点（继承 show_image 的教训）：
 *  - 工具跑在 worker 线程（ToolGateway.call），渲染/落盘在此线程做；ChatStateHolder 是
 *    Compose 快照状态，写它必须切主线程。
 *  - 眼镜端结果必须如实回报（未连接/不支持/失败三态分开说），不许「说了没反应」。
 *  - DSL 解析失败把 [HudDsl] 抛出的 message 原文回给模型 —— 模型靠它自我修正重试。
 */
internal object GlassHudToolProvider : ToolProvider {
    private const val TAG = "GlassHudToolProvider"

    /**
     * 预览卡在对话流里的 caption（2026-10-25 提为常量）：关闭预览时要认出它——
     * 合并进 AI 回复气泡的预览图，caption 只是占位文案，图没了它也没有存在意义
     * （见 [ChatStateHolder.dismissHudPreview]，两边必须同一份判据）。
     */
    internal const val HUD_PREVIEW_CAPTION = "HUD 预览"

    override val toolNames = setOf("glass_hud", "hud_display", "hud_image")

    /**
     * 面向模型的 DSL 速查（规范全文见 docs/hud-dsl.md）。要短：模型每轮都背着它。
     * 2026-10-20 新增主题动作 save/list/use（HUD 主题管理，AIUI 同款）。
     * 2026-10-21 v2：宏实例参数 + 属性表达式 expr: + fall 位移 + text dir 纵排。
     * 2026-10-24 反同构改版：用户实测「不管什么风格，出来的 HUD 都差不多」⇒
     *   根因在提示词层：注意力 95% 在语法、零视觉锚点（10-21 定稿「不给示例防趋同」的
     *   执行方式反而把产出拉向先验收均值）、bg 默认值被照抄、无反同构机制。
     *   解法（docs/hud-dsl.md 设计原则 4：风格策略只动 cheatsheet，不动协议）：
     *   创作契约前置 + 正交构图示例（只示范构图差异、明示禁止照抄）+ 默认套路黑名单。
     *   语法部分原样保留（模型写错会被解析器拒，不敢动）；原末尾「构图方法/风格」两行
     *   并入契约①③④，物理纪律并入契约⑤。
     * 2026-10-25 三连修（用户实测反馈）：
     *   ① 「让做很多动画还是做不了/不会组合」⇒ 契约⑤的克制被模型当成硬顶，新增【动画编排】段：
     *      预算明示（48 实例/128 元素/16000 字符）+ 组合模式（每元素≤2）+ 编排三法
     *      （分组错峰/类型混搭/宏实例化）+ 明确「用户要求丰富时契约让位」；
     *   ② 「不主动找参考」⇒ 图片节增加主动参考工作流（search_web→fetch_webpage→hud_image）；
     *   ③ 「预览/保存跟设计有出入」⇒ 工具无状态（scene 每次重传、模型重新生成即漂移）⇒
     *      lastScene 记忆 + scene 省略自动沿用（见 [lastSceneJson]/[rememberScene]）。
     *   internal：HudCheatsheetTest 提取语法骨架验证可解析（改坏示例即测试红）。
     * 2026-10-25 skill 化改造（用户定调：DSL 做框架，设计规范做成可升级 skill，代码与规范各改各的）：
     *   创作契约/正交示例/动画编排/参考工作流四段整体搬进内置技能 hud-design
     *   （assets/skills/hud-design/SKILL.md，经 load_skill 按需加载，gitee 独立仓库可升级）；
     *   本速查只留与解析器同版本的最小语法集（版本强耦合，不能进独立仓库——上游 skill
     *   与 App 内解析器版本错配会写出被拒的 DSL），description 增加「创作前必须先
     *   load_skill(hud-design)」命令式纪律（模型跳过即退化，纪律是唯一防线）。
     *   契约四要素/示例可解析断言迁至 SKILL.md 文件（HudCheatsheetTest 同文件）。
     * 2026-10-25 P0-1 bindOn 数值阈值比较（能力扩展路线图第一项）：三段 "数值键:op:值"
     *   （</<=/>/>=/==/!=，键限 percent/number 白名单）；解析期拆 key/op/值，渲染端走
     *   HudBind.matchOn 纯函数（双端同源）；错误消息定向提示（数值键写两段→提示三段写法）。
     * 2026-10-25 P1-2 数据键扩展（路线图第二项）：+ music(enum) / gps_speed/gps_bearing/steps
     *   (number) 四键；比例映射(sweep/count/bar/progress)除数从硬编码 100 改为 El.bindMax
     *   （percent 恒 100、number 须显式 bind.max 2..999999，双端同步）；采集器按 HUD 活动
     *   窗口/绑定需求启停（HudDataHub.setCollectorDemand/touchHudActivity/hudHidden）。
     */
    internal const val DSL_CHEATSHEET = """HUD 场景 DSL（480x640 单绿屏，绿黑两色体系）。创作规范（契约/构图/动画编排/参考图）见技能 hud-design。

【语法（写错会被解析器拒绝并回传原因，修正后原样重试即可）】
顶层：{"v":1,"bg":{"scanline":0.08,"grid":48,"gridOpacity":0.1},"macros":{"宏名":[元素...]},"scene":[元素...]}。
zone 区域锚点（x,y 相对区域中心，缺省=区域中心）：top-center(图盒子224x133)/A(表盘左上)/B(表盘右上)/C(底部横带)/full(全屏,默认)。⚠️ 坐标空间＝中心原点：x,y 是元素定位点相对区域中心的偏移（full 画布中心=0,0；+x 向右，+y 向下；偏移可正可负，-150=中心左 150px），不是左上角绝对值——居中元素就写 x:0,y:0，不要换算成 240,320。定位规则所有元素一致：circle/dot/arc/polygon/ping=中心点；rect/image/brackets/ruler=框中心（极少数要左上角定位才加 "anchor":"topleft"）；line=(x,y)→(x2,y2) 两端；path 的 d 是以元素 x,y 为原点的局部坐标；text=基线锚点。
元素 type 与专属字段：circle(r 或 rx/ry 椭圆,斜视角轨道) | arc(r,start,sweep) | line(x2,y2) | rect(w,h,radius圆角,anchor) | dot(r) | polygon(r,sides) | path(d：以元素 x,y 为原点的局部坐标,支持MLHVQCZ) | use(use=宏名,dx,dy,rot=整组旋转度,mirror=整组水平镜像,绕use定位点,不支持嵌套变换) | brackets(w,h,len,inset：以 x,y 为中心的四角角标框，w/h 缺省=全屏，len=臂长px，inset=框内缩px) | ruler(w,step,major：x,y=刻度行中心) | ping(r,interval,扩散脉冲) | text(content,size(8-48px,默认24),align(left/center)；x,y=文本基线锚点，content或bind必有其一；content 支持 \n 显式换行(≤4行,每行≤20字符,中文可用,行距=size*1.2),dir:"down" 逐字符纵排(不支持\n)) | image(id,w,h,anchor,mask:"circle"圆形蒙版直径=min(w,h)随呼吸缩放,blend:"screen"位图与下层墨线Screen叠印黑区透线亮区加亮,引用hud_image登记的图)。
图片主视觉（两步式）：用户给了图（或要搜索/生成图当 HUD 中心视觉）→ 先调 hud_image 传图片地址拿到 id（最多 2 张，新登记顶掉最旧）→ 再在 scene 里引用 {"type":"image","id":"<返回的id>","x":0,"y":0,"w","h"}：x,y=框中心相对画面中心的偏移，**居中就写 x:0,y:0**（full 区域中心就是 0,0，不是 240,320）；w/h 是显示框，图按 contain 居中适配；支持 spin/scale/glow/pulse/fade 与 use 组变换，不支持 orbit/dashFlow；眼镜端图未送达时画虚线占位框。构图：image 放视觉重心，矢量元素做周围装饰与数据绑定。图片地址按优先级：①用户在对话里发过的图 —— 该轮用户消息会附带给它一个 file:// 本地路径，直接传路径（手机端自己读文件，别让用户重传 base64）；②http/https URL；③base64（仅前两者都不可得时）。主动找参考的工作流见技能 hud-design 第 4 节。
数据绑定 bind：元素可加 {"bind":{"key":"数据键","as":"映射"}}，把实时数据映射到图形；as 可省略（arc→sweep、dot→count、text→text、其他→enum）。key 白名单：battery_glass/battery_phone(0-100，官方表盘已有电量，默认别用) | link(connected/disconnected) | ai_state(idle/busy) | music(playing/paused) | timer(剩余秒数) | calendar_next(距下一日程秒数) | unread(待读消息数) | gps_speed(km/h) | gps_bearing(度0-359,显示八方位N/NE/E/SE/S/SW/W/NW) | steps(今日步数) | tool_result(hud_display 工具写入的短文本)。as=sweep(仅arc,值/满量程×360) / count(仅dot,光点数=值/满量程×容量) / bar(仅rect,比例填充条：填充宽=w×值/满量程,clip裁剪,填充墨色 barFill:0-3默认3,轨道用元素自身stroke/fillInk画) / progress(仅line,从(x,y)按值比例描绘到(x2,y2),静态轨道另画一条line) / enum(任意图形,on=connected/busy/playing=正常亮度+呼吸,off=0.3倍亮度无呼吸) / text(text内容替换：电量"87%"、ON/OFF、IDLE/BUSY、PLAY/PAUSE、MM:SS、日程"45m"/"1h05"、消息数、速度整数、八方位、步数、tool_result 原文)。满量程 max：比例映射(sweep/count/bar/progress)的100%基准——percent键恒100(显式写max拒绝)；number键量程各异必须显式 "max":2..999999(如 {"key":"gps_speed","as":"bar","max":120}=120km/h满量程)；as=text/enum 给max拒绝。无数据空态：text显示"--"、sweep=0、count=0、enum置暗；bind弧/点阵建议配一条stroke 0的静态轨道。护栏：bind元素<=8。条件显隐：元素还可加 "bindOn":"link:connected"（enum键值匹配才显示，否则隐藏；反状态如 link:disconnected / ai_state:idle / music:paused 也合法）；数值键支持阈值 "bindOn":"timer:<300"（写法 key:比较符值，比较符 </<=/>/>=/==/!=；键限 battery_glass/battery_phone/timer/calendar_next/unread/gps_speed/gps_bearing/steps——如 battery_phone:<=20 低电量告警、unread:>0 有新消息才亮、timer:<300 倒计时紧迫态、gps_speed:>60 超速才亮；无数据时隐藏）。
公共字段：zone,x,y,opacity(0-1),width(线宽px),stroke(墨色0暗-3亮,默认3),fillInk(-1不填充),dash([on,off]),anim,bindOn。
anim：单对象或数组(最多2个组合)：{"type":"spin|orbit|pulse|fade|scale|glow|dashFlow|fall","period":秒,"phase":0-360错峰,"reverse":bool}；spin自转 orbit公转(orbitR:px) pulse/fade透明度呼吸 scale半径尺寸±amp呼吸(amp:0-0.9默认0.3,仅circle/dot/rect/polygon,rect绕自身中心) glow亮度±amp呼吸(amp:默认0.5) dashFlow蚂蚁线流动(speed:px/s默认30,需dash:[on,off],仅描边图形) fall匀速下落循环位移(speed:px/s默认90,出底回顶,数字雨/飘落类,任一元素可用)。
v2 参数化与表达式（一套积木组合出效果，不再逐个加 type）：①宏参数：宏定义内字段值可写 "p.名字"(use 未提供即拒绝)或 "p.名字|缺省数字"，use 时 {"use":"名","p":{"名字":数字}}(≤6个)逐实例注入——同构不同值只定义一次；②属性表达式：几何字段(x/y/x2/y2/r/rx/ry/w/h/opacity/size/start/sweep)值可写 "expr:算式" 随帧求值（x/y/x2/y2 的求值结果=相对区域中心的偏移，与静态值同语义），变量 t(秒) i(元素序号) w/h(元素zone宽高) sw/sh(480/640)，函数 min/max/floor/abs/sin/cos(平滑波形,漂浮/波浪/心跳,均 StrictMath 跨设备逐位一致)/pow(a,b)(幂,加速落体/抛物线/缓动,如 pow(t%10,2)；NaN/Inf取0)/r01(n)(确定性伪随机0-1,100ms粒度,同帧同输出),运算 + - * / % 括号；expr 体内可引用宏参数 p.名字，如 "expr:(t*p.v0)%h"；③text 加 "dir":"down" 逐字符纵排。护栏：expr每条≤48字符、每元素≤3个、全场景≤48个、字面量≤6位、除零模零取0。
护栏：展开后元素<=128、动画实例<=48(组合每项算1,每元素<=2)、宏嵌套<=2、JSON<=16000字符、image元素<=2，超限会被拒绝。
语法骨架（仅示范 JSON 形状，非设计参考）：{"v":1,"bg":{"scanline":0.08,"grid":48,"gridOpacity":0.1},"macros":{"名":[元素...]},"scene":[元素...]}"""

    override fun tools(): List<ToolEntry> = listOf(
        ToolEntry(
            name = "glass_hud",
            group = ToolRegistry.DOMAIN_DISPLAY,
            displayNameRes = R.string.ai_tool_glass_hud_name,
            descriptionRes = R.string.ai_tool_glass_hud_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            statusText = "正在生成 HUD…",
            // ⚠️ 与 show_image 同理：眼镜端 HUD 悬浮层是单槽位（新场景顶掉旧场景），
            //   且往对话流插图片消息 ⇒ 必须标 sideEffect，避免并行执行错位。
            sideEffect = true,
            schema = toolSchema(
                name = "glass_hud",
                description = "为眼镜设计并显示生成式 HUD 悬浮层（叠加在表盘之上的图形装饰层，非应用）。当用户说「给眼镜加个 HUD」「HUD 主题/设计 HUD 主题/换个 HUD 主题」「贾维斯界面」「魔法阵风格」「在右上角加个装饰」「关掉 HUD」时调用。" +
                    "⚠️ 排他：HUD 主题/悬浮层一律用本工具，不要用 aiui-dev 技能或 save_code_file/AIUI 应用做 HUD（那是网页小程序路线，不是 HUD）。" +
                    "⚠️ 创作规范必读：动手写 scene 前**必须先调用 load_skill(name=\"hud-design\")**——创作契约（立意三问/默认套路黑名单/构图范式轮换/多样性铁律）、动画编排手法、参考图工作流都在该技能里。跳过它直接凭下面的语法骨架生成，会产出千篇一律的「默认科幻脸」。" +
                    "流程：先 action=preview 给用户看手机端预览（实时动画卡），按反馈修改，满意后 action=try 让用户在眼镜上试戴 60 秒，用户认可后 action=apply 常驻。" +
                    "⚠️ scene 一致性（最高优先级纪律，2026-10-25 实测失守后加强）：对同一个设计做 try/apply/save 时**禁止重新生成 scene**——不传 scene 即自动沿用上一次 preview/try/apply 的 scene。用户说「预览/试戴/上屏/保存/就这个/可以」等动作词而没有要求修改设计时，一律不传 scene；只有用户明确说「改/换/调整/重新设计」时才传新 scene。用户要求修改时也应基于上一次的 scene 改字段，不要推倒重来。" +
                    "主题管理：用户说「保存这个主题/存为主题」用 action=save（带 name）；「列出我的主题」用 action=list；「用 xx 主题/切到 xx」用 action=use（带 name，直接常驻下发）；「改一下 xx 主题」用 action=load（带 name，返回主题 DSL 原文）→ 修改字段 → action=save 同名覆盖。" +
                    DSL_CHEATSHEET,
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "action" to mapOf(
                            "type" to "string",
                            "enum" to listOf("preview", "try", "apply", "hide", "save", "list", "use", "load"),
                            "description" to "preview=只在手机对话里出实时预览卡；try=推眼镜试戴60秒自动收起；apply=常驻显示直到hide；hide=收起眼镜端HUD；save=把scene存为命名主题（同名覆盖）；list=列出已保存主题；use=应用命名主题（常驻下发，自动恢复主题图片）；load=返回主题 DSL 原文（用于微调后 save 同名覆盖）",
                        ),
                        "scene" to mapOf(
                            "type" to "string",
                            "description" to "HUD 场景 DSL JSON 字符串。**仅在用户明确要求「设计/修改/更换」时才传**；用户只说预览/试戴/保存等动作词时不要传（不传即自动沿用上一次设计，预览/保存与设计零出入）。action=preview/try/apply/save 时要设计的场景；action=hide/list/use/load 时省略。",
                        ),
                        "name" to mapOf(
                            "type" to "string",
                            "description" to "主题名。action=save/use/load 时必填。",
                        ),
                    ),
                    "required" to listOf("action"),
                ),
            ),
        ),
        ToolEntry(
            name = "hud_display",
            group = ToolRegistry.DOMAIN_DISPLAY,
            displayNameRes = R.string.ai_tool_hud_display_name,
            descriptionRes = R.string.ai_tool_hud_display_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            statusText = "正在更新 HUD…",
            // ⚠️ 与 glass_hud 同理：写 HudDataHub + 下行 TOPIC_HUD_DATA，必须保序 ⇒ sideEffect
            sideEffect = true,
            schema = toolSchema(
                name = "hud_display",
                description = "把一条短文本（≤20 字符）实时写到眼镜 HUD 上：写入 tool_result 数据键，" +
                    "已常驻场景里绑定 {\"bind\":{\"key\":\"tool_result\",\"as\":\"text\"}} 的 text 元素会立即更新。" +
                    "仅当 apply 过含该绑定的场景才可见——没有就先 glass_hud 设计一个。" +
                    "适合状态词（READY/DONE）、工具结果摘要、关键词提醒等短内容；text 传空串 = 清除。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "text" to mapOf(
                            "type" to "string",
                            "description" to "要上屏的短文本，≤20 字符（绿屏建议 ASCII 短词）；空串=清除",
                        ),
                    ),
                    "required" to listOf("text"),
                ),
            ),
        ),
        ToolEntry(
            name = "hud_image",
            group = ToolRegistry.DOMAIN_DISPLAY,
            displayNameRes = R.string.ai_tool_hud_image_name,
            descriptionRes = R.string.ai_tool_hud_image_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            statusText = "正在登记 HUD 图片…",
            // ⚠️ 图片资源必须先于引用它的 glass_hud 场景到达眼镜端（先推资源后推场景），
            //   必须与 glass_hud 保序 ⇒ sideEffect
            sideEffect = true,
            schema = toolSchema(
                name = "hud_image",
                description = "为 HUD 场景登记一张主视觉图片（两步式第一步）：传入图片地址（http/https URL、**本地文件路径** file:// 或绝对路径、或 base64），" +
                    "返回资源 id 与尺寸；然后在 glass_hud 的 scene 里用 {\"type\":\"image\",\"id\":\"<返回的id>\",\"x\",\"y\",\"w\",\"h\"} 引用。" +
                    "适合「HUD 中间用这张图」的需求：登记后把 image 元素放构图重心，矢量元素做配套装饰。" +
                    "用户在对话里发过图时，该轮用户消息会附带给它一个 file:// 本地路径 —— 直接传那个路径（手机端自己读文件解码），不要改画矢量图、也不要找外部 URL。" +
                    "最多登记 2 张，新登记会顶掉最旧的（返回的 id 随之过期）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "image" to mapOf(
                            "type" to "string",
                            "description" to "图片地址：http/https URL、本地文件路径（file:// 或绝对路径，如用户在对话里发过的图）或 base64 数据（可带 data:image/...;base64, 前缀）",
                        ),
                    ),
                    "required" to listOf("image"),
                ),
            ),
        ),
    )

    override fun execute(context: Context, name: String, args: JSONObject): String {
        return when (name) {
            "glass_hud" -> executeGlassHud(context, args)
            "hud_display" -> pushToolResult(args)
            "hud_image" -> executeHudImage(context, args)
            else -> throw IllegalArgumentException("未知工具: $name")
        }
    }

    private fun executeGlassHud(context: Context, args: JSONObject): String {
        val action = args.optString("action").trim()
        return when (action) {
            "preview" -> preview(context, args)
            "try" -> push(context, args, action)
            "apply" -> push(context, args, action)
            "hide" -> hide(context)
            "save" -> saveTheme(context, args)
            "list" -> listThemes(context)
            "use" -> useTheme(context, args)
            "load" -> loadTheme(context, args)
            else -> "action 必须是 preview/try/apply/hide/save/list/use/load"
        }
    }

    // ── preview ──────────────────────────────────────────────────

    private fun preview(context: Context, args: JSONObject): String {
        val (_, json) = resolveAndParseScene(context, args) ?: return lastParseError ?: "缺 scene 字段"
        rememberScene(context, json)
        postPreviewToChat(json)
        return "HUD 实时预览卡已生成（对话中的动画预览，480x640 真实比例，点击可放大；与眼镜渲染同源）。" +
            "请等用户反馈：不满意就修改 scene 重新 preview；满意后用 action=try 推到眼镜试戴。"
    }

    // ── try / apply ──────────────────────────────────────────────

    private fun push(context: Context, args: JSONObject, action: String): String {
        val prev = lastSceneJson(context)
        val (_, json) = resolveAndParseScene(context, args) ?: return lastParseError ?: "缺 scene 字段"
        // 机制兜底（2026-10-25 实测「说预览却重新设计了一版」）：try/apply 显式重传 scene
        // 且与上一次设计结构差异大 ⇒ 末尾提醒模型判断用户是否真想改设计
        val drift = driftWarnIfExplicit(args, prev, json)
        rememberScene(context, json)
        // P1-2：场景绑定了 gps/steps/music ⇒ 采集器常开（预览卡与眼镜同源取数）
        HudDataHub.setCollectorDemand(json)
        // 对话里同步放一张实时预览卡（用户在手机上也能看到推了什么）
        postPreviewToChat(json)
        return when (val r = sendToGlasses(context, action, json)) {
            GlassesPush.OK -> {
                // try/apply 都会顶掉眼镜上常驻的主题（try 60s 后自动收起，原主题也不会回来）
                // → 清 activeId，主题管理页的「停止」按钮随之退回「应用」
                HudThemeStore.setActiveId(context, null)
                if (action == "try") "已推到眼镜试戴（60 秒后自动收起）。满意就回复确认，我会用 apply 常驻。"
                else "HUD 已常驻显示在眼镜上。"
            }
            GlassesPush.NOT_CONNECTED ->
                "手机端预览卡已生成；眼镜未连接，未能下发。请连接眼镜后重试。"
            GlassesPush.APP_NOT_READY ->
                "手机端预览卡已生成；眼镜端 RokidLink 未就绪（未运行或未握手），未能下发。"
            GlassesPush.UNSUPPORTED ->
                "手机端预览卡已生成；眼镜端版本过旧不支持 HUD（缺 Cap.HUD），请先更新眼镜端。"
            GlassesPush.FAILED ->
                "手机端预览卡已生成；下发眼镜端失败，请重试。"
        } + drift.orEmpty()
    }

    /** 仅当本次**显式传了** scene 才做漂移检测（缺省沿用是正确行为，不算漂移） */
    private fun driftWarnIfExplicit(args: JSONObject, prev: String?, next: String): String? {
        if (args.optString("scene").trim().isEmpty()) return null
        return sceneDriftWarn(prev, next)
    }

    // ── 主题管理（save / list / use）────────────────────────────

    private fun saveTheme(context: Context, args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isEmpty()) return "缺 name 字段（主题名）"
        // scene 缺省 = 沿用上一次设计（2026-10-25：用户实测「保存跟设计有出入」的根因是
        // 模型每次重新生成 scene；不传即零出入沿用）
        val dsl = pickSceneJson(args, lastSceneJson(context))
        if (dsl.isNullOrBlank()) return "缺 scene 字段（要保存的场景 DSL）。想保存上一次的设计就直接不传 scene"
        val drift = driftWarnIfExplicit(args, lastSceneJson(context), dsl)
        return try {
            // 场景引用的登记图随主题落盘（重启/换机后应用不丢图；缩略图渲染也带图）
            val refIds = runCatching { HudDsl.parse(dsl).elements.mapNotNull { it.imageId } }
                .getOrDefault(emptyList()).distinct()
            val images = synchronized(imageLock) {
                refIds.mapNotNull { id -> registeredImages[id]?.let { id to it } }.toMap()
            }
            val t = HudThemeStore.save(context, name, dsl, images)
            rememberScene(context, dsl)
            val note = if (images.isNotEmpty()) "（已随主题保存 ${images.size} 张图片）" else ""
            "主题「${t.name}」已保存$note（${HudThemeStore.list(context).size} 个主题）。用户可在 设置→HUD 主题管理 查看/应用/分享。" +
                drift.orEmpty()
        } catch (e: IllegalArgumentException) {
            "保存失败：${e.message}"
        }
    }

    private fun listThemes(context: Context): String {
        val themes = HudThemeStore.list(context)
        if (themes.isEmpty()) return "主题库为空。用户满意某个 HUD 设计时用 action=save 存为主题。"
        return "已保存 ${themes.size} 个主题：\n" + themes.joinToString("\n") {
            "- ${it.name}（${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it.updatedAt))}）"
        } + "\n用 action=use + name 应用某主题。"
    }

    private fun useTheme(context: Context, args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isEmpty()) return "缺 name 字段（主题名）。先用 action=list 查看已有主题。"
        val theme = HudThemeStore.find(context, name)
            ?: return "没有叫「$name」的主题。用 action=list 查看已有主题，或先 action=save 保存。"
        // 先图后场景：登记表回灌 + 眼镜端重推（主题 image 引用继续有效，避免只出占位框）
        restoreThemeImages(context, theme.id)
        // 应用主题即成为「当前设计」：后续不传 scene 的 preview/try/apply/save 沿用它
        rememberScene(context, theme.dsl)
        // P1-2：主题绑定 gps/steps/music ⇒ 采集器常开
        HudDataHub.setCollectorDemand(theme.dsl)
        postPreviewToChat(theme.dsl)
        return when (sendToGlasses(context, "apply", theme.dsl)) {
            GlassesPush.OK -> {
                HudThemeStore.setActiveId(context, theme.id)
                "主题「${theme.name}」已常驻显示在眼镜上。"
            }
            GlassesPush.NOT_CONNECTED -> "眼镜未连接；预览卡已放到对话里，请连接眼镜后重试。"
            GlassesPush.APP_NOT_READY -> "眼镜端 RokidLink 未就绪，未能下发。"
            GlassesPush.UNSUPPORTED -> "眼镜端版本过旧不支持 HUD（缺 Cap.HUD）。"
            GlassesPush.FAILED -> "下发失败，请重试。"
        }
    }

    /** 读取主题 DSL 原文给模型（对话微调已存主题：load → 改字段 → save 同名覆盖） */
    private fun loadTheme(context: Context, args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isEmpty()) return "缺 name 字段（主题名）。先用 action=list 查看已有主题。"
        val theme = HudThemeStore.find(context, name)
            ?: return "没有叫「$name」的主题。用 action=list 查看已有主题，或先 action=save 保存。"
        return "主题「${theme.name}」的 scene DSL 原文（修改后用 action=save + 同名 name 覆盖保存，或 action=use 直接应用）：\n${theme.dsl}"
    }

    // ── hide ─────────────────────────────────────────────────────

    /** 下发 hide 指令（工具 hide action 与 HUD 主题管理页共用）；true = 已送达 */
    internal fun hideHud(context: Context): Boolean {
        val ok = sendToGlasses(context, "hide", "") == GlassesPush.OK
        if (ok) {
            HudThemeStore.setActiveId(context, null)
            // P1-2：HUD 收起 ⇒ 停采集器并清数据（眼镜上已无绑定元素）
            HudDataHub.hudHidden()
        }
        return ok
    }

    private fun hide(context: Context): String =
        when (sendToGlasses(context, "hide", "")) {
            GlassesPush.OK -> {
                HudDataHub.hudHidden()
                "已收起眼镜端 HUD。"
            }
            else -> "已发送收起指令，但眼镜可能未连接；若 HUD 仍在请稍后重试。"
        }

    // ── hud_display：工具结果上屏（tool_result 键）────────────────

    /**
     * 把一条短文本写入 HudDataHub 的 `tool_result` 键（≤20 字符护栏在 hub 侧再截一刀）。
     * 数据经 TOPIC_HUD_DATA 事件下行，已 apply 场景中绑定该键的 text 元素即时更新；
     * 手机预览卡取 HudDataHub.current() 同步可见。眼镜未连接时数据暂存 hub（只影响预览），
     * 如实回报，不假装已上屏。
     */
    private fun pushToolResult(args: JSONObject): String {
        val text = args.optString("text").trim()
        if (text.length > HudDsl.MAX_TEXT_CHARS) {
            return "text 过长（${text.length} > ${HudDsl.MAX_TEXT_CHARS} 字符），请精简后重试"
        }
        // P1-2：hud_display 是一次 HUD 活动 ⇒ 刷新采集窗口（不改绑定需求）
        HudDataHub.touchHudActivity()
        HudDataHub.updateToolResult(text)
        if (text.isEmpty()) return "已清除 HUD 上的工具结果文本。"
        val live = GlassesHandshake.supports(LinkProtocol.Cap.HUD) == true &&
            GlassToolConfirmChannel.global.liveSession() != null
        return if (live) "已上屏：HUD 上绑定 tool_result 的文本已实时更新。"
        else "已写入 HUD 数据（手机预览实时可见）；眼镜未连接，重连后自动补发上屏。"
    }

    /**
     * 设置页「HUD 主题管理」的「应用」按钮复用：把主题 DSL 以 apply 常驻下发。
     * 返回 null = 成功；非 null = 用户可读的失败原因（toast 用）。
     * [themeId] 非空 = 本次 apply 来自主题管理（成功后记为 active，管理页「应用」切「停止」）。
     */
    internal fun applyDsl(context: Context, dslJson: String, themeId: String? = null): String? {
        // P1-2：按场景绑定键声明采集需求（主题管理页应用 + HudThemeScheduler 定时/情景联动共用入口）
        HudDataHub.setCollectorDemand(dslJson)
        // 主题路径先回灌图片（登记表 + 眼镜端缓存），主题 DSL 的 image 引用继续有效
        if (themeId != null) restoreThemeImages(context, themeId)
        return when (sendToGlasses(context, "apply", dslJson)) {
            GlassesPush.OK -> {
                HudThemeStore.setActiveId(context, themeId)
                null
            }
            GlassesPush.NOT_CONNECTED -> "眼镜未连接"
            GlassesPush.APP_NOT_READY -> "眼镜端 RokidLink 未就绪"
            GlassesPush.UNSUPPORTED -> "眼镜端版本过旧，不支持 HUD"
            GlassesPush.FAILED -> "下发失败，请重试"
        }
    }

    // ── hud_image：HUD 主视觉图片登记 ────────────────────────────

    /** 登记图最长边（240px 足够 HUD 主视觉：240×240×4×2 ≈ 460KB 双端内存上限） */
    private const val MAX_IMAGE_SIDE = 240

    /** 已登记图片表（id→位图）：HudDsl.MAX_IMAGES 槽 LRU，新登记顶掉最旧（旧 id 过期） */
    private val imageLock = Any()
    private val registeredImages = LinkedHashMap<String, Bitmap>()
    private var imageSeq = 0

    /**
     * 眼镜端图片缓存镜像（2 槽 LRU）：主题应用时据此**跳过眼镜端还持有的图**，
     * 场景不再排在图片重传后面 —— 2026-10-25「主题刷新慢」的修复主体。
     * 正确性契约（为何敢跳过）见 [GlassesImageCacheMirror] 类注释。
     */
    private val glassesImageMirror = GlassesImageCacheMirror()

    /** glass_hud parse 校验用：当前登记的 id 集（未登记的 image 元素会被拒） */
    private fun registeredImageIds(): Set<String> = synchronized(imageLock) { registeredImages.keys.toSet() }

    /** 预览卡渲染 image 元素时取登记位图（主线程读 / worker 线程写，锁保护） */
    internal fun imageFor(id: String): Bitmap? = synchronized(imageLock) { registeredImages[id] }

    private fun executeHudImage(context: Context, args: JSONObject): String {
        val raw = args.optString("image").trim()
        if (raw.isEmpty()) return "缺 image 字段（图片 URL、本地路径或 base64）"
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            val bmp = downloadBitmap(raw)
                ?: return "图片下载失败：${raw.take(160)}（检查 URL 可访问性，或改传本地路径/base64）"
            return registerHudImage(context, bmp)
        }
        // 本地路径（2026-10-21 新增）：用户在对话里发过的图，其 file:// 路径随该轮用户消息
        // 一起给模型（见 ChatScreen.sendWithAttachments）—— 手机端直接读文件，不用 base64。
        if (raw.startsWith("file://") || raw.startsWith("/")) {
            val bmp = decodeLocalBitmap(raw.removePrefix("file://"))
                ?: return "本地图片读取失败：${raw.take(160)}（确认路径存在、为 JPEG/PNG 且不过大）"
            return registerHudImage(context, bmp)
        }
        val b64 = raw.substringAfter("base64,", raw)
        val bytes = runCatching { Base64.decode(b64, Base64.NO_WRAP or Base64.NO_CLOSE) }
            .getOrElse { return "image 不是合法 base64：${it.message?.take(120)}" }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return "图片解码失败：数据不是受支持的图片格式（JPEG/PNG）"
        return registerHudImage(context, bmp)
    }

    /**
     * 本地图片解码（[executeHudImage] 的本地路径分支，2026-10-21 新增）。
     *
     * 先量边界再采样：相册原图动辄几千 px，直接全量 decode 会 OOM —— 与
     * [com.rokidlab.phone.store.ChatAttachments] 读图同思路，`inSampleSize` 降到
     * 长边 ≤2048 再全解码（后续 [registerHudImage] 还会统一缩到 240px）。
     * >32MB 直接拒（防异常大文件把 IO 线程拖死）。
     */
    private fun decodeLocalBitmap(path: String): Bitmap? = runCatching {
        val file = java.io.File(path)
        if (!file.isFile || file.length() > 32L * 1024 * 1024) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** URL 下载（8s 超时，>8MB 拒绝防 OOM）；任何失败返回 null，由调用方报错 */
    private fun downloadBitmap(url: String): Bitmap? = runCatching {
        // 走 NetGuard（2026-10-25）：cheatsheet 开始引导模型把网页里的图片 URL 传进来，
        // 下载不能再裸连 —— 否则页面里一个指向内网/眼镜 WebServer 的 img 就成了
        // 审批闸门的旁路（NetGuard 逐跳校验重定向 + 拒绝内网/回环）。
        val conn = NetGuard.open(url, "GET", mapOf("User-Agent" to HUD_IMAGE_UA), 8000, 8000)
        try {
            if (conn.contentLength > 8 * 1024 * 1024) {
                null
            } else {
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }.getOrNull()

    /** hud_image 下载用的 UA（与 WebTools 同款桌面 Chrome，避免站点按默认 UA 拒载） */
    private const val HUD_IMAGE_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    /** 缩放→JPEG→Base64→登记→推眼镜端（先资源后场景）；返回面向模型的结果文本 */
    private fun registerHudImage(context: Context, src: Bitmap): String {
        // 长边缩放 → JPEG(80) → Base64 NO_WRAP（换行会破坏载荷，与 ChartGlassPush 同因）
        val scaled = if (maxOf(src.width, src.height) <= MAX_IMAGE_SIDE) {
            src
        } else {
            val ratio = MAX_IMAGE_SIDE.toFloat() / maxOf(src.width, src.height)
            Bitmap.createScaledBitmap(
                src,
                (src.width * ratio).toInt().coerceAtLeast(1),
                (src.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        }
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        val jpegBytes = out.toByteArray()
        val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val id: String
        synchronized(imageLock) {
            imageSeq += 1
            id = "img$imageSeq"
            registeredImages[id] = scaled
            while (registeredImages.size > HudDsl.MAX_IMAGES) {
                registeredImages.entries.iterator().let { it.next(); it.remove() }
            }
        }
        val push = sendImageToGlasses(context, id, b64)
        // 推送成功即记入眼镜端缓存镜像 —— 之后引用同一张图的主题应用可跳过重传
        if (push == GlassesPush.OK) glassesImageMirror.record(id, jpegBytes.contentHashCode())
        val note = when (push) {
            GlassesPush.OK -> "图片已同步下发眼镜端。"
            GlassesPush.NOT_CONNECTED -> "眼镜未连接，图片暂未下发（连接后重新调 hud_image 一次即可）。"
            GlassesPush.UNSUPPORTED -> "眼镜端版本过旧不支持 HUD 图片，手机端登记不受影响（预览正常）。"
            else -> "图片下发眼镜端失败，手机端登记不受影响；预览正常。"
        }
        return "已登记 HUD 图片：id=$id，显示尺寸 ${scaled.width}x${scaled.height}。$note\n" +
            "下一步：在 glass_hud 的 scene 里引用 {\"type\":\"image\",\"id\":\"$id\",\"x\":0,\"y\":0,\"w\":..,\"h\":..}" +
            "（坐标是中心原点：x,y=框中心相对画面中心的偏移，居中就写 0,0（full 画布中心），不是 240,320；w/h 为显示框，图按 contain 居中适配；支持 spin/scale/glow/pulse/fade，不支持 orbit/dashFlow）。"
    }

    /** 下发图片资源（先资源后场景；不做 ensureGlassesLinkRunning 兜底——未连接时登记照常成立） */
    private fun sendImageToGlasses(context: Context, id: String, b64: String): GlassesPush {
        if (GlassesHandshake.supports(LinkProtocol.Cap.HUD) == false) return GlassesPush.UNSUPPORTED
        val session = GlassToolConfirmChannel.global.liveSession() ?: return GlassesPush.NOT_CONNECTED
        val link = session.cxrLink ?: return GlassesPush.NOT_CONNECTED
        return runCatching {
            val caps = Caps()
            caps.write(AiChannel.CMD_HUD_IMAGE)
            caps.write(AiChannel.SCHEMA_VERSION.toString())
            caps.write(id)
            caps.write(b64)
            val r = session.rawSendCustomCmd(link, AiChannel.TOPIC_HUD_IMAGE, caps)
            Log.i(TAG, "push hud_image id=$id b64Len=${b64.length} -> $r")
            if (r == 0) GlassesPush.OK else GlassesPush.FAILED
        }.onFailure { Log.w(TAG, "push hud_image failed: ${it.message}") }
            .getOrDefault(GlassesPush.FAILED)
    }

    /**
     * 主题应用路径的图片回灌（use/applyDsl 调用，先于场景下发）：
     *  1. 登记表回灌 —— 主题落盘图按**原 id** 解码回 2 槽 LRU（聊天预览卡渲染用）；
     *  2. 眼镜端补推 —— 落盘 JPEG 原字节零重压直推（TOPIC_HUD_IMAGE，先资源后场景）。
     *
     * ## 2026-10-25「主题刷新慢」修复：按镜像去重
     * 原来这里**每次应用都全量重推所有主题图**（每张 13–27KB Base64 走 CXR 串行链），
     * 场景被排在重传之后 —— 重复应用同一主题/主题间来回切时，绝大多数图眼镜端缓存里
     * 还在（2 槽 LRU），纯属浪费。现在经 [GlassesImageCacheMirror] 判定：
     *  - 眼镜端仍持有同内容图 → **跳过重传**，场景立即下发（提速的主体）；
     *  - 镜像没有 / 推送失败 / 眼镜端世代变化（重连/重启）→ 照常重推（失败方向只有
     *    「多推」，占位框风险为零）。
     * 推送失败不阻塞场景下发：眼镜端占位框兜底，重新应用一次主题即可恢复。
     */
    private fun restoreThemeImages(context: Context, themeId: String) {
        val files = HudThemeStore.themeImageFiles(context, themeId)
        if (files.isEmpty()) return
        synchronized(imageLock) {
            files.forEach { (id, bytes) ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { registeredImages[id] = it }
            }
            while (registeredImages.size > HudDsl.MAX_IMAGES) {
                registeredImages.entries.iterator().let { it.next(); it.remove() }
            }
        }
        // 眼镜端重连/重启会重新 HELLO ⇒ 世代变化即清镜像，本批次全量重推一次
        glassesImageMirror.onGeneration(GlassesHandshake.lastHelloAtMs)
        files.forEach { (id, bytes) ->
            val hash = bytes.contentHashCode()
            if (glassesImageMirror.has(id, hash)) {
                Log.i(TAG, "restore theme image $id skipped (glasses cache hit)")
                return@forEach
            }
            val r = sendImageToGlasses(context, id, Base64.encodeToString(bytes, Base64.NO_WRAP))
            if (r == GlassesPush.OK) glassesImageMirror.record(id, hash)
            else {
                glassesImageMirror.evict(id)
                Log.w(TAG, "restore theme image $id -> $r")
            }
        }
    }

    // ── 内部 ────────────────────────────────────────────────────

    /** 解析错误暂存（execute 单线程串行，无并发问题），把 [HudDsl] 的报错原文回给模型 */
    private var lastParseError: String? = null

    // ── lastScene：scene 省略时自动沿用上一次设计（2026-10-25）────────────

    /**
     * 上一次成功设计/预览/下发/保存/应用的 scene 原文（进程内缓存；文件见 [LAST_SCENE_FILE]）。
     *
     * 存在的理由：glass_hud 是无状态工具——scene 每次调用都要重传，而模型**重新生成**的
     * scene 与设计稿必然有出入（用户实测「设计好以后，预览/保存跟设计都会有出入」）。
     * 记住上一次的 scene 后，try/apply/save 不传 scene 即零出入沿用。
     */
    private var lastSceneDsl: String? = null

    /** lastScene 的落盘位置（app 重启后「保存」仍能沿用到上一次设计） */
    private const val LAST_SCENE_FILE = "hud_last_scene.json"

    /** 取上一次设计的 scene（内存优先，回退文件）；没有则 null */
    private fun lastSceneJson(context: Context): String? {
        lastSceneDsl?.let { return it }
        val fromDisk = runCatching {
            java.io.File(context.applicationContext.filesDir, LAST_SCENE_FILE)
                .takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
        }.getOrNull()
        lastSceneDsl = fromDisk
        return fromDisk
    }

    /** 记住本次 scene（内存 + 落盘）；写盘失败不拦主流程（沿用是增强，不是必需） */
    private fun rememberScene(context: Context, dsl: String) {
        if (dsl.isBlank()) return
        lastSceneDsl = dsl
        runCatching {
            java.io.File(context.applicationContext.filesDir, LAST_SCENE_FILE).writeText(dsl)
        }
    }

    /**
     * 本次调用生效的 scene 原文：参数传了用传的（允许修改设计），没传沿用上一次。
     *
     * 纯函数 —— 单测直接钉行为（含「两者都没有」的 null 语义）。
     */
    internal fun pickSceneJson(args: JSONObject, last: String?): String? {
        val fromArgs = args.optString("scene").trim()
        if (fromArgs.isNotEmpty()) return fromArgs
        val lastTrimmed = last?.trim()
        return if (lastTrimmed.isNullOrEmpty()) null else lastTrimmed
    }

    /**
     * scene 漂移告警（2026-10-25 用户实测「说预览它却重新设计了一版」的机制兜底）。
     *
     * 根因：模型把「在眼镜预览」当成新创作请求，顺手重新生成了 scene（且被契约④
     * 多样性铁律推着换构图，正好换进黑名单套路）。提示词纪律是唯一防线但会失守，
     * 这里加第二道：try/apply/save 且本次**显式传了** scene 时，若与上一次设计
     * 结构差异大，在返回文案末尾提醒模型判断「用户是不是只想要动作、没想改设计」。
     *
     * 判据是**结构指纹**（元素 type 多重集合 + bg 键集合）的 Jaccard 相似度 <0.5，
     * 不是数值差异——坐标/透明度微调是正常迭代，不算漂移。解析失败一律 null
     * （宽容：宁可漏报也不误报，误报会干扰真正的修改请求）。
     */
    internal fun sceneDriftWarn(prevJson: String?, nextJson: String?): String? {
        val prev = prevJson?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val next = nextJson?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (prev == next) return null
        val a = runCatching { HudDsl.parse(prev) }.getOrNull() ?: return null
        val b = runCatching { HudDsl.parse(next) }.getOrNull() ?: return null
        val sim = jaccard(sceneFingerprint(a), sceneFingerprint(b))
        return if (sim < DRIFT_SIMILARITY_FLOOR) DRIFT_WARN else null
    }

    /** 结构指纹：元素 type 计数 + bg 启用特性（值不计——微调不算漂移） */
    private fun sceneFingerprint(s: HudDsl.Scene): Map<String, Int> {
        val fp = HashMap<String, Int>()
        s.elements.forEach { e ->
            fp[e.type] = (fp[e.type] ?: 0) + 1
        }
        if (s.scanline > 0f) fp["bg:scanline"] = 1
        if (s.grid > 0f) fp["bg:grid"] = 1
        if (s.gridOpacity > 0f) fp["bg:gridOpacity"] = 1
        return fp
    }

    private fun jaccard(a: Map<String, Int>, b: Map<String, Int>): Double {
        val keys = a.keys + b.keys
        if (keys.isEmpty()) return 1.0
        var inter = 0
        var union = 0
        keys.forEach { k ->
            inter += minOf(a[k] ?: 0, b[k] ?: 0)
            union += maxOf(a[k] ?: 0, b[k] ?: 0)
        }
        return if (union == 0) 1.0 else inter.toDouble() / union
    }

    private const val DRIFT_SIMILARITY_FLOOR = 0.5

    private const val DRIFT_WARN =
        "⚠️ scene 一致性提醒：本次 scene 与上一次设计的结构差异较大。" +
            "如果用户本轮只是想预览/试戴/保存同一个设计、并没有要求修改或更换设计，" +
            "请不传 scene 用同一个 action 重新调用（会自动沿用上一次设计，保证前后一致）；" +
            "如果用户确实要求了修改/更换设计，忽略本提示。"

    /** 解析并采纳本次 scene：参数优先、缺省沿用上一次；返回 (Scene, 生效的原文)，失败 null（错误已暂存） */
    private fun resolveAndParseScene(context: Context, args: JSONObject): Pair<HudDsl.Scene, String>? {
        val json = pickSceneJson(args, lastSceneJson(context))
        if (json.isNullOrEmpty()) {
            lastParseError = "缺 scene 字段（HUD 场景 DSL）。想沿用上一次的设计就直接不传 scene"
            return null
        }
        val scene = parseSceneJson(json) ?: return null
        return scene to json
    }

    /** 解析 scene 原文（错误暂存 [lastParseError]，把 [HudDsl] 的报错原文回给模型自我修正） */
    private fun parseSceneJson(json: String): HudDsl.Scene? {
        lastParseError = null
        return try {
            HudDsl.parse(json, registeredImages = registeredImageIds())
        } catch (e: IllegalArgumentException) {
            lastParseError = "场景 DSL 不合法：${e.message}。请修正后原样重试（不要改 action）。"
            null
        }
    }

    /**
     * 预览卡进对话流（2026-10-20 方案 A）：不再落 PNG 静态图，imageUrl 走
     * `hud://<URL编码的 DSL>` 协议 —— [ChatImageCard] 分流到 [HudPreviewRoute] 实时
     * 渲染动画卡（与眼镜同源绘制）。复用图片消息的持久化/挂气泡/放大链路，模型零改动。
     */
    private fun postPreviewToChat(dslJson: String) {
        val url = "hud://" + java.net.URLEncoder.encode(dslJson, "UTF-8")
        Handler(Looper.getMainLooper()).post {
            ChatStateHolder.addImage(
                isUser = false,
                imageUrl = url,
                caption = HUD_PREVIEW_CAPTION,
            )
        }
    }

    private enum class GlassesPush { OK, NOT_CONNECTED, APP_NOT_READY, UNSUPPORTED, FAILED }

    /**
     * 下发 hud_ctrl（照 DisplayToolProvider.pushImageToGlasses 的链路与重试语义）：
     * 未握手先 ensureGlassesLinkRunning 拉一次再重试 —— RokidLink 进程不在 = 指令石沉大海。
     */
    private fun sendToGlasses(context: Context, action: String, dslJson: String): GlassesPush {
        var capSupport = GlassesHandshake.supports(LinkProtocol.Cap.HUD)
        if (capSupport == false) {
            Log.i(TAG, "glasses does not support hud_ctrl (old build)")
            return GlassesPush.UNSUPPORTED
        }
        var session = GlassToolConfirmChannel.global.liveSession()
        var link = session?.cxrLink
        if (session == null || link == null || capSupport == null) {
            if (ToolRegistry.ensureGlassesLinkRunning(context)) {
                runCatching { Thread.sleep(1500) }
                capSupport = GlassesHandshake.supports(LinkProtocol.Cap.HUD)
                session = GlassToolConfirmChannel.global.liveSession()
                link = session?.cxrLink
            }
        }
        if (session == null || link == null) {
            Log.i(TAG, "glasses not connected, skip hud push (capSupport=$capSupport)")
            return GlassesPush.NOT_CONNECTED
        }
        val target = link
        val targetSession = session
        return runCatching {
            val caps = Caps()
            caps.write(AiChannel.CMD_HUD_CTRL)
            caps.write(AiChannel.SCHEMA_VERSION.toString())
            caps.write(action)
            caps.write(dslJson)
            val r = targetSession.rawSendCustomCmd(target, AiChannel.TOPIC_HUD_CTRL, caps)
            Log.i(TAG, "push hud_ctrl action=$action jsonLen=${dslJson.length} -> $r")
            when {
                r == 0 -> GlassesPush.OK
                capSupport == null -> GlassesPush.APP_NOT_READY
                else -> GlassesPush.FAILED
            }
        }.onFailure { Log.w(TAG, "push hud_ctrl failed: ${it.message}") }
            .getOrDefault(GlassesPush.FAILED)
    }
}
