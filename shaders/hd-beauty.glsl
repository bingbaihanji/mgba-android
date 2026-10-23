// HD Beauty — mGBA Android 自定义片元着色器
// 兼容 GLSL ES 2.0；用法：设置 → 视频 → 着色器 → 选择 GLSL 文件…
//
// 效果：
// - Sharp bilinear：把采样点向最近 texel 中心收拢，放大后更清晰
// - 轻量 unsharp mask：轻微锐化边缘，避免糊成一片
// - Vibrance：低饱和区域提色更多，高饱和区域少提，颜色更自然
// - 柔和 S 曲线对比度 + 轻微提亮
//
// 没有扫描线、网格和暗角，适合想要“干净高清”的画面。

precision mediump float;

varying vec2 vTexCoord;
uniform sampler2D uTexture;
uniform vec2 uTexelSize;   // 1.0 / 帧宽、1.0 / 帧高
uniform float uSwizzle;    // XRGB8888 时为 1.0

vec3 sampleColor(vec2 uv) {
    vec3 c = texture2D(uTexture, uv).rgb;
    return mix(c, c.bgr, uSwizzle);
}

void main() {
    vec2 uv = vTexCoord;
    vec2 texel = uTexelSize;

    // ---- Sharp bilinear：向最近 texel 中心收拢 25% ----
    vec2 texSize = 1.0 / texel;
    vec2 pixel = uv * texSize;
    vec2 texelCenter = floor(pixel) + 0.5;
    vec2 offset = (pixel - texelCenter) * 0.75;
    vec2 sharpUV = clamp((texelCenter + offset) / texSize, vec2(0.0), vec2(1.0));

    vec3 base = sampleColor(sharpUV);

    // ---- 轻量 unsharp mask：5 抽样 ----
    vec3 neighbors = sampleColor(sharpUV + vec2(texel.x, 0.0))
                   + sampleColor(sharpUV - vec2(texel.x, 0.0))
                   + sampleColor(sharpUV + vec2(0.0, texel.y))
                   + sampleColor(sharpUV - vec2(0.0, texel.y));
    vec3 blur = base * 0.4 + neighbors * 0.15;
    vec3 color = base + (base - blur) * 0.18;
    color = clamp(color, 0.0, 1.0);

    // ---- Vibrance：低饱和区域提色更多 ----
    float luma = dot(color, vec3(0.299, 0.587, 0.114));
    float maxC = max(color.r, max(color.g, color.b));
    float minC = min(color.r, min(color.g, color.b));
    float saturation = maxC - minC;
    float vibrance = mix(0.16, 0.04, saturation);
    color = clamp(mix(vec3(luma), color, 1.0 + vibrance), 0.0, 1.0);

    // ---- 柔和对比度 ----
    vec3 curved = color * color * (3.0 - 2.0 * color);
    color = mix(color, curved, 0.18);

    // ---- 轻微提亮 ----
    color = clamp(color * 1.02, 0.0, 1.0);

    gl_FragColor = vec4(color, 1.0);
}
