package com.mgba.bingbaihanji.ui.cheat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mgba.bingbaihanji.core.cheat.Cheat
import com.mgba.bingbaihanji.core.cheat.CheatCodeParser
import com.mgba.bingbaihanji.core.cheat.CheatStore
import com.mgba.bingbaihanji.core.cheat.CheatType
import java.util.UUID

/**
 * 金手指管理页。
 *
 * 交互参考 My Boy：
 * - 列表展示名称、类型、代码预览与启用开关；
 * - 点击开关立即保存并重新应用到核心；
 * - 支持新建/编辑/删除、全部启用、全部禁用；
 * - 新建时可以选择类型或自动识别，代码支持多行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheatsScreen(
    romId: String,
    store: CheatStore,
    onApply: (List<Cheat>) -> Unit,
    onBack: () -> Unit,
) {
    var cheats by remember(romId) { mutableStateOf(store.load(romId)) }
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Cheat?>(null) }
    var deleteTarget by remember { mutableStateOf<Cheat?>(null) }

    fun persist(newCheats: List<Cheat>) {
        cheats = newCheats
        store.save(romId, newCheats)
        onApply(newCheats)
    }

    BackHandler(enabled = !showEditor && deleteTarget == null) {
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("金手指") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            editing = null
                            showEditor = true
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "新建金手指")
                    }
                },
            )
        },
    ) { padding ->
        if (cheats.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("还没有金手指", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "支持 GameShark / Action Replay、CodeBreaker 与 Raw 代码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        editing = null
                        showEditor = true
                    }
                ) {
                    Text("添加金手指")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "已启用 ${cheats.count { it.enabled }} / ${cheats.size}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = {
                                    persist(cheats.map { it.copy(enabled = true) })
                                }
                            ) {
                                Text("全部启用")
                            }
                            TextButton(
                                onClick = {
                                    persist(cheats.map { it.copy(enabled = false) })
                                }
                            ) {
                                Text("全部禁用")
                            }
                        }
                    }
                }

                items(cheats, key = { it.id }) { cheat ->
                    CheatCard(
                        cheat = cheat,
                        onEnabledChange = { enabled ->
                            persist(
                                cheats.map {
                                    if (it.id == cheat.id) it.copy(enabled = enabled) else it
                                }
                            )
                        },
                        onEdit = {
                            editing = cheat
                            showEditor = true
                        },
                        onDelete = { deleteTarget = cheat },
                    )
                }
            }
        }
    }

    if (showEditor) {
        CheatEditorDialog(
            initial = editing,
            onDismiss = {
                showEditor = false
                editing = null
            },
            onSave = { cheat ->
                val newList = if (editing == null) {
                    cheats + cheat
                } else {
                    cheats.map { if (it.id == cheat.id) cheat else it }
                }
                persist(newList)
                showEditor = false
                editing = null
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除金手指") },
            text = { Text("删除「${target.name}」？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        persist(cheats.filterNot { it.id == target.id })
                        deleteTarget = null
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun CheatCard(
    cheat: Cheat,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = cheat.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = cheatTypeLabel(cheat.type),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Switch(
                    checked = cheat.enabled,
                    onCheckedChange = onEnabledChange,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = cheat.codes.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

@Composable
private fun CheatEditorDialog(
    initial: Cheat?,
    onDismiss: () -> Unit,
    onSave: (Cheat) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var typeSelection by remember(initial) {
        mutableStateOf(
            when (initial?.type) {
                CheatType.GameShark -> CheatType.GameShark
                CheatType.CodeBreaker -> CheatType.CodeBreaker
                CheatType.Raw -> CheatType.Raw
                else -> CheatType.Auto
            }
        )
    }
    var codeText by remember(initial) {
        mutableStateOf(initial?.codes?.joinToString("\n") ?: "")
    }
    var error by remember(initial) { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    if (initial == null) "新建金手指" else "编辑金手指",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("名称") },
                    placeholder = { Text("例如：无限 HP") },
                )
                Spacer(Modifier.height(8.dp))
                Text("类型", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                // 类型直接内联展示：弹层菜单在竖屏的对话框窗口里会被夹到
                // 顶部或被裁切，内联选项在任何方向下位置都稳定。
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CheatTypeChip(CheatType.Auto, typeSelection, Modifier.weight(1f)) {
                        typeSelection = it
                        error = null
                    }
                    CheatTypeChip(CheatType.GameShark, typeSelection, Modifier.weight(1f)) {
                        typeSelection = it
                        error = null
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CheatTypeChip(CheatType.CodeBreaker, typeSelection, Modifier.weight(1f)) {
                        typeSelection = it
                        error = null
                    }
                    CheatTypeChip(CheatType.Raw, typeSelection, Modifier.weight(1f)) {
                        typeSelection = it
                        error = null
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = codeText,
                    onValueChange = { codeText = it.uppercase() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("代码") },
                    minLines = 4,
                    maxLines = 8,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                    ),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = cheatTypeHint(typeSelection),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val trimmedName = name.trim()
                            if (trimmedName.isEmpty()) {
                                error = "请输入名称"
                            } else {
                                when (val parsed = CheatCodeParser.parse(codeText, typeSelection)) {
                                    is CheatCodeParser.ParseResult.Failure ->
                                        error = parsed.message

                                    is CheatCodeParser.ParseResult.Success -> {
                                        val cheat = initial?.copy(
                                            name = trimmedName,
                                            type = parsed.type,
                                            codes = parsed.codes,
                                        ) ?: Cheat(
                                            id = UUID.randomUUID().toString(),
                                            name = trimmedName,
                                            type = parsed.type,
                                            codes = parsed.codes,
                                            enabled = true,
                                        )
                                        onSave(cheat)
                                    }
                                }
                            }
                        }
                    ) {
                        Text("保存")
                    }
                }
            }
        }
    }
}

@Composable
private fun CheatTypeChip(
    type: CheatType,
    selected: CheatType,
    modifier: Modifier = Modifier,
    onSelect: (CheatType) -> Unit,
) {
    FilterChip(
        selected = selected == type,
        onClick = { onSelect(type) },
        label = {
            Text(
                text = cheatTypeChipLabel(type),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier,
    )
}

private fun cheatTypeChipLabel(type: CheatType): String = when (type) {
    CheatType.Auto -> "自动检测"
    CheatType.GameShark -> "GameShark / AR"
    CheatType.CodeBreaker -> "CodeBreaker"
    CheatType.Raw -> "Raw 直写"
    CheatType.Mixed -> "混合"
}

private fun cheatTypeLabel(type: CheatType): String = when (type) {
    CheatType.Auto -> "自动检测"
    CheatType.GameShark -> "GameShark / Action Replay"
    CheatType.CodeBreaker -> "CodeBreaker"
    CheatType.Raw -> "Raw（直接写内存）"
    CheatType.Mixed -> "混合格式"
}

private fun cheatTypeHint(type: CheatType): String = when (type) {
    CheatType.Auto ->
        "自动检测：8+8（GameShark/AR）、8+4（CodeBreaker）、8+2（Raw）。\n" +
                "支持多行，可用空格、+、:、- 分隔。"

    CheatType.GameShark ->
        "GameShark / Action Replay：8 位地址 + 8 位数值，例如 820051F4 0000FFFF。"

    CheatType.CodeBreaker ->
        "CodeBreaker：8 位地址 + 4 位数值，例如 820051F4 0063。"

    CheatType.Raw ->
        "Raw：8 位地址 + 2/4/8 位数值，直接写入内存，例如 030050C4:0063。\n" +
                "数值 2/4/8 位分别对应 1/2/4 字节；8+4 的直写代码请选 Raw，" +
                "否则会被当作 CodeBreaker。"

    CheatType.Mixed -> "混合格式：每行独立识别。"
}
