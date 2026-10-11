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
        // Samsung has moved/renamed this activity across One UI releases. The
        // hiddennetworksetting (singular) form is the known path on older firmware,
        // while newer One UI builds may expose the fully-qualified class name.
        val knownActivities = listOf(
            "com.samsung.android.app.telephonyui/.bandselection.BandSelectionActivity",
            "com.samsung.android.app.telephonyui/.hiddennetworksetting.MainActivity",
            "com.samsung.android.app.telephonyui/com.samsung.android.app.telephonyui.hiddennetworksetting.MainActivity",
        )

        // Prefer activities actually present on this phone instead of relying
        // on a hard-coded Samsung component name.
        val discoveredActivities = discoverSamsungBandSelectionActivities()

        val activities = (discoveredActivities + knownActivities)
            .distinct()

        val errors = mutableListOf<String>()
        for (activity in activities) {
            try {
                val process = ProcessBuilder(
                    "am", "start", "-n", activity
                ).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText().trim() }
                val exit = process.waitFor()

                if (exit == 0 && !output.contains("Error:", ignoreCase = true)) {
                    return "Samsung Band Selection opened"
                }

                errors += "$activity -> ${output.ifBlank { "exit code $exit" }}"
            } catch (t: Throwable) {
                errors += "$activity -> ${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
            }
        }

        return "Could not open Samsung Band Selection: " +
            errors.joinToString(" | ").takeLast(1800)
    }

    private fun discoverSamsungBandSelectionActivities(): List<String> {
        return try {
            val process = ProcessBuilder(
                "dumpsys", "package", "com.samsung.android.app.telephonyui"
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()

            val componentPattern = Regex(
                """com\.samsung\.android\.app\.telephonyui/(?:[A-Za-z0-9_$.]+)"""
            )

            componentPattern.findAll(output)
                .map { it.value }
                .filter {
                    it.contains("bandselection", ignoreCase = true) ||
                        it.contains("hiddennetworksetting", ignoreCase = true)
                }
                .distinct()
                .toList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override fun setThermalStatusOverride(status: Int): String {
        if (status < 0 || status > 6) return "Invalid thermal status: $status"
        return runThermalCommand("override-status", status.toString())
    }

    override fun resetThermalStatus(): String =
        runThermalCommand("reset")

    private fun runThermalCommand(vararg args: String): String {
        return try {
            val command = mutableListOf("cmd", "thermalservice")
            command.addAll(args)
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            if (process.waitFor() == 0) {
                if (output.isBlank()) "Thermal service command applied." else output
            } else {
                "Thermal service rejected command: " + (output.ifBlank { "unknown error" })
            }
        } catch (t: Throwable) {
            "Thermal service failed: ${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
        }
    }

    override fun switchDefaultDataSubscription(subscriptionId: Int): String {
        if (subscriptionId < 0) return "No eligible SIM selected."

        return try {
            val subscriptionManager = context.getSystemService(android.telephony.SubscriptionManager::class.java)
            val target = subscriptionManager?.getActiveSubscriptionInfo(subscriptionId)
                ?: return "Cannot switch data SIM: subscription " + subscriptionId + " is not active."

            // Samsung does not expose the cmd phone set-default-data-subscription command.
            // Shizuku gives this service shell-level privileges, so call the hidden framework
            // SystemApi directly first, then use the backing global setting as a fallback.
            val errors = mutableListOf<String>()
            try {
                val method = android.telephony.SubscriptionManager::class.java.getMethod("setDefaultDataSubId", Int::class.javaPrimitiveType)
                method.invoke(subscriptionManager, subscriptionId)
                Thread.sleep(700)
                if (android.telephony.SubscriptionManager.getDefaultDataSubscriptionId() == subscriptionId) {
                    return "Data SIM switched to SIM " + (target.simSlotIndex + 1)
                }
                errors += "framework call returned without changing the default data SIM"
            } catch (t: Throwable) {
                errors += "framework call -> " + t.javaClass.simpleName + ": " + (t.message ?: "unknown error")
            }

            try {
                val settings = ProcessBuilder("settings", "put", "global", "multi_sim_data_call", subscriptionId.toString())
                    .redirectErrorStream(true).start()
                val output = settings.inputStream.bufferedReader().use { it.readText().trim() }
                val exit = settings.waitFor()
                Thread.sleep(700)
                if (android.telephony.SubscriptionManager.getDefaultDataSubscriptionId() == subscriptionId) {
                    return "Data SIM switched to SIM " + (target.simSlotIndex + 1)
                }
                errors += "settings -> " + output.ifBlank { "exit code " + exit }
            } catch (t: Throwable) {
                errors += "settings -> " + t.javaClass.simpleName + ": " + (t.message ?: "unknown error")
            }

            "Could not switch data SIM to SIM " + (target.simSlotIndex + 1) + ": " + errors.joinToString(" | ")
        } catch (t: Throwable) {
            "Data SIM switch failed: " + t.javaClass.simpleName + ": " + (t.message ?: "unknown error")
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

    /**
     * Turns the Wi-Fi radio on or off.
     *
     * Apps have been unable to call `WifiManager.setWifiEnabled` since Android 10, but this service
     * runs with shell privileges under Shizuku, where `svc wifi` still works. `svc` returns as soon as
     * the request is queued, so a short settle is observed before reporting success — the caller is
     * using this to decide whether it may claim Wi-Fi is available.
     */
    override fun setWifiEnabled(enable: Boolean): String {
        val action = if (enable) "enable" else "disable"
        return try {
            val process = ProcessBuilder("svc", "wifi", action)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            val exit = process.waitFor()
            Thread.sleep(900)
            if (exit == 0) {
                "Wi-Fi ${if (enable) "enabled" else "disabled"}"
            } else {
                "Wi-Fi switch rejected: " + output.ifBlank { "exit code " + exit }
            }
        } catch (t: Throwable) {
            "Wi-Fi switch failed: ${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
