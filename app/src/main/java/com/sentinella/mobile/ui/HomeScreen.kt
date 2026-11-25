package com.sentinella.mobile.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sentinella.mobile.Beat
import com.sentinella.mobile.DAY_MS
import com.sentinella.mobile.MainActivity
import com.sentinella.mobile.Monitor
import com.sentinella.mobile.Outage
import com.sentinella.mobile.Prefs
import com.sentinella.mobile.ST_DOWN
import com.sentinella.mobile.ST_PENDING
import com.sentinella.mobile.ST_UP
import com.sentinella.mobile.isIgnoringBattery

data class HomeItem(
    val m: Monitor,
    val last: Beat?,
    val up24: Double?,
    val beats: List<Beat>,
    val lastOutage: Outage?,
) {
    val status: Int? get() = last?.status
}

private fun rank(i: HomeItem): Int = when {
    !i.m.active -> 4
    i.status == ST_DOWN -> 0
    i.status == ST_PENDING -> 1
    i.status == ST_UP -> 2
    else -> 3
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (Long) -> Unit, onAdd: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val items by rememberDb { db ->
        val now = System.currentTimeMillis()
        db.monitors().map { m ->
            HomeItem(m, db.lastBeat(m.id), db.uptime(m.id, now - DAY_MS), db.recentBeats(m.id, 60), db.lastOutage(m.id))
        }.sortedWith(compareBy<HomeItem> { rank(it) }.thenBy { it.m.name.lowercase() })
    }
    val tick by MainActivity.resumeTick.collectAsState()
    val needBattery = remember(tick) { Prefs.continuous(ctx) && !isIgnoringBattery(ctx) }

    Scaffold(
        containerColor = K.Bg,
        topBar = {
            TopAppBar(
                title = { Text("Sentinella", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Impostazioni") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = K.Bg),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd, containerColor = K.Green, contentColor = K.DarkText) {
                Icon(Icons.Filled.Add, "Aggiungi monitor")
            }
        },
    ) { pad ->
        val list = items
        if (list == null) return@Scaffold
        LazyColumn(
            contentPadding = PaddingValues(
                start = 14.dp, end = 14.dp,
                top = pad.calculateTopPadding() + 4.dp,
                bottom = pad.calculateBottomPadding() + 88.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (needBattery && list.isNotEmpty()) item { BatteryBanner() }
            if (list.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 120.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Nessun monitor.\nTocca + per aggiungere un dispositivo o un sito da controllare.",
                            color = K.Muted, textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                item { Summary(list) }
                items(list, key = { it.m.id }) { MonitorRow(it) { onOpen(it.m.id) } }
            }
        }
    }
}

@Composable
private fun Summary(list: List<HomeItem>) {
    val up = list.count { it.m.active && it.status == ST_UP }
    val down = list.count { it.m.active && it.status == ST_DOWN }
    val pending = list.count { it.m.active && it.status == ST_PENDING }
    val paused = list.count { !it.m.active }
    KCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat("Online", up, K.Green)
            Stat("Offline", down, K.Red)
            if (pending > 0) Stat("In attesa", pending, K.Orange)
            Stat("In pausa", paused, K.Grey)
        }
    }
}

@Composable
private fun Stat(label: String, n: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = K.Muted, fontSize = 13.sp)
        Text("$n", color = color, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MonitorRow(item: HomeItem, onClick: () -> Unit) {
    val color = statusColor(item.status, item.m.active)
    KCard(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UptimePill(if (item.m.active) fmtPct(item.up24) else "Pausa", color, Modifier.width(70.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                item.m.name,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val ping = item.last?.takeIf { it.status == ST_UP }?.ping
            if (item.m.active && ping != null) {
                Text(fmtMs(ping), color = K.Muted, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        HeartbeatBar(item.beats, height = 18.dp)
        val o = item.lastOutage
        val now = System.currentTimeMillis()
        if (o != null && item.m.active && (o.ongoing || now - (o.end ?: now) < DAY_MS)) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (o.ongoing) "Non risponde da ${fmtDuration(o.duration(now))}"
                else "Ultima interruzione: ${fmtWhen(o.start)} · ${fmtDuration(o.duration(now))}",
                color = when {
                    o.ongoing && o.reachedDown -> K.Red
                    o.ongoing -> K.Orange
                    else -> K.Muted
                },
                fontSize = 12.sp,
            )
        }
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun BatteryBanner() {
    val ctx = LocalContext.current
    KCard(Modifier.fillMaxWidth()) {
        Text("Controlli a schermo spento", fontWeight = FontWeight.SemiBold, color = K.Orange)
        Spacer(Modifier.height(4.dp))
        Text(
            "Android sta limitando Sentinella per risparmiare batteria: con lo schermo spento i controlli possono saltare.",
            color = K.Muted, fontSize = 13.sp,
        )
        TextButton(onClick = {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                )
            } catch (_: Exception) {
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }) { Text("Togli la limitazione") }
    }
}
