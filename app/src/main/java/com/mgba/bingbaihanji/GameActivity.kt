package com.mgba.bingbaihanji

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.mgba.bingbaihanji.core.emulation.CoreOption
import com.mgba.bingbaihanji.core.input.InputAction
import com.mgba.bingbaihanji.core.input.InputShortcut
import com.mgba.bingbaihanji.core.perf.PerformanceStats
import com.mgba.bingbaihanji.core.session.GameSession
import com.mgba.bingbaihanji.core.settings.AppSettings
import com.mgba.bingbaihanji.core.settings.ScreenOrientation
import com.mgba.bingbaihanji.core.settings.SpeedPresets
import com.mgba.bingbaihanji.core.storage.SavestateLoadResult
import com.mgba.bingbaihanji.core.video.displayName
import com.mgba.bingbaihanji.platform.android.audio.AudioTrackSink
import com.mgba.bingbaihanji.platform.android.emulation.JniEmulatorCore
import com.mgba.bingbaihanji.platform.android.storage.AndroidRomRepository
import com.mgba.bingbaihanji.platform.android.storage.FileSaveStorage
import com.mgba.bingbaihanji.platform.android.video.GameSurfaceView
import com.mgba.bingbaihanji.ui.GameMenuOverlay
import com.mgba.bingbaihanji.ui.SaveSlotDialog
import com.mgba.bingbaihanji.ui.SettingsNav
import com.mgba.bingbaihanji.ui.cheat.CheatsScreen
import com.mgba.bingbaihanji.ui.input.GamepadLayoutEditor
import com.mgba.bingbaihanji.ui.input.VirtualGamepad
import com.mgba.bingbaihanji.ui.settings.CoreOptionsScreen
import com.mgba.bingbaihanji.ui.settings.LocalKeyMapStore
import com.mgba.bingbaihanji.ui.settings.LocalShaderStore
import com.mgba.bingbaihanji.ui.settings.LocalSettingsStore
import com.mgba.bingbaihanji.ui.settings.RomInfoUi
import com.mgba.bingbaihanji.ui.theme.MgbaAndroidTheme
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 游戏页面：只保留 Android 平台胶水。
 *
 * 仿真、帧循环、音频、输入、存档与设置的生命周期策略全部在
 * [GameSession] 中，这里负责 SAF/Intent、SurfaceView、Toast 与对话框。
 */
class GameActivity : ComponentActivity() {

    private lateinit var gameSession: GameSession
    private lateinit var surfaceView: GameSurfaceView

    private var romId by mutableStateOf("")
    private var showSlotDialog by mutableStateOf(false)
    private var slotIsSaveMode by mutableStateOf(true)
    private var menuOpen by mutableStateOf(false)
    private var layoutEditing by mutableStateOf(false)
    private var showCoreOptions by mutableStateOf(false)
    private var coreOptions by mutableStateOf<List<CoreOption>>(emptyList())
    private var showCheats by mutableStateOf(false)
    /** 设置页内嵌在游戏页中显示，避免切 Activity 导致核心重载。 */
    private var showSettings by mutableStateOf(false)
    /** ROM 加载期间的遮罩状态。 */
    private var loading by mutableStateOf(true)
    /** 加载遮罩上显示的 ROM 名称。 */
    private var romTitle by mutableStateOf("游戏")
    /** 布局编辑期间按返回键的请求计数，由编辑器内部决定打开/关闭哪个弹窗。 */
    private var layoutEditorBackRequest by mutableStateOf(0)
    private var fastForwarding by mutableStateOf(false)

    /** 两次呈现之间至少间隔的纳秒数；0 表示不限制（跟随核心帧率）。 */
    @Volatile
    private var presentIntervalNanos = 0L

    /** 上一次请求渲染的时间，仅由仿真线程访问。 */
    private var lastPresentNanos = 0L

    /** 当前按下的物理键 → 动作，用于松开时释放对应按键。 */
    private val heldActions = mutableMapOf<Int, InputAction>()

    /** 键盘源每个 GBA 按键的引用计数，支持多个物理键映射到同一按键。 */
    private val buttonRefCounts = mutableMapOf<Int, Int>()

    /** 由 Application 组合根提供的设置存储。 */
    private val settingsStore by lazy { (application as MgbaApplication).settings }

    /** 按键映射配置存储。 */
    private val keyMapStore by lazy { (application as MgbaApplication).keyMapStore }

    /** 虚拟按键布局存储，游戏内手柄与布局编辑器共享。 */
    private val layoutStore by lazy { (application as MgbaApplication).gamepadLayoutStore }

    /** 视频着色器配置存储。 */
    private val shaderStore by lazy { (application as MgbaApplication).shaderStore }

    /** 核心选项持久化存储。 */
    private val coreOptionStore by lazy { (application as MgbaApplication).coreOptionStore }

    /** 按 ROM 保存的金手指存储。 */
    private val cheatStore by lazy { (application as MgbaApplication).cheatStore }

    /** 调试浮层显示的应用版本号。 */
    private val appVersionName: String by lazy {
        runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "未知"
    }

    /**
     * 游戏页 Compose 根：统一主题，并向内嵌的设置页注入平台层实现。
     */
    @Composable
    private fun GameTheme(content: @Composable () -> Unit) {
        MgbaAndroidTheme {
            CompositionLocalProvider(
                LocalSettingsStore provides settingsStore,
                LocalShaderStore provides shaderStore,
                LocalKeyMapStore provides keyMapStore,
                content = content,
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawableResource(android.R.color.transparent)

        val romPath = intent.getStringExtra(EXTRA_ROM_PATH)
        val romUri = intent.getStringExtra(EXTRA_ROM_URI)?.let(Uri::parse)
        if (romPath == null && romUri == null) {
            Toast.makeText(this, "No ROM specified", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        romTitle = romPath?.let { File(it).nameWithoutExtension }
            ?: romUri?.lastPathSegment?.substringAfterLast('/')
            ?: "游戏"

        val corePath = File(applicationInfo.nativeLibraryDir, "libmgba_libretro.so")
            .let { if (it.exists()) it.absolutePath else "libmgba_libretro.so" }

        val core = JniEmulatorCore(corePath, filesDir.absolutePath)
        gameSession = GameSession(
            core = core,
            audioSinkFactory = { sampleRate -> AudioTrackSink(sampleRate) },
            saveStorage = FileSaveStorage(filesDir),
            settings = settingsStore,
            coreOptionsStore = coreOptionStore,
            cheatStore = cheatStore,
            scope = lifecycleScope,
        )

        surfaceView = GameSurfaceView(this)
        setContentView(FrameLayout(this).apply {
            addView(surfaceView)
            addView(ComposeView(context).apply {
                setContent {
                    GameTheme {
                        val gamepadLayout by layoutStore.layout.collectAsState()
                        val appSettings by settingsStore.settings.collectAsState()
                        val shaderConfig by shaderStore.config.collectAsState()
                        val fullScreenPageOpen = layoutEditing || showCoreOptions || showCheats || showSettings

                        if (!loading && !menuOpen && !fullScreenPageOpen) {
                            VirtualGamepad(
                                input = gameSession.virtualInput,
                                layout = gamepadLayout,
                                showGameControls = appSettings.enableVKeypad,
                                baseAlpha = appSettings.vkeypadOpacity,
                                onMenu = { openMenu() },
                            )
                        }

                        if (layoutEditing) {
                            GamepadLayoutEditor(
                                initialLayout = gamepadLayout,
                                backRequest = layoutEditorBackRequest,
                                onSave = { newLayout ->
                                    layoutStore.save(newLayout)
                                    layoutEditing = false
                                    gameSession.resume()
                                },
                                onCancel = {
                                    layoutEditing = false
                                    gameSession.resume()
                                },
                            )
                        } else if (showCoreOptions) {
                            CoreOptionsScreen(
                                coreOptions = coreOptions,
                                onBack = { exitCoreOptions() },
                                onOptionSelected = { option, index ->
                                    val applied = gameSession.setCoreOption(option, index)
                                    if (!applied) {
                                        Toast.makeText(
                                            this@GameActivity,
                                            "无法设置该核心选项",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                    coreOptions = gameSession.coreOptions
                                },
                            )
                        } else if (showCheats) {
                            CheatsScreen(
                                romId = romId.ifEmpty { "unknown" },
                                store = cheatStore,
                                onApply = { cheats ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        gameSession.applyCheats(cheats)
                                    }
                                },
                                onBack = { exitCheats() },
                            )
                        } else if (showSettings) {
                            // 设置页内的“ROM 信息 / 系统信息”需要当前游戏的真实数据。
                            SettingsNav(
                                onBack = { exitSettings() },
                                romInfo = RomInfoUi(
                                    title = romTitle,
                                    path = gameSession.romPath,
                                    sizeBytes = runCatching {
                                        File(gameSession.romPath).length()
                                    }.getOrDefault(0L),
                                    sha256 = gameSession.romSha256,
                                    romId = romId,
                                    coreName = gameSession.coreName,
                                    coreVersion = gameSession.coreVersion,
                                    fps = gameSession.avInfo.fps,
                                    sampleRate = gameSession.avInfo.sampleRate.toInt(),
                                ),
                            )
                        } else {
                            GameMenuOverlay(
                                visible = menuOpen,
                                onSaveState = {
                                    menuOpen = false
                                    slotIsSaveMode = true
                                    showSlotDialog = true
                                },
                                onLoadState = {
                                    menuOpen = false
                                    slotIsSaveMode = false
                                    showSlotDialog = true
                                },
                                onSettings = {
                                    menuOpen = false
                                    releaseAllHeldActions()
                                    gameSession.pause(autoSave = false)
                                    showSettings = true
                                },
                                onEditLayout = {
                                    menuOpen = false
                                    // 进入编辑器会暂停仿真，先释放键盘源，避免计数残留。
                                    releaseAllHeldActions()
                                    // 重置计数，避免下次进入编辑器时立即弹出上一次的返回菜单。
                                    layoutEditorBackRequest = 0
                                    layoutEditing = true
                                    gameSession.pause(autoSave = false)
                                },
                                onCoreOptions = {
                                    menuOpen = false
                                    releaseAllHeldActions()
                                    gameSession.pause(autoSave = false)
                                    coreOptions = gameSession.coreOptions
                                    showCoreOptions = true
                                },
                                onCheats = {
                                    menuOpen = false
                                    releaseAllHeldActions()
                                    gameSession.pause(autoSave = false)
                                    showCheats = true
                                },
                                fastForwarding = fastForwarding,
                                gameSpeed = appSettings.gameSpeed,
                                onGameSpeedChange = { speed ->
                                    settingsStore.update { s ->
                                        s.copy(gameSpeed = speed)
                                    }
                                    // 拖动即生效：菜单打开期间仿真虽已暂停，
                                    // 速度会保留到关闭菜单恢复运行时。
                                    gameSession.setSpeed(speed)
                                    fastForwarding = speed != 1f
                                },
                                onFastForward = {
                                    toggleFastForward()
                                    menuOpen = false
                                    resumeIfIdle()
                                },
                                onReset = {
                                    menuOpen = false
                                    resumeIfIdle()
                                    doReset()
                                },
                                onExit = {
                                    menuOpen = false
                                    resumeIfIdle()
                                    doExit()
                                },
                                onDismiss = { closeMenu() },
                            )
                        }

                        if (showSlotDialog && !fullScreenPageOpen) {
                            SaveSlotDialog(
                                romId = romId,
                                loadSlots = gameSession::listSlots,
                                isSaveMode = slotIsSaveMode,
                                onDismiss = {
                                    showSlotDialog = false
                                    resumeIfIdle()
                                },
                                onSelect = { slot ->
                                    showSlotDialog = false
                                    if (slotIsSaveMode) saveStateSlot(slot) else loadStateSlot(slot)
                                    resumeIfIdle()
                                },
                                onDelete = { slot -> deleteStateSlot(slot) },
                            )
                        }

                        if (appSettings.hasDebugOverlay && !fullScreenPageOpen && !loading) {
                            val metrics = resources.displayMetrics
                            PerformanceOverlay(
                                stats = gameSession.performance,
                                droppedFrames = { gameSession.framesDropped },
                                settings = appSettings,
                                shaderLabel = shaderConfig.displayName(),
                                info = DebugOverlayInfo(
                                    romName = romTitle,
                                    romPath = gameSession.romPath,
                                    coreName = gameSession.coreName,
                                    coreVersion = gameSession.coreVersion,
                                    coreFps = gameSession.avInfo.fps,
                                    sampleRate = gameSession.avInfo.sampleRate.toInt(),
                                    surfaceWidth = surfaceView.width.takeIf { it > 0 }
                                        ?: metrics.widthPixels,
                                    surfaceHeight = surfaceView.height.takeIf { it > 0 }
                                        ?: metrics.heightPixels,
                                    refreshRate = display?.refreshRate ?: 0f,
                                    deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}",
                                    androidLabel = "Android ${Build.VERSION.RELEASE}" +
                                            "（API ${Build.VERSION.SDK_INT}）",
                                    appVersion = appVersionName,
                                ),
                            )
                        }

                        if (loading) {
                            LoadingOverlay(romTitle)
                        }
                    }
                }
            })
        })
        surfaceView.attach(gameSession.frameSource, gameSession.performance)
        // 每产出一帧就请求一次渲染：GL 线程只在有新画面时绘制，
        // 避免连续模式下空交换导致的旧帧回退，也降低高刷屏幕的 GPU 占用。
        // 设置了较低的呈现帧率时跳过部分帧（不请求渲染就不会交换缓冲）。
        gameSession.onFrameProduced = {
            if (shouldPresentFrame()) surfaceView.requestRender()
        }
        surfaceView.setShaderErrorListener { message ->
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
        observeSettings()
        observeShader()

        lifecycleScope.launch(Dispatchers.Main) {
            val (romFile, sha256) = try {
                withContext(Dispatchers.IO) {
                    if (romUri != null) {
                        val entry = AndroidRomRepository.prepareRom(this@GameActivity, romUri)
                        entry.cachedFile to entry.sha256
                    } else {
                        val file = File(romPath!!)
                        if (!file.canRead()) {
                            throw IllegalArgumentException("Cannot read ROM: $romPath")
                        }
                        // 按内容标识区分存档与金手指；缓存文件名就是 SHA-256。
                        file to AndroidRomRepository.sha256For(this@GameActivity, file)
                    }
                }
            } catch (e: Exception) {
                fail("Failed to prepare ROM: ${e.message}")
                return@launch
            }
            val result = gameSession.load(romFile, sha256)
            // 以会话内部的 romId 为准，避免 UI 重复实现截断规则。
            romId = gameSession.romId

            when (result) {
                is GameSession.LoadResult.InitFailed -> {
                    fail("Core init failed: ${result.cause.message}")
                    return@launch
                }

                is GameSession.LoadResult.GameLoadFailed -> {
                    fail("ROM load failed: ${result.cause.message}")
                    return@launch
                }

                GameSession.LoadResult.Success -> Unit
            }

            gameSession.start()
            loading = false
        }
    }

    override fun onPause() {
        super.onPause()
        if (::gameSession.isInitialized) {
            releaseAllHeldActions()
            gameSession.pause()
        }
        if (::surfaceView.isInitialized) surfaceView.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::surfaceView.isInitialized) surfaceView.onResume()
        if (::gameSession.isInitialized) {
            // 菜单、布局编辑器、核心选项、金手指、设置页打开期间保持仿真暂停。
            if (!menuOpen && !layoutEditing && !showCoreOptions &&
                !showCheats && !showSettings
            ) {
                gameSession.resume()
            }
            // 系统栏可能被系统临时恢复，重新应用一次显示设置。
            applyDisplaySettings(settingsStore.settings.value)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 先解除渲染器引用，再关闭核心，避免 GL 线程访问已释放的会话。
        if (::surfaceView.isInitialized) surfaceView.detach()
        if (::gameSession.isInitialized) gameSession.close()
    }

    /** 把设置变化实时应用到窗口、系统栏、渲染器和快进速度。 */
    private fun observeSettings() {
        lifecycleScope.launch {
            // 只应用真正变化的字段：音量、倍率等高频改动不应反复触碰
            // 窗口 Insets 和渲染器，否则拖动滑块时会出现卡顿/不同步。
            var previous: AppSettings? = null
            settingsStore.settings.collect { settings ->
                val last = previous
                previous = settings

                if (last == null ||
                    last.screenOrientation != settings.screenOrientation ||
                    last.fullScreenMode != settings.fullScreenMode ||
                    last.fullScreenImmersive != settings.fullScreenImmersive ||
                    last.displayFps != settings.displayFps
                ) {
                    applyDisplaySettings(settings)
                }

                if (::surfaceView.isInitialized &&
                    (last == null ||
                        last.stretchToFit != settings.stretchToFit ||
                        last.linearFiltering != settings.linearFiltering)
                ) {
                    surfaceView.applyVideoSettings(
                        stretchToFit = settings.stretchToFit,
                        linearFiltering = settings.linearFiltering,
                    )
                }

                // 只要游戏内的速度设置变化就立即应用，不依赖 fastForwarding：
                // 菜单滑块和「设置 → 杂项」里的滑块都走同一路径，避免状态不同步。
                // 首次发射（last == null）不应用，保证每次进入游戏从 1x 开始。
                if (::gameSession.isInitialized &&
                    last != null && last.gameSpeed != settings.gameSpeed
                ) {
                    gameSession.setSpeed(settings.gameSpeed)
                    fastForwarding = settings.gameSpeed != 1f
                }
            }
        }
    }

    /** 监听着色器配置；编译由 GL 线程在下一帧完成。 */
    private fun observeShader() {
        lifecycleScope.launch {
            shaderStore.config.collect { config ->
                if (::surfaceView.isInitialized) {
                    surfaceView.applyShader(config.preset, config.source)
                }
            }
        }
    }

    /** 应用屏幕方向、全屏/沉浸模式。 */
    private fun applyDisplaySettings(settings: AppSettings) {
        val targetOrientation = when (settings.screenOrientation) {
            ScreenOrientation.Sensor -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            ScreenOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            ScreenOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (requestedOrientation != targetOrientation) {
            requestedOrientation = targetOrientation
        }

        applyDisplayFps(settings.displayFps)

        WindowCompat.setDecorFitsSystemWindows(window, !settings.fullScreenMode)
        val controller = WindowCompat.getInsetsController(window, window.decorView)

        when {
            settings.fullScreenMode && settings.fullScreenImmersive -> {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }

            settings.fullScreenMode -> {
                controller.show(WindowInsetsCompat.Type.navigationBars())
                controller.hide(WindowInsetsCompat.Type.statusBars())
            }

            else -> {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    /**
     * 按设置选择屏幕刷新率。
     *
     * 模拟器本身固定按核心帧率（GBA 约 59.73 Hz）推进，这里调整的是
     * 屏幕呈现节奏：刷新率越高，新帧等待下一次 vsync 的时间越短，画面越稳。
     * 只考虑与当前显示模式分辨率相同的模式，避免顺带切换分辨率。
     * [fps] 为 0 时恢复系统默认选择。
     */
    private fun applyDisplayFps(fps: Int) {
        // 软件层同时限制呈现频率：部分设备没有 30 Hz 显示模式，
        // 靠跳帧也能保证“30 Hz”选项的实际效果。
        // 核心帧率约 59.73 Hz，60 及以上的上限没有意义：按“上次呈现时间”
        // 节流会因为几十微秒的抖动误跳帧，反而制造卡顿，因此不做限制。
        presentIntervalNanos = if (fps in 1 until 60) 1_000_000_000L / fps else 0L

        val targetModeId = if (fps <= 0) {
            0
        } else {
            val currentDisplay = display ?: return
            val currentMode = currentDisplay.mode
            val modes = currentDisplay.supportedModes
            val sameResolution = modes.filter {
                it.physicalWidth == currentMode.physicalWidth &&
                        it.physicalHeight == currentMode.physicalHeight
            }
            val candidates = if (sameResolution.isNotEmpty()) {
                sameResolution
            } else {
                modes.toList()
            }
            candidates.minByOrNull { abs(it.refreshRate - fps) }?.modeId ?: 0
        }

        val attrs = window.attributes
        if (attrs.preferredDisplayModeId == targetModeId) return
        attrs.preferredDisplayModeId = targetModeId
        window.attributes = attrs
    }

    /**
     * 按设定的帧率节流呈现；返回 false 时这一帧不请求渲染，
     * 也就不会发生缓冲交换，下一帧再尝试。
     */
    private fun shouldPresentFrame(): Boolean {
        val interval = presentIntervalNanos
        if (interval <= 0L) return true

        val now = System.nanoTime()
        if (now - lastPresentNanos < interval) return false
        lastPresentNanos = now
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (layoutEditing) {
            // 编辑布局时不把按键送入游戏；返回键通过计数交给编辑器，
            // 由它决定关闭当前弹窗还是打开布局选项菜单。
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    layoutEditorBackRequest++
                }
                return true
            }
            return true
        }

        if (showCoreOptions) {
            // 核心选项页打开时不向游戏发送按键；返回键关闭页面并恢复仿真。
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    exitCoreOptions()
                }
                return true
            }
            return true
        }

        if (showCheats) {
            // 金手指页打开时不向游戏发送按键；返回键关闭页面并恢复仿真。
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    exitCheats()
                }
                return true
            }
            return true
        }

        if (showSettings) {
            // 设置页用 BackHandler 逐级返回（分类 → 首页 → 游戏）；
            // 其余按键不送入正在暂停的仿真核心。
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                return super.dispatchKeyEvent(event)
            }
            return true
        }

        if (menuOpen) {
            // 菜单打开时返回键只关闭菜单，不退出游戏。
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    closeMenu()
                }
                return true
            }
            // 映射为「打开菜单」的物理键可以再次按下关闭菜单。
            val mapped = keyMapStore.state.value.selectedProfile.actionFor(event.keyCode)
            if (event.action == KeyEvent.ACTION_DOWN &&
                event.repeatCount == 0 &&
                mapped?.shortcut == InputShortcut.OPEN_MENU
            ) {
                closeMenu()
            }
            return true
        }

        if (!::gameSession.isInitialized) return super.dispatchKeyEvent(event)

        val action = keyMapStore.state.value.selectedProfile.actionFor(event.keyCode)
            ?: return super.dispatchKeyEvent(event)

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    when (action.shortcut) {
                        null -> {
                            pressAction(action)
                            heldActions[event.keyCode] = action
                        }

                        InputShortcut.FAST_FORWARD -> toggleFastForward()
                        InputShortcut.OPEN_MENU -> openMenu()

                        InputShortcut.SAVE_STATE -> {
                            releaseAllHeldActions()
                            slotIsSaveMode = true
                            showSlotDialog = true
                        }

                        InputShortcut.LOAD_STATE -> {
                            releaseAllHeldActions()
                            slotIsSaveMode = false
                            showSlotDialog = true
                        }
                    }
                }
                return true
            }

            KeyEvent.ACTION_UP -> {
                heldActions.remove(event.keyCode)?.let { releaseAction(it) }
                return true
            }
        }
        return true
    }

    /** 按下动作对应的所有 GBA 按键（组合键会同时按下多个）。 */
    private fun pressAction(action: InputAction) {
        for (button in action.gbaButtons) {
            val count = buttonRefCounts[button] ?: 0
            if (count == 0) {
                gameSession.keyboardInput.press(button)
            }
            buttonRefCounts[button] = count + 1
        }
    }

    /** 松开动作对应的按键；多个物理键映射到同一按键时使用引用计数。 */
    private fun releaseAction(action: InputAction) {
        for (button in action.gbaButtons) {
            val count = buttonRefCounts[button] ?: 0
            if (count <= 1) {
                if (count == 1) {
                    gameSession.keyboardInput.release(button)
                }
                buttonRefCounts.remove(button)
            } else {
                buttonRefCounts[button] = count - 1
            }
        }
    }

    /**
     * 打开菜单/存档对话框前释放全部键盘源按键。
     * 独立 Dialog 窗口可能收不到后续 ACTION_UP，避免按键卡住。
     */
    private fun releaseAllHeldActions() {
        heldActions.clear()
        buttonRefCounts.clear()
        if (::gameSession.isInitialized) {
            gameSession.keyboardInput.releaseAll()
        }
    }

    // -- 菜单动作 ------------------------------------------------------------

    private fun saveStateSlot(slot: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = gameSession.saveState(slot)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@GameActivity,
                    if (ok) "已保存到槽位 $slot" else "保存失败",
                    Toast.LENGTH_SHORT,
                ).show()
                // 重新打开对话框以刷新槽位列表。
                showSlotDialog = false
                if (ok) {
                    slotIsSaveMode = false // 切到读取模式，便于确认存档已写入
                    showSlotDialog = true
                }
            }
        }
    }

    private fun loadStateSlot(slot: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = gameSession.loadState(slot)
            withContext(Dispatchers.Main) {
                when (result) {
                    is SavestateLoadResult.Success ->
                        Toast.makeText(this@GameActivity, "已加载槽位 $slot", Toast.LENGTH_SHORT).show()

                    SavestateLoadResult.SlotEmpty ->
                        Toast.makeText(this@GameActivity, "槽位 $slot 为空", Toast.LENGTH_SHORT).show()

                    is SavestateLoadResult.RomMismatch ->
                        Toast.makeText(this@GameActivity, "ROM 不匹配", Toast.LENGTH_SHORT).show()

                    is SavestateLoadResult.Corrupted ->
                        Toast.makeText(this@GameActivity, "存档损坏: ${result.reason}", Toast.LENGTH_SHORT).show()

                    SavestateLoadResult.CoreRejected ->
                        Toast.makeText(this@GameActivity, "核心拒绝了存档", Toast.LENGTH_SHORT).show()

                    is SavestateLoadResult.FileError ->
                        Toast.makeText(this@GameActivity, "文件错误: ${result.reason}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun deleteStateSlot(slot: Int) {
        gameSession.deleteState(slot)
    }

    private fun toggleFastForward() {
        fastForwarding = !fastForwarding
        val speed = if (fastForwarding) {
            val saved = settingsStore.settings.value.gameSpeed
            // 滑块停在 1x 时没有可用的快进倍率，回落到默认 4x。
            val effective = if (saved == 1f) SpeedPresets.DEFAULT_SPEED else saved
            if (effective != saved) {
                settingsStore.update { it.copy(gameSpeed = effective) }
            }
            effective
        } else {
            1f
        }
        gameSession.setSpeed(speed)
        val label = if (fastForwarding) {
            if (SpeedPresets.isSlow(speed)) "${SpeedPresets.label(speed)} 慢放"
            else "${SpeedPresets.label(speed)} 快进"
        } else {
            "正常速度"
        }
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
    }

    private fun doReset() {
        lifecycleScope.launch(Dispatchers.IO) {
            gameSession.reset()
        }
    }

    private fun exitCoreOptions() {
        showCoreOptions = false
        resumeIfIdle()
    }

    private fun exitCheats() {
        showCheats = false
        resumeIfIdle()
    }

    private fun exitSettings() {
        showSettings = false
        resumeIfIdle()
    }

    /** 打开游戏菜单：暂停仿真，避免菜单操作期间游戏继续运行。 */
    private fun openMenu() {
        if (menuOpen) return
        menuOpen = true
        releaseAllHeldActions()
        if (::gameSession.isInitialized) {
            gameSession.pause(autoSave = false)
        }
    }

    /** 关闭菜单；没有其它覆盖页时恢复仿真。 */
    private fun closeMenu() {
        if (!menuOpen) return
        menuOpen = false
        resumeIfIdle()
    }

    /** 没有菜单/全屏覆盖页/存档弹窗时恢复仿真。 */
    private fun resumeIfIdle() {
        if (!::gameSession.isInitialized) return
        if (menuOpen || layoutEditing || showCoreOptions || showCheats ||
            showSettings || showSlotDialog || loading
        ) {
            return
        }
        gameSession.resume()
    }

    private fun doExit() {
        if (settingsStore.settings.value.confirmOnClose) {
            AlertDialog.Builder(this)
                .setTitle("退出游戏")
                .setMessage("确定退出吗？")
                .setPositiveButton("退出") { _, _ -> finish() }
                .setNegativeButton("取消", null)
                .show()
        } else {
            finish()
        }
    }

    private fun fail(message: String) {
        loading = false
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        const val EXTRA_ROM_PATH = "rom_path"
        const val EXTRA_ROM_URI = "rom_uri"

        fun createIntent(context: android.content.Context, uri: Uri): Intent =
            Intent(context, GameActivity::class.java).putExtra(EXTRA_ROM_URI, uri.toString())

        fun createIntent(context: android.content.Context, path: String): Intent =
            Intent(context, GameActivity::class.java).putExtra(EXTRA_ROM_PATH, path)
    }
}

/** 调试浮层的采样间隔。 */
private const val PERF_SAMPLE_INTERVAL_MS = 500L

private data class PerformanceSample(
    val emulatedFps: Double = 0.0,
    val renderedFps: Double = 0.0,
    val frameMillis: Double = 0.0,
    val droppedPerSecond: Double = 0.0,
    val gameWidth: Int = 0,
    val gameHeight: Int = 0,
    val availableGb: Double = 0.0,
    val totalGb: Double = 0.0,
)

/** 调试浮层需要显示的静态信息，由游戏页在组合时提供。 */
private data class DebugOverlayInfo(
    val romName: String,
    val romPath: String,
    val coreName: String?,
    val coreVersion: String?,
    val coreFps: Double,
    val sampleRate: Int,
    val surfaceWidth: Int,
    val surfaceHeight: Int,
    val refreshRate: Float,
    val deviceLabel: String,
    val androidLabel: String,
    val appVersion: String,
)

/**
 * 调试浮层：左上角按开关绘制帧率、变速、着色器、分辨率、音频、ROM 与系统信息。
 *
 * “游戏 FPS”是核心真正推进的 GBA 帧数；“绘制 FPS”是实际绘制上屏的次数。
 * 两者明显不一致时，瓶颈通常在渲染（例如复杂着色器）或呈现刷新率。
 * 每个分组使用独立颜色，便于同时开启时区分。
 */
@Composable
private fun PerformanceOverlay(
    stats: PerformanceStats,
    droppedFrames: () -> Long,
    settings: AppSettings,
    shaderLabel: String,
    info: DebugOverlayInfo,
) {
    val context = LocalContext.current
    var sample by remember { mutableStateOf(PerformanceSample()) }

    LaunchedEffect(stats) {
        var last = stats.counters()
        var lastTime = System.nanoTime()
        // 丢帧数是累计值，浮层只显示本采样窗口内新增的速率。
        var lastDropped = droppedFrames()
        while (isActive) {
            delay(PERF_SAMPLE_INTERVAL_MS)
            val now = System.nanoTime()
            val current = stats.counters()
            // 读取丢帧计数需要经过核心线程，放到 IO 上避免阻塞主线程。
            val droppedNow = withContext(Dispatchers.IO) { droppedFrames() }
            val memory = withContext(Dispatchers.IO) { readMemoryGb(context) }
            val frameSize = stats.frameSize()
            val elapsedNanos = (now - lastTime).coerceAtLeast(1L)
            val frames = current.emulatedFrames - last.emulatedFrames
            val rendered = current.renderedFrames - last.renderedFrames
            val emulatedNanos = current.emulatedNanos - last.emulatedNanos

            sample = PerformanceSample(
                emulatedFps = frames * 1_000_000_000.0 / elapsedNanos,
                renderedFps = rendered * 1_000_000_000.0 / elapsedNanos,
                frameMillis = if (frames > 0) emulatedNanos / 1_000_000.0 / frames else 0.0,
                droppedPerSecond =
                    (droppedNow - lastDropped) * 1_000_000_000.0 / elapsedNanos,
                gameWidth = frameSize.width,
                gameHeight = frameSize.height,
                availableGb = memory?.first ?: 0.0,
                totalGb = memory?.second ?: 0.0,
            )
            last = current
            lastDropped = droppedNow
            lastTime = now
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopStart,
    ) {
        // 调试数据直接绘制在游戏画面上，不使用任何背景板。
        Column(
            modifier = Modifier
                .padding(start = 12.dp, top = 12.dp)
                .widthIn(max = 360.dp),
        ) {
            val style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF8CEB8C),
            )
            if (settings.showFps) {
                Text("游戏 ${"%.1f".format(sample.emulatedFps)} FPS", style = style)
                Text("绘制 ${"%.1f".format(sample.renderedFps)} FPS", style = style)
                Text("帧耗时 ${"%.2f".format(sample.frameMillis)} ms", style = style)
                Text("掉帧 ${"%.1f".format(sample.droppedPerSecond)} /s", style = style)
            }
            if (settings.showSpeedStatus) {
                // 与帧率的绿色区分：快进橙色、慢放蓝色、正常灰色。
                val (speedColor, stateLabel) = when {
                    settings.gameSpeed > 1f -> Color(0xFFFFB74D) to "快进"
                    settings.gameSpeed < 1f -> Color(0xFF64B5F6) to "慢放"
                    else -> Color(0xFFB0BEC5) to "正常"
                }
                Text(
                    text = "速度 ${SpeedPresets.label(settings.gameSpeed)} · $stateLabel",
                    style = style.copy(color = speedColor),
                )
            }
            if (settings.showShaderInfo) {
                Text(
                    text = "着色器 $shaderLabel",
                    style = style.copy(color = Color(0xFF4DD0E1)),
                )
            }
            if (settings.showResolutionInfo) {
                val gameSize = if (sample.gameWidth > 0 && sample.gameHeight > 0) {
                    "${sample.gameWidth}×${sample.gameHeight}"
                } else {
                    "未知"
                }
                val refresh = if (info.refreshRate > 0f) {
                    "${"%.2f".format(info.refreshRate)} Hz"
                } else {
                    "未知"
                }
                Text(
                    text = "游戏 $gameSize · 屏幕 ${info.surfaceWidth}×${info.surfaceHeight}",
                    style = style.copy(color = Color(0xFF81C784)),
                )
                Text(
                    text = "刷新率 $refresh",
                    style = style.copy(color = Color(0xFF81C784)),
                )
            }
            if (settings.showVolumeInfo) {
                val volume = if (settings.enableSound) {
                    "${settings.soundVolume}%"
                } else {
                    "静音"
                }
                Text(
                    text = "音量 $volume",
                    style = style.copy(color = Color(0xFFFFF176)),
                )
            }
            if (settings.showSampleRateInfo) {
                val rate = if (info.sampleRate > 0) "${info.sampleRate} Hz" else "未知"
                Text(
                    text = "采样率 $rate",
                    style = style.copy(color = Color(0xFFFFCC80)),
                )
            }
            if (settings.showRomName) {
                Text(
                    text = "ROM ${info.romName}",
                    style = style.copy(color = Color(0xFF90CAF9)),
                )
            }
            if (settings.showRomPath) {
                Text(
                    text = "路径 ${info.romPath}",
                    style = style.copy(color = Color(0xFF9FA8DA)),
                )
            }
            if (settings.showSystemInfo) {
                Text(
                    text = "系统 ${info.deviceLabel}",
                    style = style.copy(color = Color(0xFFF48FB1)),
                )
                Text(
                    text = "${info.androidLabel} · 应用 ${info.appVersion}",
                    style = style.copy(color = Color(0xFFF48FB1)),
                )
            }
            if (settings.showCoreInfo) {
                val core = listOfNotNull(info.coreName, info.coreVersion)
                    .joinToString(" ")
                    .ifBlank { "mGBA（libretro）" }
                val fps = if (info.coreFps > 0) {
                    "${"%.2f".format(info.coreFps)} Hz"
                } else {
                    "未知"
                }
                Text(
                    text = "核心 $core · $fps",
                    style = style.copy(color = Color(0xFFB39DDB)),
                )
            }
            if (settings.showMemoryInfo) {
                Text(
                    text = "内存 ${"%.1f".format(sample.availableGb)} / " +
                            "${"%.1f".format(sample.totalGb)} GB",
                    style = style.copy(color = Color(0xFF80CBC4)),
                )
            }
        }
    }
}

/** 读取系统可用内存与总内存（GB）。 */
private fun readMemoryGb(context: Context): Pair<Double, Double>? {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        ?: return null
    val info = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(info)
    val gb = 1024.0 * 1024.0 * 1024.0
    return info.availMem / gb to info.totalMem / gb
}

/** ROM 加载遮罩：覆盖游戏画面，避免加载期间只看到空白 Surface。 */
@Composable
private fun LoadingOverlay(title: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(
                text = "正在加载 $title…",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
