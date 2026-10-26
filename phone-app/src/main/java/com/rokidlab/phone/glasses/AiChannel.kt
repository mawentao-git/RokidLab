package com.rokidlab.phone.glasses

/**
 * Lab(手机端) ↔ RokidLink(眼镜端) 配置通道协议（双端同源副本，必须同步修改）。
 *
 * 版本化背景：此前 ai_config / key_config / quiz_enabled 三类配置走 CXR 自定义指令，
 * Caps 载荷按「位置顺序」解析且无版本号——任一端将来增删字段都会让另一端错位读取
 * （把本应是 baseUrl 的字段写进 model、把 mode 当 apiKey 等），且无法察觉。
 *
 * 本协议收敛规则：
 *  - 载荷第一字段固定为 cmd（动作名），第二字段固定为 schemaVersion（十进制字符串）；
 *  - 接收端先校验 cmd，再校验版本：未知版本(>当前)直接返回 null 丢弃并告警，
 *    绝不按错位偏移解析写入 prefs；
 *  - v0 旧载荷（历史版本手机端下发，无版本字段）按历史偏移兼容解析。
 *
 * 本文件只含纯 Kotlin 编解码（List<String?>），不依赖 Caps/SDK/Android，
 * 双端各自用薄适配层把 Caps 转 List 后调用，保证解析逻辑可单测且两端一致。
 */
object AiChannel {
    /** 载荷 schema 版本：任何字段结构变化都必须 +1 并同步双端；旧端对未知新版本返回 null */
    const val SCHEMA_VERSION = 1

    // ── 配置类通道 topic（sendCustomCmd / subscribe 通道名）──
    const val TOPIC_AI_CONFIG = "rokidlab_ai_config"
    /** 下行存活探测（手机端 → 眼镜端）：手机端连接期间每 60s 下发空消息，RokidLink 收到即证明
     *  cxr-service 到本 App 的分发路由健康（断线重连后路由可能 stale 丢失，依赖此探测自愈）。 */
    const val TOPIC_PING = "rokidlab_ping"
    const val TOPIC_KEY_CONFIG = "rokidlab_key_config"
    const val TOPIC_KEY_QUIZ = "rokidlab_key_quiz"
    const val TOPIC_TTS_PLAY = "tts_play"
    const val TOPIC_TTS_STOP = "tts_stop"
    /** 推送通道远程踢活（手机端 → 眼镜端）：手机端检测到 RFCOMM 推送通道连续秒断、
     *  监听疑似死亡时下发，眼镜端收到后 stop+start 整个 AsrPushServer 释放蓝牙栈资源重建监听。
     *  走 CXR 自定义频道（系统 cxr-service 托管，独立于本推送通道，推送死了它仍可达）。 */
    const val TOPIC_PUSH_RESTART = "rokidlab_push_restart"

    // ── cmd（载荷[0]，与 topic 呼应）──
    const val CMD_AI_CONFIG = "ai_config"
    const val CMD_KEY_CONFIG = "key_config"
    const val CMD_QUIZ_ENABLED = "quiz_enabled"
    const val CMD_TTS_PLAY = "tts_play"
    const val CMD_TTS_STOP = "tts_stop"

    // ── AI 对话模式取值（契约值，双端一致）──
    const val AI_MODE_OFFICIAL = "official"
    const val AI_MODE_CUSTOM = "custom"

    /** ai_config 载荷：手机端 → 眼镜端，眼镜端持久化后本地直调模型 */
    data class AiConfigFields(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val mode: String,
    )

    /** key_config 载荷：短按/长按 按键动作映射（包名 + Activity） */
    data class KeyConfigFields(
        val shortPkg: String,
        val shortActivity: String,
        val longPkg: String,
        val longActivity: String,
    )

    /** 编码 ai_config：[cmd, version, baseUrl, apiKey, model, mode] */
    fun encodeAiConfig(baseUrl: String, apiKey: String, model: String, mode: String): List<String> =
        listOf(CMD_AI_CONFIG, SCHEMA_VERSION.toString(), baseUrl, apiKey, model, mode)

    /** 编码 key_config：[cmd, version, shortPkg, shortAct, longPkg, longAct] */
    fun encodeKeyConfig(
        shortPkg: String,
        shortActivity: String,
        longPkg: String,
        longActivity: String,
    ): List<String> =
        listOf(CMD_KEY_CONFIG, SCHEMA_VERSION.toString(), shortPkg, shortActivity, longPkg, longActivity)

    /** 编码 quiz_enabled：[cmd, version, "true"/"false"] */
    fun encodeQuizConfig(enabled: Boolean): List<String> =
        listOf(CMD_QUIZ_ENABLED, SCHEMA_VERSION.toString(), enabled.toString())

    /**
     * 解析 ai_config 载荷。
     * @return null = cmd 不符 / 长度不足 / 版本不受支持（接收端必须整体丢弃，禁止部分写入）
     */
    fun decodeAiConfig(fields: List<String?>): AiConfigFields? {
        if (fields.size < 2 || fields[0] != CMD_AI_CONFIG) return null
        return when (val v = fields[1]?.toIntOrNull()) {
            SCHEMA_VERSION -> {
                if (fields.size < 6) return null
                val f = fields.map { it.orEmpty() }
                AiConfigFields(f[2], f[3], f[4], f[5].ifBlank { AI_MODE_CUSTOM })
            }
            // v0 历史载荷：[cmd, baseUrl, apiKey, model, (mode)]，mode 缺省 custom
            null -> {
                if (fields.size < 4) return null
                val f = fields.map { it.orEmpty() }
                AiConfigFields(
                    f[1], f[2], f[3],
                    if (fields.size >= 5 && f[4].isNotBlank()) f[4] else AI_MODE_CUSTOM,
                )
            }
            else -> null // 未来版本：显式拒绝，防止错位解析
        }
    }

    /** 解析 key_config 载荷（v1：[cmd, version, shortPkg, shortAct, longPkg, longAct]；v0 无 version） */
    fun decodeKeyConfig(fields: List<String?>): KeyConfigFields? {
        if (fields.size < 2 || fields[0] != CMD_KEY_CONFIG) return null
        return when (val v = fields[1]?.toIntOrNull()) {
            SCHEMA_VERSION -> {
                if (fields.size < 6) return null
                val f = fields.map { it.orEmpty() }
                KeyConfigFields(f[2], f[3], f[4], f[5])
            }
            null -> {
                if (fields.size < 5) return null
                val f = fields.map { it.orEmpty() }
                KeyConfigFields(f[1], f[2], f[3], f[4])
            }
            else -> null
        }
    }

    /**
     * 编码 tts_play（手机端 → 眼镜端本地 TTS 播报）：[cmd, version, text]
     * 注意：v0 载荷为 [cmd, text]，text 是自由文本。为避免 v1 的 version 字段
     * 与纯数字文本（如播报"1"）歧义，v1 编码恒为 3 字段，
     * 解码规则见 decodeTtsPlay。
     */
    fun encodeTtsPlay(text: String): List<String> =
        listOf(CMD_TTS_PLAY, SCHEMA_VERSION.toString(), text)

    /**
     * 解析 tts_play 载荷，返回待播报文本。
     * 歧义规则：v1 载荷必为 3 字段，故 size==2 一律按 v0 文本解析
     * （历史任意文本均可播报，含纯数字"1"）；size>=3 时才按版本号判定。
     * @return null = cmd 不符 / 长度不足 / 文本字段缺失 / 未知未来版本（整体丢弃）
     */
    fun decodeTtsPlay(fields: List<String?>): String? {
        if (fields.size < 2 || fields[0] != CMD_TTS_PLAY) return null
        return when {
            // v0：[cmd, text]，文本可为任意字符串（含纯数字，如播报"1"）
            fields.size == 2 -> fields[1]
            // v1：[cmd, version, text]，文本必须为字符串
            fields[1]?.toIntOrNull() == SCHEMA_VERSION && fields[2] != null -> fields[2]
            // 文本字段缺失（非字符串）或未知未来版本：显式拒绝，防止错位解析
            else -> null
        }
    }

    /** 解析 quiz_enabled 载荷。
     * @return null = 载荷非法/版本不支持；否则返回开关状态。
     */
    fun decodeQuizConfig(fields: List<String?>): Boolean? {
        if (fields.size < 2 || fields[0] != CMD_QUIZ_ENABLED) return null
        return when (val v = fields[1]?.toIntOrNull()) {
            SCHEMA_VERSION -> if (fields.size >= 3) fields[2] == "true" else null
            null -> fields[1] == "true" // v0：[cmd, enabled]
            else -> null
        }
    }

    // ── 连续对话（多轮免唤醒）开关（手机端 → 眼镜端）──
    // 开启后：眼镜端把 Lab 回复的本地 TTS 播完作为「一轮结束」信号，自动重新开启官方
    // ai_assist 会话（等价于再喊一次唤醒词），用户可直接接着说下一句。
    // 决策点放在眼镜端是刻意的 —— 只有它知道 TTS 的真实播放结束时刻（ITtsListener.onTtsStop）；
    // 手机端下发 tts_play 后拿不到播放进度，按文本长度估算会提前开麦而把播报尾音收进麦克风。
    const val TOPIC_CONTINUE_DIALOG = "rokidlab_chat_continue"
    /** 与 TOPIC_CONTINUE_DIALOG 呼应的载荷动作名（注意与同名的 topic 字符串分开维护） */
    const val CMD_CONTINUE_DIALOG = "continue_dialog"

    /** 编码 continue_dialog：[cmd, version, "true"/"false"] */
    fun encodeContinueDialog(enabled: Boolean): List<String> =
        listOf(CMD_CONTINUE_DIALOG, SCHEMA_VERSION.toString(), enabled.toString())

    /**
     * 解析 continue_dialog 载荷。
     * @return null = cmd 不符 / 长度不足 / 未知未来版本（接收端必须整体丢弃，保持原状态）
     */
    fun decodeContinueDialog(fields: List<String?>): Boolean? {
        if (fields.size < 2 || fields[0] != CMD_CONTINUE_DIALOG) return null
        return when (fields[1]?.toIntOrNull()) {
            SCHEMA_VERSION -> if (fields.size >= 3) fields[2] == "true" else null
            else -> null
        }
    }

    // ── 眼镜 WiFi IP 上行通道（眼镜端 → 手机端）──
    // 眼镜连上 WiFi 后自动上报自身 IP，手机端据此免手动输入，
    // 自动填充到投屏 / 手机镜像 / 文件管理 / ADB 工具共用的单一数据源。
    const val TOPIC_GLASSES_IP = "rokidlab_glasses_ip"
    const val CMD_GLASSES_IP = "glasses_ip"

    /** 编码 glasses_ip 载荷：[cmd, version, ip] */
    fun encodeGlassesIp(ip: String): List<String> =
        listOf(CMD_GLASSES_IP, SCHEMA_VERSION.toString(), ip)

    /**
     * 解析 glasses_ip 载荷。
     * @return null = 载荷非法/版本不支持；否则返回眼镜 WiFi IPv4 地址。
     */
    fun decodeGlassesIp(fields: List<String?>): String? {
        if (fields.size < 2 || fields[0] != CMD_GLASSES_IP) return null
        return when (val v = fields[1]?.toIntOrNull()) {
            SCHEMA_VERSION -> if (fields.size >= 3 && !fields[2].isNullOrBlank()) fields[2] else null
            else -> null
        }
    }

    // ── 停止手机投屏指令（手机端 → 眼镜端）──
    // 手机端按「停止投屏」时下发，眼镜端据此关闭 PhoneMirrorActivity：
    // 该页面设计为 socket 断开后保持前台等待重连（避免重连后画面更新在后台不可见），
    // 故停止投屏必须显式下发关闭指令，否则最后一帧画面会残留在眼镜上。
    // 历史实现靠 stopApp 整包杀 RokidLink，会与前台自动保活冲突（被立刻重新拉起），已废弃。
    // 无载荷：收到即关。
    const val TOPIC_STOP_PHONE_MIRROR = "rokidlab_stop_phone_mirror"
    const val CMD_STOP_PHONE_MIRROR = "stop_phone_mirror"

    // ── 图片下发（手机端 → 眼镜端）──
    // 手机端对话气泡里的图片（show_image 工具展示的图 / 当前播放歌曲封面）同步显示到眼镜端
    // 悬浮图片层。载荷直接带 Base64 JPEG，不依赖眼镜端自身上网去下载 URL
    // （眼镜端网络能力不保证，投屏/ASR 等都是手机端供数据）。
    // 体积：手机端已压到长边 ≤480px / JPEG q80，Base64 后约 50~150KB，远低于 Binder 单事务上限。
    const val TOPIC_SHOW_IMAGE = "rokidlab_show_image"
    const val CMD_SHOW_IMAGE = "show_image"

    /**
     * 编码 show_image：[cmd, version, base64, caption, persist, animated]
     *
     * @param persist true = **常驻不自动隐藏**：只在眼镜端收到 [encodeImageHide] 或助手页离开时收起
     *   （设置页「自定义图」走这条：用户是配好一张常驻图，不是瞥一眼）；
     *   false = 12s 自动隐藏（AI 工具 show_image / 歌曲封面链路，零变化）。
     * @param animated true = [base64Jpeg] 里装的是**动画 WebP 原始字节**（透传零转码，
     *   眼镜端用 AnimatedImageDrawable 播放）；false = 静态 JPEG。第 6 字段旧端不认，
     *   会把动画 WebP 当静态 Bitmap 显示首帧（不崩溃，只是不动）。
     */
    fun encodeShowImage(
        base64Jpeg: String,
        caption: String,
        persist: Boolean = false,
        animated: Boolean = false,
    ): List<String> =
        listOf(
            CMD_SHOW_IMAGE, SCHEMA_VERSION.toString(), base64Jpeg, caption,
            if (persist) "1" else "0", if (animated) "1" else "0",
        )

    /** show_image 载荷（解码结果）。[base64] 内容形态由 [animated] 决定：JPEG 字节 / WebP 动画原始字节。 */
    data class ShowImagePayload(
        val base64: String,
        val caption: String,
        val persist: Boolean,
        val animated: Boolean,
    )

    /**
     * 解析 show_image 载荷。
     * @return [ShowImagePayload]；null = cmd 不符 / 版本不受支持 / 图片数据缺失（接收端整体丢弃）
     */
    fun decodeShowImage(fields: List<String?>): ShowImagePayload? {
        if (fields.size < 3 || fields[0] != CMD_SHOW_IMAGE) return null
        if (fields[1]?.toIntOrNull() != SCHEMA_VERSION) return null
        val b64 = fields[2]?.takeIf { it.isNotBlank() } ?: return null
        return ShowImagePayload(
            base64 = b64,
            caption = fields.getOrNull(3).orEmpty(),
            persist = fields.getOrNull(4) == "1",
            // 第 6 字段缺省 false：旧端 5 字段载荷按静态图处理（向前兼容）
            animated = fields.getOrNull(5) == "1",
        )
    }

    // ── 自定义图（设置页开关 + 眼镜助手页才显示）──
    // 眼镜端探测到官方助手页（SpriteMainActivity）进入前台 → 上行请求；手机端若开关开着
    // 且已存图，就回一张 show_image(persist=true) 常驻显示。开关关掉时手机端主动下发
    // image_hide 让眼镜立刻收起（否则要等下次离开助手页，用户会以为没生效）。
    const val TOPIC_CUSTOM_IMG_REQ = "rokidlab_custom_img_req"
    const val CMD_CUSTOM_IMG_REQ = "custom_img_req"
    const val TOPIC_IMAGE_HIDE = "rokidlab_image_hide"
    const val CMD_IMAGE_HIDE = "image_hide"

    /** 编码 custom_img_req：[cmd, version]（无载荷：要图） */
    fun encodeCustomImgReq(): List<String> = listOf(CMD_CUSTOM_IMG_REQ, SCHEMA_VERSION.toString())

    /** 解析 custom_img_req；true = 合法请求（其余字段整体丢弃） */
    fun decodeCustomImgReq(fields: List<String?>): Boolean =
        fields.size >= 2 && fields[0] == CMD_CUSTOM_IMG_REQ && fields[1]?.toIntOrNull() == SCHEMA_VERSION

    /** 编码 image_hide：[cmd, version]（无载荷：收起当前常驻图） */
    fun encodeImageHide(): List<String> = listOf(CMD_IMAGE_HIDE, SCHEMA_VERSION.toString())

    /** 解析 image_hide；true = 合法指令 */
    fun decodeImageHide(fields: List<String?>): Boolean =
        fields.size >= 2 && fields[0] == CMD_IMAGE_HIDE && fields[1]?.toIntOrNull() == SCHEMA_VERSION

    // ── 生成式 HUD 控制（手机端 → 眼镜端）──
    // 手机端对话里经 glass_hud 工具生成/下发 HUD 场景 DSL，眼镜端解释渲染为悬浮层。
    // action：try = 60s 试戴；apply = 常驻；hide = 收起（preview 只在手机端本地预览，不下发）。
    // dslJson 为场景 DSL（见 docs/hud-dsl.md），hide 时可为空串。
    const val TOPIC_HUD_CTRL = "rokidlab_hud_ctrl"
    const val CMD_HUD_CTRL = "hud_ctrl"

    /** 编码 hud_ctrl：[cmd, version, action, dslJson] */
    fun encodeHudCtrl(action: String, dslJson: String): List<String> =
        listOf(CMD_HUD_CTRL, SCHEMA_VERSION.toString(), action, dslJson)

    /** hud_ctrl 载荷（解码结果） */
    data class HudCtrlPayload(val action: String, val dslJson: String)

    /**
     * 解析 hud_ctrl 载荷。
     * @return [HudCtrlPayload]；null = cmd 不符 / 版本不支持 / action 缺失（整体丢弃）
     */
    fun decodeHudCtrl(fields: List<String?>): HudCtrlPayload? {
        if (fields.size < 3 || fields[0] != CMD_HUD_CTRL) return null
        if (fields[1]?.toIntOrNull() != SCHEMA_VERSION) return null
        val action = fields[2]?.takeIf { it.isNotBlank() } ?: return null
        return HudCtrlPayload(action, fields.getOrNull(3).orEmpty())
    }

    // ── HUD 数据下行（手机端 → 眼镜端）──
    // bind 元素的实时数据源（docs/hud-dsl.md「数据绑定」）。事件驱动小包：只发变化的键，
    // 手机端 HudDataHub 节流合并；键必须在 HudDsl 白名单内（接收端不校验语义，渲染空态兜底）。
    // 能力：随 Cap.HUD（旧眼镜端不订阅本 topic，自然忽略）。
    const val TOPIC_HUD_DATA = "rokidlab_hud_data"
    const val CMD_HUD_DATA = "hud_data"

    /**
     * 编码 hud_data：[cmd, version, count, k1, v1, k2, v2, …]。值一律字符串承载
     * （延续本文件「纯 Kotlin 编解码」的可单测设计）。
     */
    fun encodeHudData(entries: List<Pair<String, String>>): List<String> {
        val out = ArrayList<String>(3 + entries.size * 2)
        out.add(CMD_HUD_DATA)
        out.add(SCHEMA_VERSION.toString())
        out.add(entries.size.toString())
        for ((k, v) in entries) {
            out.add(k)
            out.add(v)
        }
        return out
    }

    /**
     * 解析 hud_data 载荷。
     * @return key→value 映射；null = cmd 不符 / 版本不支持 / 字段数不符（接收端整体丢弃）。
     * 个别空 key 跳过；value 允许空串（语义 = 清掉该键的数据）。
     */
    fun decodeHudData(fields: List<String?>): Map<String, String>? {
        if (fields.size < 3 || fields[0] != CMD_HUD_DATA) return null
        if (fields[1]?.toIntOrNull() != SCHEMA_VERSION) return null
        val n = fields[2]?.toIntOrNull() ?: return null
        if (n < 0 || fields.size < 3 + n * 2) return null
        val out = LinkedHashMap<String, String>()
        var i = 3
        repeat(n) {
            val k = fields[i]
            if (!k.isNullOrBlank()) out[k] = fields[i + 1].orEmpty()
            i += 2
        }
        return out
    }

    // ── HUD 图片资源（手机端 → 眼镜端）──
    // hud_image 工具登记的 HUD 主视觉图：**先推资源、后推引用它的场景 DSL**
    // （DSL 里 image 元素只带 id，不内嵌 base64 —— 16000 字符护栏装不下图片）。
    // 能力：随 Cap.HUD（旧眼镜端不订阅本 topic，自然忽略）。
    const val TOPIC_HUD_IMAGE = "rokidlab_hud_image"
    const val CMD_HUD_IMAGE = "hud_image"

    /** 编码 hud_image：[cmd, version, id, base64Jpeg] */
    fun encodeHudImage(id: String, base64Jpeg: String): List<String> =
        listOf(CMD_HUD_IMAGE, SCHEMA_VERSION.toString(), id, base64Jpeg)

    /** hud_image 载荷（解码结果） */
    data class HudImagePayload(val id: String, val base64: String)

    /**
     * 解析 hud_image 载荷。
     * @return [HudImagePayload]；null = cmd 不符 / 版本不支持 / id 或图片缺失（整体丢弃）
     */
    fun decodeHudImage(fields: List<String?>): HudImagePayload? {
        if (fields.size < 4 || fields[0] != CMD_HUD_IMAGE) return null
        if (fields[1]?.toIntOrNull() != SCHEMA_VERSION) return null
        val id = fields[2]?.takeIf { it.isNotBlank() } ?: return null
        val b64 = fields[3]?.takeIf { it.isNotBlank() } ?: return null
        return HudImagePayload(id, b64)
    }

    // ── 图表全屏查看器（手机端 → 眼镜端，与 show_image 小悬浮层不同：全屏 + 常驻 + 可交互）──
    // 手机端把 ECharts 图表离屏渲染成图片下发，眼镜端进入全屏查看器：
    // 触摸板滑动切换多张图表、单击请求重发、双击退出（按键语义见 KeyRouteCoordinator）。
    // chartId 由手机端生成（毫秒时间戳），作为重发关联键与查看器去重键。
    const val TOPIC_CHART_PUSH = "rokidlab_chart_push"
    const val CMD_CHART_PUSH = "chart_push"

    /** 编码 chart_push：[cmd, version, chartId, base64Jpeg, caption] */
    fun encodeChartPush(chartId: String, base64Jpeg: String, caption: String): List<String> =
        listOf(CMD_CHART_PUSH, SCHEMA_VERSION.toString(), chartId, base64Jpeg, caption)

    /**
     * 解析 chart_push 载荷。
     * @return (chartId, base64Jpeg, caption)；null = cmd 不符 / 版本不支持 / 关键字段缺失（整体丢弃）
     */
    fun decodeChartPush(fields: List<String?>): Triple<String, String, String>? {
        if (fields.size < 4 || fields[0] != CMD_CHART_PUSH) return null
        if (fields[1]?.toIntOrNull() != SCHEMA_VERSION) return null
        val chartId = fields[2]?.takeIf { it.isNotBlank() } ?: return null
        val b64 = fields[3]?.takeIf { it.isNotBlank() } ?: return null
        return Triple(chartId, b64, fields.getOrNull(4).orEmpty())
    }

    // ── 图表重发请求（眼镜端 → 手机端）──
    // 眼镜端查看器里单击（或短按）时上行，手机端从最近推送缓存里按 chartId 重发同一张图
    //（覆盖「解码失败/传输损坏后自助恢复」场景）；手机端缓存未命中则静默忽略。
    const val TOPIC_CHART_RESEND = "rokidlab_chart_resend"
    const val CMD_CHART_RESEND = "chart_resend"

    /** 编码 chart_resend：[cmd, version, chartId] */
    fun encodeChartResend(chartId: String): List<String> =
        listOf(CMD_CHART_RESEND, SCHEMA_VERSION.toString(), chartId)

    /**
     * 解析 chart_resend 载荷。
     * @return chartId；null = cmd 不符 / 版本不支持 / chartId 缺失
     */
    fun decodeChartResend(fields: List<String?>): String? {
        if (fields.size < 3 || fields[0] != CMD_CHART_RESEND) return null
        if (fields[1]?.toIntOrNull() != SCHEMA_VERSION) return null
        return fields[2]?.takeIf { it.isNotBlank() }
    }

    // ── 打开页面指令（手机端 → 眼镜端）──
    // 手机端需要把眼镜上某个 Activity 拉到前台时下发（典型：说「显示歌词」→ 拉起系统音乐页
    // com.rokid.os.sprite.launcher/.page.music.MusicPageActivity）。
    // 眼镜端收到后直接 startActivity(ComponentName(pkg, activity))；目标 Activity 必须 exported=true。
    // 纯信令、无状态：收到即拉起，不做去重（重复拉起由目标 Activity 的 launchMode 兜底）。
    const val TOPIC_OPEN_APP = "rokidlab_open_app"
    const val CMD_OPEN_APP = "open_app"

    /** 编码 open_app：[cmd, version, pkg, activity]（activity 为全限定类名） */
    fun encodeOpenApp(pkg: String, activity: String): List<String> =
        listOf(CMD_OPEN_APP, SCHEMA_VERSION.toString(), pkg, activity)

    /**
     * 解析 open_app 载荷。
     * @return (pkg, activity)；null = cmd 不符 / 版本不受支持 / 包名或 Activity 缺失（接收端整体丢弃）
     */
    fun decodeOpenApp(fields: List<String?>): Pair<String, String>? {
        if (fields.size < 4 || fields[0] != CMD_OPEN_APP) return null
        if (fields[1]?.toIntOrNull() != SCHEMA_VERSION) return null
        val pkg = fields[2]?.takeIf { it.isNotBlank() } ?: return null
        val activity = fields[3]?.takeIf { it.isNotBlank() } ?: return null
        return pkg to activity
    }

    // ── IMU 头动数据通道（v1 查询层，双端同源）──
    // 眼镜端按手机指令启停（默认关）：~20Hz 采样加速度计 + 陀螺仪 + 游戏旋转向量，
    // 500ms 批量打包上行；手机端写入 MotionBuffer 环形缓冲（60s 窗口），
    // 供 get_head_pose / get_motion_history 工具与 v2 规则引擎消费。
    // 样本字段：t(毫秒 epoch) + 加速度 ax/ay/az(m/s²，含重力) + 角速度 gx/gy/gz(rad/s)
    // + 姿态四元数 qw/qx/qy/qz（游戏旋转向量，不含地磁 → 无绝对朝向，只有相对变化）。
    // float 以字符串承载：延续本文件「纯 Kotlin 编解码（List<String?>）」的可单测设计。
    const val TOPIC_IMU_DATA = "rokidlab_imu"
    const val CMD_IMU_DATA = "imu_data"
    /** 采集控制（手机端 → 眼镜端）：载荷 [imu_start | imu_stop, version] */
    const val TOPIC_IMU_CTRL = "rokidlab_imu_ctrl"
    const val CMD_IMU_START = "imu_start"
    const val CMD_IMU_STOP = "imu_stop"

    /** 单个 IMU 采样点（时间戳 + 3 轴加速度 + 3 轴角速度 + 姿态四元数） */
    data class ImuSample(
        val t: Long,
        val ax: Float, val ay: Float, val az: Float,
        val gx: Float, val gy: Float, val gz: Float,
        val qw: Float, val qx: Float, val qy: Float, val qz: Float,
    )

    /** 编码 imu_data：[cmd, version, sampleCount, 每样本 11 字段…] */
    fun encodeImuData(samples: List<ImuSample>): List<String> {
        val out = ArrayList<String>(3 + samples.size * 11)
        out.add(CMD_IMU_DATA)
        out.add(SCHEMA_VERSION.toString())
        out.add(samples.size.toString())
        for (s in samples) {
            out.add(s.t.toString())
            out.add(s.ax.toString()); out.add(s.ay.toString()); out.add(s.az.toString())
            out.add(s.gx.toString()); out.add(s.gy.toString()); out.add(s.gz.toString())
            out.add(s.qw.toString()); out.add(s.qx.toString()); out.add(s.qy.toString()); out.add(s.qz.toString())
        }
        return out
    }

    /**
     * 解析 imu_data 载荷。
     * @return null = cmd 不符 / 版本不受支持 / 字段数不符（接收端整体丢弃）；个别非数值样本跳过
     */
    fun decodeImuData(fields: List<String?>): List<ImuSample>? {
        if (fields.size < 3 || fields[0] != CMD_IMU_DATA) return null
        if (fields[1]?.toIntOrNull() != SCHEMA_VERSION) return null
        val count = fields[2]?.toIntOrNull() ?: return null
        if (count < 0 || fields.size < 3 + count * 11) return null
        val samples = ArrayList<ImuSample>(count)
        var i = 3
        fun fl(idx: Int) = fields[idx]?.toFloatOrNull()
        repeat(count) {
            val t = fields[i]?.toLongOrNull()
            val ax = fl(i + 1); val ay = fl(i + 2); val az = fl(i + 3)
            val gx = fl(i + 4); val gy = fl(i + 5); val gz = fl(i + 6)
            val qw = fl(i + 7); val qx = fl(i + 8); val qy = fl(i + 9); val qz = fl(i + 10)
            if (t != null && ax != null && ay != null && az != null &&
                gx != null && gy != null && gz != null &&
                qw != null && qx != null && qy != null && qz != null) {
                samples.add(ImuSample(t, ax, ay, az, gx, gy, gz, qw, qx, qy, qz))
            }
            i += 11
        }
        return samples
    }

    /** 编码 IMU 采集控制：[imu_start | imu_stop, version] */
    fun encodeImuControl(start: Boolean): List<String> =
        listOf(if (start) CMD_IMU_START else CMD_IMU_STOP, SCHEMA_VERSION.toString())

    /**
     * 解析 IMU 采集控制载荷。
     * @return true=开始采集 false=停止采集；null = cmd 不符 / 版本不受支持（忽略）
     */
    fun decodeImuControl(fields: List<String?>): Boolean? {
        if (fields.size < 2 || fields[1]?.toIntOrNull() != SCHEMA_VERSION) return null
        return when (fields[0]) {
            CMD_IMU_START -> true
            CMD_IMU_STOP -> false
            else -> null
        }
    }

    // ── 过程时间线（手机端 → 眼镜端）：AgentStep 步骤流上眼镜 ──
    // 手机侧把「过程」汇聚点（setAgentTraceSink）收到的每个 AgentStep 转成本帧下行，
    // 眼镜端渲染成底部时间线 overlay（步骤流式流出 / 同 key 覆盖 / 确认态高亮）。
    // 与 Ai 频道单行进度帧（sendGlassesProgress）的分工：**眼镜端带 Cap.PROCESS_TIMELINE
    // 时手机侧只发本帧**，旧眼镜端不收本 topic、继续吃单行帧 ⇒ 无重复显示、无需改旧路径。
    // label/detail 由手机侧算好（眼镜端无 ToolRegistry，不做工具名→中文映射）；
    // state 传 AgentStep.State 枚举名（字符串契约，双端各映射字形，不共享枚举）。
    const val TOPIC_PROCESS_STEP = "rokidlab_process_step"
    const val CMD_PROCESS_STEP = "process_step"

    /** 轮次结束下行：眼镜端据此把时间线标记终态并延迟收起。caps = [cmd, version, failed(0/1)] */
    const val TOPIC_PROCESS_DONE = "rokidlab_process_done"
    const val CMD_PROCESS_DONE = "process_done"

    /**
     * 一条过程步骤帧（眼镜端渲染的最小数据集：覆盖键 + 短名 + 摘要 + 状态名 + 步骤类型名）
     *
     * [kind] = 手机端 `AgentStep.Kind` 枚举名（字符串契约，与 [state] 同套约定：双端各映射
     * 字形，不共享枚举）。眼镜端据此在状态圆点后画**步骤类型图标**（Material Outlined 字形，
     * 与手机过程卡片同一套，字体子集见 [ProcessTimelineController.stepIconCodepoint]）；
     * 空串 = 未知类型 → 不画图标（不猜）。
     */
    data class ProcessStepFrame(
        val key: String,
        val label: String,
        val detail: String,
        val state: String,
        val kind: String = "",
    )

    /**
     * 编码 process_step：[cmd, version, key, label, detail, state, kind?]
     *
     * ⚠️ [kind] 是**尾部可选字段**，故意不升 SCHEMA_VERSION：
     *  - 位置 0–5 完全不变 ⇒ 已装的旧手机端/旧眼镜按原偏移解析照常工作（只是没图标），
     *    而升版会让旧端对**所有** cmd（含 ai_config）返回 null 丢弃 —— 爆炸半径远大于收益；
     *  - 反向兼容：旧手机端下发 6 字段帧时 [kind] 为 null → 兜底空串 → 不画图标。
     * 未来若要改位置 0–5 内的字段，才必须 +1。
     */
    fun encodeProcessStep(
        key: String,
        label: String,
        detail: String,
        state: String,
        kind: String = "",
    ): List<String> =
        listOf(CMD_PROCESS_STEP, SCHEMA_VERSION.toString(), key, label, detail, state, kind)

    /**
     * 解析 process_step 载荷。
     * @return null = cmd 不符 / 版本不受支持 / 关键字段缺失（接收端整体丢弃）
     */
    fun decodeProcessStep(fields: List<String?>): ProcessStepFrame? {
        if (fields.size < 6 || fields[0] != CMD_PROCESS_STEP) return null
        if (fields[1]?.toIntOrNull() != SCHEMA_VERSION) return null
        val key = fields[2]?.takeIf { it.isNotBlank() } ?: return null
        return ProcessStepFrame(
            key = key,
            label = fields[3].orEmpty(),
            detail = fields[4].orEmpty(),
            state = fields[5]?.takeIf { it.isNotBlank() } ?: "RUNNING",
            kind = fields.getOrNull(6)?.takeIf { it.isNotBlank() } ?: "",
        )
    }

    /** 编码 process_done：[cmd, version, "0"/"1"]（"1" = 本轮有失败/被取消） */
    fun encodeProcessDone(failed: Boolean): List<String> =
        listOf(CMD_PROCESS_DONE, SCHEMA_VERSION.toString(), if (failed) "1" else "0")

    /**
     * 解析 process_done 载荷。
     * @return true=本轮失败 false=正常结束；null = 载荷非法/版本不支持（忽略）
     */
    fun decodeProcessDone(fields: List<String?>): Boolean? {
        if (fields.size < 3 || fields[0] != CMD_PROCESS_DONE) return null
        if (fields[1]?.toIntOrNull() != SCHEMA_VERSION) return null
        return when (fields[2]) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }
}
