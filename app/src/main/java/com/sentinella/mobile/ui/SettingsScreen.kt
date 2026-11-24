package com.sentinella.mobile.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.sentinella.mobile.MainActivity
import com.sentinella.mobile.MonitorService
import com.sentinella.mobile.Prefs
import com.sentinella.mobile.isIgnoringBattery

@SuppressLint("BatteryLife")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var continuous by remember { mutableStateOf(Prefs.continuous(ctx)) }
    var wake by remember { mutableStateOf(Prefs.wakeLock(ctx)) }
    val tick by MainActivity.resumeTick.collectAsState()
    var battery by remember { mutableStateOf(isIgnoringBattery(ctx)) }
    var notif by remember { mutableStateOf(NotificationManagerCompat.from(ctx).areNotificationsEnabled()) }
    LaunchedEffect(tick) {
        battery = isIgnoringBattery(ctx)
        notif = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    val version = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    } catch (_: Exception) {
        ""
    }

    Scaffold(
        containerColor = K.Bg,
        topBar = {
            TopAppBar(
                title = { Text("Impostazioni", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = K.Bg),
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            KCard(Modifier.fillMaxWidth()) {
                SwitchRow(
                    "Controllo continuo",
                    "Controlla agli intervalli scelti anche a schermo spento. Mostra una notifica fissa \"Sentinella attiva\".",
                    continuous,
                ) {
                    continuous = it
                    Prefs.setContinuous(ctx, it)
                    if (it) MonitorService.start(ctx) else MonitorService.stop(ctx)
                }
                if (!continuous) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Spento: con l'app chiusa Android fa i controlli circa ogni 15 minuti.",
                        color = K.Orange, fontSize = 12.sp,
                    )
                }
                if (continuous) {
                    Spacer(Modifier.height(10.dp))
                    SwitchRow(
                        "Precisione massima",
                        "Tiene sveglio il processore per non saltare controlli. Consuma un po' più batteria.",
                        wake,
                    ) {
                        wake = it
                        Prefs.setWakeLock(ctx, it)
                        MonitorService.start(ctx)
                    }
                }
            }

            KCard(Modifier.fillMaxWidth()) {
                Text("Batteria", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (battery) "Ottimizzazione batteria disattivata per Sentinella: ok."
                    else "Android sta limitando Sentinella: a schermo spento i controlli possono saltare.",
                    color = if (battery) K.Green else K.Orange, fontSize = 13.sp,
                )
                if (!battery) {
                    OutlinedButton(onClick = {
                        try {
                            ctx.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${ctx.packageName}")
                                )
                            )
                        } catch (_: Exception) {
                            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }, modifier = Modifier.padding(top = 6.dp)) { Text("Togli la limitazione") }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Sui telefoni Honor: Impostazioni › Batteria › Avvio app › Sentinella › Gestione manuale, e lascia attive tutte e tre le voci.",
                    color = K.Muted, fontSize = 12.sp,
                )
                OutlinedButton(onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    )
                }, modifier = Modifier.padding(top = 6.dp)) { Text("Apri impostazioni dell'app") }
            }

            KCard(Modifier.fillMaxWidth()) {
                Text("Notifiche", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (notif) "Attive: ricevi un avviso quando un monitor va offline o torna online."
                    else "Disattivate: non riceverai gli avvisi.",
                    color = if (notif) K.Green else K.Red, fontSize = 13.sp,
                )
                OutlinedButton(onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    )
                }, modifier = Modifier.padding(top = 6.dp)) { Text("Impostazioni notifiche") }
            }

            KCard(Modifier.fillMaxWidth()) {
                Text("Informazioni", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Sentinella $version\nLo storico dei controlli viene conservato per 31 giorni, gli eventi importanti per un anno.",
                    color = K.Muted, fontSize = 13.sp,
                )
            }
        }
    }
}
