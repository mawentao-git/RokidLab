package com.rokidlab.phone.store

import com.rokidlab.phone.ai.GroupToolScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 分组收窄在 [ChatStateHolder] 单例上的解析回归（2026-10-19 二期）。
 *
 * 可测性同 `ChatStateHolderTraceTest`：`testOptions.unitTests.isReturnDefaultValues = true`
 * ⇒ Looper 为 null 时 `runOnMain` 走内联分支；`appContext` 为 null ⇒ 所有落盘路径
 * 静默跳过（persistGroups / persistIndex 都是 `appContext ?: return`）。
 * 因此可以直接驱动单例的 createGroup / newSession，**不动设备、不碰文件**。
 *
 * 锁住的不变式：装配侧（schemasFor 的 groupScope）与审批侧（preExecute 的
 * groupDisabled）都调 [ChatStateHolder.currentGroupToolScope] —— 这里返回什么，
 * 两侧就信什么。所以"分组改了配置即时生效""未分组零收窄""删了配置回落 null"
 * 这三条必须钉住。
 */
class ChatStateHolderGroupScopeTest {

    @Before
    fun setUp() {
        ChatStateHolder.groups.clear()
        ChatStateHolder.sessions.clear()
    }

    @After
    fun tearDown() {
        ChatStateHolder.groups.clear()
        ChatStateHolder.sessions.clear()
    }

    @Test
    fun `分组收窄配置解析进当前会话的工具范围`() {
        val gid = ChatStateHolder.createGroup("写代码", disabledTools = setOf("run_shell"))
        ChatStateHolder.newSession(gid)

        val scope = ChatStateHolder.currentGroupToolScope()
        assertNotNull("配了收窄的分组必须产出范围", scope)
        assertTrue(scope!!.excludes("run_shell"))
        assertFalse("没列出的工具不受影响", scope.excludes("get_current_time"))
    }

    @Test
    fun `未分组会话没有任何收窄范围`() {
        // 显式建未分组会话（2026-10-19 三期起 newSession() 缺省继承当前分组，
        // 不再默认建未分组 —— 见 ChatStateHolder.newSession 的 KDoc）
        ChatStateHolder.newSession(ChatStateHolder.UNGROUPED_ID)
        assertNull(ChatStateHolder.currentGroupToolScope())
    }

    @Test
    fun `分组没配收窄时返回 null_零开销路径`() {
        val gid = ChatStateHolder.createGroup("闲聊")
        ChatStateHolder.newSession(gid)
        assertNull(
            "没收窄配置的分组不该产生范围对象（装配侧连缓存键都不带分组）",
            ChatStateHolder.currentGroupToolScope(),
        )
    }

    @Test
    fun `更新分组后收窄范围即时改变`() {
        val gid = ChatStateHolder.createGroup("g", disabledTools = setOf("run_shell"))
        ChatStateHolder.newSession(gid)
        ChatStateHolder.updateGroup(gid, "g", "", disabledTools = setOf("call_phone"))

        val scope = ChatStateHolder.currentGroupToolScope()!!
        assertTrue("新配置生效", scope.excludes("call_phone"))
        assertFalse("旧配置已移除", scope.excludes("run_shell"))
    }

    @Test
    fun `清空收窄配置后回落 null`() {
        val gid = ChatStateHolder.createGroup("g", disabledTools = setOf("run_shell"))
        ChatStateHolder.newSession(gid)
        ChatStateHolder.updateGroup(gid, "g", "")
        assertNull(ChatStateHolder.currentGroupToolScope())
    }

    @Test
    fun `技能排除表按组解析_未分组为空集`() {
        val gid = ChatStateHolder.createGroup("写小说", disabledSkills = setOf("aiui-dev"))
        ChatStateHolder.newSession(gid)
        assertEquals(setOf("aiui-dev"), ChatStateHolder.currentGroupDisabledSkills())

        // 显式切到未分组（三期起缺省参数继承当前分组，不能再用无参 newSession 表达"未分组"）
        ChatStateHolder.newSession(ChatStateHolder.UNGROUPED_ID)
        assertTrue(ChatStateHolder.currentGroupDisabledSkills().isEmpty())
    }

    @Test
    fun `新建会话缺省继承当前分组_2026_10_19三期`() {
        val gid = ChatStateHolder.createGroup("写小说", disabledSkills = setOf("aiui-dev"))
        ChatStateHolder.newSession(gid)

        // 无参新建 = 继承当前会话所在分组（此前缺省是未分组，用户点「新建对话」
        // 会被踢出分组，还得手动挪回去 —— 三期修的正是这个）
        val sid = ChatStateHolder.newSession()
        assertEquals("新会话应落在当前分组", gid, ChatStateHolder.sessions.first { it.id == sid }.groupId)
        assertEquals("分组收窄跟着走", setOf("aiui-dev"), ChatStateHolder.currentGroupDisabledSkills())

        // 分组被删后（防御竞态）缺省新建落回未分组，不会挂在幽灵分组上
        ChatStateHolder.deleteGroup(gid)
        val sid2 = ChatStateHolder.newSession()
        assertEquals(
            ChatStateHolder.UNGROUPED_ID,
            ChatStateHolder.sessions.first { it.id == sid2 }.groupId,
        )
    }

    @Test
    fun `只配 MCP server 收窄时范围非 null_连上工具后自动生效`() {
        // 单测环境里 McpRegistry 快照为空 ⇒ 展开结果为空集，但范围本身要保留
        val gid = ChatStateHolder.createGroup("项目维护", disabledMcpServers = setOf("srv-a"))
        ChatStateHolder.newSession(gid)

        val scope = ChatStateHolder.currentGroupToolScope()
        assertNotNull("server 暂未连接也要保留收窄意图", scope)
        assertTrue(scope!!.disabledTools.isEmpty())
    }

    @Test
    fun `会话移出分组后收窄范围消失`() {
        val gid = ChatStateHolder.createGroup("g", disabledTools = setOf("run_shell"))
        val sid = ChatStateHolder.newSession(gid)
        assertNotNull(ChatStateHolder.currentGroupToolScope())

        ChatStateHolder.moveSessionToGroup(sid, ChatStateHolder.UNGROUPED_ID)
        assertNull("移出分组 = 回到全局能力", ChatStateHolder.currentGroupToolScope())
    }

    @Test
    fun `删除分组后会话回到未分组_收窄范围消失`() {
        val gid = ChatStateHolder.createGroup("g", disabledTools = setOf("run_shell"))
        ChatStateHolder.newSession(gid)
        ChatStateHolder.deleteGroup(gid)
        assertNull(ChatStateHolder.currentGroupToolScope())
        assertEquals("", ChatStateHolder.currentGroupId())
    }

    @Test
    fun `范围对象的相等性按内容_缓存等值校验依赖它`() {
        val gid = ChatStateHolder.createGroup("g", disabledTools = setOf("run_shell"))
        ChatStateHolder.newSession(gid)
        val a = ChatStateHolder.currentGroupToolScope()
        val b = ChatStateHolder.currentGroupToolScope()
        assertEquals(a, b)
        assertEquals(GroupToolScope(gid, setOf("run_shell")), a)
    }
}
