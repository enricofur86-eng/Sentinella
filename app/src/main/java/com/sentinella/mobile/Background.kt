package com.sentinella.mobile

import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class App : Application() {
    companion object {
        /** Per i controlli lanciati dall'interfaccia (sopravvivono al cambio schermata). */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        CheckWorker.schedule(this)
    }
}

/** Controllo continuo: gira sempre, con notifica fissa, agli intervalli di ogni monitor. */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        fun start(c: Context) {
            try {
                ContextCompat.startForegroundService(c, Intent(c, MonitorService::class.java))
            } catch (_: Exception) {
                // Android non permette di avviarlo da qui: ci pensa il controllo periodico
            }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, MonitorService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs.continuous(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, Notifier.SERVICE_ID, Notifier.service(this, "Controllo in corso…"), type)
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        applyWakeLock()

        if (loop?.isActive != true) {
            loop = scope.launch {
                var lastText = ""
                var lastPrune = 0L
                while (isActive) {
                    val ctx = this@MonitorService
                    val db = Db.get(ctx)
                    Engine.due(db).forEach { m -> launch { Engine.runOne(ctx, m) } }
                    val text = Engine.summary(db)
                    if (text != lastText) {
                        Notifier.updateService(ctx, text)
                        lastText = text
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastPrune > 3600_000L) {
                        db.prune(now)
                        lastPrune = now
                    }
                    delay(1000)
                }
            }
        }
        return START_STICKY
    }

    private fun applyWakeLock() {
        if (Prefs.wakeLock(this)) {
            if (wakeLock?.isHeld != true) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sentinella:controllo").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
}

/** Controllo di riserva ogni 15 minuti (minimo consentito da Android). */
class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Engine.runDueAndWait(applicationContext)
        Db.get(applicationContext).prune()
        if (Prefs.continuous(applicationContext)) MonitorService.start(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(c: Context) {
            val req = PeriodicWorkRequestBuilder<CheckWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork("controlli", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

/** Riavvia il controllo continuo dopo l'accensione del telefono o un aggiornamento. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CheckWorker.schedule(context)
        if (Prefs.continuous(context)) MonitorService.start(context)
    }
}
