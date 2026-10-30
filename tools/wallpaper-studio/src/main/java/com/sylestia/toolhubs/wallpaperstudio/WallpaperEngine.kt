package com.sylestia.toolhubs.wallpaperstudio

import android.app.WallpaperManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.LruCache
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * Bounded cache of original encoded image bytes. Keeping the original bytes preserves EXIF
 * orientation and lets WallpaperManager handle device-specific crop and decoding.
 */
private object WallpaperAssetCache {
    private const val MAX_CACHE_BYTES = 32 * 1024 * 1024
    private const val MAX_SINGLE_IMAGE_BYTES = 16 * 1024 * 1024
    private val cache = object : LruCache<String, ByteArray>(MAX_CACHE_BYTES / 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = (value.size / 1024).coerceAtLeast(1)
    }

    fun get(context: Context, uriString: String): ByteArray? {
        cache.get(uriString)?.let { return it }
        val bytes = runCatching {
            context.contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_SINGLE_IMAGE_BYTES) return@use null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }.getOrNull() ?: return null
        cache.put(uriString, bytes)
        return bytes
    }

    fun warm(context: Context, scenarios: List<WallpaperScenario>, images: List<WallpaperImage>) {
        val byId = images.associateBy { it.id }
        scenarios.asSequence()
            .filter { it.enabled }
            .sortedByDescending { it.priority }
            .mapNotNull { byId[it.imageId]?.uri }
            .distinct()
            .take(6)
            .forEach { uri -> get(context, uri) }
    }
}

object WallpaperEngine {
    fun preload(context: Context) {
        val appContext = context.applicationContext
        thread(name = "wallpaper-preload") {
            val store = WallpaperStore(appContext)
            WallpaperAssetCache.warm(appContext, store.scenarios(), store.images())
        }
    }

    fun applyScenario(context: Context, scenarioId: String, skipIfRecentlyApplied: Boolean = false, automaticTrigger: WallpaperTrigger? = null): Boolean {
        val appContext = context.applicationContext
        val store = WallpaperStore(appContext)
        val scenario = store.scenario(scenarioId) ?: return false
        val image = store.images().firstOrNull { it.id == scenario.imageId } ?: run {
            store.recordFailure("The image for \${scenario.name} is no longer in your library.")
            return false
        }

        // Unlock broadcasts and OEM event delivery can arrive in quick succession.
        if (
            skipIfRecentlyApplied &&
            automaticTrigger != null &&
            store.activeTrigger() == automaticTrigger &&
            store.lastAppliedScenarioId() == scenario.id &&
            System.currentTimeMillis() - store.lastAppliedAt() in 0..15_000
        ) {
            return true
        }

        return runCatching {
            val flags = when (scenario.target) {
                WallpaperTarget.HOME -> WallpaperManager.FLAG_SYSTEM
                WallpaperTarget.LOCK -> WallpaperManager.FLAG_LOCK
                WallpaperTarget.BOTH -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            }
            val manager = WallpaperManager.getInstance(appContext)
            if (automaticTrigger != null) {
                check(WallpaperBaseline.capture(appContext, flags)) {
                    "Android did not allow Wallpaper Studio to save the previous wallpaper. The scene was not applied."
                }
            }
            val cachedBytes = WallpaperAssetCache.get(appContext, image.uri)
            val composed = WallpaperComposer.render(appContext, image.uri, scenario.fit, scenario.focusX, scenario.focusY, cachedBytes)
            try {
                val crop = android.graphics.Rect(0, 0, composed.width, composed.height)
                val wallpaperId = manager.setBitmap(composed, crop, true, flags)
                check(wallpaperId != 0) { "Android did not apply the selected wallpaper." }
            } finally {
                composed.recycle()
            }
            store.recordApplied(scenario.id, automaticTrigger)
            true
        }.getOrElse { error ->
            store.recordFailure(error.message ?: "Android could not apply this wallpaper.")
            false
        }
    }

    fun applyForEvent(context: Context, event: WallpaperEvent) {
        val store = WallpaperStore(context)
        val candidates = store.scenarios()
            .filter { it.enabled && matches(it.trigger, event) }
            .sortedByDescending { it.priority }
        val applied = candidates.firstOrNull {
            applyScenario(context, it.id, skipIfRecentlyApplied = true, automaticTrigger = it.trigger)
        }
        if (applied == null && store.activeTrigger()?.let { isExitEvent(it, event) } == true) {
            if (WallpaperBaseline.restore(context)) {
                store.clearActiveScene()
            } else {
                store.recordFailure("The previous wallpaper could not be restored. Open Wallpaper Studio to try again.")
            }
        }
    }

    private fun matches(trigger: WallpaperTrigger, event: WallpaperEvent): Boolean = when (event) {
        WallpaperEvent.UNLOCKED -> trigger == WallpaperTrigger.UNLOCKED
        WallpaperEvent.CHARGING -> trigger == WallpaperTrigger.CHARGING
        WallpaperEvent.UNPLUGGED -> trigger == WallpaperTrigger.UNPLUGGED
        WallpaperEvent.WIFI_CONNECTED -> trigger == WallpaperTrigger.WIFI_CONNECTED
        WallpaperEvent.WIFI_DISCONNECTED -> trigger == WallpaperTrigger.WIFI_DISCONNECTED
        WallpaperEvent.LOW_BATTERY -> trigger == WallpaperTrigger.LOW_BATTERY
        WallpaperEvent.BATTERY_OKAY -> trigger == WallpaperTrigger.BATTERY_OKAY
        WallpaperEvent.SCREEN_ON -> trigger == WallpaperTrigger.SCREEN_ON
        WallpaperEvent.SCREEN_OFF -> trigger == WallpaperTrigger.SCREEN_OFF
        WallpaperEvent.AIRPLANE_ON -> trigger == WallpaperTrigger.AIRPLANE_ON
        WallpaperEvent.AIRPLANE_OFF -> trigger == WallpaperTrigger.AIRPLANE_OFF
        WallpaperEvent.BATTERY_SAVER_ON -> trigger == WallpaperTrigger.BATTERY_SAVER_ON
        WallpaperEvent.BATTERY_SAVER_OFF -> trigger == WallpaperTrigger.BATTERY_SAVER_OFF
        WallpaperEvent.HEADPHONES_CONNECTED -> trigger == WallpaperTrigger.HEADPHONES_CONNECTED
        WallpaperEvent.HEADPHONES_DISCONNECTED -> trigger == WallpaperTrigger.HEADPHONES_DISCONNECTED
    }

    private fun isExitEvent(active: WallpaperTrigger, event: WallpaperEvent): Boolean = when (active) {
        WallpaperTrigger.CHARGING -> event == WallpaperEvent.UNPLUGGED
        WallpaperTrigger.UNPLUGGED -> event == WallpaperEvent.CHARGING
        WallpaperTrigger.WIFI_CONNECTED -> event == WallpaperEvent.WIFI_DISCONNECTED
        WallpaperTrigger.WIFI_DISCONNECTED -> event == WallpaperEvent.WIFI_CONNECTED
        WallpaperTrigger.AIRPLANE_ON -> event == WallpaperEvent.AIRPLANE_OFF
        WallpaperTrigger.AIRPLANE_OFF -> event == WallpaperEvent.AIRPLANE_ON
        WallpaperTrigger.LOW_BATTERY -> event == WallpaperEvent.BATTERY_OKAY
        WallpaperTrigger.BATTERY_OKAY -> event == WallpaperEvent.LOW_BATTERY
        WallpaperTrigger.BATTERY_SAVER_ON -> event == WallpaperEvent.BATTERY_SAVER_OFF
        WallpaperTrigger.BATTERY_SAVER_OFF -> event == WallpaperEvent.BATTERY_SAVER_ON
        WallpaperTrigger.HEADPHONES_CONNECTED -> event == WallpaperEvent.HEADPHONES_DISCONNECTED
        WallpaperTrigger.HEADPHONES_DISCONNECTED -> event == WallpaperEvent.HEADPHONES_CONNECTED
        WallpaperTrigger.SCREEN_OFF -> event == WallpaperEvent.SCREEN_ON
        WallpaperTrigger.SCREEN_ON -> event == WallpaperEvent.SCREEN_OFF
        else -> false
    }

    fun isWifiConnected(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
}
