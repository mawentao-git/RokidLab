package com.rokidlab.phone.ai.embedding

/**
 * 查询向量的**线程内按轮去重**缓存（2026-10-23 效率优化）。
 *
 * ## 解决的问题
 * 配置了语义检索（嵌入模型）时，每轮对话的 prompt 组装路径上，**同一段提问文本会被
 * 独立嵌入三次**：
 *  1. 知识库检索 `KnowledgeBase.vectorCandidates`（BM25+向量 RRF 融合的语义路）；
 *  2. 长期记忆事实 `LongTermMemoryManager.vectorRanked`（memoriesContext 内）；
 *  3. 经验教训 `LongTermMemoryManager.vectorRanked`（lessonsContext 内）。
 *
 * 三次调用打的是同一个端点、同一个模型、同一段文本 —— 除浪费两次网络 RTT
 * （典型 200~800ms，直接加在首字延迟上）外没有任何信息增量。
 *
 * ## 为什么是 ThreadLocal 而不是全局 Map
 * - 三处调用都发生在**同一条生成线程**上（prompt 组装是顺序阻塞调用，协程不换线程），
 *   ThreadLocal 天然把作用域收在"这一轮"内，不需要额外的轮次标识与清理钩子；
 * - 查询向量只在**当轮**有复用价值（跨轮同一句话的概率低），线程复用时的陈旧条目
 *   键里含 (baseUrl, model, query)，即便命中也不会给出错误向量；
 * - 全局 Map 需要自己设计淘汰与清理策略，还要防"跨会话/跨用户配置变更后陈旧"——
 *   线程内 + 有界条数把这两个问题同时消掉。
 *
 * ## 语义边界
 * - **只缓存成功结果**：嵌入失败（网络/协议异常）不落缓存，调用方照旧走各自的降级路
 *   （知识库回退纯 BM25、记忆回退词法+最近 K 条），下一处调用会重新尝试；
 * - 查询向量是**无副作用纯读取**的产物，缓存不改变任何调用的返回值语义 ——
 *   未命中时行为与改造前逐字一致；
 * - 条数有界（[MAX_ENTRIES]，访问序淘汰）：池线程整个生命周期最多留几条向量，
 *   内存占用可忽略（千维 float ≈ 几 KB）。
 */
internal object QueryVectorCache {

    /** 单线程最多缓存的查询向量条数（一轮通常只用到 1 条；留余量给同线程连续不同提问） */
    private const val MAX_ENTRIES = 4

    private data class Key(val baseUrl: String, val model: String, val query: String)

    /**
     * 线程私有的小缓存（access-order：淘汰最久未用的条目）。
     *
     * 不做同步：映射只可能被**拥有它的那条线程**访问（这就是 ThreadLocal 的语义），
     * 而 `embed` 是阻塞调用、调用期间不会有同线程的其他代码插进来。
     */
    private val cache = ThreadLocal<LinkedHashMap<Key, FloatArray>>()

    /**
     * 取查询向量；未命中时调 [embed] 现算并缓存。
     *
     * @param embed 真正的嵌入调用（只在未命中时执行）。返回 null = 服务端没给向量，
     *   按未命中处理、**不缓存**。
     * @return 单位化向量；null = 嵌入失败或无结果（调用方照旧降级）。
     */
    fun getOrEmbed(
        baseUrl: String,
        model: String,
        query: String,
        embed: () -> FloatArray?,
    ): FloatArray? {
        val key = Key(baseUrl, model, query)
        val map = cache.get() ?: LinkedHashMap<Key, FloatArray>(16, 0.75f, true)
            .also { cache.set(it) }
        map[key]?.let { return it }
        val vec = embed() ?: return null
        map[key] = vec
        if (map.size > MAX_ENTRIES) {
            // access-order 下首个键即最久未用
            val eldest = map.keys.iterator().next()
            map.remove(eldest)
        }
        return vec
    }

    /** 测试可见：清空**当前线程**的缓存（单测之间隔离用） */
    fun clearForTest() {
        cache.get()?.clear()
    }
}
