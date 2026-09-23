package com.sylestia.toolhubs.networkswitcher

import android.telephony.TelephonyManager
import java.io.BufferedReader
import java.io.InputStreamReader

class NetworkModeService(private val context: android.content.Context) : INetworkModeService.Stub() {
    override fun reconnect5G(): String {
        return try {
            val current = runShell("cmd", "phone", "get-allowed-network-types-for-users").trim()

            // Keep the carrier/device's existing RAT configuration and only
            // remove NR temporarily. Samsung firmware can reject a hard
            // LTE-only mask if it removes RATs that the SIM/carrier requires.
            val temporaryMask = maskWithoutNr(current)
            val lteResult = runShell(
                "cmd", "phone", "set-allowed-network-types-for-users",
                temporaryMask
            )
            if (!isSuccessful(lteResult)) {
                return "Radio operation failed: LTE reset rejected: " + lteResult
            }

            Thread.sleep(750)

            val restoreResult = runShell(
                "cmd", "phone", "set-allowed-network-types-for-users",
                restoreMask(current)
            )
            if (!isSuccessful(restoreResult)) {
                return "Radio operation failed: 5G restore rejected: " + restoreResult
            }

            "5G re-selection triggered"
        } catch (t: Throwable) {
            "Radio operation failed: " + t.javaClass.simpleName + ": " + (t.message ?: "unknown error")
        }
    }

    private fun maskWithoutNr(current: String): String {
        val names = current
            .replace("[", "")
            .replace("]", "")
            .split("|", ",", " ")
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }

        var mask = 0L
        for (name in names) {
            mask = mask or networkTypeMask(name)
        }

        // If the command output cannot be parsed, fall back to LTE rather than
        // sending an invalid zero mask.
        if (mask == 0L) {
            mask = TelephonyManager.NETWORK_TYPE_BITMASK_LTE or
                TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA
        }

        mask = mask and TelephonyManager.NETWORK_TYPE_BITMASK_NR.inv()

        // Make sure LTE remains enabled during the temporary reset.
        mask = mask or
            TelephonyManager.NETWORK_TYPE_BITMASK_LTE or
            TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA

        return java.lang.Long.toBinaryString(mask)
    }

    private fun networkTypeMask(name: String): Long = when (name) {
        "NR" -> TelephonyManager.NETWORK_TYPE_BITMASK_NR
        "LTE" -> TelephonyManager.NETWORK_TYPE_BITMASK_LTE
        "LTE_CA" -> TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA
        "GSM" -> TelephonyManager.NETWORK_TYPE_BITMASK_GSM
        "GPRS" -> TelephonyManager.NETWORK_TYPE_BITMASK_GPRS
        "EDGE" -> TelephonyManager.NETWORK_TYPE_BITMASK_EDGE
        "UMTS" -> TelephonyManager.NETWORK_TYPE_BITMASK_UMTS
        "HSDPA" -> TelephonyManager.NETWORK_TYPE_BITMASK_HSDPA
        "HSUPA" -> TelephonyManager.NETWORK_TYPE_BITMASK_HSUPA
        "HSPA" -> TelephonyManager.NETWORK_TYPE_BITMASK_HSPA
        "HSPAP" -> TelephonyManager.NETWORK_TYPE_BITMASK_HSPAP
        "CDMA" -> TelephonyManager.NETWORK_TYPE_BITMASK_CDMA
        "EVDO_0" -> TelephonyManager.NETWORK_TYPE_BITMASK_EVDO_0
        "EVDO_A" -> TelephonyManager.NETWORK_TYPE_BITMASK_EVDO_A
        "EVDO_B" -> TelephonyManager.NETWORK_TYPE_BITMASK_EVDO_B
        else -> 0L
    }

    private fun restoreMask(current: String): String {
        val normalized = current.uppercase()
        if (normalized.contains("NR")) {
            return extractBinaryOrFallback(current)
        }
        return java.lang.Long.toBinaryString(
            TelephonyManager.NETWORK_TYPE_BITMASK_LTE or
                TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA or
                TelephonyManager.NETWORK_TYPE_BITMASK_NR
        )
    }

    private fun extractBinaryOrFallback(current: String): String {
        val names = current
            .replace("[", "")
            .replace("]", "")
            .split("|", ",", " ")
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }

        var mask = 0L
        for (name in names) {
            mask = mask or networkTypeMask(name)
        }

        if (mask == 0L) {
            mask = TelephonyManager.NETWORK_TYPE_BITMASK_LTE or
                TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA or
                TelephonyManager.NETWORK_TYPE_BITMASK_NR
        }
        return java.lang.Long.toBinaryString(mask)
    }

    private fun runShell(vararg command: String): String {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
        val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        process.waitFor()
        return output.trim()
    }

    private fun isSuccessful(output: String): Boolean {
        val text = output.lowercase()
        return !text.contains("failed") &&
            !text.contains("error") &&
            !text.contains("exception") &&
            !text.contains("invalid")
    }

    override fun destroy() {
        System.exit(0)
    }
}
