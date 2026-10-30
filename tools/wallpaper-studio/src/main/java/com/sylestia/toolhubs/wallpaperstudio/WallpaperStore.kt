package com.sylestia.toolhubs.wallpaperstudio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class WallpaperStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "wallpaper_studio",
        Context.MODE_PRIVATE,
    )

    fun images(): List<WallpaperImage> = runCatching {
        val json = JSONArray(preferences.getString(KEY_IMAGES, "[]") ?: "[]")
        buildList {
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                val uri = item.optString("uri")
                if (uri.isBlank()) continue
                add(
                    WallpaperImage(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        uri = uri,
                        name = item.optString("name").ifBlank { "Wallpaper" },
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    fun addImages(images: List<WallpaperImage>) {
        val current = images().toMutableList()
        val knownUris = current.mapTo(mutableSetOf()) { it.uri }
        images.filter { knownUris.add(it.uri) }.forEach { current.add(it) }
        saveImages(current)
    }

    fun saveImages(images: List<WallpaperImage>) {
        val json = JSONArray()
        images.forEach { image ->
            json.put(
                JSONObject()
                    .put("id", image.id)
                    .put("uri", image.uri)
                    .put("name", image.name),
            )
        }
        preferences.edit().putString(KEY_IMAGES, json.toString()).apply()
    }

    fun scenarios(): List<WallpaperScenario> = runCatching {
        val json = JSONArray(preferences.getString(KEY_SCENARIOS, "[]") ?: "[]")
        buildList {
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                val trigger = runCatching {
                    WallpaperTrigger.valueOf(item.optString("trigger"))
                }.getOrDefault(WallpaperTrigger.UNLOCKED)
                val target = runCatching {
                    WallpaperTarget.valueOf(item.optString("target"))
                }.getOrDefault(WallpaperTarget.BOTH)
                add(
                    WallpaperScenario(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = item.optString("name").ifBlank { "Untitled scene" },
                        imageId = item.optString("imageId"),
                        trigger = trigger,
                        target = target,
                        enabled = item.optBoolean("enabled", true),
                        priority = item.optInt("priority", 3).coerceIn(1, 5),
                        time = item.optString("time", "21:00"),
                        fit = runCatching { WallpaperFit.valueOf(item.optString("fit")) }.getOrDefault(WallpaperFit.FILL),
                        focusX = item.optDouble("focusX", 0.5).toFloat().coerceIn(0f, 1f),
                        focusY = item.optDouble("focusY", 0.5).toFloat().coerceIn(0f, 1f),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    fun saveScenario(scenario: WallpaperScenario) {
        val updated = scenarios().filterNot { it.id == scenario.id } + scenario
        writeScenarios(updated)
    }

    fun removeScenario(id: String) {
        writeScenarios(scenarios().filterNot { it.id == id })
    }

    fun scenario(id: String): WallpaperScenario? = scenarios().firstOrNull { it.id == id }

    fun setScenarioEnabled(id: String, enabled: Boolean) {
        scenarios().firstOrNull { it.id == id }?.let {
            saveScenario(it.copy(enabled = enabled))
        }
    }

    private fun writeScenarios(scenarios: List<WallpaperScenario>) {
        val json = JSONArray()
        scenarios.forEach { scenario ->
            json.put(
                JSONObject()
                    .put("id", scenario.id)
                    .put("name", scenario.name)
                    .put("imageId", scenario.imageId)
                    .put("trigger", scenario.trigger.name)
                    .put("target", scenario.target.name)
                    .put("enabled", scenario.enabled)
                    .put("priority", scenario.priority)
                    .put("time", scenario.time)
                    .put("fit", scenario.fit.name)
                    .put("focusX", scenario.focusX.toDouble())
                    .put("focusY", scenario.focusY.toDouble()),
            )
        }
        preferences.edit().putString(KEY_SCENARIOS, json.toString()).apply()
    }

    fun lastAppliedScenarioId(): String? = preferences.getString(KEY_LAST_SCENARIO, null)

    fun lastAppliedAt(): Long = preferences.getLong(KEY_LAST_APPLIED_AT, 0L)

    fun recordApplied(scenarioId: String, trigger: WallpaperTrigger? = null) {
        val editor = preferences.edit()
            .putString(KEY_LAST_SCENARIO, scenarioId)
            .putLong(KEY_LAST_APPLIED_AT, System.currentTimeMillis())
        if (trigger != null) editor.putString(KEY_ACTIVE_TRIGGER, trigger.name)
        editor.remove(KEY_LAST_ERROR).apply()
    }

    fun activeTrigger(): WallpaperTrigger? = runCatching {
        preferences.getString(KEY_ACTIVE_TRIGGER, null)?.let(WallpaperTrigger::valueOf)
    }.getOrNull()

    fun clearActiveScene() {
        preferences.edit()
            .remove(KEY_ACTIVE_TRIGGER)
            .remove(KEY_LAST_SCENARIO)
            .remove(KEY_LAST_APPLIED_AT)
            .apply()
    }

    fun recordFailure(message: String) {
        preferences.edit().putString(KEY_LAST_ERROR, message.take(180)).apply()
    }

    fun lastError(): String? = preferences.getString(KEY_LAST_ERROR, null)

    companion object {
        private const val KEY_IMAGES = "images"
        private const val KEY_SCENARIOS = "scenarios"
        private const val KEY_LAST_SCENARIO = "last_scenario"
        private const val KEY_LAST_APPLIED_AT = "last_applied_at"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_ACTIVE_TRIGGER = "active_trigger"
    }
}
