package com.sylestia.toolhubs.networkswitcher

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

enum class ShizukuHealth { UNAVAILABLE, PERMISSION_REQUIRED, BINDING, READY, SERVICE_DIED }

/** Single source of truth for UI and background reconnect diagnostics. */
object ShizukuHealthMonitor {
    @Volatile var state: ShizukuHealth = ShizukuHealth.UNAVAILABLE
        private set

    private val deadListener = Shizuku.OnBinderDeadListener { state = ShizukuHealth.SERVICE_DIED }
    private val receivedListener = Shizuku.OnBinderReceivedListener { refresh() }
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addBinderReceivedListenerSticky(receivedListener)
        refresh()
    }

    fun refresh(): ShizukuHealth {
        state = when {
            !Shizuku.pingBinder() -> ShizukuHealth.UNAVAILABLE
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuHealth.PERMISSION_REQUIRED
            else -> ShizukuHealth.READY
        }
        return state
    }

    fun binding() { state = ShizukuHealth.BINDING }
}
