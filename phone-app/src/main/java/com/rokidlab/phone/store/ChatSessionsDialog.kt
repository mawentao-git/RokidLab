package com.rokidlab.phone.store

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.SkillRegistry
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.mcp.McpRegistry
import com.rokidlab.phone.design.BrewAmber
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewDim
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelHi
import com.rokidlab.phone.design.BrewRed
import com.rokidlab.phone.design.BrewTextBright
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 会话列表（全屏对话）。
 *
 * 多会话改造前，乐奇聊天只有一份全局历史：想换话题只能「清空对话」，
 * 且不可逆。这里给出会话的增删改查入口 —— 也是 [ChatStateHolder.sessions] 的唯一 UI。
 *
 * 入口在 [ChatHeader] 的标题区（点击标题打开）：标题栏宽度实测只有 369dp，
 * 已被 5 个 IconButton 占满，再插一个按钮必然让副标题折行。
 */
@Composable
internal fun ChatSessionsDialog(
    onDismiss: () -> Unit,
    /**
     * 打开「对话记录图」（节点连接图 + 搜索 + 导出 + 逐条复制/编辑/删除）。
     *
     * 由调用方（`ChatScreen`）host 那个对话框而不是在这里 host：记录视图里的
     * 「编辑重发」要落到聊天页的**输入框**上，那是 ChatScreen 的状态。
     */
    onOpenRecords: (ChatSessionMeta) -> Unit = {},
    /** 编辑该会话的附加提示词（见 [ChatSessionMeta.systemPrompt]） */
    onEditPrompt: (ChatSessionMeta) -> Unit = {},
    /**
     * 生成/答题进行中（2026-10-17 审计修）：置灰会话结构操作。
     *
     * ChatStateHolder 侧的 `blockedByGenerating` 是权威闸门（返回即无效），
     * 这里补 UI 反馈 —— 否则用户点了没反应，只会以为界面卡了。
     */
    busy: Boolean = false,
) {
    val sessions = ChatStateHolder.sessions
    val groups = ChatStateHolder.groups
    val currentId = ChatStateHolder.currentSessionId

    var renaming by remember { mutableStateOf<ChatSessionMeta?>(null) }
    var deleting by remember { mutableStateOf<ChatSessionMeta?>(null) }
    var moving by remember { mutableStateOf<ChatSessionMeta?>(null) }
    var creatingGroup by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<ChatGroupMeta?>(null) }
    var deletingGroup by remember { mutableStateOf<ChatGroupMeta?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BrewBg)
                .systemBarsPadding(),
        ) {
            // 顶栏：标题 + 新建 + 关闭
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BrewPanel)
                    .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.chat_sessions_title),
                    color = BrewChat,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    // 无参 = 继承当前会话所在分组（三期起）：在「写小说」分组里新建，
                    // 新对话就还在这个分组（人设与能力收窄跟着走）；未分组会话新建的仍落未分组
                    onClick = { ChatStateHolder.newSession() },
                    enabled = !busy,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = BrewChat,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.chat_sessions_new),
                        color = BrewChat,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                // 「新分组」：分组 = 一套独立 agent 配置（人设/模型覆盖），分组间数据不互通。
                // 分组操作不参与 busy 置灰 —— 它只改索引元数据，不碰在途轮要写的消息文件。
                TextButton(
                    onClick = { creatingGroup = true },
                ) {
                    Icon(
                        imageVector = Icons.Filled.CreateNewFolder,
                        contentDescription = null,
                        tint = BrewChat,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.chat_groups_new),
                        color = BrewChat,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.chat_sessions_close),
                        tint = BrewMuted,
                    )
                }
            }

            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.chat_sessions_empty),
                        color = BrewMuted,
                        fontSize = 13.sp,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp,
                        vertical = 10.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val hasGroups = groups.isNotEmpty()
                    if (!hasGroups) {
                        // 无分组：与改造前完全一致的扁平列表 —— 分组是可选能力，
                        // 一个分组都没建过的用户看到的界面与升级前逐像素一致。
                        items(sessions, key = { it.id }) { s ->
                            SessionRow(
                                meta = s,
                                active = s.id == currentId,
                                enabled = !busy,
                                onOpen = {
                                    ChatStateHolder.switchTo(s.id)
                                    onDismiss()
                                },
                                onRename = { renaming = s },
                                onDelete = { deleting = s },
                                // 按用户要求加在「删除」**后面**（原来删除是最后一个）
                                onOpenRecords = { onOpenRecords(s) },
                                onEditPrompt = { onEditPrompt(s) },
                                onMove = null,
                            )
                        }
                    } else {
                        // 分组分区渲染：每个分组（按创建顺序）一段，最后「未分组」一段。
                        // 会话在分区内的顺序沿用 sessions 的全局倒序（updatedAt），不再二次排序。
                        val orderedGroups = ChatGroupStore.sorted(groups.toList())
                        orderedGroups.forEach { g ->
                            item(key = "group:${g.id}") {
                                GroupHeaderRow(
                                    group = g,
                                    onAddSession = {
                                        ChatStateHolder.newSession(g.id)
                                        onDismiss()
                                    },
                                    onEdit = { editingGroup = g },
                                    onDelete = { deletingGroup = g },
                                )
                            }
                            val inGroup = sessions.filter { it.groupId == g.id }
                            items(inGroup, key = { it.id }) { s ->
                                SessionRow(
                                    meta = s,
                                    active = s.id == currentId,
                                    enabled = !busy,
                                    onOpen = {
                                        ChatStateHolder.switchTo(s.id)
                                        onDismiss()
                                    },
                                    onRename = { renaming = s },
                                    onDelete = { deleting = s },
                                    onOpenRecords = { onOpenRecords(s) },
                                    onEditPrompt = { onEditPrompt(s) },
                                    onMove = { moving = s },
                                )
                            }
                        }
                        // 「未分组」段：未归属的会话都落这里；段头的「＋」显式建未分组会话
                        // （无参 newSession() 自三期起继承当前会话所在分组，不能用在这里）
                        item(key = "group:__ungrouped__") {
                            GroupHeaderRow(
                                group = null,
                                onAddSession = {
                                    ChatStateHolder.newSession(ChatStateHolder.UNGROUPED_ID)
                                    onDismiss()
                                },
                                onEdit = null,
                                onDelete = null,
                            )
                        }
                        items(sessions.filter { it.groupId.isEmpty() }, key = { it.id }) { s ->
                            SessionRow(
                                meta = s,
                                active = s.id == currentId,
                                enabled = !busy,
                                onOpen = {
                                    ChatStateHolder.switchTo(s.id)
                                    onDismiss()
                                },
                                onRename = { renaming = s },
                                onDelete = { deleting = s },
                                onOpenRecords = { onOpenRecords(s) },
                                onEditPrompt = { onEditPrompt(s) },
                                onMove = { moving = s },
                            )
                        }
                    }
                }
            }
        }
    }

    renaming?.let { target ->
        RenameSessionDialog(
            initial = target.title,
            onConfirm = { name ->
                ChatStateHolder.renameSession(target.id, name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.chat_sessions_delete_title)) },
            text = {
                Text(
                    stringResource(R.string.chat_sessions_delete_message, target.title),
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        ChatStateHolder.deleteSession(target.id)
                        deleting = null
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrewRed,
                        contentColor = BrewBg,
                    ),
                ) {
                    Text(stringResource(R.string.chat_common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.chat_common_cancel))
                }
            },
        )
    }

    // ===== 分组对话框（创建 / 编辑 / 删除 / 移动） =====

    if (creatingGroup) {
        GroupEditorDialog(
            initialName = "",
            initialPrompt = "",
            initialDisabledTools = emptySet(),
            initialDisabledMcpServers = emptySet(),
            initialDisabledSkills = emptySet(),
            onConfirm = { r ->
                ChatStateHolder.createGroup(
                    r.name, r.prompt,
                    r.disabledTools, r.disabledMcpServers, r.disabledSkills,
                )
                creatingGroup = false
            },
            onDismiss = { creatingGroup = false },
        )
    }

    editingGroup?.let { g ->
        GroupEditorDialog(
            initialName = g.name,
            initialPrompt = g.systemPrompt,
            initialDisabledTools = g.disabledTools,
            initialDisabledMcpServers = g.disabledMcpServers,
            initialDisabledSkills = g.disabledSkills,
            onConfirm = { r ->
                ChatStateHolder.updateGroup(
                    g.id, r.name, r.prompt,
                    r.disabledTools, r.disabledMcpServers, r.disabledSkills,
                )
                editingGroup = null
            },
            onDismiss = { editingGroup = null },
        )
    }

    deletingGroup?.let { g ->
        AlertDialog(
            onDismissRequest = { deletingGroup = null },
            title = { Text(stringResource(R.string.chat_groups_delete_title)) },
            text = {
                Text(
                    stringResource(R.string.chat_groups_delete_message, g.name),
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        ChatStateHolder.deleteGroup(g.id)
                        deletingGroup = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrewRed,
                        contentColor = BrewBg,
                    ),
                ) {
                    Text(stringResource(R.string.chat_common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingGroup = null }) {
                    Text(stringResource(R.string.chat_common_cancel))
                }
            },
        )
    }

    moving?.let { target ->
        MoveSessionDialog(
            currentGroupId = target.groupId,
            onConfirm = { gid ->
                ChatStateHolder.moveSessionToGroup(target.id, gid)
                moving = null
            },
            onDismiss = { moving = null },
        )
    }
}

/** 单条会话卡片 */
@Composable
private fun SessionRow(
    meta: ChatSessionMeta,
    active: Boolean,
    enabled: Boolean = true,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpenRecords: () -> Unit,
    onEditPrompt: () -> Unit,
    /** 移动到分组；null = 隐藏入口（没有任何分组时，移了也没地方去） */
    onMove: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) BrewPanelHi else BrewPanel)
            .border(
                width = 1.dp,
                // 当前会话用聊天主色描边 + 左侧琥珀竖条双重标识：
                // 只靠底色深浅区分，在弱光/低对比屏上几乎看不出
                color = if (active) BrewChat.copy(alpha = 0.55f) else BrewBorder,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(enabled = enabled) { onOpen() }
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(BrewAmber),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = meta.title,
                    color = BrewTextBright,
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            val preview = meta.preview
            if (preview.isNotBlank()) {
                Text(
                    text = preview,
                    color = BrewMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Text(
                text = stringResource(R.string.chat_sessions_count, meta.messageCount) +
                    " · " + formatSessionTime(meta.updatedAt),
                color = BrewDim,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        IconButton(onClick = onRename, modifier = Modifier.size(38.dp)) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = stringResource(R.string.chat_sessions_rename),
                tint = BrewMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.size(38.dp)) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.chat_sessions_delete),
                tint = BrewMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        // 「对话记录图」：把该会话的事件流画成节点连接图（对话内容 / 工具调用 / 执行过程），
        // 并提供搜索、导出 md、逐条复制/编辑/删除。图标用 AccountTree（真正的"节点树"语义）。
        IconButton(onClick = onOpenRecords, modifier = Modifier.size(38.dp)) {
            Icon(
                imageVector = Icons.Filled.AccountTree,
                contentDescription = stringResource(R.string.chat_sessions_records),
                tint = BrewMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        // 「本会话提示词」：为该会话单独指定附加要求（追加在人设之后）
        IconButton(onClick = onEditPrompt, modifier = Modifier.size(38.dp)) {
            Icon(
                imageVector = Icons.Filled.Tune,
                contentDescription = stringResource(R.string.chat_sessions_prompt),
                tint = if (meta.systemPrompt.isNotBlank()) BrewChat else BrewMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        // 「移动到分组」：把会话挪进/挪出分组（只在存在分组时显示）
        if (onMove != null) {
            IconButton(onClick = onMove, enabled = enabled, modifier = Modifier.size(38.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.DriveFileMove,
                    contentDescription = stringResource(R.string.chat_groups_move),
                    tint = BrewMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * 本会话提示词（[ChatSessionMeta.systemPrompt]）的编辑对话框。
 *
 * 明确写出"追加而非覆盖"：用户以为自己在改人设时可能写出"忽略以上所有指令"这类内容，
 * 而我们的实现是**追加**（保住工具使用准则），先说清楚比事后解释便宜。
 */
@Composable
internal fun SessionPromptDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_prompt_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 3,
                    maxLines = 6,
                    placeholder = {
                        Text(
                            stringResource(R.string.chat_prompt_placeholder),
                            color = BrewMuted,
                            fontSize = 12.sp,
                        )
                    },
                    textStyle = TextStyle(color = BrewTextBright, fontSize = 14.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrewChat,
                        unfocusedBorderColor = BrewBorder,
                        focusedTextColor = BrewTextBright,
                        unfocusedTextColor = BrewTextBright,
                        cursorColor = BrewChat,
                    ),
                )
                Text(
                    text = stringResource(R.string.chat_prompt_hint),
                    color = BrewMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrewChat,
                    contentColor = BrewBg,
                ),
            ) {
                Text(stringResource(R.string.chat_common_confirm))
            }
        },
        dismissButton = {
            Row {
                if (text.isNotBlank()) {
                    TextButton(onClick = { text = ""; onConfirm("") }) {
                        Text(stringResource(R.string.chat_prompt_clear), color = BrewRed)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.chat_common_cancel))
                }
            }
        },
    )
}

/**
 * 分组区头：分组名 + 「在该分组新建对话」+（非未分组时）编辑 / 删除。
 *
 * `group == null` 表示「未分组」段头 —— 它没有编辑/删除（未分组不是分组，
 * 只是"没归属"的兜底区），只保留「新建对话」快捷入口。
 */
@Composable
private fun GroupHeaderRow(
    group: ChatGroupMeta?,
    onAddSession: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group?.name ?: stringResource(R.string.chat_groups_ungrouped),
            color = BrewDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onAddSession, modifier = Modifier.size(30.dp)) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.chat_groups_new_session),
                tint = BrewMuted,
                modifier = Modifier.size(16.dp),
            )
        }
        if (group != null && onEdit != null) {
            IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.chat_groups_edit_title),
                    tint = BrewMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (group != null && onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.chat_sessions_delete),
                    tint = BrewMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * 分组编辑的提交结果（5 个字段打包成一个值对象 —— 位置参数的 lambda
 * 迟早传错一对，尤其是三个 Set 类型相同、编译器一声不响）。
 */
private data class GroupEditResult(
    val name: String,
    val prompt: String,
    val disabledTools: Set<String>,
    val disabledMcpServers: Set<String>,
    val disabledSkills: Set<String>,
)

/**
 * 分组的创建 / 编辑对话框（同一套表单，创建时字段全空）。
 *
 * 字段分两组：基本（名称 + 提示词）→ **能力收窄**（工具 / MCP server / 技能
 * 三张排除表）。
 *
 * 能力收窄的交互约束：
 *  - 勾选 = 停用（排除语义），不是勾选 = 启用 —— 与「只收窄不放宽」的实现同向，
 *    用户看到的状态就是实际生效的状态；
 *  - MCP 走 **server 粒度**（工具名是运行期才有的 wire name，逐个列没意义）；
 *  - 工具列表带搜索（全量 100+ 项），MCP/技能为空时给去向提示而不是空列表。
 *
 * 布局：text 槽直接放 LazyColumn（限高 440dp）——**不要**在外层套 verticalScroll，
 * 那会让 LazyColumn 拿到无穷高度直接崩；所有字段与勾选行都是同一个 lazy list 的 item。
 */
@Composable
private fun GroupEditorDialog(
    initialName: String,
    initialPrompt: String,
    initialDisabledTools: Set<String>,
    initialDisabledMcpServers: Set<String>,
    initialDisabledSkills: Set<String>,
    onConfirm: (GroupEditResult) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var prompt by remember { mutableStateOf(initialPrompt) }
    var disabledTools by remember { mutableStateOf(initialDisabledTools) }
    var disabledMcpServers by remember { mutableStateOf(initialDisabledMcpServers) }
    var disabledSkills by remember { mutableStateOf(initialDisabledSkills) }
    var toolQuery by remember { mutableStateOf("") }

    val ctx = LocalContext.current
    // 工具清单：排除 MCP（走 server 粒度勾选）与 hidden（设置页同样不展示的内部机制）
    val toolMetas = remember {
        ToolRegistry.toolList.filter {
            !it.hidden && it.category != ToolRegistry.ToolCategory.MCP
        }
    }
    val mcpServers = remember { McpRegistry.allStates() }
    // 技能清单要读盘 + 解析每个 SKILL.md（大技能好几十 KB）—— 必须后台加载，
    // 主线程做会让对话框弹出时掉帧
    val skills by produceState<List<SkillRegistry.SkillMeta>>(initialValue = emptyList()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            SkillRegistry.listSkills(ctx)
        }
    }
    val filteredTools = remember(toolMetas, toolQuery) {
        val q = toolQuery.trim()
        if (q.isEmpty()) {
            toolMetas
        } else {
            toolMetas.filter {
                it.name.contains(q, ignoreCase = true) ||
                    it.displayName(ctx).contains(q, ignoreCase = true)
            }
        }
    }

    @Composable
    fun field(
        value: String,
        onValueChange: (String) -> Unit,
        label: String,
        singleLine: Boolean = true,
        minLines: Int = 1,
        maxLines: Int = 1,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label, color = BrewMuted, fontSize = 12.sp) },
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = TextStyle(color = BrewTextBright, fontSize = 13.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = BrewChat,
                unfocusedBorderColor = BrewBorder,
                focusedTextColor = BrewTextBright,
                unfocusedTextColor = BrewTextBright,
                cursorColor = BrewChat,
            ),
        )
    }

    @Composable
    fun sectionTitle(text: String) {
        Text(
            text = text,
            color = BrewDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_groups_edit_title)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        field(name, { name = it }, stringResource(R.string.chat_groups_name_label))
                        field(
                            prompt,
                            { prompt = it },
                            stringResource(R.string.chat_groups_prompt_label),
                            singleLine = false,
                            minLines = 2,
                            maxLines = 4,
                        )
                        Text(
                            text = stringResource(R.string.chat_groups_prompt_hint),
                            color = BrewMuted,
                            fontSize = 11.sp,
                        )
                        sectionTitle(stringResource(R.string.chat_groups_cap_section))
                        Text(
                            text = stringResource(R.string.chat_groups_cap_hint),
                            color = BrewMuted,
                            fontSize = 11.sp,
                        )
                    }
                }
                // ── MCP server 收窄 ──
                item {
                    sectionTitle(
                        stringResource(R.string.chat_groups_cap_mcp, disabledMcpServers.size)
                    )
                }
                if (mcpServers.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.chat_groups_cap_mcp_empty),
                            color = BrewMuted,
                            fontSize = 11.sp,
                        )
                    }
                } else {
                    items(mcpServers, key = { it.config.id }) { st ->
                        NarrowOption(
                            title = st.config.name,
                            subtitle = if (st.connected) "已连接 · ${st.tools.size} 个工具" else "未连接",
                            checked = st.config.id in disabledMcpServers,
                            onToggle = { on ->
                                disabledMcpServers = if (on) {
                                    disabledMcpServers + st.config.id
                                } else {
                                    disabledMcpServers - st.config.id
                                }
                            },
                        )
                    }
                }
                // ── 技能收窄 ──
                item {
                    sectionTitle(
                        stringResource(R.string.chat_groups_cap_skills, disabledSkills.size)
                    )
                }
                if (skills.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.chat_groups_cap_skills_empty),
                            color = BrewMuted,
                            fontSize = 11.sp,
                        )
                    }
                } else {
                    items(skills, key = { it.name }) { s ->
                        NarrowOption(
                            title = s.name,
                            subtitle = s.description.take(60),
                            checked = s.name in disabledSkills,
                            onToggle = { on ->
                                disabledSkills = if (on) {
                                    disabledSkills + s.name
                                } else {
                                    disabledSkills - s.name
                                }
                            },
                        )
                    }
                }
                // ── 工具收窄（带搜索） ──
                item {
                    sectionTitle(
                        stringResource(R.string.chat_groups_cap_tools, disabledTools.size)
                    )
                    field(
                        toolQuery,
                        { toolQuery = it },
                        stringResource(R.string.chat_groups_cap_tools_search),
                    )
                }
                items(filteredTools, key = { it.name }) { meta ->
                    NarrowOption(
                        title = meta.displayName(ctx),
                        subtitle = meta.name,
                        checked = meta.name in disabledTools,
                        onToggle = { on ->
                            disabledTools = if (on) {
                                disabledTools + meta.name
                            } else {
                                disabledTools - meta.name
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        GroupEditResult(
                            name = name,
                            prompt = prompt,
                            disabledTools = disabledTools,
                            disabledMcpServers = disabledMcpServers,
                            disabledSkills = disabledSkills,
                        )
                    )
                },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrewChat,
                    contentColor = BrewBg,
                ),
            ) {
                Text(stringResource(R.string.chat_common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_common_cancel))
            }
        },
    )
}

/**
 * 能力收窄的单个勾选行（复选框 + 主副标题，整行可点）。
 * 选中态 = 「该分组停用此项」——见 [GroupEditorDialog] 的排除语义。
 */
@Composable
private fun NarrowOption(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onToggle(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle(it) },
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = BrewTextBright,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = BrewMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (checked) {
            Text(
                text = stringResource(R.string.chat_groups_cap_narrowed),
                color = BrewRed,
                fontSize = 10.sp,
            )
        }
    }
}

/**
 * 「移动到分组」选择对话框：未分组 + 全部分组，单选。
 * 确认才生效（与删除会话的确认同口径 —— 误触不该直接改归属）。
 */
@Composable
private fun MoveSessionDialog(
    currentGroupId: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val groups = ChatGroupStore.sorted(ChatStateHolder.groups.toList())
    var selected by remember { mutableStateOf(currentGroupId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_groups_move_title)) },
        text = {
            Column {
                MoveOption(
                    label = stringResource(R.string.chat_groups_ungrouped),
                    selected = selected.isEmpty(),
                    onClick = { selected = "" },
                )
                groups.forEach { g ->
                    MoveOption(
                        label = g.name,
                        selected = selected == g.id,
                        onClick = { selected = g.id },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selected) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrewChat,
                    contentColor = BrewBg,
                ),
            ) {
                Text(stringResource(R.string.chat_common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_common_cancel))
            }
        },
    )
}

/** 移动对话框的单个选项（RadioButton + 名称，整行可点） */
@Composable
private fun MoveOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = BrewTextBright,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 重命名对话框 */
@Composable
private fun RenameSessionDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_sessions_rename_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        stringResource(R.string.chat_sessions_rename_hint),
                        color = BrewMuted,
                        fontSize = 13.sp,
                    )
                },
                singleLine = true,
                textStyle = TextStyle(color = BrewTextBright, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(text) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrewChat,
                    unfocusedBorderColor = BrewBorder,
                    focusedTextColor = BrewTextBright,
                    unfocusedTextColor = BrewTextBright,
                    cursorColor = BrewChat,
                ),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrewChat,
                    contentColor = BrewBg,
                ),
            ) {
                Text(stringResource(R.string.chat_common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_common_cancel))
            }
        },
    )
}

/**
 * 会话时间：今天只显示时分，今年显示月日，跨年才带年份 —— 列表信息密度有限，
 * 完整时间戳对"找上次那个对话"没有帮助。
 */
private fun formatSessionTime(ms: Long): String {
    if (ms <= 0L) return ""
    val target = Calendar.getInstance().apply { timeInMillis = ms }
    val now = Calendar.getInstance()
    val sameDay = target.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        target.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    val pattern = when {
        sameDay -> "HH:mm"
        target.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> "M月d日"
        else -> "yyyy年M月d日"
    }
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
}
