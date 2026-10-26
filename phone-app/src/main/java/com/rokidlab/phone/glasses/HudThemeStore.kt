package com.rokidlab.phone.glasses

import android.graphics.Bitmap
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * HUD 主题库（2026-10-20，AIUI 主题管理同款形态）：把对话里生成的 HUD 场景存档成
 * 命名主题，可再次应用 / 删除 / 导出分享 / 导入。
 *
 * 存储：`filesDir/hud_themes/<id>/`
 *  - `theme.json`：{id,name,dsl,createdAt,updatedAt}（dsl = 场景 DSL 原文，唯一真相源）
 *  - `image_<imgId>.jpg`：场景 image 元素引用的登记图副本（hud_image 两步式的持久化，
 *    应用/导出时随主题恢复；无图场景没有这些文件）
 *  - `preview.png`：静态首帧缩略图（列表展示用；渲染自 [HudRenderer.render]）
 *
 * 分享包格式（`.hudtheme`，本质 zip，规范见 docs/hud-dsl.md「主题包」一节）：
 *  - `manifest.json`：{format:"hudtheme", v:1, name, createdAt, app}
 *  - `scene.json`：场景 DSL 原文
 *  - `preview.png`：静态首帧（可选；缺省导入方自己渲染）
 *
 * 线程模型：全部 IO 在调用方 worker 线程执行（工具执行线程 / Compose IO scope），
 * 本类不加锁 —— 单写者（glass_hud 工具串行执行）+ 设置页同线程操作，无并发竞争。
 */
internal object HudThemeStore {
    private const val TAG = "HudThemeStore"
    private const val FORMAT = "hudtheme"
    private const val FORMAT_V = 1
    private const val PREFS = "hud_themes_prefs"
    private const val K_ACTIVE_ID = "active_id"

    data class Theme(
        val id: String,
        val name: String,
        val dsl: String,
        val createdAt: Long,
        val updatedAt: Long,
    )

    private fun root(context: android.content.Context): File =
        File(context.filesDir, "hud_themes").apply { mkdirs() }

    // ── 存 / 删 / 查 ────────────────────────────────────────────

    /**
     * 保存主题：同名主题覆盖更新（保留原 id/createdAt），否则新建。返回落库后的主题。
     * [images]：场景 image 元素引用的登记图（id→位图，调用方从登记表快照收集）——
     * 落盘成 `image_<id>.jpg` 副本，缩略图渲染也用它（场景含图时预览不再只有占位框）。
     */
    fun save(
        context: android.content.Context,
        name: String,
        dsl: String,
        images: Map<String, Bitmap> = emptyMap(),
    ): Theme {
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty()) { "主题名不能为空" }
        require(dsl.isNotBlank()) { "场景 DSL 不能为空" }
        HudDsl.parse(dsl) // 不合法直接抛（消息回给模型自我修正）
        val now = System.currentTimeMillis()
        val existing = list(context).firstOrNull { it.name == trimmedName }
        val theme = Theme(
            id = existing?.id ?: "t${now}",
            name = trimmedName,
            dsl = dsl,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        val dir = File(root(context), theme.id).apply { mkdirs() }
        // 图片副本：同名覆盖先清旧，防止残留已被替换的图（新场景可能不引用或引用别的图）
        dir.listFiles()?.filter { it.name.startsWith("image_") }?.forEach { it.delete() }
        images.forEach { (imgId, bmp) ->
            runCatching {
                File(dir, "image_$imgId.jpg").outputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
                }
            }.onFailure { Log.w(TAG, "theme image save failed ($imgId): ${it.message}") }
        }
        JSONObject().apply {
            put("id", theme.id)
            put("name", theme.name)
            put("dsl", theme.dsl)
            put("createdAt", theme.createdAt)
            put("updatedAt", theme.updatedAt)
        }.let { File(dir, "theme.json").writeText(it.toString()) }
        // 缩略图（渲染失败不致命：列表降级为无图占位）
        runCatching {
            val bmp = HudRenderer.render(HudDsl.parse(dsl), images = images)
            File(dir, "preview.png").outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            bmp.recycle()
        }.onFailure { Log.w(TAG, "theme preview render failed: ${it.message}") }
        return theme
    }

    fun delete(context: android.content.Context, id: String): Boolean =
        File(root(context), id).takeIf { it.isDirectory }?.deleteRecursively() ?: false

    fun list(context: android.content.Context): List<Theme> {
        val out = ArrayList<Theme>()
        root(context).listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            runCatching {
                val o = JSONObject(File(dir, "theme.json").readText())
                Theme(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    dsl = o.getString("dsl"),
                    createdAt = o.optLong("createdAt"),
                    updatedAt = o.optLong("updatedAt"),
                )
            }.onSuccess { out.add(it) }
                .onFailure { Log.w(TAG, "skip broken theme dir ${dir.name}: ${it.message}") }
        }
        return out.sortedByDescending { it.updatedAt }
    }

    fun find(context: android.content.Context, name: String): Theme? =
        list(context).firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    /**
     * 眼镜端当前常驻的主题 id（最后经主题路径 apply 成功者）。管理页据此把「应用」
     * 切成「停止」。眼镜上 HUD 由 RokidLink 进程持有，手机进程重启不丢 → 状态落 prefs。
     * 对话 apply/try（非主题场景）与 hide 成功会清空 —— 眼镜上挂的不是本库主题时无「停止」语义。
     */
    fun activeId(context: android.content.Context): String? =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(K_ACTIVE_ID, null)

    fun setActiveId(context: android.content.Context, id: String?) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putString(K_ACTIVE_ID, id)
            .apply()
    }

    fun previewBitmap(context: android.content.Context, id: String): Bitmap? =
        runCatching {
            val f = File(File(root(context), id), "preview.png")
            if (!f.exists()) return@runCatching null
            android.graphics.BitmapFactory.decodeFile(f.absolutePath)
        }.getOrNull()

    /** 主题落盘的图片副本（imgId→JPEG 原始字节，应用时零重压直推眼镜 / 导出打包）；无图返回空 map */
    fun themeImageFiles(context: android.content.Context, id: String): Map<String, ByteArray> =
        File(root(context), id).takeIf { it.isDirectory }
            ?.listFiles()
            ?.filter { it.name.startsWith("image_") && it.name.endsWith(".jpg") }
            ?.mapNotNull { f ->
                val imgId = f.name.removePrefix("image_").removeSuffix(".jpg")
                if (imgId.isEmpty()) null else imgId to f.readBytes()
            }?.toMap()
            ?: emptyMap()

    // ── 主题包（.hudtheme）导出 / 导入 ─────────────────────────

    /** 导出为主题包文件（cacheDir 下，调用方负责分享后清理或移到公开目录）。 */
    fun export(context: android.content.Context, id: String): File? {
        val theme = list(context).firstOrNull { it.id == id } ?: return null
        val zip = File(context.cacheDir, "${theme.name}.hudtheme")
        runCatching {
            ZipOutputStream(zip.outputStream().buffered()).use { z ->
                z.putNextEntry(ZipEntry("manifest.json"))
                z.write(
                    JSONObject().apply {
                        put("format", FORMAT)
                        put("v", FORMAT_V)
                        put("name", theme.name)
                        put("createdAt", theme.createdAt)
                        put("app", "RokidLab")
                    }.toString().toByteArray(),
                )
                z.closeEntry()
                z.putNextEntry(ZipEntry("scene.json"))
                z.write(theme.dsl.toByteArray())
                z.closeEntry()
                previewBitmap(context, id)?.let { bmp ->
                    z.putNextEntry(ZipEntry("preview.png"))
                    val buf = java.io.ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.PNG, 90, buf)
                    bmp.recycle()
                    z.write(buf.toByteArray())
                    z.closeEntry()
                }
                // 图片副本（可选条目；旧版导入方显式按名读取，未知条目自动忽略 → 向后兼容）
                themeImageFiles(context, id).forEach { (imgId, bytes) ->
                    z.putNextEntry(ZipEntry("image_$imgId.jpg"))
                    z.write(bytes)
                    z.closeEntry()
                }
            }
        }.onFailure {
            Log.w(TAG, "export failed: ${it.message}")
            zip.delete()
            return null
        }
        return zip
    }

    /** 从主题包导入（新 id 落库，重名自动加后缀）。返回导入的主题；包不合法返回 null。 */
    fun import(context: android.content.Context, file: File): Theme? = runCatching {
        ZipFile(file).use { z ->
            val manifest = z.getEntry("manifest.json")?.let { e ->
                JSONObject(z.getInputStream(e).bufferedReader().readText())
            }
            if (manifest?.optString("format") != FORMAT) return@runCatching null
            val dsl = z.getEntry("scene.json")?.let { e ->
                z.getInputStream(e).bufferedReader().readText()
            } ?: return@runCatching null
            val name = manifest.optString("name", "导入主题").ifBlank { "导入主题" }
            // 位图资源（可选；旧包没有 → 应用时登记表/眼镜端 miss 画占位框兜底）
            val images = HashMap<String, Bitmap>()
            val entries = z.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.name.startsWith("image_") && e.name.endsWith(".jpg")) {
                    val imgId = e.name.removePrefix("image_").removeSuffix(".jpg")
                    if (imgId.isNotEmpty()) {
                        android.graphics.BitmapFactory.decodeStream(z.getInputStream(e))
                            ?.let { images[imgId] = it }
                    }
                }
            }
            val finalName = dedupName(context, name)
            save(context, finalName, dsl, images)
        }
    }.onFailure { Log.w(TAG, "import failed: ${it.message}") }.getOrNull()

    /** 重名消歧：「贾维斯」→「贾维斯 (2)」→「贾维斯 (3)」… */
    private fun dedupName(context: android.content.Context, name: String): String {
        val names = list(context).map { it.name }.toHashSet()
        if (name !in names) return name
        var i = 2
        while ("$name ($i)" in names) i++
        return "$name ($i)"
    }
}
