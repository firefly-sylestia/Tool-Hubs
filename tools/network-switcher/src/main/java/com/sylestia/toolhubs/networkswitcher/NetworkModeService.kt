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
                """com\\.samsung\\.android\\.app\\.telephonyui/(?:[A-Za-z0-9_$.]+)"""
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
