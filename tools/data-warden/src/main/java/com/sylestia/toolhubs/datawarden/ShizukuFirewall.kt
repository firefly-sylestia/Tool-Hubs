package com.sylestia.toolhubs.datawarden

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ShizukuFirewall {
    fun isReady() = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    }.getOrDefault(false)

    fun setFirewallEnabled(context: Context, enabled: Boolean): Boolean =
        execute(context) { it.setChain3(enabled) }

    fun setPackageNetworking(context: Context, packageName: String, enabled: Boolean): Boolean =
        execute(context) { it.setPackageNetworking(enabled, packageName) }

    fun setPackagesNetworking(context: Context, packageNames: Collection<String>, enabled: Boolean): Boolean {
        if (packageNames.isEmpty()) return true
        return execute(context) { it.setPackagesNetworking(enabled, packageNames.toTypedArray()) }
    }

    private fun execute(context: Context, call: (INetworkControlService) -> String): Boolean {
        if (!isReady()) return false
        val latch = CountDownLatch(1)
        var service: INetworkControlService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = binder?.let { INetworkControlService.Stub.asInterface(it) }
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { latch.countDown() }
        }
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, NetworkControlUserService::class.java.name)
        ).daemon(false).processNameSuffix("network_control")
            .tag("data-warden-network-control").version(1)
        return try {
            Shizuku.bindUserService(args, conn)
            if (!latch.await(8, TimeUnit.SECONDS)) return false
            val result = service?.let(call) ?: return false
            result.startsWith("exit=0")
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { Shizuku.unbindUserService(args, conn, true) }
        }
    }
}
