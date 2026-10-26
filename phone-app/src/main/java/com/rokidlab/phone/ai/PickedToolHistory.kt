package com.rokidlab.phone.ai

/**
 * 菜单点名累计集的**纯逻辑**（2026-10-23，装配集合跨轮冻结；接线见
 * `AiConversationService.pickedToolHistory`）。
 *
 * 从主循环抽出的原因：冻结的三条不变式（有界 / 只增 / 按会话重置）是这套优化的
 * 全部安全边界，必须能被"验证它真的能红"的单测钉住 —— 写在 4500 行的主循环里
 * 等于没有测试（主循环零单测是既知事实）。
 *
 * 无线程同步：调用方（主循环线程）保证读写同线程；本对象只做集合运算。
 */
internal object PickedToolHistory {

    /**
     * 把 [names] 并进 [history]（LinkedHashSet，保插入序 ⇒ 确定性）。
     *
     * 有界：达到 [cap] 即停止（已存在的名字仍可重复 add —— 幂等，不计入增长）。
     *
     * @return 实际新增的名字数（0 = 全部已存在或已到顶）
     */
    fun addWithinCap(history: LinkedHashSet<String>, names: Set<String>, cap: Int): Int {
        var added = 0
        for (n in names) {
            if (history.size >= cap) break
            if (history.add(n)) added++
        }
        return added
    }

    /**
     * 累计集是否该为当前会话重置（对话会话切换 = 任务上下文切换）。
     *
     * 首轮（bound 为 null）不重置 —— 那时 history 必然也是空的，清空是空操作；
     * 真正的判据只有"bound 非空且与当前不同"。
     */
    fun shouldReset(boundSessionId: String?, currentSessionId: String): Boolean =
        boundSessionId != null && boundSessionId != currentSessionId
}
