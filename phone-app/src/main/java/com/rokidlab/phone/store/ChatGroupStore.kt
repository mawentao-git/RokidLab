package com.rokidlab.phone.store

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 分组元数据（存 `chat_groups.json`，2026-10-19 新增）。
 *
 * 一个分组 = 一套独立 agent 的配置容器：
 *   - [systemPrompt]：**分组人设**，追加到全局人设之后、会话附加提示词之前
 *     （三层顺序：全局人设 → 分组 → 会话；后两者都走轮变尾消息的追加通道，
 *     不碰 system 头部，保住 prompt 缓存前缀纪律，见 OpenAiService.buildContextTailMessage）。
 *   - [disabledTools] / [disabledMcpServers] / [disabledSkills]：**能力收窄**
 *     （2026-10-19）。三份都是**排除表**：有效能力 = 全局已开启 ∩ 本组未排除，
 *     只收窄不放宽 —— 全局关掉的任何能力，分组都开不回来（见 [com.rokidlab.phone.ai.GroupToolScope]）。
 *     典型场景：「写小说」分组排掉 shell/文件写类工具防误触，「项目维护」分组只挂该项目 MCP。
 *
 * ⚠️ 分组是**辅助配置**，不是消息真相源：会话消息本体在 `chat_sessions/<id>.jsonl`，
 * 分组索引损坏最多让会话回到「未分组」（会话侧 [ChatSessionMeta.groupId] 指向不存在的
 * 分组时按未分组渲染，不丢数据），与消息文件损坏的后果不在一个量级。
 */
internal data class ChatGroupMeta(
    val id: String,
    /** 分组名（用户可改；分组在 UI 上按创建顺序排列） */
    val name: String,
    val createdAt: Long,
    /** 分组人设（追加语义，空串 = 没设） */
    val systemPrompt: String = "",
    /** 能力收窄：本分组**排除**的工具名集合（空集 = 不收窄，跟随全局开关） */
    val disabledTools: Set<String> = emptySet(),
    /** 能力收窄：本分组**排除**的 MCP server id 集合（空集 = 全部可用） */
    val disabledMcpServers: Set<String> = emptySet(),
    /** 能力收窄：本分组**排除**的技能名集合（空集 = 全部可用） */
    val disabledSkills: Set<String> = emptySet(),
)

/**
 * 分组索引的落盘格式与派生逻辑（与 [ChatSessionStore] 同一套纯 JVM 模式）。
 *
 * 存储布局（挂在会话索引旁边）：
 * ```
 * filesDir/
 *   chat_groups.json            ← 分组索引（本对象负责）
 *   chat_sessions.json          ← 会话索引（ChatSessionStore 负责，会话靠 groupId 挂到分组）
 * ```
 */
internal object ChatGroupStore {

    private const val TAG = "ChatGroupStore"

    /** 分组索引文件名 */
    const val GROUPS_FILE = "chat_groups.json"

    /** 未分组的会话归属值（分组 id 永远不为空串，见 [ChatStateHolder.newGroupId]） */
    const val UNGROUPED = ""

    /**
     * 分组索引文本 → 内存列表。
     *
     * 与会话索引同样区分「损坏」（返回 null）与「没有」（返回空列表）——
     * 但消费方（ChatStateHolder.bootstrap）对损坏的处理是**静默按空处理**：
     * 分组不是消息数据，丢配置的代价远小于丢历史，不做重建。
     */
    fun parseGroupsOrNull(text: String): List<ChatGroupMeta>? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (!trimmed.startsWith("[")) {
            Log.w(TAG, "group index corrupted: not a JSON array (len=${trimmed.length})")
            return null
        }
        val arr = runCatching { JSONArray(trimmed) }.getOrNull() ?: run {
            Log.w(TAG, "group index corrupted: JSONArray parse failed")
            return null
        }
        val out = ArrayList<ChatGroupMeta>(arr.length())
        for (i in 0 until arr.length()) {
            val o = runCatching { arr.getJSONObject(i) }.getOrNull() ?: continue
            val id = o.optString("id").trim()
            if (id.isEmpty()) continue
            out.add(
                ChatGroupMeta(
                    id = id,
                    name = o.optString("name").ifBlank { "分组" },
                    createdAt = o.optLong("createdAt", 0L),
                    systemPrompt = o.optString("systemPrompt"),
                    disabledTools = o.optStringArray("disabledTools"),
                    disabledMcpServers = o.optStringArray("disabledMcpServers"),
                    disabledSkills = o.optStringArray("disabledSkills"),
                )
            )
        }
        return out
    }

    /** 内存列表 → 分组索引文本 */
    fun toGroupsJson(groups: List<ChatGroupMeta>): String = JSONArray().apply {
        groups.forEach { g ->
            put(
                JSONObject().apply {
                    put("id", g.id)
                    put("name", g.name)
                    put("createdAt", g.createdAt)
                    // 与会话索引同口径：空串不落字段，老版本读到也不出错
                    if (g.systemPrompt.isNotBlank()) put("systemPrompt", g.systemPrompt)
                    // 收窄集同理：空集不落字段（一期文件与没有收窄配置的分组
                    // 在磁盘上完全一致，二期前的版本读到也不出错）
                    if (g.disabledTools.isNotEmpty()) put("disabledTools", JSONArray(g.disabledTools.toList()))
                    if (g.disabledMcpServers.isNotEmpty()) put("disabledMcpServers", JSONArray(g.disabledMcpServers.toList()))
                    if (g.disabledSkills.isNotEmpty()) put("disabledSkills", JSONArray(g.disabledSkills.toList()))
                }
            )
        }
    }.toString()

    /**
     * 分组排列：按创建先后（先建的在前）。分组不是按「最近使用」排的东西 ——
     * 它是用户心智里的固定架子（写代码的 / 维护某项目的），顺序稳定才好找。
     */
    fun sorted(groups: List<ChatGroupMeta>): List<ChatGroupMeta> =
        groups.sortedWith(compareBy<ChatGroupMeta> { it.createdAt }.thenBy { it.id })

    /**
     * 读一个字符串数组字段；缺字段 / 类型不对 / 含空串一律按空集。
     *
     * 空串元素要滤掉：它会在装配侧把「空名」当成一个被排除的工具
     * （虽然实际上没有工具叫空名，但污染集合的等值判断 —— 缓存键与
     * `GroupToolScope` 的 data class equals 都拿集合比相等）。
     */
    private fun JSONObject.optStringArray(key: String): Set<String> {
        val arr = optJSONArray(key) ?: return emptySet()
        val out = LinkedHashSet<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optString(i).trim()
            if (v.isNotEmpty()) out.add(v)
        }
        return out
    }
}
