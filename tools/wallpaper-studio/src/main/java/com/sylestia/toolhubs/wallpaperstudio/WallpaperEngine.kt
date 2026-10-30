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

    fun applyScenario(context: Context, scenarioId: String, skipIfRecentlyApplied: Boolean = false): Boolean {
        val appContext = context.applicationContext
        val store = WallpaperStore(appContext)
        val scenario = store.scenario(scenarioId)?.takeIf { it.enabled } ?: return false
        val image = store.images().firstOrNull { it.id == scenario.imageId } ?: run {
            store.recordFailure("The image for \${scenario.name} is no longer in your library.")
            return false
        }

        // Unlock broadcasts and OEM event delivery can arrive in quick succession.
        if (
            skipIfRecentlyApplied &&
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
            val cached = WallpaperAssetCache.get(appContext, image.uri)
            val wallpaperId = if (cached != null) {
                manager.setStream(ByteArrayInputStream(cached), null, true, flags)
            } else {
                val stream = appContext.contentResolver.openInputStream(Uri.parse(image.uri))
                    ?: error("The selected image could not be opened.")
                stream.use { manager.setStream(it, null, true, flags) }
            }
            check(wallpaperId != 0) { "Android did not apply the selected wallpaper." }
            store.recordApplied(scenario.id)
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
        candidates.firstOrNull { applyScenario(context, it.id, skipIfRecentlyApplied = true) }
    }

    private fun matches(trigger: WallpaperTrigger, event: WallpaperEvent): Boolean = when (event) {
        WallpaperEvent.UNLOCKED -> trigger == WallpaperTrigger.UNLOCKED
        WallpaperEvent.CHARGING -> trigger == WallpaperTrigger.CHARGING
        WallpaperEvent.UNPLUGGED -> trigger == WallpaperTrigger.UNPLUGGED
        WallpaperEvent.WIFI_CONNECTED -> trigger == WallpaperTrigger.WIFI_CONNECTED
        WallpaperEvent.WIFI_DISCONNECTED -> trigger == WallpaperTrigger.WIFI_DISCONNECTED
        WallpaperEvent.LOW_BATTERY -> trigger == WallpaperTrigger.LOW_BATTERY
    }

    fun isWifiConnected(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
}
