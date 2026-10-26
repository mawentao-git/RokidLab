package com.rokidlab.phone.store

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.tools.GlassHudToolProvider
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelHi
import com.rokidlab.phone.design.BrewRed
import com.rokidlab.phone.design.BrewTextBright
import com.rokidlab.phone.glasses.HudDsl
import com.rokidlab.phone.glasses.HudSceneCanvas
import com.rokidlab.phone.glasses.HudThemeScheduler
import com.rokidlab.phone.glasses.HudThemeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * HUD 主题管理子页（2026-10-20，AIUI 应用管理同款形态）：列出已保存主题，
 * 支持 应用到眼镜 / 实时预览 / 导出分享 / 导入 / 删除。
 * 主题数据全部在 [HudThemeStore]（filesDir/hud_themes/），本页只做展示与调度。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HudThemeManagePage(
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var themes by remember { mutableStateOf(HudThemeStore.list(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<HudThemeStore.Theme?>(null) }
    // 眼镜端当前常驻的主题 id（HudThemeStore 持久化；该主题的「应用」切「停止」）
    var activeId by remember { mutableStateOf(HudThemeStore.activeId(ctx)) }
    // 点缩略图 → 实时动画预览（复用 hud:// 路由的画布，非静态图）
    var previewing by remember { mutableStateOf<HudThemeStore.Theme?>(null) }
    // 定时 / 情景联动配置（2026-10-25 P0-3）
    var schedOpen by remember { mutableStateOf(false) }
    val df = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun runOp(block: () -> String) {
        scope.launch {
            busy = true
            val msg = withContext(Dispatchers.IO) { block() }
            busy = false
            toast(msg)
        }
    }

    // 导入 .hudtheme（zip）：拷到 cache 再解析落库（状态只在主线程写）
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                val msg = withContext(Dispatchers.IO) {
                    val tmp = File(ctx.cacheDir, "import_${System.currentTimeMillis()}.hudtheme")
                    runCatching {
                        ctx.contentResolver.openInputStream(uri)?.use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        } ?: return@withContext ctx.getString(R.string.hud_theme_import_read_fail)
                        val t = HudThemeStore.import(ctx, tmp)
                        if (t != null) ctx.getString(R.string.hud_theme_import_ok, t.name)
                        else ctx.getString(R.string.hud_theme_import_bad)
                    }.onFailure { }
                        .getOrElse { ctx.getString(R.string.hud_theme_import_bad) }
                        .also { tmp.delete() }
                }
                themes = HudThemeStore.list(ctx)
                busy = false
                toast(msg)
            }
        }
    }

    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BrewBg)
                .padding(20.dp),
        ) {
            // 顶栏：标题 + 完成
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.hud_theme_title),
                    color = BrewTextBright,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.done), color = BrewChat, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(12.dp))

            // 导入主题包
            Button(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                enabled = !busy,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrewChat, contentColor = BrewBg),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.hud_theme_import_btn), fontWeight = FontWeight.Bold)
            }
            if (busy) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.hud_theme_busy), color = BrewMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))

            // 定时 / 情景联动入口（规则自动切主题，走 applyDsl；协议零改）
            Button(
                onClick = { schedOpen = true },
                enabled = !busy,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrewPanelHi, contentColor = BrewTextBright),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.hud_theme_sched_btn), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))

            if (themes.isEmpty()) {
                Text(
                    text = stringResource(R.string.hud_theme_empty),
                    color = BrewMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 40.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    themes.forEach { t ->
                        val bmp = remember(t.id) { HudThemeStore.previewBitmap(ctx, t.id) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(BrewPanel)
                                .border(1.dp, BrewBorder, RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 静态缩略图（保存时渲染的首帧）；点击 → 实时动画预览
                            Box(
                                modifier = Modifier
                                    .size(width = 66.dp, height = 88.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(androidx.compose.ui.graphics.Color(0xFF050805))
                                    .clickable(enabled = !busy) { previewing = t },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = t.name,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Text("HUD", color = BrewMuted, fontSize = 11.sp)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = t.name,
                                    color = BrewTextBright,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = df.format(Date(t.updatedAt)),
                                    color = BrewMuted,
                                    fontSize = 11.sp,
                                )
                                Spacer(Modifier.height(8.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (activeId == t.id) {
                                        ActionText(stringResource(R.string.hud_theme_stop), !busy, color = BrewRed) {
                                            runOp {
                                                if (GlassHudToolProvider.hideHud(ctx)) {
                                                    activeId = null
                                                    ctx.getString(R.string.hud_theme_stop_ok)
                                                } else {
                                                    ctx.getString(R.string.hud_theme_stop_fail)
                                                }
                                            }
                                        }
                                    } else {
                                        ActionText(stringResource(R.string.hud_theme_apply), !busy) {
                                            runOp {
                                                val err = GlassHudToolProvider.applyDsl(ctx, t.dsl, t.id)
                                                if (err == null) {
                                                    activeId = t.id
                                                    ctx.getString(R.string.hud_theme_apply_ok, t.name)
                                                } else {
                                                    err
                                                }
                                            }
                                        }
                                    }
                                    ActionText(stringResource(R.string.hud_theme_share), !busy) {
                                        runOp {
                                            val f = HudThemeStore.export(ctx, t.id)
                                            if (f == null) {
                                                ctx.getString(R.string.hud_theme_export_fail)
                                            } else {
                                                shareTheme(ctx, f)
                                                ctx.getString(R.string.hud_theme_export_ok)
                                            }
                                        }
                                    }
                                    ActionText(stringResource(R.string.hud_theme_delete), !busy) {
                                        pendingDelete = t
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 删除确认
    pendingDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.hud_theme_delete_confirm_title), color = BrewTextBright) },
            text = { Text(stringResource(R.string.hud_theme_delete_confirm, t.name), color = BrewMuted) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    runOp {
                        val ok = HudThemeStore.delete(ctx, t.id)
                        themes = HudThemeStore.list(ctx)
                        if (ok && activeId == t.id) { // 删的是常驻主题 → 无从「停止」，一并清状态
                            HudThemeStore.setActiveId(ctx, null)
                            activeId = null
                        }
                        if (ok) ctx.getString(R.string.hud_theme_delete_ok)
                        else ctx.getString(R.string.hud_theme_delete_fail)
                    }
                }) { Text(stringResource(R.string.delete), color = BrewChat) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.chat_key_dialog_cancel), color = BrewMuted)
                }
            },
        )
    }

    // 实时动画预览（与对话流预览卡同源画布）
    previewing?.let { t ->
        val scene = remember(t.id) { runCatching { HudDsl.parse(t.dsl) }.getOrNull() }
        Dialog(
            onDismissRequest = { previewing = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BrewBg.copy(alpha = 0.97f))
                    .clickable(onClick = { previewing = null })
                    .padding(horizontal = 12.dp, vertical = 44.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = t.name,
                    color = BrewTextBright,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                if (scene != null) {
                    HudSceneCanvas(scene, Modifier.fillMaxWidth())
                } else {
                    Text(stringResource(R.string.hud_theme_dsl_bad), color = BrewMuted, fontSize = 13.sp)
                }
            }
        }
    }

    // 定时 / 情景联动（2026-10-25 P0-3）：规则存 HudThemeScheduler，运行时自动 applyDsl 切主题
    if (schedOpen) {
        HudThemeSchedulerDialog(themeNames = themes.map { it.name }) { schedOpen = false }
    }
}

/**
 * 主题定时 / 情景联动配置对话框：总开关 + 规则列表 + 规则编辑器。
 * 运行时（[HudThemeScheduler]）在 App 级注册：30s tick + HudDataHub 数据变更即评估，
 * 边沿触发（只有期望主题变化才下发）；无命中不动，用户手动应用的主题不被抢回。
 */
@Composable
private fun HudThemeSchedulerDialog(
    themeNames: List<String>,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(HudThemeScheduler.enabled(ctx)) }
    var rules by remember { mutableStateOf(HudThemeScheduler.rules(ctx)) }
    var editing by remember { mutableStateOf<HudThemeScheduler.Rule?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BrewBg)
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.hud_theme_sched_title),
                    color = BrewTextBright,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.done), color = BrewChat, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.hud_theme_sched_enable),
                    color = BrewMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        HudThemeScheduler.setEnabled(ctx, it)
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (rules.isEmpty()) {
                    Text(stringResource(R.string.hud_theme_sched_empty), color = BrewMuted, fontSize = 13.sp)
                } else {
                    rules.forEach { r ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(BrewPanel)
                                .border(1.dp, BrewBorder, RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = r.themeName,
                                    color = BrewTextBright,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = if (r.kind == HudThemeScheduler.KIND_STATE) {
                                        stringResource(R.string.hud_theme_sched_rule_state, r.cond)
                                    } else {
                                        stringResource(
                                            R.string.hud_theme_sched_rule_time,
                                            HudThemeScheduler.fmtMin(r.startMin),
                                            HudThemeScheduler.fmtMin(r.endMin),
                                        )
                                    },
                                    color = BrewMuted,
                                    fontSize = 11.sp,
                                )
                            }
                            ActionText(stringResource(R.string.hud_theme_sched_edit_title), true) {
                                editing = r
                                editingIsNew = false
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        editing = HudThemeScheduler.Rule(
                            id = HudThemeScheduler.newRuleId(),
                            kind = HudThemeScheduler.KIND_TIME,
                            themeName = themeNames.firstOrNull() ?: "",
                        )
                        editingIsNew = true
                    },
                    enabled = themeNames.isNotEmpty(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrewChat, contentColor = BrewBg),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.hud_theme_sched_add), fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    editing?.let { initial ->
        HudRuleEditor(
            initial = initial,
            isNew = editingIsNew,
            themeNames = themeNames,
            onDismiss = { editing = null },
            onSave = { rule ->
                HudThemeScheduler.saveRule(ctx, rule)
                rules = HudThemeScheduler.rules(ctx)
                editing = null
                Toast.makeText(ctx, ctx.getString(R.string.hud_theme_sched_saved), Toast.LENGTH_SHORT).show()
            },
            onDelete = { id ->
                HudThemeScheduler.deleteRule(ctx, id)
                rules = HudThemeScheduler.rules(ctx)
                editing = null
            },
        )
    }
}

/** 规则编辑器：主题 + 定时（HH:mm 每日窗口，支持跨零点）/ 情景（bindOn 同款条件）二选一 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HudRuleEditor(
    initial: HudThemeScheduler.Rule,
    isNew: Boolean,
    themeNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (HudThemeScheduler.Rule) -> Unit,
    onDelete: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var kind by remember { mutableStateOf(initial.kind) }
    var themeName by remember { mutableStateOf(initial.themeName) }
    var start by remember {
        mutableStateOf(if (initial.kind == HudThemeScheduler.KIND_TIME) HudThemeScheduler.fmtMin(initial.startMin) else "22:00")
    }
    var end by remember {
        mutableStateOf(if (initial.kind == HudThemeScheduler.KIND_TIME) HudThemeScheduler.fmtMin(initial.endMin) else "07:00")
    }
    var cond by remember { mutableStateOf(initial.cond.ifEmpty { "ai_state:busy" }) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hud_theme_sched_edit_title), color = BrewTextBright) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudKindChip(
                        stringResource(R.string.hud_theme_sched_kind_time),
                        kind == HudThemeScheduler.KIND_TIME,
                    ) { kind = HudThemeScheduler.KIND_TIME }
                    HudKindChip(
                        stringResource(R.string.hud_theme_sched_kind_state),
                        kind == HudThemeScheduler.KIND_STATE,
                    ) { kind = HudThemeScheduler.KIND_STATE }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = themeName,
                    onValueChange = { themeName = it },
                    label = { Text(stringResource(R.string.hud_theme_sched_theme)) },
                    singleLine = true,
                    colors = hudFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (themeNames.isNotEmpty()) {
                    Text(stringResource(R.string.hud_theme_sched_theme_pick), color = BrewMuted, fontSize = 11.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        themeNames.forEach { n -> HudKindChip(n, themeName == n) { themeName = n } }
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (kind == HudThemeScheduler.KIND_TIME) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = start,
                            onValueChange = { start = it },
                            label = { Text(stringResource(R.string.hud_theme_sched_start)) },
                            singleLine = true,
                            colors = hudFieldColors(),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = end,
                            onValueChange = { end = it },
                            label = { Text(stringResource(R.string.hud_theme_sched_end)) },
                            singleLine = true,
                            colors = hudFieldColors(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 跨零点提示（开始 > 结束 = 跨夜，如 22:00 → 07:00）
                    Text(
                        text = stringResource(R.string.hud_theme_sched_rule_time, "22:00", "07:00") +
                            "（开始晚于结束 = 跨零点）",
                        color = BrewMuted,
                        fontSize = 11.sp,
                    )
                } else {
                    OutlinedTextField(
                        value = cond,
                        onValueChange = { cond = it },
                        label = { Text(stringResource(R.string.hud_theme_sched_cond)) },
                        singleLine = true,
                        colors = hudFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.hud_theme_sched_cond_hint), color = BrewMuted, fontSize = 11.sp)
                }
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = BrewRed, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val rule = if (kind == HudThemeScheduler.KIND_TIME) {
                    val s = parseHhMm(start)
                    val e = parseHhMm(end)
                    if (s == null || e == null) {
                        error = ctx.getString(R.string.hud_theme_sched_time_bad)
                        return@TextButton
                    }
                    HudThemeScheduler.Rule(
                        id = initial.id, kind = kind, themeName = themeName.trim(), startMin = s, endMin = e,
                    )
                } else {
                    HudThemeScheduler.Rule(
                        id = initial.id, kind = kind, themeName = themeName.trim(), cond = cond.trim(),
                    )
                }
                val v = HudThemeScheduler.validate(ctx, rule)
                if (v != null) {
                    error = v
                    return@TextButton
                }
                onSave(rule)
            }) { Text(stringResource(R.string.hud_theme_sched_save), color = BrewChat) }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = { onDelete(initial.id) }) {
                        Text(stringResource(R.string.hud_theme_sched_delete_rule), color = BrewRed)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.chat_key_dialog_cancel), color = BrewMuted)
                }
            }
        },
    )
}

/** "HH:mm" → 分钟（0..1439）；非法返回 null */
private fun parseHhMm(s: String): Int? {
    val m = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").find(s.trim()) ?: return null
    return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
}

/** 规则类型/主题名小chip（选中 = 聊天色底） */
@Composable
private fun HudKindChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (selected) BrewBg else BrewTextBright,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) BrewChat else BrewPanel)
            .border(1.dp, if (selected) BrewChat else BrewBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** 规则编辑器输入框配色（Lab 视觉口径，同 ProviderManagePage） */
@Composable
private fun hudFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BrewChat,
    unfocusedBorderColor = BrewBorder,
    focusedTextColor = BrewTextBright,
    unfocusedTextColor = BrewTextBright,
    cursorColor = BrewChat,
)

/** 主题包分享（FileProvider cache-path 已覆盖 cacheDir 全目录） */
private fun shareTheme(ctx: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(intent, file.name))
}
