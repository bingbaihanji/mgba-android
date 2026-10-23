// 黑白像素点 — mGBA Android 自定义片元着色器
// 兼容 GLSL ES 2.0；用法：设置 → 视频 → 着色器 → 选择 GLSL 文件…
//
// 效果：
// 1. 按亮度转换为纯黑/纯白；
// 2. 4x4 Bayer 有序抖动，避免直接阈值导致的大片死黑/死白；
// 3. 每个游戏像素绘制为一个小圆点，形成点阵式黑白画面。

precision mediump float;

varying vec2 vTexCoord;
uniform sampler2D uTexture;
uniform vec2 uTexelSize;   // 1.0 / 帧宽、1.0 / 帧高
uniform float uSwizzle;    // XRGB8888 时为 1.0

// 4x4 Bayer 抖动矩阵：
//  0  8  2 10
// 12  4 14  6
//  3 11  1  9
// 15  7 13  5
float bayer4(vec2 p) {
    vec2 cell = floor(mod(p, 4.0));
    float x = cell.x;
    float y = cell.y;

    if (y < 0.5) {
        if (x < 0.5) return 0.0;
        if (x < 1.5) return 8.0;
        if (x < 2.5) return 2.0;
        return 10.0;
    } else if (y < 1.5) {
        if (x < 0.5) return 12.0;
        if (x < 1.5) return 4.0;
        if (x < 2.5) return 14.0;
        return 6.0;
    } else if (y < 2.5) {
        if (x < 0.5) return 3.0;
        if (x < 1.5) return 11.0;
        if (x < 2.5) return 1.0;
        return 9.0;
    } else {
        if (x < 0.5) return 15.0;
        if (x < 1.5) return 7.0;
        if (x < 2.5) return 13.0;
        return 5.0;
    }
}

void main() {
    vec4 color = texture2D(uTexture, vTexCoord);

    // XRGB8888 需要交换 R/B
    color = mix(color, vec4(color.b, color.g, color.r, 1.0), uSwizzle);

    // 当前游戏像素坐标（不是屏幕像素）
    vec2 pixel = vTexCoord / uTexelSize;

    // 感知亮度
    float lum = dot(color.rgb, vec3(0.299, 0.587, 0.114));

    // 有序抖动后二值化
    float threshold = (bayer4(pixel) + 0.5) / 16.0;
    float bw = step(threshold, lum);

    // 每个游戏像素内画一个圆点，点外保持黑色
    vec2 local = fract(pixel) - 0.5;
    float dotMask = 1.0 - smoothstep(0.32, 0.48, length(local));
    bw *= dotMask;

    gl_FragColor = vec4(vec3(bw), 1.0);
}
