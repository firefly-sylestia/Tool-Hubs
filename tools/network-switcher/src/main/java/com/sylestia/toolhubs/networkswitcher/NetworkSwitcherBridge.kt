package com.sylestia.toolhubs.networkswitcher

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/**
 * Talks to the privileged [NetworkModeService] running inside Shizuku.
 *
 * The previous revision destroyed the user-service process after *every* call, which made each
 * attempt pay a full cold start and made transient binds common right after Shizuku restarts. It
 * also treated those transient failures as radio failures, so the watcher burned its whole retry
 * budget and backed off for minutes even though nothing was wrong with the network.
 *
 * Now the service is kept warm, a single bind timeout is retried, and the caller can tell an
 * environment problem ("Shizuku isn't running") apart from a radio problem.
 */
class NetworkSwitcherBridge(private val context: Context) {

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context, NetworkModeService::class.java)
    )
        .daemon(false)
        .processNameSuffix("network")
        .version(BuildConfig.VERSION_CODE)
        .tag("network-switcher")

    fun reconnect5G(): String = withService { it.reconnect5G() }

    fun switchDefaultDataSubscription(subscriptionId: Int): String =
        withService { it.switchDefaultDataSubscription(subscriptionId) }

    fun openSamsungBandSelection(): String =
        withService { it.openSamsungBandSelection() }

    fun setThermalStatusOverride(status: Int): String =
        withService { it.setThermalStatusOverride(status) }

    fun resetThermalStatus(): String =
        withService { it.resetThermalStatus() }

    private fun withService(operation: (INetworkModeService) -> String): String {
        ShizukuHealthMonitor.install()
        when (ShizukuHealthMonitor.refresh()) {
            ShizukuHealth.READY -> Unit
            ShizukuHealth.PERMISSION_REQUIRED -> return "Shizuku permission is not granted."
            ShizukuHealth.BINDING -> return "Shizuku is still connecting."
            ShizukuHealth.SERVICE_DIED -> return "Shizuku restarted. Open Shizuku, then retry."
            ShizukuHealth.UNAVAILABLE -> return "Shizuku is not running."
        }

        return try {
            var result = bindAndCall(operation)
            if (result == CONNECT_TIMEOUT) {
                // Transient binds are the norm while Shizuku is settling after a restart.
                Thread.sleep(RETRY_PAUSE_MS)
                result = bindAndCall(operation)
            }
            result
        } catch (t: Throwable) {
            "Shizuku operation failed: ${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
        } finally {
            ShizukuHealthMonitor.refresh()
        }
    }

    private fun bindAndCall(operation: (INetworkModeService) -> String): String {
        val latch = CountDownLatch(1)
        var service: INetworkModeService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = INetworkModeService.Stub.asInterface(binder)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
                latch.countDown()
            }
        }

        ShizukuHealthMonitor.binding()
        return try {
            Shizuku.bindUserService(args, connection)
            if (!latch.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                CONNECT_TIMEOUT
            } else {
                service?.let(operation) ?: "Shizuku service disconnected before the operation."
            }
        } finally {
            // `false` keeps the user service process warm for the next call instead of paying a
            // cold start on every attempt. Shizuku recycles it when the app process goes away.
            runCatching { Shizuku.unbindUserService(args, connection, false) }
            ShizukuHealthMonitor.refresh()
        }
    }

    companion object {
        /** Sentinel so the caller can distinguish "never connected" from a radio error string. */
        const val CONNECT_TIMEOUT = "Could not connect to the Shizuku service."

        private const val CONNECT_TIMEOUT_SECONDS = 3L
        private const val RETRY_PAUSE_MS = 450L

        /** True when [result] describes the environment rather than the radio. */
        fun isEnvironmentFailure(result: String): Boolean =
            result == CONNECT_TIMEOUT || result.startsWith("Shizuku") || result.startsWith("Could not connect")
    }
}
