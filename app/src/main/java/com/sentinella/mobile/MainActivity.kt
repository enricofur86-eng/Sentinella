package com.sentinella.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sentinella.mobile.ui.AppRoot
import com.sentinella.mobile.ui.SentinellaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        /** Monitor da aprire (tocco su una notifica). */
        val openRequest = MutableStateFlow<Long?>(null)

        /** Cambia a ogni ritorno nell'app (per aggiornare lo stato batteria/notifiche). */
        val resumeTick = MutableStateFlow(0)
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick.value++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Prefs.continuous(this)) MonitorService.start(this)

        // Con l'app aperta i controlli partono comunque, anche se il servizio è spento.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val list = withContext(Dispatchers.IO) { Engine.due(Db.get(this@MainActivity)) }
                    list.forEach { m -> App.scope.launch { Engine.runOne(applicationContext, m) } }
                    delay(1000)
                }
            }
        }

        setContent { SentinellaTheme { AppRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumeTick.value++
    }

    private fun handleIntent(i: Intent?) {
        val id = i?.getLongExtra("monitor_id", -1L) ?: -1L
        if (id > 0) openRequest.value = id
    }
}
