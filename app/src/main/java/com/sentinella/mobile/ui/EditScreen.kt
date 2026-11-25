package com.sentinella.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sentinella.mobile.App
import com.sentinella.mobile.Db
import com.sentinella.mobile.Engine
import com.sentinella.mobile.Monitor
import com.sentinella.mobile.Prefs
import com.sentinella.mobile.TYPE_HTTP
import com.sentinella.mobile.TYPE_PING
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val INTERVALS = listOf(5, 10, 15, 20, 30, 60, 120, 300, 600, 1800, 3600)
private val TIMEOUTS = listOf(2, 3, 5, 10, 20, 30)
private val RETRIES = listOf(0, 1, 2, 3, 5)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(id: Long?, onBack: () -> Unit, onSaved: (Long) -> Unit) {
    val existing by rememberDb(id) { db -> id?.let { db.monitor(it) } ?: Monitor(name = "", type = TYPE_PING, target = "") }

    Scaffold(
        containerColor = K.Bg,
        topBar = {
            TopAppBar(
                title = { Text(if (id == null) "Nuovo monitor" else "Modifica monitor", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = K.Bg),
            )
        },
    ) { pad ->
        val start = existing ?: return@Scaffold
        EditForm(start, Modifier.padding(pad), onSaved)
    }
}

@Composable
private fun EditForm(start: Monitor, modifier: Modifier, onSaved: (Long) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(start.type) }
    var name by remember { mutableStateOf(start.name) }
    var target by remember { mutableStateOf(start.target) }
    var interval by remember { mutableIntStateOf(start.intervalSec) }
    var timeout by remember { mutableIntStateOf(start.timeoutSec) }
    var retries by remember { mutableIntStateOf(start.retries) }
    var notify by remember { mutableStateOf(start.notify) }
    var ignoreTls by remember { mutableStateOf(start.ignoreTls) }
    var error by remember { mutableStateOf<String?>(null) }
    val isHttp = type == TYPE_HTTP

    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Section("Tipo di controllo")
        ChipRow(listOf(TYPE_PING to "Ping", TYPE_HTTP to "Sito web HTTP(S)"), type) { type = it }

        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Nome") },
            placeholder = { Text(if (isHttp) "Es. Immich" else "Es. NAS di casa") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = target, onValueChange = { target = it; error = null },
            label = { Text(if (isHttp) "Indirizzo web (URL)" else "Indirizzo IP o nome host") },
            placeholder = { Text(if (isHttp) "https://esempio.duckdns.org" else "192.168.1.10") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            isError = error != null,
            supportingText = { error?.let { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )

        Section("Controlla ogni")
        ChipRow(INTERVALS.map { it to fmtInterval(it) }, interval) { interval = it }
        if (!Prefs.continuous(ctx)) {
            Text(
                "Il controllo continuo è spento: a schermo spento Android controlla circa ogni 15 minuti.",
                color = K.Muted, fontSize = 12.sp,
            )
        }

        Section("Tentativi prima di segnare offline")
        ChipRow(RETRIES.map { it to it.toString() }, retries) { retries = it }
        Text(
            "Con 1 tentativo, un singolo controllo fallito viene segnato \"in attesa\" (arancione) e solo il secondo di fila fa scattare l'avviso.",
            color = K.Muted, fontSize = 12.sp,
        )

        Section("Tempo massimo di risposta")
        ChipRow(TIMEOUTS.filter { it < interval }.map { it to "$it s" }, timeout.coerceAtMost(interval - 1)) { timeout = it }
        if (timeout >= interval) {
            Text("Ridotto a ${interval - 1} s perché deve essere più corto dell'intervallo.", color = K.Muted, fontSize = 12.sp)
        }

        SwitchRow("Avvisi", "Notifica quando va offline o torna online", notify) { notify = it }
        if (isHttp) {
            SwitchRow(
                "Ignora errori del certificato",
                "Utile per indirizzi locali https con certificato non valido",
                ignoreTls,
            ) { ignoreTls = it }
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = save@{
                val t = target.trim()
                if (t.isEmpty()) {
                    error = "Inserisci l'indirizzo da controllare"; return@save
                }
                if (t.contains(' ')) {
                    error = "L'indirizzo non può contenere spazi"; return@save
                }
                val m = start.copy(
                    name = name.trim().ifEmpty { t },
                    type = type,
                    target = t,
                    intervalSec = interval,
                    timeoutSec = timeout.coerceAtMost(interval - 1),
                    retries = retries,
                    notify = notify,
                    ignoreTls = ignoreTls,
                )
                scope.launch {
                    val newId = withContext(Dispatchers.IO) { Db.get(ctx).saveMonitor(m) }
                    Db.bump()
                    App.scope.launch { Db.get(ctx).monitor(newId)?.let { Engine.runOne(ctx.applicationContext, it) } }
                    onSaved(newId)
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = K.Green, contentColor = K.DarkText),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text("Salva", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, color = K.Text, modifier = Modifier.padding(bottom = 0.dp))
}

@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (v, label) ->
            FilterChip(
                selected = v == selected,
                onClick = { onSelect(v) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = K.Green,
                    selectedLabelColor = K.DarkText,
                ),
            )
        }
    }
}

@Composable
fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(sub, color = K.Muted, fontSize = 12.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = K.Green, checkedThumbColor = K.DarkText),
        )
    }
}
