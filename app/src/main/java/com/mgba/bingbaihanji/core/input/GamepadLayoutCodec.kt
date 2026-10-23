package com.mgba.bingbaihanji.core.input

/**
 * 虚拟按键布局的紧凑文本编码。
 *
 * 格式：`1;type,cx,cy,hw,hh;type,cx,cy,hw,hh...`
 * 使用 [Float.toString] 输出小数字面量，避免受系统 Locale 影响。
 * 解析失败或版本不匹配时返回 null，由调用方回退到默认布局。
 */
object GamepadLayoutCodec {

    private const val VERSION = "1"
    private const val RECORD_SEPARATOR = ";"
    private const val FIELD_SEPARATOR = ","
    private const val FIELD_COUNT = 5

    fun encode(layout: GamepadLayout): String = buildString {
        append(VERSION)
        for (control in layout.controls) {
            append(RECORD_SEPARATOR)
            append(control.type.id).append(FIELD_SEPARATOR)
            append(control.centerX).append(FIELD_SEPARATOR)
            append(control.centerY).append(FIELD_SEPARATOR)
            append(control.halfWidth).append(FIELD_SEPARATOR)
            append(control.halfHeight)
        }
    }

    fun decode(raw: String?): GamepadLayout? {
        if (raw.isNullOrBlank()) return null

        val parts = raw.split(RECORD_SEPARATOR)
        if (parts.firstOrNull() != VERSION) return null

        val controls = parts.drop(1).mapNotNull { record ->
            val fields = record.split(FIELD_SEPARATOR)
            if (fields.size != FIELD_COUNT) return@mapNotNull null

            val type = GamepadControlType.fromId(fields[0]) ?: return@mapNotNull null
            val centerX = fields[1].toFloatOrNull() ?: return@mapNotNull null
            val centerY = fields[2].toFloatOrNull() ?: return@mapNotNull null
            val halfWidth = fields[3].toFloatOrNull() ?: return@mapNotNull null
            val halfHeight = fields[4].toFloatOrNull() ?: return@mapNotNull null

            GamepadControl(type, centerX, centerY, halfWidth, halfHeight)
        }

        if (controls.isEmpty()) return null
        return GamepadLayout(controls).sanitized()
    }
}
