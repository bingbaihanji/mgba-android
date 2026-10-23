package com.mgba.bingbaihanji.platform.android.cheat

import android.content.Context
import android.content.SharedPreferences
import com.mgba.bingbaihanji.core.cheat.Cheat
import com.mgba.bingbaihanji.core.cheat.CheatStore
import com.mgba.bingbaihanji.core.cheat.CheatType
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * [CheatStore] 的 Android 实现。
 *
 * JSON 结构：
 * ```json
 * {
 *   "950b4e1370984c0c": [
 *     {
 *       "id": "...",
 *       "name": "无限 HP",
 *       "type": "gs",
 *       "codes": ["820051F4 0000FFFF"],
 *       "enabled": true
 *     }
 *   ]
 * }
 * ```
 */
class SharedPreferencesCheatStore(context: Context) : CheatStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(romId: String): List<Cheat> {
        val root = readRoot()
        val array = root.optJSONArray(key(romId)) ?: return emptyList()
        val result = mutableListOf<Cheat>()

        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val codesArray = obj.optJSONArray("codes") ?: continue
            val codes = mutableListOf<String>()
            for (codeIndex in 0 until codesArray.length()) {
                val code = codesArray.optString(codeIndex, "").trim()
                if (code.isNotEmpty()) codes.add(code)
            }
            if (codes.isEmpty()) continue

            val name = obj.optString("name", "").trim().ifEmpty { "未命名金手指" }
            val id = obj.optString("id", "").ifEmpty { UUID.randomUUID().toString() }
            result.add(
                Cheat(
                    id = id,
                    name = name,
                    type = CheatType.fromId(obj.optString("type", CheatType.Auto.id)),
                    codes = codes,
                    enabled = obj.optBoolean("enabled", true),
                )
            )
        }
        return result
    }

    override fun save(romId: String, cheats: List<Cheat>) {
        val root = readRoot()
        val array = JSONArray()

        for (cheat in cheats) {
            val obj = JSONObject()
            obj.put("id", cheat.id)
            obj.put("name", cheat.name)
            obj.put("type", cheat.type.id)
            obj.put(
                "codes",
                JSONArray().apply {
                    for (code in cheat.codes) put(code)
                },
            )
            obj.put("enabled", cheat.enabled)
            array.put(obj)
        }

        root.put(key(romId), array)
        writeRoot(root)
    }

    private fun key(romId: String): String = romId.ifEmpty { "unknown" }

    private fun readRoot(): JSONObject = try {
        val raw = prefs.getString(KEY_DATA, null)
        if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)
    } catch (_: Exception) {
        JSONObject()
    }

    private fun writeRoot(root: JSONObject) {
        prefs.edit().putString(KEY_DATA, root.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "cheats"
        private const val KEY_DATA = "data_v1"
    }
}
