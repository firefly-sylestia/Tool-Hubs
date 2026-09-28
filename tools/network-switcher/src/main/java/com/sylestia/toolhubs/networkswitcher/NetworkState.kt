package com.sylestia.toolhubs.networkswitcher

import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

/**
 * What the modem currently reports, plus whether this Android build can actually distinguish
 * non-standalone 5G from plain LTE.
 *
 * This distinction is the whole ballgame for auto recovery. On a 5G NSA network the data network
 * type is reported as *LTE* even though the modem is genuinely attached to 5G; only
 * `TelephonyDisplayInfo` (Android 11+) exposes that override. Without it every NSA device looks
 * like a permanent LTE fallback, which is exactly why the watcher used to re-trigger the radio in a
 * loop. So: when the platform cannot tell us, we decline to call it a fallback rather than guess.
 */
data class RadioState(
    val label: String,
    val dataType: Int,
    val nrCapable: Boolean,
    val fiveGConfirmed: Boolean,
    val detectionReliable: Boolean,
) {
    val is5G: Boolean get() = fiveGConfirmed

    /** True only when we are confident the modem has actually dropped off 5G. */
    val isLteFallback: Boolean
        get() = detectionReliable && !fiveGConfirmed && dataType == TelephonyManager.NETWORK_TYPE_LTE
}

/**
 * Owns the platform display-info registration so the dashboard and the background watcher never
 * disagree about what the radio is doing.
 */
class RadioWatcher(
    private val telephony: TelephonyManager,
    private val executor: Executor,
) {
    @Volatile private var overrideNr = false
    @Volatile private var started = false

    private val modernCallback: TelephonyCallback? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) newCallback() else null

    private val legacyListener: PhoneStateListener? =
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) newLegacyListener() else null

    @RequiresApi(Build.VERSION_CODES.S)
    private fun newCallback(): TelephonyCallback = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
        override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) = applyOverride(info)
    }

    // Only ever constructed inside a `SDK_INT == R` branch, hence the annotation.
    @RequiresApi(Build.VERSION_CODES.R)
    @Suppress("DEPRECATION")
    private fun newLegacyListener(): PhoneStateListener = object : PhoneStateListener() {
        override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) = applyOverride(info)
    }

    // `TelephonyDisplayInfo` and its constants only exist on Android 11+, so every reference to
    // them lives behind a @RequiresApi boundary; lintVitalRelease treats NewApi as fatal.
    @RequiresApi(Build.VERSION_CODES.R)
    private fun applyOverride(info: TelephonyDisplayInfo) {
        val type = info.overrideNetworkType
        overrideNr = type == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
            type == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED)
    }

    fun start() {
        if (started) return
        started = true
        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> modernCallback?.let { telephony.registerTelephonyCallback(executor, it) }
                Build.VERSION.SDK_INT == Build.VERSION_CODES.R -> legacyListener?.let {
                    @Suppress("DEPRECATION")
                    telephony.listen(it, PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED)
                }
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> modernCallback?.let { telephony.unregisterTelephonyCallback(it) }
                Build.VERSION.SDK_INT == Build.VERSION_CODES.R -> legacyListener?.let {
                    @Suppress("DEPRECATION")
                    telephony.listen(it, PhoneStateListener.LISTEN_NONE)
                }
            }
        }
    }

    fun snapshot(): RadioState {
        val dataType = runCatching { telephony.dataNetworkType }
            .getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)

        val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                telephony.getAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER)
            }.getOrDefault(0L)
        } else {
            0L
        }
        val nrCapable = (allowed and TelephonyManager.NETWORK_TYPE_BITMASK_NR) != 0L

        val nrByType = dataType == TelephonyManager.NETWORK_TYPE_NR
        val nrByOverride = overrideNr
        val confirmed = nrByType || nrByOverride

        // Android 11+ can read TelephonyDisplayInfo, so its verdict is trustworthy either way.
        val reliable = nrByType || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        return RadioState(
            label = classify(dataType, confirmed),
            dataType = dataType,
            nrCapable = nrCapable,
            fiveGConfirmed = confirmed,
            detectionReliable = reliable,
        )
    }

    private fun classify(dataType: Int, confirmed5G: Boolean): String {
        if (confirmed5G) return "5G"
        return when (dataType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G / LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_GSM,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
            else -> "Unknown"
        }
    }
}
