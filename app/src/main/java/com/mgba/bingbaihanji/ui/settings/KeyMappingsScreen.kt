package com.mgba.bingbaihanji.ui.settings

import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mgba.bingbaihanji.core.input.InputAction
import com.mgba.bingbaihanji.core.input.KeyMapProfile
import com.mgba.bingbaihanji.core.input.KeyMapState
import com.mgba.bingbaihanji.core.input.KeyMapStore

/**
 * 按键映射设置页。
 *
 * 结构参考 My Boy 的 KeyMappingsFragment：
 * - 顶部选择/新建/重命名/删除命名配置；
 * - 按“基本按键 / 组合键 / 快捷键”分组列出动作；
 * - 点击动作后直接按下要绑定的物理键，支持清除；
 * - 支持恢复当前配置的内置默认键位。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyMappingsScreen(onBack: () -> Unit) {
    val store = LocalKeyMapStore.current
    val state by store.state.collectAsState()
    val profile = state.selectedProfile
    val context = LocalContext.current

    var showNewProfile by remember { mutableStateOf(false) }
    var showRenameProfile by remember { mutableStateOf(false) }
    var showDeleteProfile by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }

    BackHandler(
        enabled = !showNewProfile && !showRenameProfile &&
                !showDeleteProfile && !showReset
    ) {
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("按键映射") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                ProfileSection(
                    state = state,
                    selectedName = profile.name,
                    onSelect = { store.selectProfile(it) },
                    onNew = { showNewProfile = true },
                    onRename = { showRenameProfile = true },
                    onDelete = { showDeleteProfile = true },
                )
            }

            item { SectionHeader("基本按键") }
            items(InputAction.basic) { action ->
                KeyRow(
                    action = action,
                    profile = profile,
                    onClick = { showCaptureDialog(context, store, profile, action) },
                )
            }

            item { SectionHeader("组合键") }
            items(InputAction.combos) { action ->
                KeyRow(
                    action = action,
                    profile = profile,
                    onClick = { showCaptureDialog(context, store, profile, action) },
                )
            }

            item { SectionHeader("快捷键") }
            items(InputAction.shortcuts) { action ->
                KeyRow(
                    action = action,
                    profile = profile,
                    onClick = { showCaptureDialog(context, store, profile, action) },
                )
            }

            item {
                TextButton(
                    onClick = { showReset = true },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("恢复当前配置的默认按键")
                }
            }
        }
    }

    if (showNewProfile) {
        ProfileNameDialog(
            title = "新建按键配置",
            initialName = "",
            onDismiss = { showNewProfile = false },
            onConfirm = { name ->
                val trimmed = name.trim()
                when {
                    trimmed.isEmpty() ->
                        Toast.makeText(context, "配置名称不能为空", Toast.LENGTH_SHORT).show()

                    state.profiles.any { it.name.equals(trimmed, ignoreCase = true) } ->
                        Toast.makeText(context, "配置名称已存在", Toast.LENGTH_SHORT).show()

                    else -> {
                        store.addProfile(trimmed)
                        showNewProfile = false
                    }
                }
            },
        )
    }

    if (showRenameProfile) {
        ProfileNameDialog(
            title = "重命名配置",
            initialName = profile.name,
            onDismiss = { showRenameProfile = false },
            onConfirm = { name ->
                val trimmed = name.trim()
                when {
                    trimmed.isEmpty() ->
                        Toast.makeText(context, "配置名称不能为空", Toast.LENGTH_SHORT).show()

                    state.profiles.any {
                        it.id != profile.id && it.name.equals(trimmed, ignoreCase = true)
                    } ->
                        Toast.makeText(context, "配置名称已存在", Toast.LENGTH_SHORT).show()

                    else -> {
                        store.renameProfile(profile.id, trimmed)
                        showRenameProfile = false
                    }
                }
            },
        )
    }

    if (showDeleteProfile) {
        AlertDialog(
            onDismissRequest = { showDeleteProfile = false },
            title = { Text("删除配置") },
            text = { Text("删除「${profile.name}」？至少需要保留一套配置。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.deleteProfile(profile.id)
                        showDeleteProfile = false
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteProfile = false }) {
                    Text("取消")
                }
            },
        )
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("恢复默认按键") },
            text = { Text("恢复「${profile.name}」的内置默认按键？当前修改会被覆盖。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.resetProfile(profile.id)
                        showReset = false
                    }
                ) {
                    Text("恢复")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun ProfileSection(
    state: KeyMapState,
    selectedName: String,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text("配置", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            Text(selectedName, modifier = Modifier.weight(1f))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            for (profile in state.profiles) {
                DropdownMenuItem(
                    text = { Text(profile.name) },
                    onClick = {
                        expanded = false
                        onSelect(profile.id)
                    },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onNew) { Text("新建") }
            TextButton(onClick = onRename) { Text("重命名") }
            TextButton(
                onClick = onDelete,
                enabled = state.profiles.size > 1,
            ) {
                Text("删除")
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun KeyRow(
    action: InputAction,
    profile: KeyMapProfile,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(actionLabel(action), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = keysLabel(profile.keysFor(action)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Default.Edit,
            contentDescription = "修改按键",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProfileNameDialog(
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("配置名称") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 捕获物理按键。
 *
 * 与 My Boy 一样，对话框获得焦点后直接监听按键事件：
 * 按下任意键即绑定到当前动作并关闭；“清除”移除该动作的全部按键。
 */
private fun showCaptureDialog(
    context: android.content.Context,
    store: KeyMapStore,
    profile: KeyMapProfile,
    action: InputAction,
) {
    val current = keysLabel(profile.keysFor(action))
    val dialog = android.app.AlertDialog.Builder(context)
        .setTitle("按键映射")
        .setMessage("为「${actionLabel(action)}」按下要绑定的按键\n当前：$current")
        .setNegativeButton("取消", null)
        .setPositiveButton("清除") { _, _ -> store.clear(profile.id, action) }
        .create()

    dialog.setOnKeyListener { _, keyCode, event ->
        if (event.action == KeyEvent.ACTION_DOWN && keyCode > 0) {
            store.bind(profile.id, action, keyCode)
            dialog.dismiss()
        }
        // 捕获所有按键，避免返回键等先关闭对话框。
        true
    }
    dialog.show()
}

private fun keysLabel(keys: Set<Int>): String =
    if (keys.isEmpty()) {
        "未绑定"
    } else {
        keys.sorted().joinToString(" / ") { keyLabel(it) }
    }

private fun keyLabel(code: Int): String {
    if (code <= 0) return "未绑定"
    return when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> "方向上"
        KeyEvent.KEYCODE_DPAD_DOWN -> "方向下"
        KeyEvent.KEYCODE_DPAD_LEFT -> "方向左"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "方向右"
        KeyEvent.KEYCODE_DPAD_CENTER -> "方向中"

        KeyEvent.KEYCODE_BUTTON_A -> "手柄 A"
        KeyEvent.KEYCODE_BUTTON_B -> "手柄 B"
        KeyEvent.KEYCODE_BUTTON_X -> "手柄 X"
        KeyEvent.KEYCODE_BUTTON_Y -> "手柄 Y"
        KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
        KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
        KeyEvent.KEYCODE_BUTTON_L2 -> "L2"
        KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
        KeyEvent.KEYCODE_BUTTON_START -> "手柄 Start"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "手柄 Select"

        KeyEvent.KEYCODE_SPACE -> "空格"
        KeyEvent.KEYCODE_ENTER -> "回车"
        KeyEvent.KEYCODE_BACK -> "返回"
        KeyEvent.KEYCODE_MENU -> "菜单键"

        else -> KeyEvent.keyCodeToString(code)
            .removePrefix("KEYCODE_")
            .replace('_', ' ')
    }
}

private fun actionLabel(action: InputAction): String = when (action) {
    InputAction.UP -> "方向上"
    InputAction.DOWN -> "方向下"
    InputAction.LEFT -> "方向左"
    InputAction.RIGHT -> "方向右"
    InputAction.SELECT -> "Select"
    InputAction.START -> "Start"
    InputAction.A -> "A"
    InputAction.B -> "B"
    InputAction.L -> "L"
    InputAction.R -> "R"

    InputAction.UP_LEFT -> "上 + 左"
    InputAction.UP_RIGHT -> "上 + 右"
    InputAction.DOWN_LEFT -> "下 + 左"
    InputAction.DOWN_RIGHT -> "下 + 右"
    InputAction.A_B -> "A + B"
    InputAction.L_R -> "L + R"
    InputAction.L_A -> "L + A"
    InputAction.L_B -> "L + B"
    InputAction.R_A -> "R + A"
    InputAction.R_B -> "R + B"

    InputAction.FAST_FORWARD -> "快进"
    InputAction.OPEN_MENU -> "打开菜单"
    InputAction.SAVE_STATE -> "保存进度"
    InputAction.LOAD_STATE -> "读取进度"
}
