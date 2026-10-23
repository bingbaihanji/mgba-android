package com.mgba.bingbaihanji.platform.android.input

import android.content.Context
import android.content.SharedPreferences
import com.mgba.bingbaihanji.core.input.InputAction
import com.mgba.bingbaihanji.core.input.KeyMapProfile
import com.mgba.bingbaihanji.core.input.KeyMapState
import com.mgba.bingbaihanji.core.input.KeyMapStore
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * [KeyMapStore] 的 Android 实现。
 *
 * 使用独立偏好文件保存 JSON：
 * ```json
 * {
 *   "selected": "default",
 *   "profiles": [
 *     {"id":"default","name":"默认","bindings":{"a":[96,23,99,62]}}
 *   ]
 * }
 * ```
 */
class SharedPreferencesKeyMapStore(context: Context) : KeyMapStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    override val state: StateFlow<KeyMapState> = _state.asStateFlow()

    override fun selectProfile(id: String) {
        update { state ->
            if (state.profiles.any { it.id == id }) {
                state.copy(selectedProfileId = id)
            } else {
                state
            }
        }
    }

    override fun addProfile(name: String): String {
        val id = UUID.randomUUID().toString()
        val profile = KeyMapProfile(
            id = id,
            name = name.trim(),
            bindings = KeyBindingDefaults.bindings(),
        )
        update { state ->
            state.copy(
                profiles = state.profiles + profile,
                selectedProfileId = id,
            )
        }
        return id
    }

    override fun renameProfile(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        update { state ->
            state.copy(
                profiles = state.profiles.map { profile ->
                    if (profile.id == id) profile.copy(name = trimmed) else profile
                }
            )
        }
    }

    override fun deleteProfile(id: String) {
        update { state ->
            val remaining = state.profiles.filterNot { it.id == id }
            if (remaining.isEmpty()) {
                state
            } else {
                state.copy(
                    profiles = remaining,
                    selectedProfileId = if (state.selectedProfileId == id) {
                        remaining.first().id
                    } else {
                        state.selectedProfileId
                    },
                )
            }
        }
    }

    override fun bind(profileId: String, action: InputAction, keyCode: Int) {
        if (keyCode <= 0) return
        update { state ->
            state.copy(
                profiles = state.profiles.map { profile ->
                    if (profile.id != profileId) {
                        profile
                    } else {
                        // 一个键只属于一个动作：先从所有动作移除，再加入目标动作。
                        val updated = profile.bindings
                            .mapValues { (_, keys) -> keys - keyCode }
                            .filterValues { it.isNotEmpty() }
                            .toMutableMap()
                        updated[action] = (updated[action] ?: emptySet()) + keyCode
                        profile.copy(bindings = updated)
                    }
                }
            )
        }
    }

    override fun clear(profileId: String, action: InputAction) {
        update { state ->
            state.copy(
                profiles = state.profiles.map { profile ->
                    if (profile.id == profileId) {
                        profile.copy(bindings = profile.bindings - action)
                    } else {
                        profile
                    }
                }
            )
        }
    }

    override fun resetProfile(profileId: String) {
        val defaults = KeyBindingDefaults.bindings()
        update { state ->
            state.copy(
                profiles = state.profiles.map { profile ->
                    if (profile.id == profileId) {
                        profile.copy(bindings = defaults)
                    } else {
                        profile
                    }
                }
            )
        }
    }

    private fun update(transform: (KeyMapState) -> KeyMapState) {
        val current = _state.value
        val next = transform(current)
        if (next == current) return
        _state.value = next
        persist(next)
    }

    private fun load(): KeyMapState {
        val raw = prefs.getString(KEY_STATE, null) ?: return defaultState()
        return try {
            parse(raw)
        } catch (_: Exception) {
            defaultState()
        }
    }

    private fun defaultState(): KeyMapState {
        val profile = KeyMapProfile(
            id = DEFAULT_PROFILE_ID,
            name = "默认",
            bindings = KeyBindingDefaults.bindings(),
        )
        return KeyMapState(
            profiles = listOf(profile),
            selectedProfileId = DEFAULT_PROFILE_ID,
        )
    }

    private fun parse(raw: String): KeyMapState {
        val root = JSONObject(raw)
        val selected = root.optString("selected", DEFAULT_PROFILE_ID)
        val array = root.optJSONArray("profiles")
        val profiles = mutableListOf<KeyMapProfile>()

        if (array != null) {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val id = obj.optString("id")
                val name = obj.optString("name")
                if (id.isBlank() || name.isBlank()) continue

                val bindingsObj = obj.optJSONObject("bindings")
                val bindings = mutableMapOf<InputAction, Set<Int>>()
                if (bindingsObj != null) {
                    for (actionId in bindingsObj.keys()) {
                        val action = InputAction.fromId(actionId) ?: continue
                        val keysArray = bindingsObj.optJSONArray(actionId) ?: continue
                        val keys = mutableSetOf<Int>()
                        for (keyIndex in 0 until keysArray.length()) {
                            val keyCode = keysArray.optInt(keyIndex, 0)
                            if (keyCode > 0) keys.add(keyCode)
                        }
                        if (keys.isNotEmpty()) bindings[action] = keys
                    }
                }
                profiles.add(KeyMapProfile(id, name, bindings))
            }
        }

        if (profiles.isEmpty()) return defaultState()

        val selectedId = profiles.firstOrNull { it.id == selected }?.id
            ?: profiles.first().id
        return KeyMapState(profiles, selectedId)
    }

    private fun persist(state: KeyMapState) {
        val root = JSONObject()
        root.put("selected", state.selectedProfileId)

        val array = JSONArray()
        for (profile in state.profiles) {
            val obj = JSONObject()
            obj.put("id", profile.id)
            obj.put("name", profile.name)

            val bindings = JSONObject()
            for ((action, keys) in profile.bindings) {
                val keysArray = JSONArray()
                keys.sorted().forEach { keysArray.put(it) }
                bindings.put(action.id, keysArray)
            }
            obj.put("bindings", bindings)
            array.put(obj)
        }
        root.put("profiles", array)

        prefs.edit().putString(KEY_STATE, root.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "keymap_settings"
        private const val KEY_STATE = "state_v1"
        private const val DEFAULT_PROFILE_ID = "default"
    }
}
