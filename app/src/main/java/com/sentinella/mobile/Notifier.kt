package com.sentinella.mobile

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifier {
    const val CH_ALERT = "avvisi"
    const val CH_SERVICE = "servizio"
    const val SERVICE_ID = 1

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Avvisi online/offline", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Quando un monitor va offline o torna online"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Controllo continuo", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Notifica fissa mentre Sentinella controlla in background"
                setShowBadge(false)
            }
        )
    }

    private fun openIntent(c: Context, monitorId: Long?): PendingIntent {
        val i = Intent(c, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (monitorId != null) putExtra("monitor_id", monitorId)
        }
        return PendingIntent.getActivity(
            c, monitorId?.toInt() ?: 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @SuppressLint("MissingPermission")
    fun statusChanged(c: Context, m: Monitor, status: Int, msg: String) {
        val nmc = NotificationManagerCompat.from(c)
        if (!nmc.areNotificationsEnabled()) return
        val down = status == ST_DOWN
        val n = NotificationCompat.Builder(c, CH_ALERT)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(if (down) 0xFFDC3545.toInt() else 0xFF5CDD8B.toInt())
            .setContentTitle(if (down) "🔴 ${m.name} è offline" else "🟢 ${m.name} è di nuovo online")
            .setContentText(msg)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openIntent(c, m.id))
            .build()
        try {
            nmc.notify(1000 + m.id.toInt(), n)
        } catch (_: SecurityException) {
        }
    }

    fun service(c: Context, text: String): Notification =
        NotificationCompat.Builder(c, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFF5CDD8B.toInt())
            .setContentTitle("Sentinella attiva")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openIntent(c, null))
            .build()

    @SuppressLint("MissingPermission")
    fun updateService(c: Context, text: String) {
        try {
            NotificationManagerCompat.from(c).notify(SERVICE_ID, service(c, text))
        } catch (_: SecurityException) {
        }
    }
}
