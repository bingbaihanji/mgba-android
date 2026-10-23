package com.mgba.bingbaihanji.platform.android.video

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.mgba.bingbaihanji.core.emulation.FrameData
import com.mgba.bingbaihanji.core.emulation.FrameSource
import com.mgba.bingbaihanji.core.perf.PerformanceStats
import com.mgba.bingbaihanji.core.video.ShaderPreset
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLES2 渲染器：把仿真核心的最新帧绘制到全屏四边形上，保持宽高比居中显示。
 *
 * 线程约定：所有 GL 调用都在 GLSurfaceView 的渲染线程执行；帧通过
 * [FrameSource.acquireFrame] 拉取，该接口同样保证只在渲染线程调用。
 *
 * 着色器：片元着色器由 [ShaderPresets] 提供（关闭/LCD/扫描线/点阵/自定义）。
 * 修改通过 [setShader] 在任意线程提交，GL 线程在下一帧重建程序；
 * 自定义着色器编译失败会自动回退到默认着色器。
 */
class GameRenderer(
    private val frameSource: FrameSource,
    private val stats: PerformanceStats,
) : GLSurfaceView.Renderer, VideoSettingsAware {

    @Volatile
    private var linearFilteringValue: Boolean = true

    /** 纹理过滤：true = GL_LINEAR，false = GL_NEAREST。 */
    override var linearFiltering: Boolean
        get() = linearFilteringValue
        set(value) {
            linearFilteringValue = value
        }

    @Volatile
    private var stretchToFitValue: Boolean = false

    /** true 时忽略宽高比，把画面拉伸到整个 Surface。 */
    override var stretchToFit: Boolean
        get() = stretchToFitValue
        set(value) {
            stretchToFitValue = value
        }

    private var program = 0
    private var textureId = 0
    private var aPositionLoc = -1
    private var aTexCoordLoc = -1
    private var uTextureLoc = -1
    private var uSwizzleLoc = -1
    private var uTexelSizeLoc = -1

    /** 当前实际应用到纹理上的过滤方式，用于检测运行中的变化。 */
    private var appliedLinearFiltering = true

    /** 着色器配置在控制线程写入，GL 线程读取。 */
    @Volatile
    private var shaderPreset: ShaderPreset = ShaderPreset.Off

    @Volatile
    private var customShaderSource: String = ""

    @Volatile
    private var shaderSpecVersion = 0

    /** GL 线程已经应用的配置版本。 */
    private var appliedShaderVersion = 0

    /** 自定义着色器编译失败时的回调（GL 线程调用）。 */
    @Volatile
    override var shaderErrorListener: ((String) -> Unit)? = null

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /** 当前纹理的实际尺寸/格式，用于判断是否需要重新分配。 */
    private var textureWidth = 0
    private var textureHeight = 0
    private var textureFormat = -1

    private var lastSeq = -1L
    private var frameWidth = 0
    private var frameHeight = 0
    private var frameFormat = 2

    /** 是否已经拿到过至少一帧；false 时只清屏。 */
    private var hasFrame = false

    /** 复用同一个数组，避免每帧计算 viewport 时产生垃圾。 */
    private val viewport = IntArray(4)

    // 全屏四边形：位置 (x,y) 与纹理坐标 (u,v) 交错存放。
    private val quadVertices: FloatBuffer = ByteBuffer
        .allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(
                floatArrayOf(
                    -1f, -1f, 0f, 1f,
                    1f, -1f, 1f, 1f,
                    -1f, 1f, 0f, 0f,
                    1f, 1f, 1f, 0f,
                )
            )
            position(0)
        }

    /** 提交新的着色器配置；实际编译在 GL 线程完成。 */
    override fun setShader(preset: ShaderPreset, customSource: String) {
        if (preset == shaderPreset && customSource == customShaderSource) return
        shaderPreset = preset
        customShaderSource = customSource
        shaderSpecVersion++
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        // 新的 GL 上下文里旧 program id 已失效，直接重建。
        program = 0
        appliedShaderVersion = shaderSpecVersion
        rebuildProgram()

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
            if (linearFiltering) GLES20.GL_LINEAR else GLES20.GL_NEAREST
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
            if (linearFiltering) GLES20.GL_LINEAR else GLES20.GL_NEAREST
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        appliedLinearFiltering = linearFiltering
        // 新 GL 上下文里的纹理是重新创建的，首帧必须重新分配。
        textureWidth = 0
        textureHeight = 0
        textureFormat = -1
        lastSeq = -1L
        hasFrame = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        // 设置页切换着色器后，在 GL 线程重建程序。
        if (appliedShaderVersion != shaderSpecVersion) {
            appliedShaderVersion = shaderSpecVersion
            rebuildProgram()
        }

        val frame = frameSource.acquireFrame()
        if (frame != null) {
            if (frame.sequence != lastSeq && frame.width > 0 && frame.height > 0) {
                uploadFrame(frame)
                lastSeq = frame.sequence
                frameWidth = frame.width
                frameHeight = frame.height
                frameFormat = frame.format
                hasFrame = true
                // 调试浮层用它显示游戏输出分辨率。
                stats.recordFrameSize(frame.width, frame.height)
            }
            frameSource.releaseFrame()
        }

        // GLSurfaceView 每次 onDrawFrame 后都会交换缓冲；跳过绘制会让后备
        // 缓冲内容未定义（可能闪回旧帧），因此请求渲染时总是完整重绘一次。
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (hasFrame) {
            drawQuad()
        }
        stats.recordRenderedFrame()
    }

    private fun uploadFrame(frame: FrameData) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        frame.buffer.position(0)

        val reallocate = textureWidth != frame.width ||
                textureHeight != frame.height ||
                textureFormat != frame.format

        when (frame.format) {
            2 -> { // RETRO_PIXEL_FORMAT_RGB565
                if (reallocate) {
                    GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB,
                        frame.width, frame.height, 0,
                        GLES20.GL_RGB, GLES20.GL_UNSIGNED_SHORT_5_6_5,
                        frame.buffer,
                    )
                } else {
                    // 尺寸不变时只更新像素，避免每帧重新分配纹理。
                    GLES20.glTexSubImage2D(
                        GLES20.GL_TEXTURE_2D, 0, 0, 0,
                        frame.width, frame.height,
                        GLES20.GL_RGB, GLES20.GL_UNSIGNED_SHORT_5_6_5,
                        frame.buffer,
                    )
                }
            }

            1 -> { // RETRO_PIXEL_FORMAT_XRGB8888（内存中为 B,G,R,X）
                // 以 RGBA 上传，再在片元着色器里做通道交换。
                if (reallocate) {
                    GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                        frame.width, frame.height, 0,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                        frame.buffer,
                    )
                } else {
                    GLES20.glTexSubImage2D(
                        GLES20.GL_TEXTURE_2D, 0, 0, 0,
                        frame.width, frame.height,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                        frame.buffer,
                    )
                }
            }

            else -> { // RETRO_PIXEL_FORMAT_0RGB1555 或未知格式
                // 按 RGB565 近似上传；当前 mGBA 核心始终返回 RGB565。
                if (reallocate) {
                    GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB,
                        frame.width, frame.height, 0,
                        GLES20.GL_RGB, GLES20.GL_UNSIGNED_SHORT_5_6_5,
                        frame.buffer,
                    )
                } else {
                    GLES20.glTexSubImage2D(
                        GLES20.GL_TEXTURE_2D, 0, 0, 0,
                        frame.width, frame.height,
                        GLES20.GL_RGB, GLES20.GL_UNSIGNED_SHORT_5_6_5,
                        frame.buffer,
                    )
                }
            }
        }

        if (reallocate) {
            textureWidth = frame.width
            textureHeight = frame.height
            textureFormat = frame.format
        }
    }

    private fun drawQuad() {
        if (program == 0) return

        GLES20.glUseProgram(program)

        computeViewport()
        GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)

        // 设置页可能在运行中切换过滤方式，这里按需更新纹理参数。
        if (appliedLinearFiltering != linearFiltering) {
            val filter = if (linearFiltering) GLES20.GL_LINEAR else GLES20.GL_NEAREST
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
            appliedLinearFiltering = linearFiltering
        }

        if (uTextureLoc >= 0) {
            GLES20.glUniform1i(uTextureLoc, 0)
        }
        if (uSwizzleLoc >= 0) {
            // XRGB8888 以 GL_RGBA 上传（内存顺序 B,G,R,X），需要交换通道。
            GLES20.glUniform1f(uSwizzleLoc, if (frameFormat == 1) 1f else 0f)
        }
        if (uTexelSizeLoc >= 0 && frameWidth > 0 && frameHeight > 0) {
            GLES20.glUniform2f(
                uTexelSizeLoc,
                1f / frameWidth.toFloat(),
                1f / frameHeight.toFloat(),
            )
        }

        quadVertices.position(0)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, quadVertices)

        quadVertices.position(2)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, quadVertices)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
    }

    /**
     * 计算 [GLES20.glViewport] 的 `[x, y, w, h]`，写入复用的 [viewport]，
     * 使游戏画面保持宽高比并居中（黑边 letterbox / pillarbox）；
     * 开启全屏拉伸时直接铺满。
     */
    private fun computeViewport() {
        if (surfaceWidth <= 0 || surfaceHeight <= 0 ||
            stretchToFit || frameWidth <= 0 || frameHeight <= 0
        ) {
            viewport[0] = 0
            viewport[1] = 0
            viewport[2] = surfaceWidth
            viewport[3] = surfaceHeight
            return
        }

        val gameAspect = frameWidth.toFloat() / frameHeight
        val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight

        val w: Int
        val h: Int
        if (surfaceAspect > gameAspect) {
            // 屏幕更宽：左右留黑边。
            h = surfaceHeight
            w = (h * gameAspect).toInt().coerceAtMost(surfaceWidth)
        } else {
            // 屏幕更高：上下留黑边。
            w = surfaceWidth
            h = (w / gameAspect).toInt().coerceAtMost(surfaceHeight)
        }
        viewport[0] = (surfaceWidth - w) / 2
        viewport[1] = (surfaceHeight - h) / 2
        viewport[2] = w
        viewport[3] = h
    }

    /** 在当前 GL 上下文中重建片元着色器程序；失败时回退默认着色器。 */
    private fun rebuildProgram() {
        val source = ShaderPresets.fragmentSource(shaderPreset, customShaderSource)
        val usingCustom = source != ShaderPresets.DEFAULT
        var newProgram = tryBuildProgram(source)

        if (newProgram == null && usingCustom) {
            Log.e(TAG, "Custom shader failed; falling back to the default shader")
            newProgram = tryBuildProgram(ShaderPresets.DEFAULT)
            if (newProgram != null) {
                shaderErrorListener?.invoke("自定义着色器编译失败，已回退默认着色器")
            }
        }

        if (newProgram == null) {
            Log.e(TAG, "Unable to build any shader program; keeping the previous one")
            return
        }

        val oldProgram = program
        program = newProgram
        aPositionLoc = GLES20.glGetAttribLocation(newProgram, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(newProgram, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(newProgram, "uTexture")
        uSwizzleLoc = GLES20.glGetUniformLocation(newProgram, "uSwizzle")
        uTexelSizeLoc = GLES20.glGetUniformLocation(newProgram, "uTexelSize")

        if (oldProgram != 0) {
            GLES20.glDeleteProgram(oldProgram)
        }
    }

    private fun tryBuildProgram(fragmentSource: String): Int? = try {
        buildProgram(fragmentSource)
    } catch (e: RuntimeException) {
        Log.e(TAG, "Shader build failed", e)
        null
    }

    private fun buildProgram(fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)

        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vertexShader)
        GLES20.glAttachShader(prog, fragmentShader)
        GLES20.glLinkProgram(prog)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            throw RuntimeException("Shader link failed: $log")
        }

        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }
        return shader
    }

    companion object {
        private const val TAG = "GameRenderer"

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """
    }
}
