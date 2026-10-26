package com.rokidlab.phone.glasses

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * L2 session/LinkVersionGate —— 眼镜端 RokidLink 版本比对（2026-10-24 启动引导 redesign）。
 *
 * **为什么需要它**：此前两端没有版本比对能力 —— 手机端更新后内置的 RokidLink.apk 变了，
 * 眼镜端还是旧版，只会静默降级（表现为「某些功能不工作」）。旧的兜底是引导步
 * 「安装眼镜端应用」**每次启动都出现**（刻意不落盘），用户烦。
 *
 * **数据源（都不需要改协议）**：
 *  - 手机端内置版本：`assets/RokidLink.apk` 的 versionName（[bundledVersion]，getPackageArchiveInfo，
 *    与 [com.rokidlab.phone.domain.DeviceControlService.readPackageName] 同款先例）；
 *  - 眼镜端实际版本：HELLO 握手第 3 字段 linkVersion（[GlassesHandshake.linkVersion]，
 *    眼镜端 `CxrBridgeCoordinator.announceHello()` 每次服务就绪都上报）。
 *
 * **三态语义**（与 queryInstalledApps 的 Boolean? 一致）：[needsUpdate] 返回
 * `true` = 眼镜端旧、该提醒更新；`false` = 一致或眼镜端反而更新；`null` = 无法判断
 * （任一側版本缺失，如眼镜离线未握手）⇒ 调用方按「不打扰」处理，宁漏勿扰。
 *
 * 纯函数部分（[compare] / [needsUpdate]）零 Android 依赖，可直接单测。
 */
internal object LinkVersionGate {
    private const val TAG = "LinkVersionGate"
    private const val APK_ASSET = "RokidLink.apk"

    @Volatile
    private var cachedBundled: String? = null
    private val probeDone = AtomicBoolean(false)

    /**
     * 手机端内置 RokidLink APK 的 versionName；读不到返回 null。
     *
     * 每进程只解一次（assets → cache 临时文件 → getPackageArchiveInfo → 删除临时文件）。
     * 内置 APK 随手机端发包走，进程生命周期内不会变，缓存安全。
     */
    fun bundledVersion(context: Context): String? {
        if (probeDone.get()) return cachedBundled
        return synchronized(this) {
            if (probeDone.get()) return cachedBundled
            cachedBundled = probeBundledVersion(context)
            probeDone.set(true)
            cachedBundled
        }
    }

    private fun probeBundledVersion(context: Context): String? = runCatching {
        val tmp = File(context.cacheDir, "RokidLink.version-probe.apk")
        context.assets.open(APK_ASSET).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(
            tmp.absolutePath,
            android.content.pm.PackageManager.GET_ACTIVITIES,
        )
        tmp.delete()
        info?.versionName?.takeIf { it.isNotBlank() }
    }.onFailure { Log.w(TAG, "probe bundled RokidLink version failed: ${it.message}") }.getOrNull()

    /**
     * 分段数字比较：`"5.10" > "5.9"`（数值比较，不是字符串比较）。
     *
     * 每段取段首连续数字（`"0-debug"` → 0、`"12abc"` → 12），非数字后缀忽略 ——
     * debug/release 同号是常态（签名不同、版本号相同），不能因为后缀把「一致」判成「落后」。
     * 段数不齐按 0 补齐（`"5.0"` < `"5.0.1"`）。
     */
    fun compare(a: String, b: String): Int {
        val sa = a.trim().split(".")
        val sb = b.trim().split(".")
        val n = maxOf(sa.size, sb.size)
        for (i in 0 until n) {
            val na = sa.getOrNull(i)?.leadingInt() ?: 0
            val nb = sb.getOrNull(i)?.leadingInt() ?: 0
            if (na != nb) return na.compareTo(nb)
        }
        return 0
    }

    private fun String.leadingInt(): Int = takeWhile { it.isDigit() }.toIntOrNull() ?: 0

    /**
     * 眼镜端是否需要更新。
     *
     * @return true = 内置版本更新、该提醒；false = 一致或眼镜端不旧；null = 任一側版本缺失，无法判断。
     */
    fun needsUpdate(bundled: String?, glasses: String?): Boolean? {
        val b = bundled?.trim()?.takeIf { it.isNotEmpty() }
        val g = glasses?.trim()?.takeIf { it.isNotEmpty() }
        if (b == null || g == null) return null
        return compare(b, g) > 0
    }
}
