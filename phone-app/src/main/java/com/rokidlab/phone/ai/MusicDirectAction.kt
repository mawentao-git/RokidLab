package com.rokidlab.phone.ai

/**
 * 媒体直执行判据（2026-10-25，结构性省 token 的第三刀，由 [QuickActionRouter] 收口）。
 *
 * ## 存在的理由
 *
 * 「放一首晴天」这类**一句话媒体动作**，走完整 Agent 循环的代价是**两次全价模型往返**
 * （工具轮 + 收尾轮，每次重发整段 system + 历史 + Schema；实测每轮固定开销 ≈13~14k
 * input token，方舟等无前缀缓存的端点每轮全价）—— 而它实际只需要一次
 * `control_music(play)` 调用。前两刀（[ChatFastPath] 砍闲聊、[ToolIntentRouter] 砍
 * 任务轮 Schema 面）把「发多少 Schema」降下来了，但没动「要不要走模型」这件事。
 * 本类补上第三刀：**高置信媒体意图本地直办，模型零往返**。
 *
 * ## 判据：anchored 白名单（与 ToolIntentRouter 同款纪律：宁可漏放、不可误放）
 *
 *  1. **整句锚定**（`^...$`）：复合句（「放首歌然后查天气」）、否定句（「别放周杰伦的歌」）、
 *     任务形状句一律不命中 —— 漏放的代价只是这轮落回完整循环（维持原状），
 *     误放的代价是用户说「别放了」却开始放歌；
 *  2. **只收带歌名的点播与显式停止**：「放首歌」「播放音乐」这种**没给歌名**的不办
 *     （办了也只能回一句「请告诉我要播放哪首歌」，交给模型反问更自然）；
 *     「暂停/下一首/上一首」不办 —— `control_music` 没有这些动作，硬办只会拿到
 *      schema 拒绝文案；
 *  3. **执行与工具链同一分发口**：路由器把 plan 落成 `control_music` 参数后走
 *     [ToolRegistry.execute]（酷我搜索 + playQueue + 如实结果文案）⇒ 直路与工具链
 *     行为零漂移，「已找到/没找到/起播失败」的措辞两边一致；
 *  4. **回复文案即工具结果原文**：不另造话术 —— 工具怎么回报，用户就看到什么。
 *
 * ## 与调用方的分工
 *
 * [plan] 是纯函数（无 Android 依赖，可单测）；执行由 [QuickActionRouter.execute]
 * 统一做（含工具开关/分组收窄门禁）。调用方（`AiConversationService` 的生成体）负责
 * 其余门禁（本机/只读/图片/答题轮不走）、抢占检查（执行前 `isSuperseded()`）与
 * 回复落链路（onReply/TTS/事件流）。
 */
internal object MusicDirectAction {

    /** 与 ToolIntentRouter/ChatFastPath 一致：长文本多半是任务描述/粘贴内容，绝不直办 */
    private const val MAX_LEN = 64

    /** 歌名/余项长度上限（超长多半是粘贴内容） */
    private const val MAX_SONG_LEN = 24

    /**
     * 没给歌名时的通用令牌（点播余项命中这些 ⇒ 不直办，落回完整循环）：
     * 「放首歌」「来点音乐」「我想听歌」都要模型先反问「想听哪首」。
     */
    private val GENERIC_TOKENS = setOf(
        "音乐", "歌", "歌曲", "一首歌", "首歌", "啥", "什么", "一下", "些",
    )

    /** 直办计划（纯数据，单测锚点） */
    sealed interface Plan {
        /** 按顺序点播（一首或多首） */
        data class Play(val songs: List<String>) : Plan

        /** 停止当前播放 */
        data object Stop : Plan
    }

    // ── 停止：两类，都要求「对象明确」──
    // ① 否定式（别/不要/… 开头才收，裸「放/播放」是点播不是停止）
    private val STOP_NEGATED = Regex(
        "^(别|不要|不用|甭|别再|不要再)(再)?(放|播放|播)(歌|歌曲|音乐|一首歌|一首)?(了|吧|呀)?$",
    )
    // ② 祈使式（必须带对象：停止播放/关掉音乐；裸「停止」可能是停任何东西）
    private val STOP_IMPERATIVE = Regex(
        "^(停止|停下|关掉|关闭)(播放|音乐|歌曲|歌)$",
    )
    private val STOP_EXPLICIT = Regex("^(不听了|不想听了)$")

    // ── 点播：触发词 + 可选量词 + 歌名 + 可选语气尾 ──
    // 前缀（请/帮我/给我）与量词（一首/一个/一下）都可选；歌名必填（见 GENERIC_TOKENS）。
    private val PLAY = Regex(
        "^(请|帮我|给我|帮忙)?(播放|放一首|放首|放个|来一首|来首|来个|来点|我想听|想听|点播|点歌)" +
            "(一首|一个|一首歌|首|个|一下|些)?\\s*(.+?)\\s*(吧|呀|哦|啦|听听|试试)?$",
    )

    /** 句内分隔：多首点歌（与 control_music 描述里的示例分隔符一致） */
    private val SONG_SPLIT = Regex("[、，,]")

    /**
     * 复合句线索：命中 ⇒ 不直办（落回完整循环，让模型拆解多意图）。
     *
     * 为什么不能只靠 anchored 模式：锚定只保证「整句是一个点歌句」，
     * 但「放一首晴天然后查一下天气」在形状上确实是点歌句开头 —— 它的后半截
     * 会被我的多首分隔符（逗号）或歌名一起吞掉。这些连接词是**低误伤成本**的：
     * 没有歌名会含「然后/接着/顺便」。（「播放晴天还有稻香」这种多歌连接词也
     * 一并漏放 —— 交给模型拆更稳。）
     */
    private val COMPOUND_HINTS = listOf("然后", "接着", "顺便", "还有", "再帮", "再查", "再设", "以及", "对了")

    /**
     * 这条消息是否直办 + 怎么办。null = 不直办（调用方落回完整 Agent 循环）。
     *
     * 纯函数：只做文本判定，不碰 Context/网络 —— 单测直接钉全部边界。
     */
    fun plan(text: String): Plan? {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_LEN) return null
        // 多行：粘贴内容，不直办
        if (t.contains('\n') || t.contains('\r')) return null
        // 复合句线索（然后/接着/顺便…）：整句不只是点歌/停止，不直办
        if (COMPOUND_HINTS.any { t.contains(it) }) return null
        if (STOP_NEGATED.matches(t) || STOP_IMPERATIVE.matches(t) || STOP_EXPLICIT.matches(t)) {
            return Plan.Stop
        }
        val m = PLAY.matchEntire(t) ?: return null
        // 组 1=礼貌前缀 2=触发词 3=量词 4=歌名余项 —— 余项在组 4（组 3 是「一首/个」这类量词）
        val rest = m.groupValues[4].trim()
        if (rest.isEmpty()) return null
        // 句读标点 ⇒ 复合句/任务句，不直办（顿号逗号是多首分隔，放行）
        if (rest.any { it in "。！？；;…—～~" }) return null
        val songs = rest.split(SONG_SPLIT)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it !in GENERIC_TOKENS && it.length <= MAX_SONG_LEN }
        if (songs.isEmpty()) return null
        return Plan.Play(songs)
    }

    // ═══════════ 执行 ═══════════
    // 执行统一走 QuickActionRouter.execute → ToolRegistry.execute("control_music")：
    // 与工具链同一分发口（行为零漂移），本类只保留**判据**（plan），不再自带第二执行体。
}
