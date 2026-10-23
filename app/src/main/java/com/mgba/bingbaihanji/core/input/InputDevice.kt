package com.mgba.bingbaihanji.core.input

import com.mgba.bingbaihanji.core.emulation.InputSink

/**
 * 逻辑输入设备。每个设备拥有独立的 [sourceId]，Native 层按来源位图合并，
 * 因此虚拟按键、键盘、外接手柄可以同时按住同一个按键而互不干扰。
 *
 * 未来新增手柄或可重映射键盘时，只需实现本接口，不需要改动输入主流程。
 */
interface InputDevice {
    /** 输入来源编号，参见 INPUT_SOURCE_* 常量。 */
    val sourceId: Int

    /** 按下 [buttonId]。 */
    fun press(buttonId: Int)

    /** 松开 [buttonId]。 */
    fun release(buttonId: Int)

    /** 释放本设备按住的全部按键（例如失去焦点或 Activity 暂停时）。 */
    fun releaseAll()
}

/**
 * 转发到 [InputSink] 的通用输入设备实现。
 */
class CoreInputDevice(
    private val sink: InputSink,
    override val sourceId: Int,
) : InputDevice {

    override fun press(buttonId: Int) {
        sink.inputPress(buttonId, sourceId)
    }

    override fun release(buttonId: Int) {
        sink.inputRelease(buttonId, sourceId)
    }

    override fun releaseAll() {
        sink.inputReleaseSource(sourceId)
    }
}
