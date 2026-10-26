package com.rokidlab.rokidlink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * HUD DSL 压测直推入口（2026-10-24 硬件摸底；调试专用，正式发版可删）。
 *
 * 用途：**不经手机链路**，用 adb 把场景 DSL 直接推到眼镜端上屏，测量
 * 「元素/动画/表达式数量 → 帧率」的真实硬件天花板。结论用于回填
 * [HudOverlayController] 的 MAX_ELEMENTS / MAX_ANIMS / MAX_EXPR_ELEMENTS 等护栏
 * （此前那些上限只是设计纪律，没有实测数据支撑）。
 *
 * 用法：
 * ```
 * adb -s <眼镜> shell am broadcast \
 *   -n com.rokidlab.rokidlink/.HudDebugReceiver \
 *   -a com.rokidlab.rokidlink.DEBUG_HUD \
 *   --es act apply --es dsl "$(cat /data/local/tmp/hud.json)"
 * ```
 * act: `try`=60s 试戴 / `apply`=常驻 / `hide`=收起（与 hud_ctrl 协议同语义）。
 * dsl 为空 + act=hide 时收起当前场景。
 *
 * ⚠️ 为什么 exported=true：shell uid（adb）不是本应用 uid，非导出组件投不进来
 * （与 KeyButtonService 的导出理由同源，见 AndroidManifest 注释）。暴露面只是
 * 「渲染一条 HUD 覆盖层」——内容本身就是 AI 生成的任意内容，风险面与既有暴露一致。
 * 压测脚本见 scripts/hud_stress.py。
 */
class HudDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val act = intent.getStringExtra("act") ?: "apply"
        val svc = KeyButtonService.instance
        if (svc == null) {
            Log.w(TAG, "DEBUG_HUD: KeyButtonService not running — 先确保眼镜端 RokidLink 已起来")
            return
        }
        if (act == "hide") {
            Log.i(TAG, "DEBUG_HUD: hide")
            svc.debugApplyHud("", "hide")
            return
        }
        val dsl = intent.getStringExtra("dsl")
        if (dsl.isNullOrBlank()) {
            Log.w(TAG, "DEBUG_HUD: empty dsl extra (act=$act)")
            return
        }
        Log.i(TAG, "DEBUG_HUD: act=$act dslChars=${dsl.length}")
        svc.debugApplyHud(dsl, act)
    }

    companion object {
        private const val TAG = "HudDebug"
    }
}
