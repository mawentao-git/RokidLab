package com.rokidlab.phone.ai

/**
 * 「版本号 + 键」单条目渲染缓存（2026-10-23 效率优化，`SkillRegistry.skillsContext` 的首个用户）。
 *
 * ## 解决什么
 * 有些注入文本（技能清单 / 任务进度…）**每轮对话**都要重算一遍，而它们的输入
 * （目录内容 / 开关 / 快照文件）在绝大多数轮里根本没变。把"重算"收敛为一次
 * Map 查找，是 prompt 组装路径上最便宜的一笔延迟/IO 节省。
 *
 * ## 为什么是"版本号 + 键"双判据
 * - **版本号**由数据方的写入方维护（任何改变内容的写入 +1）：它回答"内容变了吗"；
 * - **键**由调用方传入（如分组排除表）：它回答"这次要的切片是同一种吗"。
 *   两者缺一不可：只有版本号 ⇒ 两个分组交替请求会串味（A 组命中 B 组的渲染结果）；
 *   只有键 ⇒ 内容更新后陈旧（这正是要防的静默错误）。
 *
 * ## 语义边界（单测逐条钉住）
 * - **null 是有效缓存值**（如"零技能"）：用独立的 [has] 标志区分"没缓存"与"缓存了 null"，
 *   否则零技能场景会每轮重扫一次目录；
 * - 并发不设锁：最坏情况是两个线程同时各算一遍（纯函数重算，结果逐字相同），
 *   后写覆盖先写无歧义 —— 为几毫秒的渲染加锁不值得；
 * - 只缓存**一个**条目：这类渲染的访问模式就是"同一件事反复问"，单条目已够；
 *   （真出现两种键高频交替，再换成 Map，接口不用动。）
 */
internal class VersionedCache<V> {

    private var cachedVersion: Int = Int.MIN_VALUE
    private var cachedKey: Any? = null
    private var cachedValue: V? = null
    private var has: Boolean = false

    /**
     * 取缓存值；未命中（版本号或键不同、或从未缓存过）时调 [compute] 现算并缓存。
     *
     * @param version 数据方的内容版本号（写入方 +1）
     * @param key 本次渲染的切片键（如分组排除表；null = 无切片）
     */
    fun getOrCompute(version: Int, key: Any?, compute: () -> V?): V? {
        if (has && version == cachedVersion && key == cachedKey) return cachedValue
        val value = compute()
        cachedVersion = version
        cachedKey = key
        cachedValue = value
        has = true
        return value
    }

    /** 测试可见：当前是否缓存着值（含 null 值） */
    internal fun hasForTest(): Boolean = has
}
