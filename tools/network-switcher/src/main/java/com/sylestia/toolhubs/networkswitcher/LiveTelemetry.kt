package com.sylestia.toolhubs.networkswitcher

import android.os.SystemClock
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Process-wide ring buffer of the samples the live graphs read.
 *
 * This is deliberately in memory rather than in preferences or a file. The live graph is a
 * second-by-second view of the last half hour; persisting it would mean rewriting a few thousand
 * numbers every couple of seconds, and nothing here is worth surviving a process death — the
 * persisted minute intervals in [UsageHistoryStore] already cover anything older than the buffer.
 *
 * The watcher (`NetworkUsageTracker`) and the screen (`MainActivity`) run in the same process, so
 * both write to the same buffer: speed and byte deltas come from the watcher, latency and signal
 * level are only sampled while the screen is open.
 */
object LiveTelemetry {

    enum class Metric { SPEED, DATA, LATENCY, SIGNAL }

    data class Sample(val at: Long, val value: Float, val network: String)

    /**
     * 960 samples at the watcher's roughly two-second cadence is a little over half an hour, which is
     * the longest window the live graph offers. Bigger windows would silently drop the oldest samples
     * and draw a truncated graph that looks like a quiet period.
     */
    private const val CAPACITY = 960

    private val lock = Any()
    private val buffers = LinkedHashMap<Metric, ArrayDeque<Sample>>()

    fun record(metric: Metric, at: Long, value: Float, network: String = "") {
        // A NaN or an infinity here would make every bucket average and the whole Y axis nonsense.
        if (!value.isFinite()) return
        synchronized(lock) {
            val buffer = buffers.getOrPut(metric) { ArrayDeque() }
            // One sample per timestamp per metric: two records written for the same instant would
            // otherwise double-count in the byte sum and skew every average.
            if (buffer.lastOrNull()?.at == at) buffer.removeLast()
            buffer.addLast(Sample(at, value, network))
            while (buffer.size > CAPACITY) buffer.removeFirst()
        }
    }

    fun samples(metric: Metric, sinceAt: Long): List<Sample> = synchronized(lock) {
        buffers[metric].orEmpty().filter { it.at >= sinceAt }
    }
}

/**
 * Round-trip estimate for the latency graph.
 *
 * Android exposes no latency API, and ICMP echo is not available to an unprivileged app, so this
 * measures the TCP handshake to a well-known anycast resolver. That is a real round trip over the
 * live radio — it just is not the same thing as an ICMP ping, and the UI says so. Nothing is sent
 * beyond the handshake itself, and the probe only runs while the Watch screen is open, so it costs
 * no background traffic.
 */
object LatencyProbe {
    private const val HOST = "1.1.1.1"
    private const val PORT = 443
    const val TIMEOUT_MS = 1_500

    /** Handshake time in milliseconds, or -1 when the connection did not complete. */
    fun measure(): Long {
        val socket = Socket()
        return try {
            val started = SystemClock.elapsedRealtime()
            socket.connect(InetSocketAddress(HOST, PORT), TIMEOUT_MS)
            SystemClock.elapsedRealtime() - started
        } catch (t: Throwable) {
            -1L
        } finally {
            runCatching { socket.close() }
        }
    }
}
