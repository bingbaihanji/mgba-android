package com.mgba.bingbaihanji.ui.settings

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.mgba.bingbaihanji.core.settings.SpeedPresets
import com.mgba.bingbaihanji.core.video.displayName
import com.mgba.bingbaihanji.platform.android.storage.AndroidSaveStorage
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** 音频设置页：声音开关、音量与核心输出的采样率。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSettingsScreen(
    onBack: () -> Unit,
    romInfo: RomInfoUi? = null,
) {
    val store = LocalSettingsStore.current
    val settings by store.settings.collectAsState()

    SettingsScaffold("音频", onBack) {
        SettingSection("声音")
        SettingSwitch(
            title = "启用声音",
            checked = settings.enableSound,
            onChange = { store.update { s -> s.copy(enableSound = it) } },
        )
        SettingSlider(
            title = "音量",
            value = settings.soundVolume,
            valueRange = 0..100,
            valueLabel = "${settings.soundVolume}%",
            enabled = settings.enableSound,
            onChange = { store.update { s -> s.copy(soundVolume = it) } },
        )

        SettingSection("输出")
        SettingInfo(
            title = "采样率",
            value = romInfo?.let {
                if (it.sampleRate > 0) "${it.sampleRate} Hz（核心输出）" else "启动游戏后显示"
            } ?: "启动游戏后显示（GBA 核心通常为 32768 Hz）",
        )
        SettingInfo(title = "音频格式", value = "16-bit 立体声 PCM")
        Text(
            text = "采样率由 mGBA 核心上报，音频设备按该采样率直接播放，" +
                    "不做重采样，因此音调与游戏运行速度保持一致。",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 输入设置页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputSettingsScreen(onBack: () -> Unit) {
    val store = LocalSettingsStore.current
    val settings by store.settings.collectAsState()
    var showKeyMappings by remember { mutableStateOf(false) }

    if (showKeyMappings) {
        KeyMappingsScreen(onBack = { showKeyMappings = false })
    } else {
        SettingsScaffold("输入", onBack) {
            SettingSection("虚拟按键")
            SettingSwitch(
                title = "虚拟按键",
                subtitle = "显示屏幕上的触控按钮（关闭后仍保留菜单按钮）",
                checked = settings.enableVKeypad,
                onChange = { store.update { s -> s.copy(enableVKeypad = it) } },
            )
            SettingSlider(
                title = "按键透明度",
                value = (settings.vkeypadOpacity * 100).toInt(),
                valueRange = 5..100,
                valueLabel = "${(settings.vkeypadOpacity * 100).toInt()}%",
                enabled = settings.enableVKeypad,
                onChange = {
                    store.update { s -> s.copy(vkeypadOpacity = it / 100f) }
                },
            )
            SettingSection("按键映射")
            SettingAction(
                title = "按键映射",
                subtitle = "自定义键盘/手柄按键与快捷键",
                onClick = { showKeyMappings = true },
            )
            Text(
                text = "自定义按键位置：游戏内菜单 → 编辑布局",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 其他设置页：游戏速度与存档/退出行为。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiscSettingsScreen(onBack: () -> Unit) {
    val store = LocalSettingsStore.current
    val settings by store.settings.collectAsState()
    val context = LocalContext.current

    SettingsScaffold("其他", onBack) {
        SettingSection("游戏速度")
        // 常用档位滑块：0.2x 慢放 → 16x 快进，拖动即保存。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "游戏速度",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    SpeedPresets.label(settings.gameSpeed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = SpeedPresets.nearestIndex(settings.gameSpeed).toFloat(),
                onValueChange = { value ->
                    val index = value.roundToInt()
                        .coerceIn(SpeedPresets.VALUES.indices)
                    val speed = SpeedPresets.VALUES[index]
                    if (speed != settings.gameSpeed) {
                        store.update { s -> s.copy(gameSpeed = speed) }
                    }
                },
                valueRange = 0f..(SpeedPresets.VALUES.size - 1).toFloat(),
                steps = SpeedPresets.VALUES.size - 2,
            )
            Text(
                text = "在游戏中也可以通过「更多 → 游戏速度」随时调整。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingSection("存档与退出")
        SettingSwitch(
            title = "自动存档",
            subtitle = "退出时自动保存，启动时自动加载",
            checked = settings.autoSaveOnExit,
            onChange = { store.update { s -> s.copy(autoSaveOnExit = it) } },
        )
        SettingSwitch(
            title = "关闭确认",
            checked = settings.confirmOnClose,
            onChange = { store.update { s -> s.copy(confirmOnClose = it) } },
        )

        SettingSection("存档目录")
        SettingInfo(
            title = "存档根目录",
            value = AndroidSaveStorage.rootDir(context).absolutePath,
            monospace = true,
        )
        if (AndroidSaveStorage.canUsePublicRoot()) {
            SettingInfo(title = "权限状态", value = "已授予所有文件访问权限")
        } else {
            SettingAction(
                title = "授予所有文件访问权限",
                subtitle = "用于写入 /storage/emulated/0/bingbaihanji/mgba/",
                onClick = { AndroidSaveStorage.openAccessSettings(context) },
            )
        }
    }
}

/**
 * 调试页：按「视频 / 音频 / ROM 信息 / 系统信息 / 其他」分组，
 * 每个开关控制游戏画面左上角浮层中的一行数据。
 *
 * 浮层只在加载 ROM 后绘制，与实时帧率的显示逻辑一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugSettingsScreen(
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

    SettingsScaffold("调试", onBack) {
        Text(
            text = "以下开关只影响游戏画面左上角的调试浮层。开启后，加载 ROM " +
                    "时会绘制对应数据，和实时帧率的显示逻辑一致。",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SettingSection("视频")
        SettingSwitch(
            title = "显示实时帧率",
            subtitle = "游戏 FPS、绘制 FPS、帧耗时与丢帧/秒",
            checked = settings.showFps,
            onChange = { store.update { s -> s.copy(showFps = it) } },
        )
        SettingSwitch(
            title = "显示变速状态",
            subtitle = "当前倍速（快进橙色、慢放蓝色、正常灰色）",
            checked = settings.showSpeedStatus,
            onChange = { store.update { s -> s.copy(showSpeedStatus = it) } },
        )
        SettingSwitch(
            title = "显示着色器",
            subtitle = "当前着色器：${shaderConfig.displayName()}",
            checked = settings.showShaderInfo,
            onChange = { store.update { s -> s.copy(showShaderInfo = it) } },
        )
        SettingSwitch(
            title = "显示分辨率",
            subtitle = "游戏输出分辨率与屏幕 " +
                    "${metrics.widthPixels} × ${metrics.heightPixels} px" +
                    (refreshRate?.let {
                        String.format(Locale.ROOT, " @ %.2f Hz", it)
                    } ?: ""),
            checked = settings.showResolutionInfo,
            onChange = { store.update { s -> s.copy(showResolutionInfo = it) } },
        )

        SettingSection("音频")
        SettingSwitch(
            title = "显示音量",
            subtitle = if (settings.enableSound) {
                "当前音量 ${settings.soundVolume}%"
            } else {
                "声音已关闭（浮层显示为静音）"
            },
            checked = settings.showVolumeInfo,
            onChange = { store.update { s -> s.copy(showVolumeInfo = it) } },
        )
        SettingSwitch(
            title = "显示采样率",
            subtitle = romInfo?.takeIf { it.sampleRate > 0 }?.let {
                "核心输出 ${it.sampleRate} Hz"
            } ?: "启动游戏后显示核心输出采样率",
            checked = settings.showSampleRateInfo,
            onChange = { store.update { s -> s.copy(showSampleRateInfo = it) } },
        )

        SettingSection("ROM 信息")
        SettingSwitch(
            title = "显示 ROM 名称",
            subtitle = romInfo?.title?.ifBlank { null } ?: "启动游戏后显示",
            checked = settings.showRomName,
            onChange = { store.update { s -> s.copy(showRomName = it) } },
        )
        SettingSwitch(
            title = "显示 ROM 路径",
            subtitle = romInfo?.path?.ifBlank { null } ?: "启动游戏后显示",
            checked = settings.showRomPath,
            onChange = { store.update { s -> s.copy(showRomPath = it) } },
        )

        SettingSection("系统信息")
        SettingSwitch(
            title = "显示系统信息",
            subtitle = "${Build.MANUFACTURER} ${Build.MODEL} · " +
                    "Android ${Build.VERSION.RELEASE} · 应用 ${appVersion(context)}",
            checked = settings.showSystemInfo,
            onChange = { store.update { s -> s.copy(showSystemInfo = it) } },
        )
        SettingSwitch(
            title = "显示核心信息",
            subtitle = romInfo?.let {
                listOfNotNull(it.coreName, it.coreVersion)
                    .joinToString(" ")
                    .ifBlank { "mGBA（libretro）" }
            } ?: "启动游戏后显示核心名称与版本",
            checked = settings.showCoreInfo,
            onChange = { store.update { s -> s.copy(showCoreInfo = it) } },
        )

        SettingSection("其他")
        SettingSwitch(
            title = "显示内存",
            subtitle = "系统可用内存 / 总内存",
            checked = settings.showMemoryInfo,
            onChange = { store.update { s -> s.copy(showMemoryInfo = it) } },
        )
        Text(
            text = "浮层项目可以任意组合；全部关闭时不会绘制浮层，额外开销为零。",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** ROM 信息页：当前加载的游戏文件、校验值与存档位置。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RomInfoScreen(
    onBack: () -> Unit,
    romInfo: RomInfoUi? = null,
) {
    val context = LocalContext.current

    SettingsScaffold("ROM 信息", onBack) {
        if (romInfo == null) {
            SettingInfo(
                title = "未加载游戏",
                value = "从游戏列表启动游戏后，在游戏内菜单 → 设置 → ROM 信息中查看。",
            )
            SettingSection("存储位置")
            SettingInfo(
                title = "ROM 缓存目录",
                value = File(context.filesDir, "rom-cache").absolutePath,
                monospace = true,
            )
            SettingInfo(
                title = "存档根目录",
                value = AndroidSaveStorage.rootDir(context).absolutePath,
                monospace = true,
            )
            SettingInfo(
                title = "目录结构",
                value = "每个 ROM 一个子文件夹，内含 game.sav（游戏内存档）、" +
                        "game.rtc 与 slot0.state…slot10.state（即时存档）。",
            )
            if (!AndroidSaveStorage.canUsePublicRoot()) {
                SettingInfo(
                    title = "权限提示",
                    value = "未授予“所有文件访问权限”，当前暂用应用专属目录；" +
                            "授权后会写入 /storage/emulated/0/bingbaihanji/mgba/。",
                )
            }
        } else {
            SettingSection("游戏")
            SettingInfo(title = "名称", value = romInfo.title.ifBlank { "未命名 ROM" })
            SettingInfo(title = "路径", value = romInfo.path, monospace = true)
            SettingInfo(title = "文件大小", value = formatBytes(romInfo.sizeBytes))

            SettingSection("校验")
            SettingInfo(
                title = "SHA-256",
                value = romInfo.sha256.ifEmpty { "未计算" },
                monospace = true,
            )
            SettingInfo(
                title = "存档 ID",
                value = romInfo.romId.ifEmpty { "-" },
                monospace = true,
            )

            SettingSection("核心")
            SettingInfo(
                title = "核心",
                value = listOfNotNull(romInfo.coreName, romInfo.coreVersion)
                    .joinToString(" ")
                    .ifBlank { "mGBA（libretro）" },
            )
            SettingInfo(
                title = "帧率",
                value = if (romInfo.fps > 0) {
                    String.format(Locale.ROOT, "%.2f Hz", romInfo.fps)
                } else {
                    "未知"
                },
            )
            SettingInfo(
                title = "采样率",
                value = if (romInfo.sampleRate > 0) "${romInfo.sampleRate} Hz" else "未知",
            )

            SettingSection("存档位置")
            SettingInfo(
                title = "存档根目录",
                value = AndroidSaveStorage.rootDir(context).absolutePath,
                monospace = true,
            )
            SettingInfo(
                title = "当前游戏存档目录",
                value = romInfo.saveDirectory.ifEmpty {
                    AndroidSaveStorage.rootDir(context).absolutePath
                },
                monospace = true,
            )
            SettingInfo(
                title = "文件说明",
                value = "game.sav / game.rtc（游戏内存档与时钟）· " +
                        "slot0.state…slot10.state（即时存档）",
            )

            SettingSection("操作")
            SettingAction(
                title = "复制 ROM 路径",
                subtitle = romInfo.path,
                onClick = { copyText(context, "ROM 路径", romInfo.path) },
            )
            SettingAction(
                title = "复制 SHA-256",
                subtitle = romInfo.sha256,
                onClick = { copyText(context, "SHA-256", romInfo.sha256) },
            )
        }
    }
}

/** 系统信息页：应用、核心、设备与屏幕数据。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemInfoScreen(
    onBack: () -> Unit,
    romInfo: RomInfoUi? = null,
) {
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    val refreshRate = context.display?.refreshRate
    val memory = remember(context) { readMemory(context) }

    SettingsScaffold("系统信息", onBack) {
        SettingSection("应用")
        SettingInfo(title = "名称", value = "mGBA-Android")
        SettingInfo(title = "版本", value = appVersion(context))
        SettingInfo(title = "包名", value = context.packageName, monospace = true)

        SettingSection("仿真核心")
        SettingInfo(
            title = "名称",
            value = romInfo?.coreName ?: "mGBA（libretro）",
        )
        SettingInfo(
            title = "版本",
            value = romInfo?.coreVersion?.ifBlank { null } ?: "启动游戏后显示",
        )
        SettingInfo(
            title = "帧率",
            value = romInfo?.takeIf { it.fps > 0 }?.let {
                String.format(Locale.ROOT, "%.2f Hz", it.fps)
            } ?: "启动游戏后显示",
        )
        SettingInfo(
            title = "采样率",
            value = romInfo?.takeIf { it.sampleRate > 0 }?.let {
                "${it.sampleRate} Hz"
            } ?: "启动游戏后显示",
        )

        SettingSection("设备")
        SettingInfo(title = "型号", value = "${Build.MANUFACTURER} ${Build.MODEL}")
        SettingInfo(
            title = "Android",
            value = "${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
        )
        SettingInfo(title = "架构", value = Build.SUPPORTED_ABIS.joinToString(", "))
        SettingInfo(
            title = "屏幕",
            value = "${metrics.widthPixels} × ${metrics.heightPixels} px · " +
                    "${metrics.densityDpi} dpi",
        )
        SettingInfo(
            title = "刷新率",
            value = refreshRate?.let { String.format(Locale.ROOT, "%.2f Hz", it) }
                ?: "未知",
        )
        SettingInfo(
            title = "内存",
            value = memory?.let {
                String.format(Locale.ROOT, "可用 %.1f GB / 共 %.1f GB", it.first, it.second)
            } ?: "未知",
        )
    }
}

/** 关于页：版本、开源地址与联系方式。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    fun open(uri: String) {
        try {
            uriHandler.openUri(uri)
        } catch (_: Exception) {
            Toast.makeText(context, "无法打开链接：$uri", Toast.LENGTH_SHORT).show()
        }
    }

    SettingsScaffold("关于", onBack) {
        SettingSection("mGBA-Android")
        Text(
            text = "基于 mGBA Libretro 核心的 GBA / GB / GBC 模拟器，" +
                    "使用 Kotlin 与 Jetpack Compose 构建。",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        SettingInfo(title = "版本", value = appVersion(context))

        SettingSection("开源地址")
        SettingAction(
            title = "项目主页",
            subtitle = "https://github.com/bingbaihanji/mgba-android",
            onClick = { open("https://github.com/bingbaihanji/mgba-android") },
        )
        SettingAction(
            title = "mGBA",
            subtitle = "https://github.com/mgba-emu/mgba",
            onClick = { open("https://github.com/mgba-emu/mgba") },
        )
        SettingAction(
            title = "mGBA Libretro 核心",
            subtitle = "https://github.com/libretro/mgba",
            onClick = { open("https://github.com/libretro/mgba") },
        )

        SettingSection("作者")
        SettingInfo(title = "名称", value = "bingbaihanji")
        SettingAction(
            title = "邮箱",
            subtitle = "bingbaihanji@gmail.com",
            onClick = { open("mailto:bingbaihanji@gmail.com") },
        )
        SettingInfo(title = "QQ", value = "2761868857")
        SettingInfo(title = "交流群", value = "1128889210 / 544070859")

        SettingSection("许可")
        Text(
            text = "mGBA 核心遵循 MPL-2.0；AndroidX / Jetpack Compose 与 Kotlin " +
                    "遵循 Apache-2.0。本应用不包含任何 ROM、BIOS 或游戏数据，" +
                    "请自行准备合法持有的 ROM 文件。",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 读取应用版本号，供“系统信息”和“关于”共用。 */
private fun appVersion(context: Context): String {
    val info = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull() ?: return "未知"
    return "${info.versionName}（${info.longVersionCode}）"
}

/** 读取设备内存（可用 GB、总 GB）。 */
private fun readMemory(context: Context): Pair<Double, Double>? {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        ?: return null
    val info = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(info)
    val gb = 1024.0 * 1024.0 * 1024.0
    return info.availMem / gb to info.totalMem / gb
}

/** 把字节数格式化为易读的 MB/GB。 */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "未知"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        String.format(Locale.ROOT, "%.2f MB（%,d 字节）", mb, bytes)
    } else {
        String.format(Locale.ROOT, "%.1f KB（%,d 字节）", kb, bytes)
    }
}

/** 复制文本到剪贴板；Android 13+ 系统会自行显示复制提示。 */
private fun copyText(context: Context, label: String, text: String) {
    if (text.isBlank()) {
        Toast.makeText(context, "$label 为空", Toast.LENGTH_SHORT).show()
        return
    }
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (manager == null) {
        Toast.makeText(context, "无法访问剪贴板", Toast.LENGTH_SHORT).show()
        return
    }
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "已复制$label", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
            content()
        }
    }
}
