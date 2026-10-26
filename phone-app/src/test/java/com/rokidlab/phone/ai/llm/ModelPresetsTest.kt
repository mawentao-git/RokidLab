package com.rokidlab.phone.ai.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `llm` 接缝的能力解析回归测试（[ModelPresets] / [ModelCapabilities] / [ModelRoute]）。
 *
 * 为什么这些纯函数值得专门锁：它们的结论**直接决定用户能不能用一个功能** ——
 * `supportsImage = false` 会把图像理解开关置灰，`supportsTools = false` 意味着不发工具声明。
 * 而它们全是"按模型名匹配"的启发式，最容易在加一条规则时把另一条挤掉
 * （规则顺序变了、新加的前缀把视觉家族吃掉了），而这类错误**编译通过、界面正常、单测照绿**，
 * 只有用户发现"这个模型怎么突然不能看图了"才会暴露。
 *
 * 本测试锁两类东西：
 *  1. **已知事实**（DeepSeek 官方 chat/reasoner 都不吃图、flash 系（V4.1 Flash 起）原生多模态；视觉家族要认出来）；
 *  2. **失败方向的不对称性** —— 未知必须是"未知"而不是"不支持"，
 *     因为把未知当不支持会**平白剥夺**用户已有的能力（详见 [ModelPresets.VISION_MARKERS] 注释）。
 */
class ModelPresetsTest {

    // ═══════════ A. DeepSeek：已知事实 + 关思考字段规则 ═══════════

    @Test
    fun `A1 deepseek-chat 确认不支持图像但支持工具与关思考字段`() {
        val capsOpt = ModelPresets.lookup("deepseek-chat")
        assertNotNull("deepseek 必须在内置表里（在线默认模型，结论影响最大）", capsOpt)
        val caps = capsOpt!!
        // 官方 API 的 chat 系列没有视觉能力：以前用户开「图像理解」+ deepseek-chat 必然白跑一轮
        assertEquals(false, caps.supportsImage)
        assertTrue(caps.imageKnownUnsupported)
        assertTrue(caps.supportsTools)
        assertTrue(caps.supportsThinkingDisable)
        assertEquals(131072, caps.contextWindow)
        assertEquals(CapabilitySource.PRESET, caps.source)
    }

    @Test
    fun `A2 推理专用模型既不吃图也不支持工具调用`() {
        listOf("deepseek-reasoner", "deepseek-r1", "deepseek-r1-distill-qwen").forEach { name ->
            val capsOpt = ModelPresets.lookup(name)
            assertNotNull("$name 应命中推理专用规则", capsOpt)
            val caps = capsOpt!!
            // 给推理模型发 tools 会被服务端直接拒绝：这条规则省掉的是整轮 400
            assertFalse("$name 不应声明支持工具调用", caps.supportsTools)
            assertEquals(false, caps.supportsImage)
            assertFalse("$name 不支持关思考（发了会被拒）", caps.supportsThinkingDisable)
        }
    }

    @Test
    fun `A3 关思考字段规则与迁移前的实现逐字一致`() {
        // 历史坑（2026-09-14 真机）：曾只认名字里带 v4 / v3.2 的模型，deepseek-flash 不匹配，
        // 用户关了「长思考」但请求没带关思考字段 → reasoning 吃光输出预算 → 连续空轮
        assertTrue("deepseek-flash 必须能关思考", ModelPresets.supportsThinkingDisabled("deepseek-flash"))
        assertTrue(ModelPresets.supportsThinkingDisabled("DeepSeek-V4"))
        // 推理专用模型设计上不支持关闭 → 宁可少关也不能把请求打挂
        assertFalse(ModelPresets.supportsThinkingDisabled("deepseek-reasoner"))
        assertFalse(ModelPresets.supportsThinkingDisabled("deepseek-r1"))
        assertFalse(ModelPresets.supportsThinkingDisabled("r1-0528"))
        // 非 DeepSeek/方舟系端点收到该字段会 400 → 一律不发
        assertFalse(ModelPresets.supportsThinkingDisabled("gpt-4o"))
        assertFalse(ModelPresets.supportsThinkingDisabled("qwen2.5:7b"))
        // ★ 2026-09-29 扩展：火山方舟 doubao seed/thinking 系是默认开思考的深度思考模型，
        //   与 DeepSeek 共用 thinking:{"type":"disabled"} 语法 —— 不发的话思考开关两个方向
        //   都是安慰剂（关不掉、每轮 reasoning 烧上万输出 token）
        assertTrue(ModelPresets.supportsThinkingDisabled("doubao-seed-1.6-250615"))
        assertTrue(ModelPresets.supportsThinkingDisabled("doubao-seed-2-1-pro-260915"))
        assertTrue(ModelPresets.supportsThinkingDisabled("doubao-1.5-thinking-pro"))
        assertTrue(ModelPresets.supportsThinkingDisabled("Doubao-Seed-1.6-Flash"))
        // 老 doubao（pro/lite 纯文本模型）无思考能力 → 字段语义未知时不发，避免整请求 400
        assertFalse(ModelPresets.supportsThinkingDisabled("doubao-pro-32k"))
        assertFalse(ModelPresets.supportsThinkingDisabled("doubao-lite-4k"))
        // 内置能力表与请求路径同口径（设置页展示用）
        assertTrue(ModelPresets.lookup("doubao-seed-1.6-250615")!!.supportsThinkingDisable)
        assertFalse(ModelPresets.lookup("doubao-pro-32k")!!.supportsThinkingDisable)
    }

    @Test
    fun `A4 deepseek-flash 系原生多模态且 1M 窗口，旧别名一并收编`() {
        // ★ 2026-10-22 回归：V4.1 Flash（deepseek-flash，2026-09-10 上线）起官方 API
        //   原生多模态。修订前通用档一刀切 image=false，flash 用户的「图像理解」开关
        //   被置灰 —— 本来能用的能力被平白剥夺，正是 VISION_MARKERS 注释里
        //   "写错 false 代价不对称"要防的那类错误。
        //   本用例锁三类事实：①flash 系吃图 + 1M 窗口；②旧别名仍路由到 V4.1 Flash；
        //   ③无视觉的其余 deepseek 档（chat / v4-pro）与 reasoner 不因新规则漂移。
        listOf(
            "deepseek-flash", "DeepSeek-V4.1-Flash-1017",
            "deepseek-v4-flash", "deepseek-v4-flash-vision-exp",
        ).forEach { name ->
            val caps = ModelPresets.lookup(name)
            assertNotNull("$name 必须命中 flash 专属规则（排在通用档前）", caps)
            assertEquals("$name 是 V4.1 Flash，原生多模态", true, caps!!.supportsImage)
            assertTrue("$name 支持工具调用", caps.supportsTools)
            assertTrue("$name 可关思考（非推理专用）", caps.supportsThinkingDisable)
            assertEquals("$name 官方口径 1M 上下文", 1_000_000, caps.contextWindow)
        }
        // flash 是混合思考模型：任务轮自动开思考的准入不能漏（漏了 agent 轮白跑）
        assertTrue(ModelPresets.prefersThinking("deepseek-flash"))
        // 不误伤：官方定价页口径 v4-pro / chat 无视觉，reasoner 仍不支持工具调用
        assertEquals(false, ModelPresets.lookup("deepseek-v4-pro")!!.supportsImage)
        assertEquals(false, ModelPresets.lookup("deepseek-chat")!!.supportsImage)
        assertFalse(ModelPresets.lookup("deepseek-reasoner")!!.supportsTools)
    }

    // ═══════════ B. 视觉家族识别 ═══════════

    @Test
    fun `B1 视觉家族按命名识别，含 Ollama 无中划线写法`() {
        // `qwen2.5-vl` 是中划线写法；Ollama 官方模型名是 `qwen2.5vl:7b`（无中划线，靠 : 分 tag）。
        // 只收 `-vl` 会漏掉本地最常见的那一个 —— 而漏收的后果是开关被置灰，用户平白丢能力。
        listOf(
            "qwen2.5-vl", "qwen2.5vl:7b", "qwen2-vl", "llama3.2-vision", "gemma3:4b",
            "llava:13b", "minicpm-v", "gpt-4-vision-preview", "pixtral-12b",
        ).forEach { name ->
            assertEquals("$name 应被识别为支持图像", true, ModelPresets.lookup(name)?.supportsImage)
        }
    }

    @Test
    fun `B2 同名家族的非视觉版本不能误判为支持图像`() {
        listOf("qwen2.5:7b", "llama3.1:8b", "gemma2:9b", "mistral:7b", "glm-4").forEach { name ->
            assertEquals("$name 是纯文本模型", false, ModelPresets.lookup(name)?.supportsImage)
        }
    }

    @Test
    fun `B3 云端视觉模型与上下文窗口抽样`() {
        ModelPresets.lookup("gpt-4o")!!.let {
            assertEquals(true, it.supportsImage)
            assertEquals(128000, it.contextWindow)
        }
        ModelPresets.lookup("claude-3-5-sonnet")!!.let {
            assertEquals(true, it.supportsImage)
            assertEquals(200000, it.contextWindow)
        }
        // moonshot-v1 系列是纯文本，kimi 新模型才带视觉 —— 两者不能混为一谈
        assertEquals(false, ModelPresets.lookup("moonshot-v1-128k")!!.supportsImage)
        assertEquals(true, ModelPresets.lookup("kimi-latest")!!.supportsImage)
    }

    @Test
    fun `B4 doubao 家族 seed 系全线多模态 vision 系不被通用规则截走`() {
        // ★ 2026-10-17 回归：DOUBAO-SEED-2.1-LITE「检测不通过」——探测误判（限流/审核报错
        //   带"image"字样被读成不支持）+ 内置表按未知处理。方舟官方口径 Seed 系列全线
        //   多模态（支持图片、视频、文本理解）→ 直接内置 true；写错的代价是发图失败
        //   回退 OCR，与未知尝试同价（见 ModelPresets VISION_MARKERS 的不对称论证）。
        listOf(
            "doubao-seed-2.1-lite", "DOUBAO-SEED-2.1-LITE", "doubao-seed-1.6-250615",
            "doubao-seed-2-1-pro-260915", "doubao-1.5-thinking-pro",
        ).forEach { name ->
            assertEquals("$name 是 Seed 多模态系，应内置支持图像", true, ModelPresets.lookup(name)?.supportsImage)
        }
        // doubao-vision 系名字里带 vision，必须排在通用 doubao 规则前
        //（视觉家族优先是本表自家约定，"doubao-vision-pro-32k"曾被通用规则截走按未知处理）
        listOf("doubao-1.5-vision-lite", "doubao-vision-pro-32k", "doubao-vision-lite-32k").forEach { name ->
            assertEquals("$name 应被识别为支持图像", true, ModelPresets.lookup(name)?.supportsImage)
        }
        // 老 pro/lite 纯文本系维持未知（不写死结论）+ 不下发关思考字段，不因上面改动漂移
        val legacy = ModelPresets.lookup("doubao-pro-32k")!!
        assertNull(legacy.supportsImage)
        assertFalse(legacy.supportsThinkingDisable)
        // seed 系的关思考字段口径保持不变
        assertTrue(ModelPresets.lookup("doubao-seed-2.1-lite")!!.supportsThinkingDisable)
    }

    // ═══════════ C. 失败方向：未知 ≠ 不支持 ═══════════


    @Test
    fun `C1 未收录的模型必须是图像能力未知，而不是不支持`() {
        assertNull("未收录模型不应硬编一个结论", ModelPresets.lookup("acme-llm-v7"))
        // 这是本接缝最关键的一条不变式：未知时允许尝试（失败回退 OCR），
        // 若在这里返回 supportsImage=false，未收录模型的图像理解开关会被无故置灰
        assertNull("保守默认的图像能力必须是未知", ModelPresets.ASSUMED.supportsImage)
        assertFalse(ModelPresets.ASSUMED.imageKnownUnsupported)
        assertFalse(ModelPresets.ASSUMED.imageKnown)
        // 其余能力保守默认"支持"：不支持时问题会立刻以报错暴露，而少发工具声明是静默的能力缺失
        assertTrue(ModelPresets.ASSUMED.supportsTools)
        assertTrue(ModelPresets.ASSUMED.supportsStreaming)
    }

    @Test
    fun `C2 本地端点未收录时给出 Ollama 默认窗口而不是未知`() {
        val capsOpt = ModelPresets.lookup("acme-llm-v7", isLocal = true)
        assertNotNull("本地端点必须有兜底（Ollama 的默认 num_ctx 我们有把握）", capsOpt)
        val caps = capsOpt!!
        assertEquals(4096, caps.contextWindow)
        assertEquals(CapabilitySource.PRESET, caps.source)
        // 图像能力仍然保持未知：本地兜底不该顺手给出一个否定结论
        assertNull(caps.supportsImage)
    }

    // ═══════════ D. 路由与能力数据的辅助行为 ═══════════

    @Test
    fun `D1 本地端点判断收口一致`() {
        assertTrue(LlmRegistry.isLocalBase("http://127.0.0.1:11434/v1"))
        assertTrue(LlmRegistry.isLocalBase("http://localhost:11434"))
        assertTrue(LlmRegistry.isLocalBase("http://LocalHost:1234/v1"))
        assertFalse(LlmRegistry.isLocalBase("https://api.deepseek.com"))
        assertFalse(LlmRegistry.isLocalBase(""))
    }

    @Test
    fun `D2 能力缓存键必须带端点且大小写归一`() {
        assertEquals(
            "https://api.deepseek.com|deepseek-chat",
            ModelRoute.keyOf("https://API.DeepSeek.com/", " DeepSeek-Chat "),
        )
        // 同模型名不同端点必须是两个键：聚合站把 deepseek-chat 接到别家视觉模型并非天方夜谭
        assertTrue(
            ModelRoute.keyOf("https://a.example.com", "deepseek-chat") !=
                ModelRoute.keyOf("https://b.example.com", "deepseek-chat"),
        )
    }

    @Test
    fun `D3 上下文窗口可读文本与探测回填`() {
        assertEquals("128K", ModelCapabilities(contextWindow = 131072).contextWindowText())
        assertEquals("4K", ModelCapabilities(contextWindow = 4096).contextWindowText())
        // 未知（0）必须返回 null 让 UI 自己决定怎么措辞，而不是显示"0K"
        assertNull(ModelCapabilities(contextWindow = 0).contextWindowText())
        assertNull(ModelCapabilities().contextWindowText())

        // 探测只改图像结论与来源，其余能力（窗口/工具/流式）必须原样保留 ——
        // 探测只验证了"图像"这一件事，没有理由顺手改写别的字段
        val probed = ModelPresets.lookup("acme-llm-v7") ?: ModelPresets.ASSUMED
        val updated = probed.withImage(true, CapabilitySource.PROBED)
        assertEquals(true, updated.supportsImage)
        assertEquals(CapabilitySource.PROBED, updated.source)
        assertEquals(probed.supportsTools, updated.supportsTools)
        assertEquals(probed.contextWindow, updated.contextWindow)
    }

    @Test
    fun `D4 服务商标识推断`() {
        assertEquals("deepseek", ModelPresets.providerOf("https://api.deepseek.com", "deepseek-chat"))
        assertEquals("ollama", ModelPresets.providerOf("http://127.0.0.1:11434/v1", "qwen2.5:7b"))
        // 端点看不出服务商时按模型名兜底（聚合站/自建代理很常见）
        assertEquals("anthropic", ModelPresets.providerOf("https://my-gateway.internal/v1", "claude-3-5-sonnet"))
        assertEquals("openai-compatible", ModelPresets.providerOf("https://my-gateway.internal/v1", "acme-llm-v7"))
    }
}
