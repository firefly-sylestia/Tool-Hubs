package com.sylestia.toolhubs.networkswitcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sylestia.toolhubs.designsystem.hubAccents
import com.sylestia.toolhubs.ui.HubPill
import kotlin.math.roundToInt

/*
 * The interactive graphs.
 *
 * All three follow one rule, learned from the first usage chart: the readout above the canvas, the
 * crosshair and the highlighted point are driven by a single index, so they cannot disagree about
 * which bucket the finger is on. Each graph owns that index (except the two that lift a *selection*
 * back to their page) and each one is hit-tested with the same inset it is drawn with.
 */

/** How a metric's samples collapse into one bucket. */
enum class BucketAggregate { MAX, SUM, AVERAGE }

/** Laid out inside the canvas; hit-testing must use the exact same value. */
private val GRAPH_INSET = 14.dp

private const val LIVE_GRACE_MS = 12_000L

private data class LiveBucket(
    val value: Float?,
    val newestAt: Long,
    val network: String?,
)

/**
 * The Watch page's live graph: second-by-second speed, traffic, latency or signal.
 *
 * The samples live in [LiveTelemetry], which is a ring buffer, so the whole window is real
 * observation — there is no interpolation and no back-fill. Where the watcher (or this screen) was
 * not running, the run simply breaks, and a broken run is drawn dim and dashed instead of being
 * connected as if the radio had been quiet. That is the difference the legend means by live vs
 * earlier.
 */
@Composable
fun LiveMetricGraph(
    samples: List<LiveTelemetry.Sample>,
    windowStart: Long,
    bucketMs: Long,
    now: Long,
    aggregate: BucketAggregate,
    format: (Float) -> String,
    axisCeiling: Float?,
    accent: Color,
    emptyHint: String,
    height: Dp = 168.dp,
) {
    val bucketCount = (((now - windowStart) / bucketMs).toInt() + 1).coerceIn(2, 240)

    val buckets = remember(samples, windowStart, bucketMs, now, aggregate, bucketCount) {
        val firstBucket = (windowStart / bucketMs) * bucketMs
        val sum = FloatArray(bucketCount)
        val count = IntArray(bucketCount)
        val peak = FloatArray(bucketCount)
        val newest = LongArray(bucketCount)
        val network = arrayOfNulls<String>(bucketCount)
        samples.forEach { sample ->
            val index = ((sample.at - firstBucket) / bucketMs).toInt()
            if (index in 0 until bucketCount) {
                sum[index] += sample.value
                peak[index] = maxOf(peak[index], sample.value)
                count[index]++
                if (sample.at >= newest[index]) {
                    newest[index] = sample.at
                    if (sample.network.isNotEmpty()) network[index] = sample.network
                }
            }
        }
        (0 until bucketCount).map { index ->
            val value = when {
                count[index] == 0 -> null
                aggregate == BucketAggregate.SUM -> sum[index]
                aggregate == BucketAggregate.AVERAGE -> sum[index] / count[index]
                else -> peak[index]
            }
            LiveBucket(value, newest[index], network[index])
        }
    }

    val observedPeak = buckets.mapNotNull { it.value }.maxOrNull() ?: 0f
    val scaleMax = maxOf(axisCeiling ?: 0f, observedPeak, 1f)
    val newestSampleAt = buckets.maxOfOrNull { it.newestAt } ?: 0L
    val stale = newestSampleAt > 0L && now - newestSampleAt > LIVE_GRACE_MS

    var scrubIndex by remember { mutableStateOf<Int?>(null) }
    var graphWidth by remember { mutableIntStateOf(0) }
    val insetPx = with(LocalDensity.current) { GRAPH_INSET.toPx() }

    fun indexFor(x: Float): Int {
        val chartWidth = (graphWidth - insetPx * 2f).coerceAtLeast(1f)
        val step = chartWidth / (bucketCount - 1).coerceAtLeast(1)
        return (((x - insetPx) / step).roundToInt()).coerceIn(0, bucketCount - 1)
    }

    val scrubbed = scrubIndex?.let { buckets.getOrNull(it) }
    // Held in its own local so the readout reads the same value the branch tested, rather than
    // relying on a smart cast on a property chain.
    val scrubbedValue = scrubbed?.value
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
    val baselineColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.24f)

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        scrubbedValue != null -> format(scrubbedValue)
                        scrubbed != null -> "No sample in this bucket"
                        else -> "Drag across the graph to inspect a bucket"
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    when {
                        scrubbedValue != null ->
                            (scrubbed?.network ?: "unattributed") + " · " +
                                if (stale) "not sampling now" else "live"
                        observedPeak <= 0f -> emptyHint
                        else -> "Peak " + format(observedPeak) + " · " + bucketMs / 1_000L + "s buckets"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
            }
            HubPill(
                label = if (stale) "PAUSED" else "LIVE",
                active = !stale,
                accent = if (stale) MaterialTheme.colorScheme.tertiary else accent,
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .onSizeChanged { graphWidth = it.width }
                .pointerInput(bucketCount) {
                    detectDragGestures(
                        onDragStart = { offset -> scrubIndex = indexFor(offset.x) },
                        onDrag = { change, _ -> scrubIndex = indexFor(change.position.x) },
                    )
                }
                .pointerInput(bucketCount) {
                    detectTapGestures { offset ->
                        val index = indexFor(offset.x)
                        scrubIndex = if (scrubIndex == index) null else index
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val inset = GRAPH_INSET.toPx()
                val chartWidth = (size.width - inset * 2f).coerceAtLeast(1f)
                val chartHeight = (size.height - inset * 2f).coerceAtLeast(1f)
                val baseline = size.height - inset
                val stepX = chartWidth / (bucketCount - 1).coerceAtLeast(1)

                for (row in 0..3) {
                    val y = inset + chartHeight * row / 3f
                    drawLine(gridColor, Offset(inset, y), Offset(size.width - inset, y), strokeWidth = 1f)
                }

                // Consecutive buckets that actually hold a sample. A gap here is real: the watcher
                // was not running, or the screen was closed, so nothing may be drawn across it.
                val runs = mutableListOf<MutableList<Int>>()
                buckets.forEachIndexed { index, bucket ->
                    if (bucket.value == null) return@forEachIndexed
                    val current = runs.lastOrNull()
                    if (current != null && current.last() == index - 1) current.add(index) else runs.add(mutableListOf(index))
                }

                fun pointFor(index: Int): Offset {
                    val value = buckets[index].value ?: 0f
                    val normalized = (value / scaleMax).coerceIn(0f, 1f)
                    return Offset(inset + stepX * index, baseline - chartHeight * normalized)
                }

                runs.forEach { run ->
                    val live = run.last() >= bucketCount - 1 || buckets[run.last()].newestAt >= newestSampleAt
                    val points = run.map(::pointFor)
                    if (points.size >= 2) {
                        val area = Path().apply {
                            moveTo(points.first().x, baseline)
                            points.forEach { lineTo(it.x, it.y) }
                            lineTo(points.last().x, baseline)
                            close()
                        }
                        drawPath(area, accent.copy(alpha = if (live) 0.12f else 0.05f))
                        val line = Path().apply {
                            moveTo(points.first().x, points.first().y)
                            points.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        drawPath(
                            path = line,
                            color = accent.copy(alpha = if (live) 0.80f else 0.34f),
                            style = if (live) {
                                Stroke(width = 2.6.dp.toPx())
                            } else {
                                Stroke(
                                    width = 2.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 9f), 0f),
                                )
                            },
                        )
                    }
                    if (run.size == 1) {
                        drawCircle(
                            color = accent.copy(alpha = if (live) 0.80f else 0.34f),
                            radius = 2.5.dp.toPx(),
                            center = points.first(),
                        )
                    }
                }

                buckets.forEachIndexed { index, bucket ->
                    if (bucket.value == null) return@forEachIndexed
                    val point = pointFor(index)
                    drawCircle(color = accent.copy(alpha = 0.16f), radius = 6.dp.toPx(), center = point)
                    drawCircle(color = accent, radius = 2.6.dp.toPx(), center = point)
                }

                scrubIndex?.let { index ->
                    val point = pointFor(index.coerceIn(0, buckets.lastIndex))
                    drawLine(
                        color = accent.copy(alpha = 0.34f),
                        start = Offset(point.x, inset),
                        end = Offset(point.x, baseline),
                        strokeWidth = 1.5f,
                    )
                    drawCircle(color = accent.copy(alpha = 0.20f), radius = 10.dp.toPx(), center = point)
                    drawCircle(color = accent, radius = 4.5.dp.toPx(), center = point)
                }

                drawLine(
                    color = baselineColor,
                    start = Offset(inset, baseline),
                    end = Offset(size.width - inset, baseline),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatIntervalClock(windowStart), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
            Text("now", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
        }
    }
}

/** One hour of incident history, split by the kind of event that happened in it. */
data class IncidentBucket(
    val startAt: Long,
    val network: Int,
    val successes: Int,
    val failures: Int,
    val sim: Int,
    val other: Int,
) {
    val total: Int get() = network + successes + failures + sim + other
    val needsAttention: Int get() = failures * 3 + sim * 2 + network
}

/**
 * The Activity page's 24-hour incident graph.
 *
 * Selection is lifted to the caller: tapping an hour filters the list underneath to exactly that
 * hour, which is the difference between a decoration and a tool.
 */
@Composable
fun IncidentGraph(
    buckets: List<IncidentBucket>,
    bucketMs: Long,
    now: Long,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    height: Dp = 132.dp,
) {
    if (buckets.isEmpty()) return
    val scaleMax = maxOf(buckets.maxOfOrNull { it.total } ?: 0, 1)
    val bucketHours = (bucketMs / 3_600_000L).toInt().coerceAtLeast(1)

    var graphWidth by remember { mutableIntStateOf(0) }
    val insetPx = with(LocalDensity.current) { GRAPH_INSET.toPx() }

    fun indexFor(x: Float): Int {
        val chartWidth = (graphWidth - insetPx * 2f).coerceAtLeast(1f)
        val step = chartWidth / buckets.size.coerceAtLeast(1)
        return (((x - insetPx) / step).toInt()).coerceIn(0, buckets.lastIndex)
    }

    val selected = selectedIndex?.let { buckets.getOrNull(it) }
    val networkColor = MaterialTheme.colorScheme.tertiary
    val successColor = MaterialTheme.hubAccents.success
    val failureColor = MaterialTheme.colorScheme.error
    val simColor = MaterialTheme.colorScheme.primary
    val otherColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
    // Read outside the draw lambda on purpose: a theme lookup is a @Composable call, and the
    // Canvas block is not a composable scope.
    val selectionFillColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val selectionLineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    selected?.let { "Selected " + formatHourWindow(it.startAt, bucketMs) } ?: "Tap an hour to filter the list",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    selected?.let {
                        it.total.toString() + " event" + (if (it.total == 1) "" else "s") + " · " +
                            it.failures + " failed · " + it.network + " network"
                    } ?: ("Peak " + scaleMax + " in one " + bucketHours + "h block"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
            }
            if (selected != null) {
                HubPill(
                    label = if (selected.needsAttention > 0) "REVIEW" else "QUIET",
                    active = selected.needsAttention == 0,
                    accent = if (selected.needsAttention > 0) failureColor else successColor,
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .onSizeChanged { graphWidth = it.width }
                .pointerInput(buckets.size) {
                    detectDragGestures(
                        onDragStart = { offset -> onSelect(indexFor(offset.x)) },
                        onDrag = { change, _ -> onSelect(indexFor(change.position.x)) },
                    )
                }
                .pointerInput(buckets.size) {
                    detectTapGestures { offset ->
                        val index = indexFor(offset.x)
                        onSelect(if (selectedIndex == index) null else index)
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val inset = GRAPH_INSET.toPx()
                val chartHeight = (size.height - inset * 2f).coerceAtLeast(1f)
                val baseline = size.height - inset
                val slot = (size.width - inset * 2f).coerceAtLeast(1f) / buckets.size
                val barWidth = (slot * 0.62f).coerceAtLeast(2f)

                for (row in 0..2) {
                    val y = inset + chartHeight * row / 2f
                    drawLine(gridColor, Offset(inset, y), Offset(size.width - inset, y), strokeWidth = 1f)
                }

                buckets.forEachIndexed { index, bucket ->
                    if (bucket.total == 0) return@forEachIndexed
                    val left = inset + slot * index + (slot - barWidth) / 2f
                    var drawn = 0f
                    fun segment(count: Int, color: Color) {
                        if (count <= 0) return
                        val fraction = (count.toFloat() / scaleMax).coerceAtLeast(0.04f)
                        val segmentHeight = chartHeight * fraction
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(left, baseline - drawn - segmentHeight),
                            size = Size(barWidth, segmentHeight),
                            cornerRadius = CornerRadius(barWidth / 3f),
                        )
                        drawn += segmentHeight
                    }
                    segment(bucket.network, networkColor)
                    segment(bucket.successes, successColor)
                    segment(bucket.other, otherColor)
                    segment(bucket.sim, simColor)
                    segment(bucket.failures, failureColor)
                }

                selectedIndex?.let { index ->
                    val left = inset + slot * index
                    drawRoundRect(
                        color = selectionFillColor,
                        topLeft = Offset(left, inset),
                        size = Size(slot, chartHeight),
                        cornerRadius = CornerRadius(8.dp.toPx()),
                    )
                    drawLine(
                        color = selectionLineColor,
                        start = Offset(left + slot / 2f, inset),
                        end = Offset(left + slot / 2f, baseline),
                        strokeWidth = 1.5f,
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatIntervalClock(now - bucketMs * buckets.size),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp,
            )
            Text("now", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendDot(networkColor, "Network")
            LegendDot(successColor, "Success")
            LegendDot(failureColor, "Failed")
            LegendDot(simColor, "SIM")
        }
    }
}

/** One bucket of the usage timeline: the exact intervals that fell inside it. */
data class UsageBucket(
    val startAt: Long,
    val lteBytes: Long,
    val fiveGBytes: Long,
    val suspicious: Boolean,
    val exact: List<UsageInterval>,
) {
    val total: Long get() = lteBytes + fiveGBytes
}

/**
 * The History page's usage timeline.
 *
 * Unlike the live graph this one *does* have exact records behind every bucket, so the selection
 * carries them: choosing a bucket opens the minute-by-minute intervals inside it, right below.
 */
@Composable
fun UsageTimelineGraph(
    buckets: List<UsageBucket>,
    bucketMs: Long,
    now: Long,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    format: (Long) -> String,
    height: Dp = 176.dp,
) {
    if (buckets.isEmpty()) return
    val scaleMax = maxOf(buckets.maxOfOrNull { it.total } ?: 0L, 1L)
    val peak = buckets.maxByOrNull { it.total }

    var graphWidth by remember { mutableIntStateOf(0) }
    val insetPx = with(LocalDensity.current) { GRAPH_INSET.toPx() }

    fun indexFor(x: Float): Int {
        val chartWidth = (graphWidth - insetPx * 2f).coerceAtLeast(1f)
        val step = chartWidth / buckets.size.coerceAtLeast(1)
        return (((x - insetPx) / step).toInt()).coerceIn(0, buckets.lastIndex)
    }

    val selected = selectedIndex?.let { buckets.getOrNull(it) }
    val fiveGColor = MaterialTheme.hubAccents.success
    val lteColor = MaterialTheme.colorScheme.tertiary
    val cautionColor = MaterialTheme.colorScheme.error
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
    // Hoisted for the same reason as in IncidentGraph: no theme lookups inside the Canvas.
    val selectionFillColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    selected?.let { format(it.total) + " · " + formatHourWindow(it.startAt, bucketMs) }
                        ?: "Tap a bar to open its exact intervals",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    selected?.let {
                        format(it.fiveGBytes) + " on 5G · " + format(it.lteBytes) + " on LTE · " +
                            it.exact.size + " interval" + (if (it.exact.size == 1) "" else "s")
                    } ?: (peak?.let { "Busiest bucket " + format(it.total) + " at " + formatIntervalClock(it.startAt) }
                        ?: "No traffic recorded in this range"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
            }
            when {
                selected?.suspicious == true -> HubPill("REVIEW", active = false, accent = cautionColor)
                selectedIndex != null -> HubPill("SELECTED", active = true, accent = fiveGColor)
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .onSizeChanged { graphWidth = it.width }
                .pointerInput(buckets.size) {
                    detectDragGestures(
                        onDragStart = { offset -> onSelect(indexFor(offset.x)) },
                        onDrag = { change, _ -> onSelect(indexFor(change.position.x)) },
                    )
                }
                .pointerInput(buckets.size) {
                    detectTapGestures { offset ->
                        val index = indexFor(offset.x)
                        onSelect(if (selectedIndex == index) null else index)
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val inset = GRAPH_INSET.toPx()
                val chartHeight = (size.height - inset * 2f).coerceAtLeast(1f)
                val baseline = size.height - inset
                val slot = (size.width - inset * 2f).coerceAtLeast(1f) / buckets.size
                val barWidth = (slot * 0.66f).coerceAtLeast(2f)

                for (row in 0..3) {
                    val y = inset + chartHeight * row / 3f
                    drawLine(gridColor, Offset(inset, y), Offset(size.width - inset, y), strokeWidth = 1f)
                }

                buckets.forEachIndexed { index, bucket ->
                    if (bucket.total <= 0L) return@forEachIndexed
                    val left = inset + slot * index + (slot - barWidth) / 2f
                    val lteHeight = chartHeight * (bucket.lteBytes.toFloat() / scaleMax).coerceIn(0f, 1f)
                    val fiveGHeight = chartHeight * (bucket.fiveGBytes.toFloat() / scaleMax).coerceIn(0f, 1f)
                    val selectedBucket = index == selectedIndex
                    val alpha = if (selectedIndex == null || selectedBucket) 1f else 0.42f
                    if (lteHeight > 0f) {
                        drawRoundRect(
                            color = lteColor.copy(alpha = alpha),
                            topLeft = Offset(left, baseline - lteHeight),
                            size = Size(barWidth, lteHeight),
                            cornerRadius = CornerRadius(barWidth / 3f),
                        )
                    }
                    if (fiveGHeight > 0f) {
                        drawRoundRect(
                            color = (if (bucket.suspicious) cautionColor else fiveGColor).copy(alpha = alpha),
                            topLeft = Offset(left, baseline - lteHeight - fiveGHeight),
                            size = Size(barWidth, fiveGHeight),
                            cornerRadius = CornerRadius(barWidth / 3f),
                        )
                    }
                    if (bucket.suspicious) {
                        drawLine(
                            color = cautionColor,
                            start = Offset(left + barWidth / 2f, baseline - lteHeight - fiveGHeight - 4.dp.toPx()),
                            end = Offset(left + barWidth / 2f, baseline - lteHeight - fiveGHeight),
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                }

                selectedIndex?.let { index ->
                    val left = inset + slot * index
                    drawRoundRect(
                        color = selectionFillColor,
                        topLeft = Offset(left, inset),
                        size = Size(slot, chartHeight),
                        cornerRadius = CornerRadius(8.dp.toPx()),
                    )
                }

                drawLine(
                    color = lteColor.copy(alpha = 0.30f),
                    start = Offset(inset, baseline),
                    end = Offset(size.width - inset, baseline),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatIntervalClock(buckets.first().startAt), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
            Text(
                // The newest bucket is only part-way through, so its end is clamped to now: labelling
                // it with the bucket boundary would put a future time on the axis.
                formatIntervalClock(minOf(buckets.last().startAt + bucketMs, now)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendDot(fiveGColor, "5G")
            LegendDot(lteColor, "4G / LTE")
            LegendDot(cautionColor, "Flagged")
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(8.dp).height(8.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    }
}

/** `dd MMM · HH:00 → HH:00` for a bucket of [bucketMs]. */
private fun formatHourWindow(startAt: Long, bucketMs: Long): String {
    val end = startAt + bucketMs
    val day = java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault())
    val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return day.format(java.util.Date(startAt)) + " · " + clock.format(java.util.Date(startAt)) +
        " → " + clock.format(java.util.Date(end))
}
