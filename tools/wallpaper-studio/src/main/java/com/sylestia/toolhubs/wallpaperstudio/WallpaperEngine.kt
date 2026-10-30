package com.sylestia.toolhubs.wallpaperstudio

import android.app.WallpaperManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri

object WallpaperEngine {
    fun applyScenario(context: Context, scenarioId: String): Boolean {
        val appContext = context.applicationContext
        val store = WallpaperStore(appContext)
        val scenario = store.scenario(scenarioId)?.takeIf { it.enabled } ?: return false
        val image = store.images().firstOrNull { it.id == scenario.imageId } ?: run {
            store.recordFailure("The image for ${scenario.name} is no longer in your library.")
            return false
        }

        return runCatching {
            val flags = when (scenario.target) {
                WallpaperTarget.HOME -> WallpaperManager.FLAG_SYSTEM
                WallpaperTarget.LOCK -> WallpaperManager.FLAG_LOCK
                WallpaperTarget.BOTH -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            }
            val stream = appContext.contentResolver.openInputStream(Uri.parse(image.uri))
                ?: error("The selected image could not be opened.")
            val wallpaperId = stream.use {
                WallpaperManager.getInstance(appContext).setStream(it, null, true, flags)
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

        // A broken high-priority image should not prevent the next matching scene from working.
        candidates.firstOrNull { applyScenario(context, it.id) }
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
