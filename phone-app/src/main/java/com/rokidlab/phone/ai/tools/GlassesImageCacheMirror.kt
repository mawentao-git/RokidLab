package com.rokidlab.phone.ai.tools

/**
 * 眼镜端 HUD 图片缓存的**手机侧镜像**（2026-10-25「主题刷新慢」修复）。
 *
 * ## 为什么需要它
 * RokidLink `HudOverlayController.imageCache` 是 **2 槽 LRU**，而手机端
 * `restoreThemeImages` 原来每次应用主题都把主题图**全量重推**（完整 Base64 走
 * CXR 串行链，先资源后场景）——多数重推纯属浪费：眼镜端缓存里明明还有同一张图。
 * 每张图 13–27KB Base64 串行传输把场景下发阻塞在后面，用户视角就是「主题刷新慢」。
 *
 * 镜像以**纯数据**模拟眼镜端的淘汰语义（与 `cacheImage` 完全同款：同 id 重推也
 * 刷新到最新槽、超 2 槽淘汰最旧），使「镜像里说有」⇔「眼镜端缓存里真有」。
 * 前提：眼镜端 HUD 图的唯一写入者是手机端下发链（hud_image / 主题回灌）——成立。
 *
 * ## 世代失效（正确性兜底）
 * 眼镜端 RokidLink 进程重启 / 重装 / 重连后会重新握手 ⇒ [GlassesHandshake.lastHelloAtMs]
 * 变化。调用方必须在每次推送批次前调 [onGeneration]：世代变化即清空镜像，
 * 本批次退化为全量重推一次（宁可多推，绝不让场景画占位框）。
 *
 * 手机进程自身重启镜像也是空的 ⇒ 同样全量重推一次，随后恢复去重。
 * 失败方向只有「多推」没有「漏推」——与占位框风险相比，多推只是回到原速度。
 */
internal class GlassesImageCacheMirror {

    /** id → 内容指纹；迭代序 = LRU 新旧（末尾最新）。全部访问走自身锁。 */
    private val slots = LinkedHashMap<String, Int>()

    /** 最近一次对齐的眼镜端世代（HELLO 时间戳） */
    @Volatile
    var glassesGeneration: Long = Long.MIN_VALUE
        private set

    /**
     * 对齐眼镜端世代。世代变化 → 清空镜像并返回 true（调用方本批次全量重推）。
     */
    fun onGeneration(generation: Long): Boolean {
        if (generation == glassesGeneration) return false
        glassesGeneration = generation
        synchronized(this) { slots.clear() }
        return true
    }

    /** 眼镜端当前是否还持有 (id, hash) 这张图（未被 LRU 淘汰、内容未变）。 */
    fun has(id: String, contentHash: Int): Boolean =
        synchronized(this) { slots[id] == contentHash }

    /** 记录一次成功推送；与眼镜端 cacheImage 同款 LRU 语义（同 id 刷新到最新槽）。 */
    fun record(id: String, contentHash: Int) {
        synchronized(this) {
            slots.remove(id)
            slots[id] = contentHash
            while (slots.size > SLOTS) {
                slots.remove(slots.keys.first())
            }
        }
    }

    /** 推送失败 / 未连接时调用：从镜像剔除（下次重试会重推）。 */
    fun evict(id: String) {
        synchronized(this) { slots.remove(id) }
    }

    companion object {
        /** 与 RokidLink HudOverlayController.IMAGE_CACHE_SIZE 对齐；改那头必须同步改这头 */
        const val SLOTS = 2
    }
}
