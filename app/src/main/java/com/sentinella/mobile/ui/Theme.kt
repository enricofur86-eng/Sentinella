package com.sentinella.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sentinella.mobile.Db
import com.sentinella.mobile.ST_DOWN
import com.sentinella.mobile.ST_PENDING
import com.sentinella.mobile.ST_UP
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor

/** Colori in stile Uptime Kuma (tema scuro). */
object K {
    val Bg = Color(0xFF090C10)
    val Card = Color(0xFF0D1117)
    val Card2 = Color(0xFF161B22)
    val Border = Color(0xFF1F2A37)
    val Green = Color(0xFF5CDD8B)
    val Red = Color(0xFFDC3545)
    val Orange = Color(0xFFF8A306)
    val Grey = Color(0xFF6C757D)
    val Empty = Color(0x22FFFFFF)
    val Text = Color(0xFFF0F6FC)
    val Muted = Color(0xFF8B949E)
    val DarkText = Color(0xFF0B1F12)
}

@Composable
fun SentinellaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = K.Green,
            onPrimary = K.DarkText,
            secondary = K.Green,
            onSecondary = K.DarkText,
            secondaryContainer = Color(0xFF1E3A2A),
            onSecondaryContainer = K.Green,
            background = K.Bg,
            onBackground = K.Text,
            surface = K.Bg,
            onSurface = K.Text,
            surfaceVariant = K.Card2,
            onSurfaceVariant = K.Muted,
            surfaceContainer = K.Card,
            surfaceContainerHigh = K.Card2,
            surfaceContainerHighest = K.Card2,
            outline = K.Border,
            outlineVariant = K.Border,
            error = K.Red,
        ),
        content = content,
    )
}

@Composable
fun KCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = K.Card,
        border = BorderStroke(1.dp, K.Border),
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

/** Carica dati dal database e li ricarica dopo ogni nuovo controllo (al massimo una volta al secondo). */
@Composable
fun <T> rememberDb(vararg keys: Any?, load: (Db) -> T): State<T?> {
    val ctx = LocalContext.current
    val currentLoad by rememberUpdatedState(load)
    return produceState<T?>(initialValue = null, *keys) {
        Db.changes.conflate().collect {
            value = withContext(Dispatchers.IO) { currentLoad(Db.get(ctx)) }
            delay(1000)
        }
    }
}

// ---------------- formati ----------------

val IT: Locale = Locale.ITALY

fun fmtPct(p: Double?): String = when {
    p == null -> "–"
    p >= 100.0 -> "100%"
    else -> String.format(IT, "%.2f%%", floor(p * 100) / 100)
}

fun fmtMs(v: Number?): String = if (v == null) "–" else "${Math.round(v.toDouble())} ms"

fun fmtInterval(sec: Int): String = when {
    sec < 60 -> "$sec s"
    sec < 3600 -> "${sec / 60} min"
    else -> "${sec / 3600} h"
}

/** Durata leggibile: "10 s", "2 min 5 s", "1 h 20 min", "2 g 3 h". */
fun fmtDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "$s s"
        s < 3600 -> if (s % 60 == 0L) "${s / 60} min" else "${s / 60} min ${s % 60} s"
        s < 86400 -> if (s % 3600 / 60 == 0L) "${s / 3600} h" else "${s / 3600} h ${s % 3600 / 60} min"
        else -> "${s / 86400} g ${s % 86400 / 3600} h"
    }
}

/** Ora se è oggi, altrimenti giorno e ora. */
fun fmtWhen(t: Long): String {
    val day = SimpleDateFormat("yyyyMMdd", IT)
    val today = day.format(Date()) == day.format(Date(t))
    return SimpleDateFormat(if (today) "HH:mm:ss" else "dd/MM HH:mm:ss", IT).format(Date(t))
}

fun fmtTime(t: Long): String = SimpleDateFormat("HH:mm:ss", IT).format(Date(t))
fun fmtDateTime(t: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", IT).format(Date(t))

fun statusColor(status: Int?, active: Boolean): Color = when {
    !active -> K.Grey
    status == ST_UP -> K.Green
    status == ST_DOWN -> K.Red
    status == ST_PENDING -> K.Orange
    else -> K.Grey
}

fun statusLabel(status: Int?, active: Boolean): String = when {
    !active -> "In pausa"
    status == ST_UP -> "Online"
    status == ST_DOWN -> "Offline"
    status == ST_PENDING -> "In attesa"
    else -> "Nessun dato"
}

fun onColor(bg: Color): Color = if (bg == K.Green || bg == K.Orange) K.DarkText else Color.White
