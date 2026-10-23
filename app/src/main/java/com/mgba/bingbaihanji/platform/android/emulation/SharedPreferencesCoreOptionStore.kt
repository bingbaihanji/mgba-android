package com.mgba.bingbaihanji.platform.android.emulation

import android.content.Context
import android.content.SharedPreferences
import com.mgba.bingbaihanji.core.emulation.CoreOptionStore
import org.json.JSONObject

/**
 * [CoreOptionStore] 的 Android 实现。
 *
 * 偏好文件结构：
 * ```json
 * {
 *   "mGBA": {
 *     "mgba_color_correction": "GBA",
 *     "mgba_frameskip": "0"
 *   }
 * }
 * ```
 */
class SharedPreferencesCoreOptionStore(context: Context) : CoreOptionStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun values(coreName: String): Map<String, String> {
        val root = readRoot()
        val obj = root.optJSONObject(coreName) ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        for (key in obj.keys()) {
            val value = obj.optString(key, "")
            if (value.isNotEmpty()) result[key] = value
        }
        return result
    }

    override fun set(coreName: String, key: String, value: String) {
        val root = readRoot()
        val obj = root.optJSONObject(coreName) ?: JSONObject()
        obj.put(key, value)
        root.put(coreName, obj)
        writeRoot(root)
    }

    override fun reset(coreName: String) {
        val root = readRoot()
        root.remove(coreName)
        writeRoot(root)
    }

    private fun readRoot(): JSONObject = try {
        val raw = prefs.getString(KEY_OPTIONS, null)
        if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)
    } catch (_: Exception) {
        JSONObject()
    }

    private fun writeRoot(root: JSONObject) {
        prefs.edit().putString(KEY_OPTIONS, root.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "core_options"
        private const val KEY_OPTIONS = "values_v1"
    }
}
