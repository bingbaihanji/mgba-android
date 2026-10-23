package com.mgba.bingbaihanji.core.settings

import kotlin.math.abs

/**
 * 常用游戏速度档位。
 *
 * 参考 My Boy 的快进速度选项（0.2x / 0.5x / 1.5x / 3x …），并补充了
 * 1.25x、1.75x 之类的常用倍率。低于 1x 表示慢放，高于 1x 表示快进。
 *
 * 滑块按“档位索引”移动，因此每个档位都是精确值，不会因为浮点插值
 * 出现 2.9999x 这种落到错误档位的情况。
 */
object SpeedPresets {

    val VALUES: List<Float> = listOf(
        0.2f,   // 慢放
        0.5f,
        0.75f,
        1.0f,   // 正常速度
        1.25f,
        1.5f,
        1.75f,
        2.0f,
        3.0f,
        4.0f,   // 默认快进
        6.0f,
        8.0f,
        12.0f,
        16.0f,
    )

    /** 默认档位：4x。 */
    val DEFAULT_SPEED: Float = 4.0f

    /** 找到与 [speed] 最接近的档位索引。 */
    fun nearestIndex(speed: Float): Int {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (index in VALUES.indices) {
            val distance = abs(VALUES[index] - speed)
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

    /** 把任意倍率吸附到最近档位。 */
    fun snap(speed: Float): Float = VALUES[nearestIndex(speed)]

    /** 档位显示文案，例如 `4x`、`1.25x`、`0.5x`。 */
    fun label(speed: Float): String {
        val text = if (speed == speed.toInt().toFloat()) {
            speed.toInt().toString()
        } else {
            speed.toString().trimEnd('0').trimEnd('.')
        }
        return "${text}x"
    }

    /** 是否是慢放档位。 */
    fun isSlow(speed: Float): Boolean = speed < 1f
}
