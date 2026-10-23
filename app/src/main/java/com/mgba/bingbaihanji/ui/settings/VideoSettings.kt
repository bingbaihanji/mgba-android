package com.mgba.bingbaihanji.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mgba.bingbaihanji.core.settings.ScreenOrientation
import com.mgba.bingbaihanji.core.video.ShaderConfig
import com.mgba.bingbaihanji.core.video.ShaderPreset
import com.mgba.bingbaihanji.core.video.ShaderStore
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 自定义 GLSL 文件大小上限，避免把超大文件写入偏好存储。 */
private const val MAX_SHADER_CHARS = 256 * 1024

/** 显示帧率选项：0 表示跟随系统，其余写入窗口的 preferredDisplayModeId。 */
private val DISPLAY_FPS_VALUES = listOf(0, 30, 60, 90, 120, 144)
private val DISPLAY_FPS_LABELS = listOf("自动（跟随系统）", "30 Hz", "60 Hz", "90 Hz", "120 Hz", "144 Hz")

/** 已保存的 Hz 值映射回选项文案；未知值回落到“自动”。 */
private fun displayFpsLabel(fps: Int): String {
    val index = DISPLAY_FPS_VALUES.indexOf(fps)
    return DISPLAY_FPS_LABELS[if (index >= 0) index else 0]
}

/** 视频设置页：画面、着色器、分辨率信息与调试浮层。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoSettingsScreen(
    onBack: () -> Unit,
    romInfo: RomInfoUi? = null,
) {
    val store = LocalSettingsStore.current
    val settings by store.settings.collectAsState()
    val shaderStore = LocalShaderStore.current
    val shaderConfig by shaderStore.config.collectAsState()

    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    val refreshRate = context.display?.refreshRate

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("视频") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingSection("画面")
            SettingSwitch(
                title = "全屏模式",
                subtitle = "隐藏状态栏，让画面占据整个窗口",
                checked = settings.fullScreenMode,
                onChange = { store.update { s -> s.copy(fullScreenMode = it) } },
            )
            SettingSwitch(
                title = "沉浸模式",
                subtitle = "隐藏导航栏",
                checked = settings.fullScreenImmersive,
                enabled = settings.fullScreenMode,
                onChange = { store.update { s -> s.copy(fullScreenImmersive = it) } },
            )
            SettingSwitch(
                title = "全屏拉伸",
                subtitle = "拉伸画面以填满屏幕（忽略宽高比）",
                checked = settings.stretchToFit,
                onChange = { store.update { s -> s.copy(stretchToFit = it) } },
            )
            SettingDropdown(
                title = "屏幕方向",
                options = ScreenOrientation.entries.map { it.name },
                selected = settings.screenOrientation.name,
                onSelect = { idx ->
                    store.update { s ->
                        s.copy(screenOrientation = ScreenOrientation.entries[idx])
                    }
                },
            )
            SettingDropdown(
                title = "显示帧率",
                options = DISPLAY_FPS_LABELS,
                selected = displayFpsLabel(settings.displayFps),
                onSelect = { idx ->
                    store.update { s -> s.copy(displayFps = DISPLAY_FPS_VALUES[idx]) }
                },
            )
            Text(
                text = "调整屏幕刷新率并限制画面呈现频率。模拟器按 GBA 的约 59.73 Hz " +
                        "推进：120 Hz 让新帧更快上屏、画面更稳，30 Hz 以约 30 fps " +
                        "呈现以降低功耗。",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingSwitch(
                title = "线性过滤",
                subtitle = "平滑画面边缘（关闭则保持像素锐利）",
                checked = settings.linearFiltering,
                onChange = { store.update { s -> s.copy(linearFiltering = it) } },
            )

            SettingSection("分辨率")
            SettingInfo(
                title = "游戏分辨率",
                value = if (romInfo != null && romInfo.fps > 0) {
                    String.format(
                        Locale.ROOT,
                        "240 × 160（GBA 原生）· 核心 %.2f Hz",
                        romInfo.fps,
                    )
                } else {
                    "240 × 160（GBA 原生；GB/GBC 为 160 × 144）"
                },
            )
            SettingInfo(
                title = "屏幕分辨率",
                value = "${metrics.widthPixels} × ${metrics.heightPixels} px · " +
                        "${metrics.densityDpi} dpi",
            )
            SettingInfo(
                title = "屏幕刷新率",
                value = refreshRate?.let { String.format(Locale.ROOT, "%.2f Hz", it) }
                    ?: "未知",
            )

            SettingSection("着色器")
            ShaderSetting(config = shaderConfig, store = shaderStore)

            SettingSection("调试浮层")
            SettingSwitch(
                title = "显示实时帧率",
                subtitle = "游戏画面左上角显示仿真帧率、绘制帧率、帧耗时与丢帧数",
                checked = settings.showFps,
                onChange = { store.update { s -> s.copy(showFps = it) } },
            )
            SettingSwitch(
                title = "显示变速状态",
                subtitle = "在左上角浮层中显示当前倍速（快进橙色、慢放蓝色）",
                checked = settings.showSpeedStatus,
                onChange = { store.update { s -> s.copy(showSpeedStatus = it) } },
            )
            Text(
                text = "游戏 FPS 是核心每秒推进的 GBA 帧数（正常约 59.7）；" +
                        "绘制 FPS 是实际绘制上屏的次数。两个开关共用左上角浮层，" +
                        "使用不同颜色区分。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 着色器选择：内置预设 + 通过 SAF 选择自定义 GLSL 片元着色器。
 */
@Composable
private fun ShaderSetting(
    config: ShaderConfig,
    store: ShaderStore,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val loaded = withContext(Dispatchers.IO) { loadShader(context, uri) }
                if (loaded == null) {
                    Toast.makeText(context, "无法读取 GLSL 文件", Toast.LENGTH_SHORT).show()
                } else {
                    val (name, source) = loaded
                    val hasMain = Regex("""void\s+main\s*\(""").containsMatchIn(source)
                    if (!hasMain || !source.contains("gl_FragColor")) {
                        Toast.makeText(
                            context,
                            "不是有效的 GLSL 片元着色器（需要 main 和 gl_FragColor）",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        store.save(
                            ShaderConfig(
                                preset = ShaderPreset.Custom,
                                source = source,
                                name = name,
                            )
                        )
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("着色器", style = MaterialTheme.typography.bodyLarge)
        Text(
            "GPU 片元着色器；可用 uTexture / vTexCoord / uTexelSize / uSwizzle，需兼容 GLSL ES 2.0",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            Text(shaderLabel(config), modifier = Modifier.weight(1f))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            for (preset in listOf(
                ShaderPreset.Off,
                ShaderPreset.Lcd,
                ShaderPreset.Scanlines,
                ShaderPreset.Dot,
            )) {
                DropdownMenuItem(
                    text = { Text(presetLabel(preset)) },
                    onClick = {
                        expanded = false
                        store.save(config.copy(preset = preset))
                    },
                )
            }
            if (config.source.isNotBlank()) {
                DropdownMenuItem(
                    text = {
                        Text("上次自定义：${config.name.ifBlank { "GLSL" }}")
                    },
                    onClick = {
                        expanded = false
                        store.save(config.copy(preset = ShaderPreset.Custom))
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("选择 GLSL 文件…") },
                onClick = {
                    expanded = false
                    picker.launch(arrayOf("*/*"))
                },
            )
        }
    }
}

private fun shaderLabel(config: ShaderConfig): String = when (config.preset) {
    ShaderPreset.Off -> "关闭"
    ShaderPreset.Lcd -> "LCD 网格"
    ShaderPreset.Scanlines -> "扫描线"
    ShaderPreset.Dot -> "点阵"
    ShaderPreset.Custom ->
        if (config.name.isBlank()) "自定义 GLSL…" else "自定义：${config.name}"
}

private fun presetLabel(preset: ShaderPreset): String = when (preset) {
    ShaderPreset.Off -> "关闭"
    ShaderPreset.Lcd -> "LCD 网格"
    ShaderPreset.Scanlines -> "扫描线"
    ShaderPreset.Dot -> "点阵"
    ShaderPreset.Custom -> "自定义"
}

private fun loadShader(context: Context, uri: Uri): Pair<String, String>? {
    val name = queryDisplayName(context, uri)
    val source = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader().use { reader ->
                val builder = StringBuilder()
                val buffer = CharArray(4096)
                var tooLarge = false
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (builder.length + count > MAX_SHADER_CHARS) {
                        tooLarge = true
                        break
                    }
                    builder.append(buffer, 0, count)
                }
                if (tooLarge) null else builder.toString()
            }
        }
    } catch (_: Exception) {
        null
    }
    return if (source.isNullOrBlank()) null else name to source
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    val name = try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }
    return name ?: uri.lastPathSegment ?: "custom.glsl"
}

/** 设置分组标题，用于把长页面按主题分段。 */
@Composable
internal fun SettingSection(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** 只读信息行：标题在上、值在下，长路径可以自动换行。 */
@Composable
internal fun SettingInfo(
    title: String,
    value: String,
    monospace: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = if (monospace) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
        )
    }
}

@Composable
internal fun SettingSwitch(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
internal fun SettingSlider(
    title: String,
    value: Int,
    valueRange: IntRange,
    valueLabel: String = value.toString(),
    enabled: Boolean = true,
    onChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                valueLabel, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        androidx.compose.material3.Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            enabled = enabled,
        )
    }
}

@Composable
internal fun SettingAction(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SettingDropdown(
    title: String,
    options: List<String>,
    selected: String,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedIdx = options.indexOf(selected).coerceAtLeast(0)

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Text(options[selectedIdx], modifier = Modifier.weight(1f))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEachIndexed { idx, label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(idx)
                        expanded = false
                    },
                )
            }
        }
    }
}
