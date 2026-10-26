package com.rokidlab.phone.model

import com.rokidlab.phone.design.RokidHostApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引导步骤推导 —— 锁住两条线的不变式：
 *
 * **A. 「权限」步**（v3.9 新增，插在「安装眼镜端应用」之前，属于会挡住老用户的位置）：
 *  1. [PrerequisitesState.permissionsReady] 默认 true —— 没显式实测到缺项时这一步不出现；
 *  2. 权限步排在 [GuideStep.INSTALL_LINK] 之前（需求：装机前先把权限开齐）；
 *  3. 权限步排在 [GuideStep.AUTHORIZE] 之后（没授权时链路都没通，先谈权限没意义）。
 *
 * **B. 「安装眼镜端」步的三态语义**（2026-10-24 redesign，替代此前"每次启动恒 false 重走"）：
 *  1. `false`（眼镜端确认未安装）→ 拦截在 INSTALL_LINK（首次装机场景）；
 *  2. `true`（已安装）→ 放行；
 *  3. `null`（探测未完成/查询失败/眼镜离线）→ **放行不打扰**（宁漏勿扰，误拦比漏拦更烦）。
 *
 * CONFIGURE_WIFI 已退役出启动链路（WiFi 改按需入口），步骤总数 7 → 5。
 */
class GuideStepDerivationTest {

    private fun authorized(
        permissionsReady: Boolean,
        rokidLinkInstalled: Boolean?,
    ) = PrerequisitesState(
        hostApp = RokidHostApp.DEFAULT,
        mirrorSourceSelected = true,
        authorized = true,
        permissionsReady = permissionsReady,
        rokidLinkInstalled = rokidLinkInstalled,
    )

    @Test
    fun `默认 permissionsReady 为 true —— 老用户不会被权限步拦住`() {
        assertEquals(true, PrerequisitesState().permissionsReady)
        // 走到「安装眼镜端」这一步时，说明前面几步都过了；此时若权限状态未实测，不应凭空多出一个权限步
        assertEquals(GuideStep.INSTALL_LINK, authorized(permissionsReady = true, rokidLinkInstalled = false).currentGuideStep)
    }

    @Test
    fun `有缺项时权限步排在安装眼镜端之前`() {
        val state = authorized(permissionsReady = false, rokidLinkInstalled = false)
        assertEquals(GuideStep.PERMISSIONS, state.currentGuideStep)
        assertTrue(GuideStep.PERMISSIONS.ordinal < GuideStep.INSTALL_LINK.ordinal)
    }

    @Test
    fun `未授权时先授权再谈权限`() {
        val state = authorized(permissionsReady = false, rokidLinkInstalled = false).copy(authorized = false)
        assertEquals(GuideStep.AUTHORIZE, state.currentGuideStep)
    }

    @Test
    fun `权限已齐时直接跳到安装步`() {
        val state = authorized(permissionsReady = true, rokidLinkInstalled = false)
        assertEquals(GuideStep.INSTALL_LINK, state.currentGuideStep)
    }

    @Test
    fun `权限步未过时商店不开放`() {
        val blocked = authorized(permissionsReady = false, rokidLinkInstalled = false)
        assertFalse(blocked.canInstallApps)
        val ready = blocked.copy(permissionsReady = true, rokidLinkInstalled = true)
        assertEquals(GuideStep.READY, ready.currentGuideStep)
        assertTrue(ready.canInstallApps)
    }

    // ── B. 安装步三态（redesign 2026-10-24）──

    @Test
    fun `眼镜端确认未安装才拦截在安装步`() {
        assertEquals(GuideStep.INSTALL_LINK, authorized(true, rokidLinkInstalled = false).currentGuideStep)
        assertFalse(authorized(true, rokidLinkInstalled = false).canInstallApps)
    }

    @Test
    fun `眼镜端已安装直接放行`() {
        val state = authorized(permissionsReady = true, rokidLinkInstalled = true)
        assertEquals(GuideStep.READY, state.currentGuideStep)
        assertTrue(state.canInstallApps)
    }

    @Test
    fun `安装状态未知时放行不打扰 —— 探测失败不误拦老用户`() {
        val state = authorized(permissionsReady = true, rokidLinkInstalled = null)
        assertEquals(GuideStep.READY, state.currentGuideStep)
        assertTrue(state.canInstallApps)
    }

    @Test
    fun `默认 rokidLinkInstalled 为 null —— 冷启动探测未完成不拦`() {
        assertEquals(null, PrerequisitesState().rokidLinkInstalled)
        assertEquals(GuideStep.READY, authorized(permissionsReady = true, rokidLinkInstalled = null).currentGuideStep)
    }

    @Test
    fun `逐个推进顺序与枚举顺序一致`() {
        val steps = listOf(
            PrerequisitesState(),
            PrerequisitesState(hostApp = RokidHostApp.DEFAULT),
            authorized(permissionsReady = true, rokidLinkInstalled = null).copy(authorized = false),
            authorized(permissionsReady = false, rokidLinkInstalled = null),
            authorized(permissionsReady = true, rokidLinkInstalled = false),
            authorized(permissionsReady = true, rokidLinkInstalled = true),
        ).map { it.currentGuideStep }
        assertEquals(
            listOf(
                GuideStep.SELECT_HOST_APP,
                GuideStep.SELECT_MIRROR_SOURCE,
                GuideStep.AUTHORIZE,
                GuideStep.PERMISSIONS,
                GuideStep.INSTALL_LINK,
                GuideStep.READY,
            ),
            steps,
        )
    }
}
