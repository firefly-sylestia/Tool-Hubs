package com.sylestia.toolhubs.networkswitcher

import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Everything on this file is a pure derivation of data the app already records: the history log, the
 * interval records and the watcher's own counters. Nothing here talks to the radio, so the numbers
 * can be recomputed on every recomposition without touching the modem, and each one carries the
 * window it was measured over so the UI can say how much evidence is behind it.
 */

/** How a single health factor reads. UNKNOWN means there was not enough evidence to score it. */
enum class HealthLevel { GOOD, FAIR, POOR, UNKNOWN }

data class HealthFactor(
    val label: String,
    val detail: String,
    val points: Int,
    val weight: Float,
    val level: HealthLevel,
)

data class NetworkHealth(
    val score: Int,
    val grade: String,
    val summary: String,
    val factors: List<HealthFactor>,
    val observedMs: Long,
)

data class DailyRecovery(
    val label: String,
    val successes: Int,
    val failures: Int,
    val fallbacks: Int,
)

data class RecoveryStats(
    val attempts: Int,
    val successes: Int,
    val failures: Int,
    val successRatePct: Int,
    val averageMs: Long,
    val fastestMs: Long,
    val slowestMs: Long,
    val failureStreak: Int,
    val longestFailureStreak: Int,
    val fallbacks: Int,
    val fallbackMs: Long,
    val longestFallbackMs: Long,
    val fiveGMs: Long,
    val longestFiveGMs: Long,
    val fiveGUptimePct: Int,
    val churnPerHour: Double,
    val observedMs: Long,
    val trend: List<DailyRecovery>,
)

data class CauseEvidence(val label: String, val value: String)

/**
 * A human-readable explanation for the most recent radio change.
 *
 * [confidence] is deliberately not always "Likely": when the app's own record proves what happened
 * (a data-SIM move, a recovery it triggered itself) it says so, and only the causes the modem never
 * reports are labelled as inferred.
 */
data class LikelyCause(
    val headline: String,
    val explanation: String,
    val confidence: String,
    val evidence: List<CauseEvidence>,
    val changedAt: Long,
)

data class Transition(val at: Long, val from: String?, val to: String)

data class TimelineSlice(val label: String, val durationMs: Long)

/** Below this there is not enough observed time for a health score to mean anything. */
private const val MIN_HEALTH_WINDOW_MS = 10L * 60L * 1000L

/** How far back an explanation will reach for the most recent change. */
private const val CAUSE_LOOKBACK_MS = 12L * 60L * 60L * 1000L

internal const val NO_SERVICE_LABEL = "No service"
private const val FIVE_G_UPTIME_WEIGHT = 0.28f

/**
 * The history log stores whatever label the writer had at hand, so a large part of it is *not* a
 * radio state: "Shizuku", "SIM" and "Wi-Fi" all appear as the `network` of an event. Mapping only
 * the real radio labels keeps those out of the timeline instead of drawing a fake state change.
 */
private val RADIO_LABELS = mapOf(
    NetworkUsageTracker.FIVE_G_LABEL to NetworkUsageTracker.FIVE_G_LABEL,
    NetworkUsageTracker.LTE_LABEL to NetworkUsageTracker.LTE_LABEL,
    "3G" to "3G",
    "2G" to "2G",
    "Unknown" to NO_SERVICE_LABEL,
    NO_SERVICE_LABEL to NO_SERVICE_LABEL,
)

fun radioLabelOf(network: String): String? = RADIO_LABELS[network]

/** Every change of radio state in the retained log, in order. */
fun radioTransitions(entries: List<HistoryEntry>, now: Long): List<Transition> {
    val ordered = entries.filter { it.timestamp <= now }.sortedBy { it.timestamp }
    val result = mutableListOf<Transition>()
    var previous: String? = null
    for (entry in ordered) {
        val label = radioLabelOf(entry.network) ?: continue
        if (label == previous) continue
        result += Transition(entry.timestamp, previous, label)
        previous = label
    }
    return result
}

/**
 * The time spent in each radio state inside `[sinceAt, now]`.
 *
 * A state that began before the window still covers the start of it, which is why the slice is
 * seeded from the last transition at or before `sinceAt` — losing that would hide the 5G time the
 * user actually accumulated before the first in-window event.
 */
fun timelineSlices(transitions: List<Transition>, sinceAt: Long, now: Long): List<TimelineSlice> {
    val ordered = transitions.filter { it.at <= now }.sortedBy { it.at }
    val activeIndex = ordered.indexOfLast { it.at <= sinceAt }
    if (activeIndex < 0) return emptyList()
    val result = mutableListOf<TimelineSlice>()
    var cursor = sinceAt
    var label = ordered[activeIndex].to
    for (next in ordered.drop(activeIndex + 1)) {
        val end = next.at.coerceAtMost(now)
        if (end > cursor) result += TimelineSlice(label, end - cursor)
        cursor = end
        label = next.to
    }
    if (now > cursor) result += TimelineSlice(label, now - cursor)
    return result
}

fun levelFor(points: Double): HealthLevel = when {
    points >= 80.0 -> HealthLevel.GOOD
    points >= 55.0 -> HealthLevel.FAIR
    else -> HealthLevel.POOR
}

fun gradeFor(score: Int): String = when {
    score >= 85 -> "Excellent"
    score >= 70 -> "Good"
    score >= 50 -> "Fair"
    else -> "Poor"
}

fun signalDescription(level: Int): String = when (level) {
    4 -> "4/4 · strong"
    3 -> "3/4 · good"
    2 -> "2/4 · fair"
    1 -> "1/4 · weak"
    else -> "0/4 · very weak"
}

/**
 * A weighted score over the factors the app can actually observe.
 *
 * Every factor is scored 0–100 and carries a weight; a factor with no evidence behind it is dropped
 * and the remaining weights are renormalised, so a phone that has only just started collecting is
 * not punished for missing history. `null` means less than a third of the model had any evidence,
 * and the UI shows a collecting state rather than a made-up number.
 */
fun networkHealth(
    entries: List<HistoryEntry>,
    store: SharedPreferences,
    intervals: List<UsageInterval>,
    sinceAt: Long,
    now: Long,
): NetworkHealth? {
    val transitions = radioTransitions(entries, now)
    val slices = timelineSlices(transitions, sinceAt, now)
    val observedMs = slices.sumOf { it.durationMs }
    val factors = mutableListOf<HealthFactor>()

    if (observedMs >= MIN_HEALTH_WINDOW_MS) {
        val fiveGMs = slices.filter { it.label == NetworkUsageTracker.FIVE_G_LABEL }.sumOf { it.durationMs }
        val percent = (fiveGMs.toDouble() / observedMs * 100.0).coerceIn(0.0, 100.0)
        factors += HealthFactor(
            label = "5G uptime",
            detail = percent.roundToInt().toString() + "% of the observed " + formatDuration(observedMs),
            points = percent.roundToInt(),
            weight = FIVE_G_UPTIME_WEIGHT,
            level = levelFor(percent),
        )

        val hours = (observedMs / 3_600_000.0).coerceAtLeast(1.0 / 60.0)
        val fallbacks = transitions.count { it.at >= sinceAt && it.to == NetworkUsageTracker.LTE_LABEL }
        val fallbacksPerHour = fallbacks / hours
        val fallbackPoints = (100.0 - fallbacksPerHour * 45.0).coerceIn(0.0, 100.0)
        factors += HealthFactor(
            label = "LTE fallbacks",
            detail = fallbacks.toString() + (if (fallbacks == 1) " drop to LTE" else " drops to LTE") +
                " (" + String.format(Locale.US, "%.1f", fallbacksPerHour) + "/hour)",
            points = fallbackPoints.roundToInt(),
            weight = 0.18f,
            level = levelFor(fallbackPoints),
        )

        val changes = transitions.count { it.at >= sinceAt }
        val churnPerHour = changes / hours
        val churnPoints = (100.0 - churnPerHour * 12.0).coerceIn(0.0, 100.0)
        factors += HealthFactor(
            label = "Stability",
            detail = changes.toString() + " radio changes in " + formatDuration(observedMs),
            points = churnPoints.roundToInt(),
            weight = 0.10f,
            level = levelFor(churnPoints),
        )
    }

    val successes = store.getInt(KEY_SUCCESSFUL_ATTEMPTS, 0)
    val failures = store.getInt(KEY_FAILED_ATTEMPTS, 0)
    val attempts = successes + failures
    if (attempts >= 3) {
        val percent = successes * 100.0 / attempts
        factors += HealthFactor(
            label = "Recovery success",
            detail = successes.toString() + " of " + attempts + " attempts landed on 5G",
            points = percent.roundToInt(),
            weight = 0.22f,
            level = levelFor(percent),
        )
    }

    val recoveryCount = store.getInt(AutoReconnectService.PREF_TRIGGER_RECOVERY_COUNT, 0)
    if (recoveryCount > 0) {
        val average = store.getLong(AutoReconnectService.PREF_TRIGGER_RECOVERY_TOTAL_MS, 0L) / recoveryCount
        val points = when {
            average <= 4_000L -> 100.0
            average <= 8_000L -> 85.0
            average <= 15_000L -> 65.0
            average <= 25_000L -> 40.0
            else -> 15.0
        }
        factors += HealthFactor(
            label = "Recovery speed",
            detail = "average " + formatDuration(average) + " from trigger to 5G",
            points = points.roundToInt(),
            weight = 0.12f,
            level = levelFor(points),
        )
    }

    if (intervals.isNotEmpty()) {
        val flagged = intervals.count { it.suspicious }
        val points = (100.0 - flagged * 17.0).coerceIn(0.0, 100.0)
        factors += HealthFactor(
            label = "Data integrity",
            detail = if (flagged == 0) {
                "no flagged spikes in the last 24 hours"
            } else {
                flagged.toString() + " interval" + (if (flagged == 1) "" else "s") + " flagged for review"
            },
            points = points.roundToInt(),
            weight = 0.10f,
            level = levelFor(points),
        )
    }

    val known = factors.filter { it.level != HealthLevel.UNKNOWN }
    val weightSum = known.sumOf { it.weight.toDouble() }
    if (weightSum < 0.35) return null

    val score = (known.sumOf { it.points * it.weight.toDouble() } / weightSum).roundToInt().coerceIn(0, 100)
    val weakest = known.minByOrNull { it.points }
    val summary = when {
        weakest == null -> "Nothing measured yet."
        score >= 85 -> "Holding 5G well. Nothing here needs attention."
        score >= 70 -> "Mostly stable. " + weakest.label + " is the weakest factor."
        score >= 50 -> "Mixed. " + weakest.label + " is pulling the score down."
        else -> "Unstable. " + weakest.label + " needs attention first."
    }
    return NetworkHealth(score, gradeFor(score), summary, known, observedMs)
}

fun recoveryStats(
    entries: List<HistoryEntry>,
    store: SharedPreferences,
    sinceAt: Long,
    now: Long,
): RecoveryStats {
    val transitions = radioTransitions(entries, now)
    val slices = timelineSlices(transitions, sinceAt, now)
    val observedMs = slices.sumOf { it.durationMs }
    val successes = store.getInt(KEY_SUCCESSFUL_ATTEMPTS, 0)
    val failures = store.getInt(KEY_FAILED_ATTEMPTS, 0)
    val attempts = successes + failures
    val recoveryCount = store.getInt(AutoReconnectService.PREF_TRIGGER_RECOVERY_COUNT, 0)
    val inWindow = transitions.filter { it.at >= sinceAt }
    val lteSlices = slices.filter { it.label == NetworkUsageTracker.LTE_LABEL }
    val fiveGSlices = slices.filter { it.label == NetworkUsageTracker.FIVE_G_LABEL }
    val hours = (observedMs / 3_600_000.0).coerceAtLeast(1.0 / 60.0)

    return RecoveryStats(
        attempts = attempts,
        successes = successes,
        failures = failures,
        successRatePct = if (attempts == 0) -1 else (successes * 100.0 / attempts).roundToInt(),
        averageMs = if (recoveryCount == 0) 0L else
            store.getLong(AutoReconnectService.PREF_TRIGGER_RECOVERY_TOTAL_MS, 0L) / recoveryCount,
        fastestMs = store.getLong(AutoReconnectService.PREF_RECOVERY_FASTEST_MS, 0L),
        slowestMs = store.getLong(AutoReconnectService.PREF_RECOVERY_SLOWEST_MS, 0L),
        failureStreak = store.getInt(AutoReconnectService.PREF_FAILURE_STREAK, 0),
        longestFailureStreak = store.getInt(AutoReconnectService.PREF_FAILURE_STREAK_MAX, 0),
        fallbacks = inWindow.count { it.to == NetworkUsageTracker.LTE_LABEL },
        fallbackMs = lteSlices.sumOf { it.durationMs },
        longestFallbackMs = lteSlices.maxOfOrNull { it.durationMs } ?: 0L,
        fiveGMs = fiveGSlices.sumOf { it.durationMs },
        longestFiveGMs = fiveGSlices.maxOfOrNull { it.durationMs } ?: 0L,
        fiveGUptimePct = if (observedMs <= 0L) -1 else
            (fiveGSlices.sumOf { it.durationMs } * 100.0 / observedMs).roundToInt(),
        churnPerHour = inWindow.size / hours,
        observedMs = observedMs,
        trend = dailyRecoveryTrend(entries, transitions, now),
    )
}

private fun dailyRecoveryTrend(
    entries: List<HistoryEntry>,
    transitions: List<Transition>,
    now: Long,
): List<DailyRecovery> {
    val keyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val labelFormat = SimpleDateFormat("EEE dd MMM", Locale.getDefault())
    val calendar = Calendar.getInstance()
    val buckets = LinkedHashMap<String, DailyRecovery>()
    for (offset in 0 until 7) {
        calendar.timeInMillis = now
        calendar.add(Calendar.DAY_OF_YEAR, -offset)
        buckets[keyFormat.format(calendar.time)] = DailyRecovery(labelFormat.format(calendar.time), 0, 0, 0)
    }
    entries.forEach { entry ->
        val key = keyFormat.format(Date(entry.timestamp))
        val current = buckets[key] ?: return@forEach
        buckets[key] = when (entry.type) {
            "success" -> current.copy(successes = current.successes + 1)
            "failed" -> current.copy(failures = current.failures + 1)
            else -> current
        }
    }
    transitions.filter { it.to == NetworkUsageTracker.LTE_LABEL }.forEach { transition ->
        val key = keyFormat.format(Date(transition.at))
        val current = buckets[key] ?: return@forEach
        buckets[key] = current.copy(fallbacks = current.fallbacks + 1)
    }
    return buckets.values.toList()
}

/**
 * Why the radio last changed, in words.
 *
 * Android has never exposed the modem's own reason for a re-selection, so nothing here can be a
 * definitive cause. What the app *can* do is rule things out: if a data-SIM move or a recovery it
 * triggered itself happened in the same window, that is recorded fact, and it is reported as such.
 * Everything else is presented as the most likely explanation, with the evidence listed underneath.
 */
fun explainLastChange(
    entries: List<HistoryEntry>,
    store: SharedPreferences,
    signalLevel: Int,
    rsrp: Int,
    now: Long = System.currentTimeMillis(),
): LikelyCause? {
    val last = radioTransitions(entries, now).lastOrNull { it.at >= now - CAUSE_LOOKBACK_MS } ?: return null
    val evidence = mutableListOf(
        CauseEvidence("Change", (last.from ?: "unknown") + "  →  " + last.to),
        CauseEvidence("When", formatTimestamp(last.at)),
    )
    if (signalLevel in 0..4) evidence += CauseEvidence("Signal", signalDescription(signalLevel))
    if (rsrp < 0) evidence += CauseEvidence("RSRP", rsrp.toString() + " dBm")

    val nearby = entries.filter { kotlin.math.abs(it.timestamp - last.at) <= 180_000L }
    val simMoves = nearby.count { it.type == "sim_failover" }
    val wifiMoves = nearby.count { it.network == "Wi-Fi" }
    val attemptsBefore = entries.count { it.type == "attempt" && it.timestamp in (last.at - 120_000L)..last.at }
    val confirmedAfter = entries.count { it.type == "success" && it.timestamp in last.at..(last.at + 120_000L) }
    val fallbacksLastHour = radioTransitions(entries, now)
        .count { it.to == NetworkUsageTracker.LTE_LABEL && it.at >= last.at - 60L * 60L * 1000L }

    if (simMoves > 0) {
        return LikelyCause(
            headline = "A data-SIM move happened at the same time",
            explanation = "Mobile data was moved between SIMs within three minutes of this change. " +
                "Moving the data subscription detaches and re-attaches packet data, so the radio state " +
                "that follows is a consequence of the move rather than a fault.",
            confidence = "Recorded",
            evidence = evidence + CauseEvidence("Data-SIM events", simMoves.toString()),
            changedAt = last.at,
        )
    }

    if (wifiMoves > 0) {
        return LikelyCause(
            headline = "Wi-Fi was raised around the same time",
            explanation = "Wi-Fi failover ran within three minutes of this change, so traffic may have " +
                "handed over to Wi-Fi. Wi-Fi is not counted in the mobile totals, which can make the " +
                "cellular picture look quieter than it is.",
            confidence = "Recorded",
            evidence = evidence + CauseEvidence("Wi-Fi events", wifiMoves.toString()),
            changedAt = last.at,
        )
    }

    return when (last.to) {
        NO_SERVICE_LABEL -> {
            evidence += CauseEvidence("Fallbacks in the hour before", fallbacksLastHour.toString())
            LikelyCause(
                headline = "The modem reported no service",
                explanation = "The monitored SIM lost its serving cell. This is normally coverage, a dead " +
                    "spot or a carrier-side outage; a SIM change can also look like this for a few seconds. " +
                    "Network Switcher can move data to a backup SIM, but it cannot create coverage.",
                confidence = "Likely",
                evidence = evidence,
                changedAt = last.at,
            )
        }

        NetworkUsageTracker.LTE_LABEL -> {
            evidence += CauseEvidence("Attempts in the 2 minutes before", attemptsBefore.toString())
            evidence += CauseEvidence("Confirmed 5G within 2 minutes", confirmedAfter.toString())
            val explanation = when {
                attemptsBefore > 0 ->
                    "A 5G re-selection was triggered just before this change. Detaching from the cell can " +
                        "land the modem on LTE first, so the drop is most likely a side effect of the " +
                        "recovery itself rather than a new fault."
                confirmedAfter > 0 ->
                    "The app's recovery ran immediately afterwards and 5G was confirmed, so treat this as a " +
                        "short fallback rather than a lasting one."
                else ->
                    "Nothing in the record points at a data change, a Wi-Fi move or a recovery action in the " +
                        "minutes around this change, so plain coverage or network-driven re-selection is the " +
                        "most likely cause."
            }
            LikelyCause(
                headline = if (attemptsBefore > 0) "Likely a side effect of the app's own re-selection" else
                    "Likely coverage or a network-driven re-selection",
                explanation = explanation + " Android never reports the modem's reason, so this is inferred " +
                    "from what the app recorded — not a measurement.",
                confidence = if (confirmedAfter > 0) "Recorded" else "Likely",
                evidence = evidence,
                changedAt = last.at,
            )
        }

        NetworkUsageTracker.FIVE_G_LABEL -> {
            evidence += CauseEvidence("Attempts in the 2 minutes before", attemptsBefore.toString())
            LikelyCause(
                headline = if (attemptsBefore > 0) "5G returned after a recovery attempt" else
                    "The modem reacquired 5G on its own",
                explanation = if (attemptsBefore > 0) {
                    "A re-selection was triggered in the two minutes before this change and 5G was then " +
                        "confirmed, so the app's recovery is the recorded cause."
                } else {
                    "No recovery attempt was recorded in the two minutes before this change, so the modem " +
                        "reacquired 5G itself — most likely the cell simply became usable again."
                },
                confidence = if (attemptsBefore > 0) "Recorded" else "Likely",
                evidence = evidence,
                changedAt = last.at,
            )
        }

        else -> {
            val current = store.getString(AutoReconnectService.PREF_CURRENT_NETWORK, null)
            LikelyCause(
                headline = "The radio changed state",
                explanation = "The radio moved to " + last.to + " at this time. The app records the change " +
                    "but Android does not report why the modem selected it.",
                confidence = "Likely",
                evidence = evidence + CauseEvidence("Current radio", current ?: last.to),
                changedAt = last.at,
            )
        }
    }
}

internal const val KEY_SUCCESSFUL_ATTEMPTS = "successful_attempts"
internal const val KEY_FAILED_ATTEMPTS = "failed_attempts"
