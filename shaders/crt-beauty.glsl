// CRT Beauty — mGBA Android 自定义片元着色器
// 兼容 GLSL ES 2.0；用法：设置 → 视频 → 着色器 → 选择 GLSL 文件…
//
// 效果：
// - 四方向 bloom，柔化边缘并让亮部轻微发光
// - 轻微提升饱和度与对比度
// - 每个游戏像素行一条扫描线
// - 淡淡的像素网格
// - 暗角与轻微暖色，接近老式 CRT

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

    vec3 color = sampleColor(uv);

    // 四方向 bloom：只多采样 4 次，移动端开销可控
    vec3 glow = sampleColor(uv + vec2(texel.x, 0.0))
              + sampleColor(uv - vec2(texel.x, 0.0))
              + sampleColor(uv + vec2(0.0, texel.y))
              + sampleColor(uv - vec2(0.0, texel.y));
    glow *= 0.25;
    color = clamp(color + glow * 0.10, 0.0, 1.0);

    // 轻微提升饱和度
    float luma = dot(color, vec3(0.299, 0.587, 0.114));
    color = clamp(mix(vec3(luma), color, 1.10), 0.0, 1.0);

    // 柔和 S 曲线对比度
    vec3 curved = color * color * (3.0 - 2.0 * color);
    color = mix(color, curved, 0.20);

    // 扫描线：每个游戏像素行一条
    float row = vTexCoord.y / uTexelSize.y;
    float scan = 0.5 + 0.5 * cos(row * 3.14159265);
    color *= mix(0.86, 1.0, scan);

    // 像素网格：保持很淡，避免影响可读性
    vec2 pixel = vTexCoord / uTexelSize;
    vec2 grid = fract(pixel);
    float gx = smoothstep(0.0, 0.06, grid.x);
    float gy = smoothstep(0.0, 0.06, grid.y);
    color *= mix(0.92, 1.0, gx * gy);

    // 暗角
    vec2 v = vTexCoord - 0.5;
    float vignette = 1.0 - dot(v, v) * 0.30;
    color *= vignette;

    // 轻微补偿扫描线/网格带来的亮度损失
    color *= 1.04;

    // 轻微暖色
    color *= vec3(1.02, 1.0, 0.98);

    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
