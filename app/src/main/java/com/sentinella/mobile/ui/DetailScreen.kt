package com.sentinella.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sentinella.mobile.App
import com.sentinella.mobile.Beat
import com.sentinella.mobile.DAY_MS
import com.sentinella.mobile.Db
import com.sentinella.mobile.Engine
import com.sentinella.mobile.Monitor
import com.sentinella.mobile.Outage
import com.sentinella.mobile.ST_UP
import com.sentinella.mobile.TYPE_HTTP
import kotlinx.coroutines.launch

private data class Period(val label: String, val ms: Long)

private val PERIODS = listOf(
    Period("1 h", 3600_000L),
    Period("3 h", 3 * 3600_000L),
    Period("6 h", 6 * 3600_000L),
    Period("24 h", DAY_MS),
    Period("7 g", 7 * DAY_MS),
    Period("30 g", 30 * DAY_MS),
)

private data class DetailData(
    val m: Monitor,
    val last: Beat?,
    val beats: List<Beat>,
    val up24: Double?,
    val up30: Double?,
    val avg24: Double?,
    val outages: List<Outage>,
    val out24: List<Outage>,
    val chart: ChartData,
    val now: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(id: Long, onBack: () -> Unit, onEdit: () -> Unit, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    var periodIdx by remember { mutableIntStateOf(0) }
    val period = PERIODS[periodIdx]
    val data by rememberDb(id, periodIdx) { db ->
        val m = db.monitor(id) ?: return@rememberDb null
        val now = System.currentTimeMillis()
        val from = now - period.ms
        DetailData(
            m = m,
            last = db.lastBeat(id),
            beats = db.recentBeats(id, 100),
            up24 = db.uptime(id, now - DAY_MS),
            up30 = db.uptime(id, now - 30 * DAY_MS),
            avg24 = db.avgPing(id, now - DAY_MS),
            outages = db.outages(id, 50),
            out24 = db.outagesSince(id, now - DAY_MS),
            chart = run {
                val bucket = chartBucket(from, now, m.intervalSec * 1000L)
                buildChart(
                    db.chartPoints(id, from, bucket), db.outagesSince(id, from),
                    from, now, bucket, m.intervalSec * 1000L,
                )
            },
            now = now,
        )
    }
    var selected by remember { mutableStateOf<Beat?>(null) }
    var askDelete by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = K.Bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(data?.m?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
                },
                actions = {
                    IconButton(onClick = {
                        data?.m?.let { m -> App.scope.launch { Engine.runOne(ctx.applicationContext, m) } }
                    }) { Icon(Icons.Filled.Refresh, "Controlla ora") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = K.Bg),
            )
        },
    ) { pad ->
        val d = data ?: return@Scaffold
        val m = d.m
        val color = statusColor(d.last?.status, m.active)

        LazyColumn(
            contentPadding = PaddingValues(
                start = 14.dp, end = 14.dp,
                top = pad.calculateTopPadding() + 4.dp,
                bottom = pad.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // intestazione
            item {
                Column {
                    Text(
                        (if (m.type == TYPE_HTTP) "HTTP(S) · " else "Ping · ") + m.target,
                        color = K.Green, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text("Controllo ogni ${fmtInterval(m.intervalSec)}", color = K.Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            App.scope.launch {
                                val db = Db.get(ctx)
                                db.setActive(m.id, !m.active)
                                Db.bump()
                                if (!m.active) db.monitor(m.id)?.let { Engine.runOne(ctx.applicationContext, it) }
                            }
                        }) { Text(if (m.active) "Pausa" else "Riprendi") }
                        OutlinedButton(onClick = onEdit) { Text("Modifica") }
                        OutlinedButton(
                            onClick = { askDelete = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = K.Red),
                        ) { Text("Elimina") }
                    }
                }
            }

            // barra controlli + stato
            item {
                KCard(Modifier.fillMaxWidth()) {
                    HeartbeatBar(
                        d.beats, height = 30.dp, barWidth = 8.dp, gap = 4.dp,
                        selectedId = selected?.id,
                        onSelect = { b -> selected = if (b?.id == selected?.id) null else b },
                    )
                    Spacer(Modifier.height(12.dp))
                    val open = d.outages.firstOrNull()?.takeIf { it.ongoing && m.active }
                    if (open != null) {
                        Text(
                            "Non risponde da ${fmtDuration(open.duration(d.now))} (dalle ${fmtWhen(open.start)})",
                            color = if (open.reachedDown) K.Red else K.Orange,
                            fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        UptimePill(statusLabel(d.last?.status, m.active), color)
                        Spacer(Modifier.width(10.dp))
                        val b = selected ?: d.last
                        Text(
                            if (b == null) "In attesa del primo controllo…"
                            else buildString {
                                append(if (selected != null) fmtDateTime(b.time) else "Ultimo: " + fmtTime(b.time))
                                if (b.ping != null && b.status == ST_UP) append(" · ").append(fmtMs(b.ping))
                                if (b.msg.isNotBlank()) append(" · ").append(b.msg)
                            },
                            color = K.Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // numeri
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatBox("Risposta", "(attuale)", fmtMs(d.last?.takeIf { it.status == ST_UP }?.ping), Modifier.weight(1f))
                        StatBox("Media", "(24 ore)", fmtMs(d.avg24), Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatBox("Uptime", "(24 ore)", fmtPct(d.up24), Modifier.weight(1f))
                        StatBox("Uptime", "(30 giorni)", fmtPct(d.up30), Modifier.weight(1f))
                    }
                    val exp = m.certExpiry
                    if (m.isHttps && exp != null) {
                        val days = ((exp - System.currentTimeMillis()) / DAY_MS).toInt()
                        StatBox(
                            "Certificato", "(scadenza)",
                            if (days >= 0) "$days giorni" else "Scaduto",
                            Modifier.fillMaxWidth(),
                            valueColor = when {
                                days < 7 -> K.Red
                                days < 21 -> K.Orange
                                else -> K.Text
                            },
                        )
                    }
                }
            }

            // grafico
            item {
                KCard(Modifier.fillMaxWidth()) {
                    Text("Tempo di risposta", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PERIODS.forEachIndexed { i, p ->
                            FilterChip(
                                selected = i == periodIdx,
                                onClick = { periodIdx = i },
                                label = { Text(p.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = K.Green,
                                    selectedLabelColor = K.DarkText,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    ResponseChart(d.chart, Modifier.fillMaxWidth().height(220.dp))
                    Text(
                        "Tocca o trascina sul grafico per vedere i valori",
                        color = K.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // interruzioni
            item {
                KCard(Modifier.fillMaxWidth()) {
                    Text("Interruzioni", fontWeight = FontWeight.SemiBold)
                    val dayStart = d.now - DAY_MS
                    val total24 = d.out24.sumOf { minOf(it.end ?: d.now, d.now) - maxOf(it.start, dayStart) }
                    Text(
                        if (d.out24.isEmpty()) "Nessuna nelle ultime 24 ore"
                        else "Ultime 24 ore: ${d.out24.size} " +
                            (if (d.out24.size == 1) "interruzione" else "interruzioni") +
                            ", in tutto ${fmtDuration(total24)}",
                        color = K.Muted, fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (d.outages.isEmpty()) {
                        Text("Nessuna interruzione registrata", color = K.Muted, fontSize = 13.sp)
                    }
                    d.outages.forEachIndexed { i, o ->
                        if (i > 0) HorizontalDivider(color = K.Border)
                        OutageRow(o, d.now)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Rosso: segnato offline. Arancione: controlli falliti per poco, senza avviso.",
                        color = K.Muted, fontSize = 11.sp,
                    )
                }
            }
        }

        if (askDelete) {
            AlertDialog(
                onDismissRequest = { askDelete = false },
                title = { Text("Eliminare ${m.name}?") },
                text = { Text("Verranno cancellati anche tutti i controlli registrati.") },
                confirmButton = {
                    TextButton(onClick = {
                        askDelete = false
                        App.scope.launch {
                            Db.get(ctx).deleteMonitor(m.id)
                            Db.bump()
                        }
                        onDeleted()
                    }) { Text("Elimina", color = K.Red) }
                },
                dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Annulla") } },
                containerColor = K.Card2,
            )
        }
    }
}

@Composable
private fun OutageRow(o: Outage, now: Long) {
    val c = if (o.reachedDown) K.Red else K.Orange
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        UptimePill(
            if (o.ongoing) "In corso" else fmtDuration(o.duration(now)),
            c, Modifier.width(88.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (o.end == null) "Dalle ${fmtWhen(o.start)} · da ${fmtDuration(o.duration(now))}"
                else "${fmtWhen(o.start)} → ${fmtWhen(o.end)}",
                fontSize = 13.sp,
            )
            if (o.msg.isNotBlank()) {
                Text(o.msg, color = K.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun StatBox(
    title: String,
    sub: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = K.Text,
) {
    KCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.width(4.dp))
            Text(sub, color = K.Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(value, color = valueColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}
