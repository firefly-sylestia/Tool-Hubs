package com.sylestia.toolhubs.networkswitcher

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.content.pm.PackageManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

class NetworkSwitcherBridge(private val context: Context) {
    private var service: INetworkModeService? = null

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context, NetworkModeService::class.java)
    )
        .daemon(false)
        .processNameSuffix("network")
        .version(BuildConfig.VERSION_CODE)
        .tag("network-switcher")

    fun reconnect5G(): String {
        return withService { it.reconnect5G() }
    }

    fun switchDefaultDataSubscription(subscriptionId: Int): String {
        return withService { it.switchDefaultDataSubscription(subscriptionId) }
    }

    private fun withService(operation: (INetworkModeService) -> String): String {
        ShizukuHealthMonitor.install()
        if (!Shizuku.pingBinder()) return "Shizuku is not running."
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return "Shizuku permission is not granted."
        }
        return try {
            // A user service can be reclaimed while our foreground watcher is alive.
            // Bind every operation and wait on this invocation's latch so an old
            // connection cannot make a later background attempt look connected.
            val latch = CountDownLatch(1)
            val operationConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    service = INetworkModeService.Stub.asInterface(binder)
                    latch.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName) { service = null }
            }
            service = null
            ShizukuHealthMonitor.binding()
            Shizuku.bindUserService(args, operationConnection)
            if (!latch.await(4, TimeUnit.SECONDS)) return "Could not connect to the Shizuku service."
            ShizukuHealthMonitor.refresh()
            service?.let(operation) ?: "Shizuku service disconnected before the operation."
        } catch (t: Throwable) {
            "Shizuku operation failed: ${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
        } finally {
            runCatching { Shizuku.unbindUserService(args, null, true) }
            service = null
            ShizukuHealthMonitor.refresh()
        }
    }
}
