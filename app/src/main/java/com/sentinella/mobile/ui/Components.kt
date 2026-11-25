package com.sentinella.mobile.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sentinella.mobile.Beat
import com.sentinella.mobile.Outage
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** Pastiglia colorata con la percentuale (come in Kuma). */
@Composable
fun UptimePill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(color, RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = onColor(color), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Barra dei controlli: una stanghetta per controllo, la più recente a destra. */
@Composable
fun HeartbeatBar(
    beats: List<Beat>,
    modifier: Modifier = Modifier,
    height: Dp = 18.dp,
    barWidth: Dp = 6.dp,
    gap: Dp = 3.dp,
    selectedId: Long? = null,
    onSelect: ((Beat?) -> Unit)? = null,
) {
    val tap = if (onSelect != null) Modifier.pointerInput(beats) {
        detectTapGestures { off ->
            val bw = barWidth.toPx()
            val g = gap.toPx()
            val n = ((size.width + g) / (bw + g)).toInt().coerceAtLeast(1)
            val shown = beats.takeLast(n)
            val startX = size.width - (n * bw + (n - 1) * g)
            val i = floor((off.x - startX) / (bw + g)).toInt()
            val idx = i - (n - shown.size)
            onSelect(shown.getOrNull(idx))
        }
    } else Modifier

    Canvas(modifier.fillMaxWidth().height(height).then(tap)) {
        val bw = barWidth.toPx()
        val g = gap.toPx()
        val n = ((size.width + g) / (bw + g)).toInt().coerceAtLeast(1)
        val shown = beats.takeLast(n)
        val offset = n - shown.size
        val startX = size.width - (n * bw + (n - 1) * g)
        for (i in 0 until n) {
            val x = startX + i * (bw + g)
            val b = if (i >= offset) shown[i - offset] else null
            val color = if (b == null) K.Empty else statusColor(b.status, true)
            val sel = b != null && b.id == selectedId
            drawRoundRect(
                color = color,
                topLeft = Offset(x, 0f),
                size = Size(bw, size.height),
                cornerRadius = CornerRadius(bw / 2, bw / 2),
                alpha = if (selectedId != null && !sel) 0.55f else 1f,
            )
            if (sel) {
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(x - 1.5f, -1.5f),
                    size = Size(bw + 3f, size.height + 3f),
                    cornerRadius = CornerRadius(bw / 2, bw / 2),
                    style = Stroke(1.5.dp.toPx()),
                )
            }
        }
    }
}

// ---------------- grafico tempi di risposta ----------------

data class ChartPoint(val t: Long, val v: Float)

data class ChartZone(val start: Long, val end: Long, val down: Boolean)

data class ChartData(
    val from: Long,
    val to: Long,
    val pts: List<ChartPoint>,
    val zones: List<ChartZone>,
    val gapMs: Long,
)

/** Dimensione dei gruppi di controlli per avere al massimo ~240 punti nel grafico. */
fun chartBucket(from: Long, to: Long, intervalMs: Long): Long =
    maxOf((to - from) / 240, intervalMs, 1000L)

fun buildChart(pts: List<ChartPoint>, outages: List<Outage>, from: Long, to: Long, bucket: Long, intervalMs: Long): ChartData {
    val zones = outages.map { ChartZone(maxOf(it.start, from), it.end ?: to, it.reachedDown) }
    val gap = maxOf(bucket * 3, (intervalMs * 2.5).toLong())
    return ChartData(from, to, pts, zones, gap)
}

private fun niceMax(v: Float): Float {
    if (v <= 0f) return 100f
    val raw = v * 1.15f
    val mag = 10.0.pow(floor(log10(raw.toDouble()))).toFloat()
    for (s in floatArrayOf(1f, 2f, 2.5f, 4f, 5f, 8f, 10f)) if (s * mag >= raw) return s * mag
    return 10 * mag
}

@Composable
fun ResponseChart(data: ChartData, modifier: Modifier = Modifier) {
    var sel by remember(data) { mutableStateOf<ChartPoint?>(null) }
    val long = data.to - data.from > 26 * 3600_000L
    val axisFmt = remember(long) { SimpleDateFormat(if (long) "dd/MM" else "HH:mm", IT) }
    val tipFmt = remember(long) { SimpleDateFormat(if (long) "dd/MM HH:mm" else "HH:mm:ss", IT) }
    val label = remember { Paint().apply { isAntiAlias = true; color = K.Muted.toArgb() } }
    val tip = remember { Paint().apply { isAntiAlias = true; color = K.Text.toArgb(); isFakeBoldText = true } }

    fun nearest(x: Float, width: Float, density: Float): ChartPoint? {
        if (data.pts.isEmpty()) return null
        val left = 46 * density
        val w = width - left - 8 * density
        val t = data.from + ((x - left) / w * (data.to - data.from)).toLong()
        return data.pts.minByOrNull { abs(it.t - t) }
    }

    Canvas(
        modifier
            .pointerInput(data) {
                detectTapGestures { sel = nearest(it.x, size.width.toFloat(), density) }
            }
            .pointerInput(data) {
                detectHorizontalDragGestures(
                    onDragStart = { sel = nearest(it.x, size.width.toFloat(), density) },
                ) { change, _ -> sel = nearest(change.position.x, size.width.toFloat(), density) }
            }
    ) {
        val left = 46.dp.toPx()
        val right = 8.dp.toPx()
        val top = 10.dp.toPx()
        val bottom = 22.dp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom
        val span = (data.to - data.from).coerceAtLeast(1).toFloat()
        val yMax = niceMax(data.pts.maxOfOrNull { it.v } ?: 0f)
        fun x(t: Long) = left + (t - data.from) / span * w
        fun y(v: Float) = top + h - v / yMax * h
        val nc = drawContext.canvas.nativeCanvas

        label.textSize = 10.sp.toPx()
        label.textAlign = Paint.Align.RIGHT
        for (i in 0..4) {
            val v = yMax * i / 4
            val yy = y(v)
            drawLine(K.Border, Offset(left, yy), Offset(left + w, yy), 1f)
            nc.drawText("${v.roundToInt()} ms", left - 6.dp.toPx(), yy + 3.5.dp.toPx(), label)
        }

        for (z in data.zones) {
            val xa = x(z.start).coerceIn(left, left + w)
            val xb = x(z.end).coerceIn(left, left + w)
            drawRect(
                (if (z.down) K.Red else K.Orange).copy(alpha = 0.30f),
                Offset(xa, top), Size((xb - xa).coerceAtLeast(2.dp.toPx()), h),
            )
        }

        // segmenti continui (si interrompono se mancano controlli)
        val segs = mutableListOf<MutableList<ChartPoint>>()
        for (p in data.pts) {
            if (segs.isEmpty() || p.t - segs.last().last().t > data.gapMs) segs += mutableListOf(p)
            else segs.last() += p
        }
        val fillBrush = Brush.verticalGradient(
            listOf(K.Green.copy(alpha = 0.35f), K.Green.copy(alpha = 0f)),
            startY = top, endY = top + h,
        )
        for (seg in segs) {
            if (seg.size == 1) {
                drawCircle(K.Green, 2.5.dp.toPx(), Offset(x(seg[0].t), y(seg[0].v)))
                continue
            }
            val line = Path()
            val fill = Path()
            seg.forEachIndexed { i, p ->
                val px = x(p.t)
                val py = y(p.v)
                if (i == 0) {
                    line.moveTo(px, py); fill.moveTo(px, top + h); fill.lineTo(px, py)
                } else {
                    line.lineTo(px, py); fill.lineTo(px, py)
                }
            }
            fill.lineTo(x(seg.last().t), top + h)
            fill.close()
            drawPath(fill, fillBrush)
            drawPath(line, K.Green, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        for (i in 0..4) {
            val t = data.from + ((data.to - data.from) * i / 4)
            label.textAlign = when (i) {
                0 -> Paint.Align.LEFT
                4 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            nc.drawText(axisFmt.format(Date(t)), x(t), size.height - 5.dp.toPx(), label)
        }

        if (data.pts.isEmpty() && data.zones.isEmpty()) {
            label.textAlign = Paint.Align.CENTER
            label.textSize = 13.sp.toPx()
            nc.drawText("Nessun dato nel periodo", left + w / 2, top + h / 2, label)
        }

        sel?.let { p ->
            val px = x(p.t)
            val py = y(p.v)
            drawLine(
                K.Muted, Offset(px, top), Offset(px, top + h), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
            drawCircle(K.Green, 5.dp.toPx(), Offset(px, py))
            drawCircle(K.Bg, 2.5.dp.toPx(), Offset(px, py))
            tip.textSize = 12.sp.toPx()
            tip.textAlign = Paint.Align.LEFT
            val text = "${tipFmt.format(Date(p.t))}  ·  ${p.v.roundToInt()} ms"
            val tw = tip.measureText(text)
            val pad = 8.dp.toPx()
            val bw = tw + pad * 2
            val bh = 24.dp.toPx()
            val bx = (px - bw / 2).coerceIn(left, left + w - bw)
            drawRoundRect(K.Card2, Offset(bx, top), Size(bw, bh), CornerRadius(8.dp.toPx()))
            drawRoundRect(K.Border, Offset(bx, top), Size(bw, bh), CornerRadius(8.dp.toPx()), style = Stroke(1f))
            nc.drawText(text, bx + pad, top + bh / 2 + 4.dp.toPx(), tip)
        }
    }
}
