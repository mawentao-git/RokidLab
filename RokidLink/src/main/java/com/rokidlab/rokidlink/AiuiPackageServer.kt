package com.rokidlab.rokidlink

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AIUI .aix 推送接收服务（端口 7658）：
 * 手机端把生成的 .aix 字节流推过来，落盘到 filesDir/aiui_host/<name>.aix，
 * 完成后回调 [Listener.onPackageReceived]，由外部拉起 [AiuiLinkActivity]（自托管 Web 宿主）。
 *
 * 协议（一次连接，单客户端）：
 *   [2B big-endian fileNameLen][fileName utf8]
 *   [4B big-endian fileLen][file bytes]
 *   [32B md5 hex]（**可选尾**，2026-10-17 加：新手机端发送；旧手机端没有 → 读超时 3s 判定无尾）
 *   ack: 'OK'（失败回 'ER' 并清理 .tmp 残留）
 */
class AiuiPackageServer(
    private val port: Int = 7658,
    private val listener: Listener? = null,
) {
    interface Listener {
        fun onPackageReceived(file: File)
    }

    companion object {
        private const val TAG = "AiuiPackageServer"
        private const val MAX_NAME = 512
        private const val MAX_FILE = 64 * 1024 * 1024 // 64MB 上限
        const val DIR_NAME = "aiui_host"
    }

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private val running = AtomicBoolean(false)
    private var acceptThread: Thread? = null
    private val lock = Any()
    @Volatile
    private var hostDir: File? = null

    val isRunning: Boolean get() = running.get()

    /** 最近一次收到的 .aix（供 "open" 命令不带名时使用） */
    @Volatile
    private var lastPackage: File? = null

    fun lastReceived(): File? = lastPackage

    fun start(context: Context): Boolean {
        if (!running.compareAndSet(false, true)) return false
        hostDir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        return try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(port))
            serverSocket = server
            Log.i(TAG, "listening :$port dir=${hostDir?.absolutePath}")
            acceptThread = Thread { waitForClient() }
                .apply { name = "aiui-pkg-accept"; start() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "bind fail", e)
            running.set(false)
            false
        }
    }

    private fun waitForClient() {
        while (running.get()) {
            try {
                val sock = serverSocket?.accept() ?: break
                synchronized(lock) {
                    if (!running.get()) {
                        runCatching { sock.close() }
                        return
                    }
                    clientSocket = sock
                }
                sock.tcpNoDelay = true
                handleClient(sock)
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "accept err: ${e.message}")
            }
        }
    }

    private fun handleClient(sock: Socket) {
        val dir = hostDir
        if (dir == null) {
            runCatching { sock.close() }
            return
        }
        runCatching { dir.mkdirs() }
        var tmp: File? = null
        try {
            val input = BufferedInputStream(sock.getInputStream(), 32 * 1024)
            val output = BufferedOutputStream(sock.getOutputStream())
            // fileName
            val nameLen = readExact(input, 2)
            val nameLenInt = ((nameLen[0].toInt() and 0xFF) shl 8) or (nameLen[1].toInt() and 0xFF)
            if (nameLenInt <= 0 || nameLenInt > MAX_NAME) throw IllegalStateException("bad name len $nameLenInt")
            val nameBytes = readExact(input, nameLenInt)
            var name = String(nameBytes, Charsets.UTF_8)
            if (!name.endsWith(".aix")) name = "$name.aix"
            val target = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            // file bytes
            val lenBytes = readExact(input, 4)
            val len = ((lenBytes[0].toInt() and 0xFF) shl 24) or ((lenBytes[1].toInt() and 0xFF) shl 16) or
                ((lenBytes[2].toInt() and 0xFF) shl 8) or (lenBytes[3].toInt() and 0xFF)
            if (len <= 0 || len > MAX_FILE) throw IllegalStateException("bad file len $len")
            val tmpFile = File(dir, name + ".tmp")
            tmp = tmpFile
            BufferedOutputStream(tmpFile.outputStream(), 32 * 1024).use { out ->
                var remaining = len
                val buf = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val r = input.read(buf, 0, minOf(buf.size, remaining))
                    if (r == -1) throw IllegalStateException("conn closed")
                    out.write(buf, 0, r)
                    remaining -= r
                }
            }
            // ★ md5 校验（2026-10-17 二轮审计修）：新手机端在体后尾随 32 字节 hex。
            //   读法：先给 socket 一个 3s 短超时再尝试读满 32 字节 —— 旧手机端发完体就等
            //   ack，这里会超时/EOF，视为「无尾」放行（向后兼容）；读到了就必须对得上，
            //   不对 = 传输损坏，拒绝落座（坏 .aix 的下游是宿主白屏且无错误回传，必须挡在这）。
            //   已知取舍（2026-10-18 复审补记）：legacy 发送端每包要白等满 3s 超时才拿到
            //   ack —— .aix 包体大、传输以秒计，这 3s 占比可忽略；仅旧手机端升级过渡期存在，
            //   不为此改协议（探测握手会破坏向后兼容）。
            var expectedMd5: String? = null
            try {
                sock.soTimeout = 3_000
                expectedMd5 = String(readExact(input, 32), Charsets.US_ASCII).trim()
            } catch (e: Exception) {
                Log.i(TAG, "no md5 tail (legacy sender), skip verify: ${e.message}")
            } finally {
                sock.soTimeout = 0
            }
            if (expectedMd5 != null) {
                val actual = md5Hex(tmpFile)
                if (!actual.equals(expectedMd5, ignoreCase = true)) {
                    throw IllegalStateException("md5 mismatch: expect=$expectedMd5 actual=$actual")
                }
            }
            // ★ 覆盖式落盘**不允许先 delete 再 rename**（2026-10-17 审计修，与 phone-app
            //   AtomicFileWrite 同一条纪律）：delete 与 rename 之间被杀会让**整个 .aix 包消失**
            //   —— 那是眼镜端 AIUI 应用的唯一安装载体，消失了只能让手机端重传。
            //   改用 POSIX 覆盖式原子 rename（同文件系统内要么成功要么失败，中间态不可观测）：
            //   主路径 Files.move + ATOMIC_MOVE（明确要求覆盖语义），个别 ROM 不支持时
            //   退回 renameTo（同目录 rename 同样是覆盖式原子），并**检查返回值**。
            runCatching {
                java.nio.file.Files.move(
                    tmpFile.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            }.getOrElse {
                if (!tmpFile.renameTo(target)) {
                    tmpFile.copyTo(target, overwrite = true)
                    tmpFile.delete()
                }
            }
            tmp = null // 已移交给 target，finally 不再重复清理
            output.write("OK".toByteArray())
            output.flush()
            lastPackage = target
            Log.i(TAG, "saved ${target.name} ${len}B -> ${target.absolutePath}")
            listener?.onPackageReceived(target)
        } catch (e: Exception) {
            Log.e(TAG, "receive fail: ${e.message}")
            // ★ .tmp 残留清理（2026-10-17 补）：失败路径原先只打日志，<name>.aix.tmp 会
            //   一直躺在 aiui_host 里（每次失败再攒一个）。连同失败 ack 一起补上。
            runCatching { tmp?.delete() }
            runCatching {
                sock.getOutputStream().write("ER".toByteArray())
                sock.getOutputStream().flush()
            }
        } finally {
            synchronized(lock) { clientSocket = null }
            runCatching { sock.close() }
        }
    }

    private fun readExact(input: BufferedInputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r == -1) throw IllegalStateException("conn closed")
            off += r
        }
        return out
    }

    private fun md5Hex(file: File): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = ins.read(buf)
                if (r <= 0) break
                digest.update(buf, 0, r)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        synchronized(lock) { runCatching { clientSocket?.close() } }
        serverSocket = null
        acceptThread = null
    }
}
