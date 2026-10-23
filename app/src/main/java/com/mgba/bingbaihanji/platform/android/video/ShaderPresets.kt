package com.mgba.bingbaihanji.platform.android.video

import com.mgba.bingbaihanji.core.video.ShaderPreset

/**
 * 内置 GLSL ES 2.0 片元着色器。
 *
 * 约定：
 * - 顶点着色器提供 `varying vec2 vTexCoord`；
 * - 可用 uniform：`sampler2D uTexture`、`vec2 uTexelSize`（1/帧宽、1/帧高）、
 *   `float uSwizzle`（XRGB8888 时为 1.0，需要交换 R/B）；
 * - 必须写入 `gl_FragColor`。
 *
 * 自定义文件只需提供片元着色器，声明 `precision mediump float;` 即可。
 */
object ShaderPresets {

    val DEFAULT: String = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform float uSwizzle;
        void main() {
            vec4 color = texture2D(uTexture, vTexCoord);
            gl_FragColor = mix(color, vec4(color.b, color.g, color.r, 1.0), uSwizzle);
        }
    """.trimIndent()

    private val LCD: String = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform vec2 uTexelSize;
        uniform float uSwizzle;
        void main() {
            vec4 color = texture2D(uTexture, vTexCoord);
            vec2 pixel = vTexCoord / uTexelSize;
            vec2 grid = fract(pixel);
            float gx = smoothstep(0.0, 0.08, grid.x);
            float gy = smoothstep(0.0, 0.08, grid.y);
            float mask = mix(0.70, 1.0, gx * gy);
            color.rgb *= mask;
            gl_FragColor = mix(color, vec4(color.b, color.g, color.r, 1.0), uSwizzle);
        }
    """.trimIndent()

    private val SCANLINES: String = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform vec2 uTexelSize;
        uniform float uSwizzle;
        void main() {
            vec4 color = texture2D(uTexture, vTexCoord);
            float row = vTexCoord.y / uTexelSize.y;
            float scan = 0.5 + 0.5 * cos(row * 3.14159265);
            color.rgb *= mix(0.55, 1.0, scan);
            gl_FragColor = mix(color, vec4(color.b, color.g, color.r, 1.0), uSwizzle);
        }
    """.trimIndent()

    private val DOT: String = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform vec2 uTexelSize;
        uniform float uSwizzle;
        void main() {
            vec4 color = texture2D(uTexture, vTexCoord);
            vec2 pixel = vTexCoord / uTexelSize;
            vec2 offset = fract(pixel) - 0.5;
            float d = length(offset);
            float mask = 1.0 - smoothstep(0.18, 0.55, d);
            color.rgb *= mix(0.65, 1.0, mask);
            gl_FragColor = mix(color, vec4(color.b, color.g, color.r, 1.0), uSwizzle);
        }
    """.trimIndent()

    /** 根据配置返回片元着色器源码；自定义源码为空时回退默认着色器。 */
    fun fragmentSource(preset: ShaderPreset, customSource: String): String = when (preset) {
        ShaderPreset.Off -> DEFAULT
        ShaderPreset.Lcd -> LCD
        ShaderPreset.Scanlines -> SCANLINES
        ShaderPreset.Dot -> DOT
        ShaderPreset.Custom -> customSource.ifBlank { DEFAULT }
    }
}
