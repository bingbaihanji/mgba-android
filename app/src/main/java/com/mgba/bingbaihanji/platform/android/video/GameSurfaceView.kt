package com.mgba.bingbaihanji.platform.android.video

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.SurfaceHolder
import com.mgba.bingbaihanji.core.emulation.FrameSource
import com.mgba.bingbaihanji.core.perf.PerformanceStats
import com.mgba.bingbaihanji.core.video.ShaderPreset

/**
 * 承载仿真画面的 GLSurfaceView。
 *
 * 生命周期：Activity 必须在合适的时机调用 [onResume]/[onPause]，
 * 并且在 GL 线程停止前不要关闭帧来源。
 */
class GameSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    private var renderer: GLSurfaceView.Renderer? = null

    /**
     * 绑定帧来源并创建渲染线程。
     *
     * [rendererFactory] 允许在不修改本类的情况下替换渲染实现
     * （例如未来接入抗锯齿或外部 GLSL 着色器的渲染器）。
     */
    fun attach(
        frameSource: FrameSource,
        stats: PerformanceStats,
        rendererFactory: (FrameSource, PerformanceStats) -> GLSurfaceView.Renderer =
            { source, performance -> GameRenderer(source, performance) },
    ) {
        val r = rendererFactory(frameSource, stats)
        renderer = r
        setEGLContextClientVersion(2)
        setRenderer(r)
        // 只在仿真产出新帧（或设置变化）时渲染。连续模式下每次 vsync 都会
        // 交换缓冲，跳过绘制会交换出内容未定义的后备缓冲，反而造成抖动。
        renderMode = RENDERMODE_WHEN_DIRTY
        // 新 Surface 需要重绘一次：帧池里仍保留最后一帧，可立即恢复画面。
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                requestRender()
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) {
                requestRender()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        })
        // setRenderer 之前可能已经收到过 onResume，此处补一次。
        super.onResume()
    }

    /** 把最新的视频设置应用到当前渲染器；渲染器需实现 [VideoSettingsAware]。 */
    fun applyVideoSettings(stretchToFit: Boolean, linearFiltering: Boolean) {
        val aware = renderer as? VideoSettingsAware ?: return
        aware.stretchToFit = stretchToFit
        aware.linearFiltering = linearFiltering
        // 暂停（无新帧）时也要让设置变化立刻可见。
        requestRender()
    }

    /** 提交着色器配置；编译在渲染线程完成，失败时渲染器会回退默认着色器。 */
    fun applyShader(preset: ShaderPreset, customSource: String) {
        val aware = renderer as? VideoSettingsAware ?: return
        aware.setShader(preset, customSource)
        requestRender()
    }

    /** 设置自定义着色器编译失败的回调；回调发生在渲染线程。 */
    fun setShaderErrorListener(listener: ((String) -> Unit)?) {
        (renderer as? VideoSettingsAware)?.shaderErrorListener = listener
    }

    /** 解除渲染器引用；不负责关闭帧来源。 */
    fun detach() {
        renderer = null
    }

    override fun onResume() {
        // GLThread 只有在 setRenderer() 之后才存在，避免空指针。
        if (renderer != null) {
            super.onResume()
            // 按需渲染模式下，恢复后先重绘一次最后保留的画面。
            requestRender()
        }
    }

    override fun onPause() {
        if (renderer != null) {
            super.onPause()
        }
    }
}
