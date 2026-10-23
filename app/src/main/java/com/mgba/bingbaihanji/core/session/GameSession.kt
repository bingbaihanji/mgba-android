package com.mgba.bingbaihanji.core.session

import com.mgba.bingbaihanji.core.audio.AudioPump
import com.mgba.bingbaihanji.core.audio.AudioSink
import com.mgba.bingbaihanji.core.cheat.Cheat
import com.mgba.bingbaihanji.core.cheat.CheatStore
import com.mgba.bingbaihanji.core.emulation.AvInfo
import com.mgba.bingbaihanji.core.emulation.CoreOption
import com.mgba.bingbaihanji.core.emulation.CoreOptionStore
import com.mgba.bingbaihanji.core.emulation.EmulatorCore
import com.mgba.bingbaihanji.core.emulation.EmulationLoop
import com.mgba.bingbaihanji.core.emulation.FrameSource
import com.mgba.bingbaihanji.core.emulation.loadCoreOptions
import com.mgba.bingbaihanji.core.emulation.INPUT_SOURCE_KEYBOARD
import com.mgba.bingbaihanji.core.emulation.INPUT_SOURCE_VIRTUAL
import com.mgba.bingbaihanji.core.input.CoreInputDevice
import com.mgba.bingbaihanji.core.input.InputDevice
import com.mgba.bingbaihanji.core.perf.PerformanceStats
import com.mgba.bingbaihanji.core.settings.SettingsStore
import com.mgba.bingbaihanji.core.storage.BatterySaveRepository
import com.mgba.bingbaihanji.core.storage.RomCatalog
import com.mgba.bingbaihanji.core.storage.SaveStorage
import com.mgba.bingbaihanji.core.storage.SavestateLoadResult
import com.mgba.bingbaihanji.core.storage.SavestateRepository
import com.mgba.bingbaihanji.core.storage.SlotStatus
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 游戏会话：把仿真核心、帧循环、音频泵、输入设备、存档与设置编排在一起。
 *
 * 平台层（Android Activity / 桌面窗口）只负责转发生命周期与提供平台实现，
 * 具体的「何时暂停、何时自动存档、如何跟随设置变化」都在这里统一处理，
 * 因此两端可以共享同一套行为。
 *
 * 该类同时是核心与存储服务的组合根：外部只需提供 [EmulatorCore]、
 * [SaveStorage] 和平台音频工厂，不需要自己拼装各个仓库。
 *
 * 线程约定：除 [load] 与 [reset]（内部切到 IO）外，其余方法应在主线程调用；
 * 文件 I/O 由内部协程切换到 [Dispatchers.IO]。
 */
class GameSession(
    /** 底层仿真核心。 */
    private val core: EmulatorCore,
    /** 按核心采样率创建平台音频设备。 */
    private val audioSinkFactory: (sampleRate: Int) -> AudioSink,
    /** 存档字节存储；会话内部据此创建存档仓库。 */
    saveStorage: SaveStorage,
    private val settings: SettingsStore,
    /** 核心选项的持久化值；游戏加载后自动应用。 */
    private val coreOptionsStore: CoreOptionStore,
    /** 按 ROM 保存的金手指。 */
    private val cheatStore: CheatStore,
    private val scope: CoroutineScope,
) : AutoCloseable {

    /** 即时存档仓库，仅会话内部使用；外部通过 [saveState]/[loadState]/[listSlots] 访问。 */
    private val savestates: SavestateRepository = SavestateRepository(core, saveStorage)

    /** 电池存档仓库（SRAM/RTC），仅会话内部使用。 */
    private val batterySaves: BatterySaveRepository = BatterySaveRepository(core, saveStorage)

    /** 渲染层需要的帧来源；不暴露完整的 [EmulatorCore]。 */
    val frameSource: FrameSource get() = core

    /** 调试浮层使用的性能计数（仿真帧率、绘制帧率、帧耗时）。 */
    val performance = PerformanceStats()

    /** 因渲染跟不上而被丢弃的帧数，供调试浮层显示。 */
    val framesDropped: Long get() = core.framesDropped

    /**
     * 核心每产出一帧后的回调。
     *
     * 平台层用它请求一次渲染（例如 GLSurfaceView.requestRender），
     * 让渲染频率与仿真帧率一致，而不是按屏幕刷新率空转。
     */
    @Volatile
    var onFrameProduced: (() -> Unit)? = null

    private val loop = EmulationLoop(core, performance) { onFrameProduced?.invoke() }

    private var audioPump: AudioPump? = null
    private var settingsJob: Job? = null
    private var periodicSaveJob: Job? = null

    /** 串行化金手指应用，避免“清空 + 逐条添加”被并发调用打断。 */
    private val cheatLock = Any()

    /** 金手指应用请求序号；只让最新一次请求生效。 */
    private val cheatSequence = AtomicLong(0)

    /** [start] 是否已经成功执行过；用于决定生命周期恢复时是否重启帧循环。 */
    private var started = false

    /** ROM 是否已经成功加载；金手指只能在加载后应用。 */
    private var gameLoaded = false

    /** 平台生命周期是否处于暂停状态；后台时设置变化不应唤醒音频。 */
    private var lifecyclePaused = false

    /** 虚拟按键设备（输入源 0）。 */
    val virtualInput: InputDevice = CoreInputDevice(core, INPUT_SOURCE_VIRTUAL)

    /** 键盘输入设备（输入源 1）。 */
    val keyboardInput: InputDevice = CoreInputDevice(core, INPUT_SOURCE_KEYBOARD)

    /** 当前 ROM 的绝对路径。 */
    var romPath: String = ""
        private set

    /** 当前 ROM 的完整 SHA-256；按路径加载时为空。 */
    var romSha256: String = ""
        private set

    /** 存档文件使用的 ROM 标识（SHA-256 前 16 位）。 */
    var romId: String = ""
        private set

    /** 核心上报的音视频时序。 */
    val avInfo: AvInfo get() = core.avInfo

    /** 核心名称，供设置里的“系统信息”展示。 */
    val coreName: String? get() = core.coreName

    /** 核心版本，供设置里的“系统信息”展示。 */
    val coreVersion: String? get() = core.coreVersion

    /** ROM 加载结果；区分初始化失败与加载失败，便于平台层给出准确提示。 */
    sealed interface LoadResult {
        data object Success : LoadResult
        data class InitFailed(val cause: Throwable) : LoadResult
        data class GameLoadFailed(val cause: Throwable) : LoadResult
    }

    /**
     * 初始化核心、加载 ROM，并在有 SHA-256 时恢复 SRAM/RTC。
     * 必须在 [start] 之前调用。
     */
    suspend fun load(romFile: File, sha256: String): LoadResult {
        romPath = romFile.absolutePath
        romSha256 = sha256
        romId = sha256.take(ROM_ID_LENGTH)

        val init = withContext(Dispatchers.IO) { core.initialize() }
        if (init.isFailure) {
            return LoadResult.InitFailed(init.exceptionOrNull() ?: IllegalStateException("init failed"))
        }

        // 核心在初始化时通过 SET_VARIABLES 注册选项。
        // 先应用一次，确保 BIOS/存档类型等“加载时读取”的选项生效。
        withContext(Dispatchers.IO) { applySavedCoreOptions() }

        val load = withContext(Dispatchers.IO) { core.loadGame(romPath) }
        if (load.isFailure) {
            return LoadResult.GameLoadFailed(load.exceptionOrNull() ?: IllegalStateException("load failed"))
        }
        gameLoaded = true

        // 再应用一次，覆盖加载过程中重新注册/重置的选项。
        withContext(Dispatchers.IO) { applySavedCoreOptions() }

        if (sha256.isNotEmpty()) {
            withContext(Dispatchers.IO) { batterySaves.load(sha256) }
        }

        // 实现设置里“启动时自动加载”：恢复上次退出时写入的自动存档。
        if (settings.settings.value.autoSaveOnExit) {
            withContext(Dispatchers.IO) { restoreAutoSave() }
        }

        // 加载完成后应用该 ROM 已启用的金手指。
        withContext(Dispatchers.IO) { applyCheats() }

        return LoadResult.Success
    }

    /**
     * 重新应用当前 ROM 的金手指。
     *
     * Libretro 的 cheat 设备是“先清空、再逐条添加”的模型；
     * 只添加启用项，禁用项不会进入核心。
     */
    fun applyCheats(cheats: List<Cheat>? = null) {
        if (!gameLoaded) return

        val sequence = cheatSequence.incrementAndGet()
        synchronized(cheatLock) {
            // 更新请求在等待锁时可能已经到达，让最新请求胜出。
            if (sequence != cheatSequence.get()) return

            val list = cheats ?: cheatStore.load(romId.ifEmpty { "unknown" })
            core.cheatReset()

            var index = 0
            for (cheat in list) {
                if (!cheat.enabled) continue
                for (code in cheat.codes) {
                    core.cheatSet(index++, true, code)
                }
            }
        }
    }

    /**
     * 恢复自动存档槽位。不存在、ROM 不匹配或损坏时静默忽略，
     * 让玩家从 SRAM 或开局状态继续，而不是被一个坏存档挡住。
     */
    private fun restoreAutoSave() {
        when (val result = savestates.load(romId, AUTO_SAVE_SLOT)) {
            is SavestateLoadResult.Success -> LOG.info("Restored auto-save for $romId")
            SavestateLoadResult.SlotEmpty -> Unit
            else -> LOG.warning("Auto-save ignored for $romId: $result")
        }
    }

    /** 当前核心注册的选项；只在游戏加载后可用。 */
    val coreOptions: List<CoreOption>
        get() = core.loadCoreOptions()

    /**
     * 修改核心选项并持久化。
     *
     * 部分选项需要重置游戏或重新加载核心后完全生效。
     */
    fun setCoreOption(option: CoreOption, valueIndex: Int): Boolean {
        val value = option.values.getOrNull(valueIndex) ?: return false
        val applied = core.setOption(option.index, valueIndex)
        if (applied) {
            coreOptionsStore.set(core.coreName ?: DEFAULT_CORE_NAME, option.key, value)
        }
        return applied
    }

    /** 把已保存的核心选项应用到刚加载的核心。 */
    private fun applySavedCoreOptions() {
        val coreName = core.coreName ?: DEFAULT_CORE_NAME
        val saved = coreOptionsStore.values(coreName)
        if (saved.isEmpty()) return

        for (option in core.loadCoreOptions()) {
            val value = saved[option.key] ?: continue
            val valueIndex = option.values.indexOf(value)
            if (valueIndex >= 0 && valueIndex != option.currentIndex) {
                core.setOption(option.index, valueIndex)
            }
        }
    }

    /**
     * 启动音频与帧循环，并开始把设置变化实时应用到音频输出。
     * 必须在 [load] 成功后调用一次。
     */
    fun start() {
        if (started) return
        started = true

        val av = core.avInfo
        val fps = if (av.fps > 0) av.fps else 60.0

        val pump = AudioPump(core, audioSinkFactory(av.sampleRate.toInt()))
        val current = settings.settings.value
        pump.volume = current.soundVolume
        // 若加载期间页面已经进入后台，这里只创建泵，等 resume() 再启动。
        if (current.enableSound && !lifecyclePaused) pump.start()
        audioPump = pump

        if (!lifecyclePaused) {
            loop.start(fps)
        }
        observeSettings()
        startPeriodicSave()
    }

    /**
     * 游戏过程中定期把 SRAM 落盘。
     *
     * 进程被系统杀死或崩溃时不会执行 onDestroy，只有定期写入才能保住
     * 电池存档进度；即时存档仍只在暂停/退出时写入，避免频繁序列化。
     */
    private fun startPeriodicSave() {
        if (periodicSaveJob != null) return
        periodicSaveJob = scope.launch {
            while (isActive) {
                delay(AUTO_SAVE_INTERVAL_MS)
                if (!settings.settings.value.autoSaveOnExit) continue
                if (lifecyclePaused || !gameLoaded) continue
                withContext(Dispatchers.IO) {
                    try {
                        if (romSha256.isNotEmpty()) batterySaves.save(romSha256)
                    } catch (e: Exception) {
                        LOG.warning("Periodic save failed: ${e.message}")
                    }
                }
            }
        }
    }

    /** 跟随设置变化调整音量与静音状态（与重构前一致，首个发射值也会生效）。 */
    private fun observeSettings() {
        settingsJob = scope.launch {
            settings.settings.collect { s ->
                audioPump?.volume = s.soundVolume
                // 页面在后台时只更新音量/暂停，不要让设置变化把音频唤醒。
                if (s.enableSound && !lifecyclePaused) {
                    audioPump?.resume()
                } else {
                    audioPump?.pause()
                }
            }
        }
    }

    /**
     * 平台生命周期暂停：停止帧循环、释放键盘输入、暂停音频。
     *
     * [autoSave] 为 false 时只暂停不写存档，供布局编辑器等临时覆盖层使用，
     * 避免每次打开编辑器都生成一次自动存档。
     */
    fun pause(autoSave: Boolean = true) {
        lifecyclePaused = true
        loop.pause()
        keyboardInput.releaseAll()
        audioPump?.pause()

        if (autoSave && settings.settings.value.autoSaveOnExit) {
            scope.launch(Dispatchers.IO) {
                if (romSha256.isNotEmpty()) batterySaves.save(romSha256)
                savestates.save(romId, AUTO_SAVE_SLOT)
            }
        }
    }

    /**
     * 平台生命周期恢复：恢复音频，并在会话已经启动过的情况下重启帧循环。
     * 首次进入页面时 [start] 尚未执行，此时不会误启动循环。
     */
    fun resume() {
        // 先清除暂停标记：即使加载尚未完成（started=false），
        // 之后的 start() 也能根据当前前台状态正确启动循环。
        lifecyclePaused = false

        if (!started) return

        // 尊重当前声音开关：关闭声音时只恢复帧循环，不启动音频设备。
        if (settings.settings.value.enableSound) {
            audioPump?.resume()
        } else {
            audioPump?.pause()
        }
        if (!loop.isRunning) {
            loop.start(core.avInfo.fps.let { if (it > 0) it else 60.0 })
        }
    }

    /** 设置快进倍率（1.0 = 正常速度）。 */
    fun setSpeed(multiplier: Float) {
        loop.setSpeed(multiplier)
    }

    /**
     * 重置当前游戏：卸载并重新加载 ROM，然后重启帧循环。
     * 与重构前一致，即使重新加载失败也会尝试重启循环。
     */
    suspend fun reset() {
        withContext(Dispatchers.IO) {
            loop.reset(romPath)
            // 重新加载后核心会重新注册选项，需要再次应用用户保存的值。
            applySavedCoreOptions()
            // 卸载/重载会清空 cheat device，需要重新添加金手指。
            applyCheats()
        }
        loop.start(core.avInfo.fps.let { if (it > 0) it else 60.0 })
    }

    /** 保存到指定槽位。 */
    fun saveState(slot: Int): Boolean = savestates.save(romId, slot)

    /** 从指定槽位读取。 */
    fun loadState(slot: Int): SavestateLoadResult = savestates.load(romId, slot)

    /** 删除指定槽位。 */
    fun deleteState(slot: Int) {
        savestates.delete(romId, slot)
    }

    /** 列出指定 ROM 的全部槽位；省略 [romId] 时使用当前 ROM。 */
    fun listSlots(romId: String = this.romId): List<SlotStatus> =
        savestates.listSlots(romId)

    /**
     * 平台生命周期销毁：停止循环、按设置保存电池存档、保存自动存档、
     * 关闭音频设备与仿真核心。
     */
    override fun close() {
        settingsJob?.cancel()
        settingsJob = null
        periodicSaveJob?.cancel()
        periodicSaveJob = null
        loop.stop()

        // 自动存档开关同时控制电池存档与自动即时存档，避免关闭后仍悄悄写盘。
        if (settings.settings.value.autoSaveOnExit) {
            if (romSha256.isNotEmpty()) {
                try {
                    batterySaves.save(romSha256)
                } catch (_: Exception) {
                }
            }
            try {
                savestates.save(romId, AUTO_SAVE_SLOT)
            } catch (_: Exception) {
            }
        }

        audioPump?.close()
        audioPump = null
        started = false
        gameLoaded = false
        core.close()
    }

    companion object {
        /** 自动存档使用的槽位号，UI 不展示该槽位。 */
        const val AUTO_SAVE_SLOT = SavestateRepository.AUTO_SAVE_SLOT

        /** 存档文件名使用 SHA-256 的前 16 位，兼顾唯一性与短文件名。 */
        private const val ROM_ID_LENGTH = RomCatalog.ROM_ID_LENGTH

        /** 核心名不可用时的持久化键。 */
        private const val DEFAULT_CORE_NAME = "default"

        /** 游戏中 SRAM 定时落盘间隔。 */
        private const val AUTO_SAVE_INTERVAL_MS = 60_000L

        private val LOG: Logger = Logger.getLogger("GameSession")
    }
}
