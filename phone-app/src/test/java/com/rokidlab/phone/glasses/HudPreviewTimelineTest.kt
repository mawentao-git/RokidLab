package com.rokidlab.phone.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1-4 预览卡动画时间轴（2026-10-25）：拖动滑块 = 暂停并把 t 钉到该时刻，核对任意动画帧。
 *
 * 滑块/播放键是 Compose 组件（无 Robolectric/compose-ui-test 设施，编译期验证接线），
 * 这里锁定可纯函数化的那部分契约——时间轴读数钳域：
 *  - 0..30s 量程（TIMELINE_MAX_SEC）；
 *  - 播放超过量程钉在末端（动画继续播，拖动即定位回可调范围）；
 *  - 读数与滑块同源（hudTimelineSec 一处定义），杜绝标签与滑块分叉。
 */
class HudPreviewTimelineTest {

    @Test
    fun `时间轴读数钳在 0 到 30 秒`() {
        assertEquals(0f, hudTimelineSec(0f), 1e-6f)
        assertEquals(0f, hudTimelineSec(-5_000f), 1e-6f) // 负 t 理论不存在，钳 0 防御
        assertEquals(12.5f, hudTimelineSec(12_500f), 1e-6f)
        assertEquals(29.9f, hudTimelineSec(29_900f), 1e-6f)
    }

    @Test
    fun `播放超过量程读数钉在末端`() {
        // 连续时钟不取模：30s 后读数钉 30（滑块同步钉末端），动画本身继续播
        assertEquals(30f, hudTimelineSec(30_000f), 1e-6f)
        assertEquals(30f, hudTimelineSec(45_000f), 1e-6f)
        assertEquals(30f, hudTimelineSec(600_000f), 1e-6f)
    }

    @Test
    fun `拖动滑块落点与读数互为逆运算`() {
        // onScrub: tMs = sec * 1000f ↔ 读数: sec = tMs / 1000f（量程内无损往返）
        for (sec in listOf(0f, 3.3f, 17.7f, 30f)) {
            assertEquals(sec, hudTimelineSec(sec * 1000f), 1e-4f)
        }
    }
}
