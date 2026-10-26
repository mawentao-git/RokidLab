package com.rokidlab.phone.ai

/**
 * 分组级**工具收窄**范围（2026-10-19 二期：「分组 = 一套独立 agent」的能力隔离）。
 *
 * ## 语义：只收窄，不放宽
 * 分组配置是一张**排除表**（[disabledTools]），不是一张启用表。有效能力恒等于
 * 「全局已开启 ∩ 分组未排除」—— 因此：
 *  - 全局关掉的工具，任何分组都开不了（结构上不可能，不是靠校验）；
 *  - 分组能做的唯一动作是「在全局能力范围内砍掉一部分」。
 *
 * 这与 [ToolEnabledGuard] / MCP 的 `ensureDisabledByDefault`（未信任第三方默认关）
 * 是同一条方向：分组是**用户给自己画的更小的圈**，不是绕过护栏的后门。
 *
 * ## 为什么是纯数据、由调用方算好带入
 * 与 [com.rokidlab.phone.ai.approval.ToolCallContext.enabled] 同一模式：判定链
 * （装配侧 `schemasFor` / 审批侧 guard）只吃纯数据，不自己读存储。分组配置住在
 * `store/ChatStateHolder`（Compose 可观察状态），由它解析成一份不可变值对象带进
 * ai 层 —— 装配侧与审批侧因此**必然同源**（都从同一个 resolver 拿），
 * 不会出现"开关与实际执行判定分裂"。
 *
 * ## MCP 的展开时机
 * 分组侧按 **server 粒度**收窄（[com.rokidlab.phone.store.ChatGroupMeta.disabledMcpServers]），
 * 因为 MCP 工具名是运行期才存在的（`mcp__<serverId>__<tool>`），让用户在分组里
 * 按 wire name 逐个勾选不现实。展开（server → 该 server 当前全部 wire name）
 * 发生在 [resolve] 里，由调用方把 `McpRegistry::activeToolNamesOfServer` 作为
 * lambda 传入 —— 本类因此不依赖 android.content.Context、也不依赖 McpRegistry，
 * 可在纯 JVM 单测里直接断言。
 */
data class GroupToolScope(
    /** 所属分组 id（空串 = 未分组；构造上不会出现，见 [resolve] 的返回值语义） */
    val groupId: String,
    /** 该分组**排除**的工具名全集（含 MCP wire name 展开后的） */
    val disabledTools: Set<String>,
) {
    /** 该工具在当前分组是否被排除（未排除 ⇒ 分组层放行，仍可能被全局开关拦） */
    fun excludes(name: String): Boolean = name in disabledTools

    companion object {
        /**
         * 把分组的三份配置解析成装配/审批共用的收窄范围。
         *
         * @param disabledTools      分组直接排除的工具名
         * @param disabledMcpServers 分组排除的 MCP server id
         * @param mcpToolNamesOfServer server id → 该 server **当前**下发的 wire name 集合
         *   （传 `McpRegistry::activeToolNamesOfServer`；单测传假 lambda）
         * @return null = 该分组没有任何收窄配置（调用方据此**完全跳过**分组过滤，
         *   连缓存键都不带分组 —— 绝大多数会话走这条路，零额外开销）
         */
        fun resolve(
            groupId: String,
            disabledTools: Set<String>,
            disabledMcpServers: Set<String>,
            mcpToolNamesOfServer: (String) -> Set<String>,
        ): GroupToolScope? {
            if (disabledTools.isEmpty() && disabledMcpServers.isEmpty()) return null
            val all = HashSet<String>(disabledTools)
            disabledMcpServers.forEach { serverId ->
                all += mcpToolNamesOfServer(serverId)
            }
            return GroupToolScope(groupId, all)
        }
    }
}
