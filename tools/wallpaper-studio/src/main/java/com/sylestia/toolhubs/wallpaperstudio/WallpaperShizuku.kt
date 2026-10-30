package com.sylestia.toolhubs.wallpaperstudio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import rikka.shizuku.Shizuku

enum class ShizukuState {
    NOT_INSTALLED,
    NOT_RUNNING,
    UNSUPPORTED,
    PERMISSION_REQUIRED,
    CONNECTED,
}

object WallpaperShizuku {
    const val REQUEST_CODE = 7412
    private const val PACKAGE_NAME = "moe.shizuku.privileged.api"

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
    }.isSuccess

    fun state(): ShizukuState = runCatching {
        if (!Shizuku.pingBinder()) return ShizukuState.NOT_RUNNING
        if (Shizuku.isPreV11()) return ShizukuState.UNSUPPORTED
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return ShizukuState.PERMISSION_REQUIRED
        }
        ShizukuState.CONNECTED
    }.getOrDefault(ShizukuState.NOT_RUNNING)

    fun identity(): String = runCatching {
        when (Shizuku.getUid()) {
            0 -> "Root service"
            2000 -> "ADB / wireless debugging"
            else -> "System UID ${Shizuku.getUid()}"
        }
    }.getOrDefault("Unavailable")

    fun requestPermission() {
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            Shizuku.requestPermission(REQUEST_CODE)
        }
    }

    fun openManager(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME)
        if (launch != null) {
            context.startActivity(launch)
        } else {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
