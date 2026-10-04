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
            "Radio operation failed: ${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
        }
    }

    override fun openSamsungBandSelection(): String {
        return try {
            val process = ProcessBuilder(
                "am", "broadcast",
                "-a", "android.provider.Telephony.SECRET_CODE",
                "-d", "android_secret_code://2263"
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            if (process.waitFor() == 0) {
                "Samsung Band Selection opened"
            } else {
                "Could not open Samsung Band Selection: " + output
            }
        } catch (t: Throwable) {
            "Band selection launch failed: " + t.javaClass.simpleName + ": " + (t.message ?: "unknown error")
        }
    }
    override fun switchDefaultDataSubscription(subscriptionId: Int): String {
        if (subscriptionId < 0) return "No eligible SIM selected."
        return try {
            val process = ProcessBuilder(
                "cmd", "phone", "set-default-data-subscription", subscriptionId.toString()
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            if (process.waitFor() == 0) "Data SIM switched" else "Could not switch data SIM: $output"
        } catch (t: Throwable) {
            "Data SIM switch failed: ${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
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
