package com.sylestia.toolhubs.networkswitcher

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minute-resolution usage history.
 *
 * This deliberately lives in app-private storage rather than SharedPreferences. The history is
 * structured and can grow to thousands of intervals, while preferences are better suited to small
 * key/value settings. The file is capped to the most recent 14 days.
 */
data class UsageInterval(
    val id: Long,
    val startAt: Long,
    val endAt: Long,
    val network: String,
    val bytes: Long,
    val peakBps: Long,
    val suspicious: Boolean,
    val fake: Boolean,
)

object UsageHistoryStore {
    private const val FILE_NAME = "network_usage_intervals.tsv"
    private const val RETENTION_MS = 14L * 24L * 60L * 60L * 1000L
    private val lock = Any()
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    @Volatile private var lastPruneAt = 0L

    fun append(context: Context, interval: UsageInterval) = synchronized(lock) {
        val file = File(context.filesDir, FILE_NAME)
        file.parentFile?.mkdirs()
        file.appendText(encode(interval) + "\n")
        pruneLocked(file, System.currentTimeMillis())
    }

    fun read(context: Context, sinceMs: Long = System.currentTimeMillis() - RETENTION_MS): List<UsageInterval> =
        synchronized(lock) {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return@synchronized emptyList()
            file.useLines { lines ->
                lines.mapNotNull(::decode)
                    .filter { it.endAt >= sinceMs }
                    // Ties are broken by id so several records inside one minute keep a stable order
                    // across reads, which the review list and id-based toggling both depend on.
                    .sortedWith(compareBy({ it.startAt }, { it.id }))
                    .toList()
            }
        }

    fun toggleFake(context: Context, id: Long): UsageInterval? = synchronized(lock) {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return@synchronized null
        val rows = file.readLines().mapNotNull(::decode).toMutableList()
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null
        val updated = rows[index].copy(fake = !rows[index].fake)
        rows[index] = updated
        rewriteLocked(file, rows)
        updated
    }

    /**
     * Drops one day's records. A [network] narrows it to a single radio, so resetting the 4G figure
     * does not also clear the 5G one.
     */
    fun clearDay(context: Context, dayStamp: String, network: String? = null) = synchronized(lock) {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return@synchronized
        val rows = file.readLines().mapNotNull(::decode).filterNot { row ->
            dayFormat.format(Date(row.startAt)) == dayStamp && (network == null || row.network == network)
        }
        rewriteLocked(file, rows)
    }

    private fun encode(item: UsageInterval): String =
        listOf(
            item.id,
            item.startAt,
            item.endAt,
            item.network.replace("\t", " "),
            item.bytes,
            item.peakBps,
            if (item.suspicious) 1 else 0,
            if (item.fake) 1 else 0,
        ).joinToString("\t")

    private fun decode(line: String): UsageInterval? = runCatching {
        val p = line.split("\t")
        if (p.size < 8) return null
        UsageInterval(
            id = p[0].toLong(),
            startAt = p[1].toLong(),
            endAt = p[2].toLong(),
            network = p[3],
            bytes = p[4].toLong().coerceAtLeast(0L),
            peakBps = p[5].toLong().coerceAtLeast(0L),
            suspicious = p[6] == "1",
            fake = p[7] == "1",
        )
    }.getOrNull()

    private fun rewriteLocked(file: File, rows: List<UsageInterval>) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(rows.joinToString("\n", postfix = if (rows.isNotEmpty()) "\n" else "") { encode(it) })
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun pruneLocked(file: File, now: Long) {
        // Avoid a full rewrite on every minute.
        if (now - lastPruneAt < 6L * 60L * 60L * 1000L) return
        lastPruneAt = now
        val cutoff = now - RETENTION_MS
        val rows = file.readLines().mapNotNull(::decode).filter { it.endAt >= cutoff }
        rewriteLocked(file, rows)
    }
}
