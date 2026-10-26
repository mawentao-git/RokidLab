package com.rokidlab.phone.store

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HUD 预览卡「关闭」回归（2026-10-25 用户要求：预览只有放大没有关闭；且关闭要同时收起眼镜端）。
 *
 * 覆盖 [ChatStateHolder.dismissHudPreview] 的三条不变式：
 *  1. **独立预览消息**（无过程、正文只是 caption）⇒ 整条移除；
 *  2. **合并进 AI 回复气泡的预览图**（本轮锚点上）⇒ 只摘 imageUrl 留正文，
 *     caption 占位文案一并清（图没了它也没有存在意义）；
 *  3. **普通图片/文件消息摸不到** —— 只认 `hud://` 前缀，别的一条不动。
 *
 * 眼镜端联动（hideHud）在 ChatBubble 的 onClose 里，走 Binder 下行，单测覆盖不到，
 * 由真机验证（点关闭 → 眼镜 HUD 收起）。
 *
 * 可测性同 [ChatStateHolderTraceTest]：`isReturnDefaultValues = true` ⇒ runOnMain 内联、
 * appContext 为 null ⇒ 落盘静默跳过，直接驱动单例即可。
 */
class ChatStateHolderHudPreviewTest {

    private val messages get() = ChatStateHolder.messages

    @Before
    fun setUp() {
        messages.clear()
        ChatStateHolder.finishTrace(failed = true)
    }

    @After
    fun tearDown() {
        messages.clear()
        ChatStateHolder.finishTrace(failed = true)
    }

    @Test
    fun `独立预览消息整条移除`() {
        ChatStateHolder.add(isUser = true, content = "设计一个水墨风 HUD")
        val preview = ChatStateHolder.addImage(
            isUser = false,
            imageUrl = "hud://%7B%22v%22%3A1%7D",
            caption = "HUD 预览",
        )
        assertEquals("前置条件：预览消息已入列", 2, messages.size)

        ChatStateHolder.dismissHudPreview(preview.id)

        assertEquals("关闭后预览消息应整条移除", 1, messages.size)
        assertTrue("列表里不该再留下 hud:// 消息", messages.none { it.imageUrl?.startsWith("hud://") == true })
    }

    @Test
    fun `合并进回复气泡只摘图留正文`() {
        ChatStateHolder.add(isUser = true, content = "设计一个水墨风 HUD")
        ChatStateHolder.upsertTrace(
            com.rokidlab.phone.ai.AgentStep(
                key = "tool:call_1",
                kind = com.rokidlab.phone.ai.AgentStep.Kind.TOOL,
                title = "glass_hud",
                state = com.rokidlab.phone.ai.AgentStep.State.OK,
            ),
        )
        val merged = ChatStateHolder.addImage(
            isUser = false,
            imageUrl = "hud://%7B%22v%22%3A1%7D",
            caption = "HUD 预览",
        )
        assertEquals("前置条件：预览图应合并进锚点消息（不新增消息）", 2, messages.size)

        ChatStateHolder.dismissHudPreview(merged.id)

        val survivor = messages.firstOrNull { it.id == merged.id }
        assertEquals("合并消息必须保留（里面还有过程/正文）", true, survivor != null)
        assertNull("imageUrl 应被摘掉", survivor?.imageUrl)
        assertEquals("caption 占位文案应一并清掉", "", survivor?.content)
        assertTrue("过程卡不受影响", survivor?.trace?.isNotEmpty() == true)
    }

    @Test
    fun `普通图片消息摸不到`() {
        ChatStateHolder.add(isUser = true, content = "给我看张狗")
        val img = ChatStateHolder.addImage(
            isUser = false,
            imageUrl = "https://img.example.com/dog.jpg",
            caption = "找了一张小狗的图",
        )

        ChatStateHolder.dismissHudPreview(img.id)

        assertEquals("非 hud:// 消息不该被这条路移除", 2, messages.size)
        assertEquals("原样保留", "https://img.example.com/dog.jpg", messages[1].imageUrl)
    }

    @Test
    fun `合并消息带真实正文时关闭只摘图不动正文`() {
        ChatStateHolder.add(isUser = true, content = "设计一个水墨风 HUD")
        ChatStateHolder.upsertTrace(
            com.rokidlab.phone.ai.AgentStep(
                key = "tool:call_1",
                kind = com.rokidlab.phone.ai.AgentStep.Kind.TOOL,
                title = "glass_hud",
                state = com.rokidlab.phone.ai.AgentStep.State.OK,
            ),
        )
        val merged = ChatStateHolder.addImage(
            isUser = false,
            imageUrl = "hud://%7B%22v%22%3A1%7D",
            caption = "HUD 预览",
        )
        // 流式正文落点：isReplyText() 要求 imageUrl==null && trace.isEmpty() ⇒ 带图的锚点
        // 消息不算回复文本，正文**另起一条**（appendAiDelta 的 else 分支），不拼 caption。
        ChatStateHolder.appendAiDelta("已经设计好了，看看预览。")
        assertEquals("前置条件：正文是独立消息", 3, messages.size)

        ChatStateHolder.dismissHudPreview(merged.id)

        val survivor = messages.firstOrNull { it.id == merged.id }
        assertNull("imageUrl 应被摘掉", survivor?.imageUrl)
        assertEquals("caption 占位文案应一并清掉", "", survivor?.content)
        assertTrue("过程卡不受影响", survivor?.trace?.isNotEmpty() == true)
        assertTrue(
            "流式正文消息必须原样保留（关闭预览不该吃掉回复）",
            messages.any { it.content.contains("已经设计好了，看看预览。") },
        )
    }
}
