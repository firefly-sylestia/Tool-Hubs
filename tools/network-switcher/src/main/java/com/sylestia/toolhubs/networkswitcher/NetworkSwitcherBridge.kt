package com.sylestia.toolhubs.networkswitcher

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

class NetworkSwitcherBridge(private val context: Context) {
    private var service: INetworkModeService? = null
    private var connected = false

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context, NetworkModeService::class.java)
    )
        .daemon(false)
        .processNameSuffix("network")
        .version(1)
        .tag("network-switcher")

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = INetworkModeService.Stub.asInterface(binder)
            connected = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            connected = false
        }
    }

    fun reconnect5G(): String {
        if (!Shizuku.pingBinder()) return "Shizuku is not running."
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return "Shizuku permission is not granted."
        }
        return try {
            Shizuku.bindUserService(args, connection)
            repeat(20) {
                if (connected && service != null) return service!!.reconnect5G()
                Thread.sleep(100)
            }
            "Could not connect to the Shizuku service."
        } catch (t: Throwable) {
            "Shizuku operation failed: ${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
        }
    }
}
