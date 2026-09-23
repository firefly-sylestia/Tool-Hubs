package com.sylestia.toolhubs.networkswitcher

import android.content.Context
import android.telephony.TelephonyManager

class NetworkModeService(private val context: Context) : INetworkModeService.Stub() {
    private val telephony: TelephonyManager
        get() = context.getSystemService(TelephonyManager::class.java)

    override fun reconnect5G(): String {
        return try {
            ensureTelephonyFrameworkInitialized()

            val current = telephony.getAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER
            )

            val lteMask =
                TelephonyManager.NETWORK_TYPE_BITMASK_LTE or
                    TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA

            telephony.setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER,
                lteMask
            )

            Thread.sleep(750)

            val restore = current or TelephonyManager.NETWORK_TYPE_BITMASK_NR
            telephony.setAllowedNetworkTypesForReason(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER,
                restore
            )

            "5G re-selection triggered"
        } catch (t: Throwable) {
            "Radio operation failed: \${t.javaClass.simpleName}: \${t.message ?: "unknown error"}"
        }
    }

    private fun ensureTelephonyFrameworkInitialized() {
        val initializer = Class.forName("android.telephony.TelephonyFrameworkInitializer")
        val managerClass = Class.forName("android.os.TelephonyServiceManager")

        val getManager = initializer.getMethod("getTelephonyServiceManager")
        if (getManager.invoke(null) != null) return

        val manager = managerClass.getDeclaredConstructor().apply {
            isAccessible = true
        }.newInstance()

        initializer.getMethod("setTelephonyServiceManager", managerClass)
            .invoke(null, manager)
    }

    override fun destroy() {
        System.exit(0)
    }
}
