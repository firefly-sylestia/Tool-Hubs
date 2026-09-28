package com.sylestia.toolhubs.networkswitcher

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

enum class ShizukuHealth { UNAVAILABLE, PERMISSION_REQUIRED, BINDING, READY, SERVICE_DIED }

/**
 * Single source of truth for Shizuku availability, shared by the UI and the background watcher.
 *
 * Two defects in the previous revision made Shizuku look "stuck until you toggle it off and on":
 *
 *  1. `Shizuku.checkSelfPermission()` throws when the binder dies between `pingBinder()` and the
 *     permission read. That exception escaped `refresh()`, propagated into the watcher's coroutine
 *     and cancelled the whole monitoring loop. Nothing recovered until the service was restarted.
 *  2. Health changes were only discovered by polling, and no observer was notified. A binder that
 *     came back to life was therefore seen up to a second late — by which point the watcher had
 *     already parked itself in a long back-off.
 *
 * Both are fixed here: every read is defensive, and state changes are pushed to listeners so the
 * watcher can reset its retry budget the instant Shizuku becomes usable again.
 */
object ShizukuHealthMonitor {
    @Volatile
    var state: ShizukuHealth = ShizukuHealth.UNAVAILABLE
        private set

    private val lock = Any()
    private val listeners = mutableListOf<(ShizukuHealth) -> Unit>()

    private val deadListener = Shizuku.OnBinderDeadListener { publish(ShizukuHealth.SERVICE_DIED) }
    private val receivedListener = Shizuku.OnBinderReceivedListener { refresh() }

    @Volatile private var installed = false

    /** Idempotent. Safe to call from an Activity and a Service in the same process. */
    fun install() {
        if (installed) return
        synchronized(lock) {
            if (installed) return
            installed = true
        }
        runCatching {
            Shizuku.addBinderDeadListener(deadListener)
            Shizuku.addBinderReceivedListenerSticky(receivedListener)
        }
        refresh()
    }

    /** Recomputes health. Never throws, so it is safe to call straight from composition. */
    fun refresh(): ShizukuHealth {
        val next = runCatching { compute() }.getOrDefault(ShizukuHealth.UNAVAILABLE)
        publish(next)
        return next
    }

    private fun compute(): ShizukuHealth {
        if (!Shizuku.pingBinder()) {
            // Keep SERVICE_DIED sticky so the UI can explain *why* it is unavailable.
            return if (state == ShizukuHealth.SERVICE_DIED) ShizukuHealth.SERVICE_DIED else ShizukuHealth.UNAVAILABLE
        }
        if (Shizuku.isPreV11()) return ShizukuHealth.READY
        @Suppress("DEPRECATION")
        val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        return if (granted) ShizukuHealth.READY else ShizukuHealth.PERMISSION_REQUIRED
    }

    fun binding() = publish(ShizukuHealth.BINDING)

    fun markReady() = publish(ShizukuHealth.READY)

    /** Registers [listener] and immediately delivers the current state. */
    fun addListener(listener: (ShizukuHealth) -> Unit) {
        synchronized(lock) { if (!listeners.contains(listener)) listeners.add(listener) }
        runCatching { listener(state) }
    }

    fun removeListener(listener: (ShizukuHealth) -> Unit) {
        synchronized(lock) { listeners.remove(listener) }
    }

    private fun publish(next: ShizukuHealth) {
        if (state == next) return
        state = next
        val snapshot = synchronized(lock) { listeners.toList() }
        snapshot.forEach { runCatching { it(next) } }
    }

    val isReady: Boolean get() = state == ShizukuHealth.READY
}

/** Human-readable wording used by both the screen and the notification. */
fun ShizukuHealth.describe(): String = when (this) {
    ShizukuHealth.UNAVAILABLE -> "Shizuku isn't running"
    ShizukuHealth.PERMISSION_REQUIRED -> "Shizuku permission required"
    ShizukuHealth.BINDING -> "Connecting to Shizuku…"
    ShizukuHealth.READY -> "Shizuku ready"
    ShizukuHealth.SERVICE_DIED -> "Shizuku restarted — tap Retry"
}
