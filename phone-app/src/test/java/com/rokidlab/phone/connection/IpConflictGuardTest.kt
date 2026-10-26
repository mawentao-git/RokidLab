package com.rokidlab.phone.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IP 冲突防护（2026-10-24）判定函数的行为锁定。
 *
 * 背景：手机只连 WiFi 时，路由器若把同一地址分给手机和眼镜，Lab 启动后对 glassesIp 的
 * 高频探测会把 ARP 冲突显形化（用户报障"启动 Lab 后手机没网、有几率"）。
 * [ConnectionRouteManager.looksLikeOwnAddress] 在 WiFi 探测前拦截"目标是本机自己"的情形。
 */
class IpConflictGuardTest {

    @Test
    fun `collect returns at least one address on any networked host`() {
        // JVM（CI/本机）与 Android 一样，有网络栈就至少有一个非回环地址；
        // 空集合会让下面的正向断言失去意义，所以这里先锁"非空"这个前提。
        val own = ConnectionRouteManager.collectOwnAddresses()
        assertTrue("本机应有至少一个非回环地址，实际为空", own.isNotEmpty())
    }

    @Test
    fun `real local address is recognized as own`() {
        val own = ConnectionRouteManager.collectOwnAddresses()
        val first = own.first()
        assertTrue("本机地址 $first 必须被判为本机", ConnectionRouteManager.looksLikeOwnAddress(first))
    }

    @Test
    fun `external address is never own`() {
        // RFC 5737 文档专用网段（192.0.2.x）：现实中不可能分配给本机
        assertFalse(ConnectionRouteManager.looksLikeOwnAddress("192.0.2.77"))
        // 公网地址同理
        assertFalse(ConnectionRouteManager.looksLikeOwnAddress("8.8.8.8"))
    }

    @Test
    fun `invalid input falls back to not-own`() {
        // 非法 IP：按"不是本机"处理，不拦截探测（保持旧行为）
        assertFalse(ConnectionRouteManager.looksLikeOwnAddress(""))
        assertFalse(ConnectionRouteManager.looksLikeOwnAddress("not-an-ip"))
        assertFalse(ConnectionRouteManager.looksLikeOwnAddress("999.999.999.999"))
    }

    @Test
    fun `numeric equivalence tolerates leading zeros`() {
        // 前导零写法与标准写法等价（点分十进制数值比对）
        val own = ConnectionRouteManager.collectOwnAddresses()
        val v4 = own.firstOrNull { it.contains('.') } ?: return
        val padded = v4.split('.').joinToString(".") { it.padStart(3, '0') }
        assertTrue("前导零写法 $padded 应等价于 $v4", ConnectionRouteManager.looksLikeOwnAddress(padded))
    }

    @Test
    fun `ipv6 zone suffix is stripped before compare`() {
        val own = ConnectionRouteManager.collectOwnAddresses()
        val v6 = own.firstOrNull { it.contains(':') } ?: return
        assertTrue("带 zone 后缀的 IPv6 应剥后缀后比对", ConnectionRouteManager.looksLikeOwnAddress("$v6%wlan0"))
    }
}
