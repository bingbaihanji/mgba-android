package com.mgba.bingbaihanji.platform.android.video

import com.mgba.bingbaihanji.core.video.ShaderPreset

/**
 * 可在运行中调整的视频参数。
 *
 * 当前由 [GameRenderer] 实现；以后替换为支持抗锯齿/其他后处理的渲染器时，
 * 只要同样实现该接口，[GameSurfaceView] 的设置入口无需修改。
 */
interface VideoSettingsAware {
    /** true 时忽略宽高比，把画面拉伸到整个 Surface。 */
    var stretchToFit: Boolean

    /** true 使用 GL_LINEAR，false 使用 GL_NEAREST。 */
    var linearFiltering: Boolean

    /** 提交着色器配置；实现方应在自己的渲染线程完成编译。 */
    fun setShader(preset: ShaderPreset, customSource: String)

    /**
     * 自定义着色器编译失败时的回调；实现方在渲染线程调用。
     * 调用方应自行切回主线程再更新 UI。
     */
    var shaderErrorListener: ((String) -> Unit)?
}
