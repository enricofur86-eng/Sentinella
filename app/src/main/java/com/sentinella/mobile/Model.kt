package com.sentinella.mobile

import android.content.Context
import android.os.PowerManager

const val TYPE_PING = "ping"
const val TYPE_HTTP = "http"

const val ST_DOWN = 0
const val ST_UP = 1
const val ST_PENDING = 2

const val DAY_MS = 24L * 3600_000L

data class Monitor(
    val id: Long = 0,
    val name: String,
    val type: String,
    val target: String,
    val intervalSec: Int = 60,
    val timeoutSec: Int = 10,
    val retries: Int = 1,
    val active: Boolean = true,
    val notify: Boolean = true,
    val ignoreTls: Boolean = false,
    val certExpiry: Long? = null,
    val created: Long = System.currentTimeMillis(),
) {
    val isHttps: Boolean get() = type == TYPE_HTTP && Checker.normalizeUrl(target).startsWith("https://", true)
}

data class Beat(
    val id: Long = 0,
    val monitorId: Long,
    val time: Long,
    val status: Int,
    val ping: Long?,
    val msg: String,
)

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    fun continuous(c: Context) = sp(c).getBoolean("continuous", true)
    fun setContinuous(c: Context, v: Boolean) = sp(c).edit().putBoolean("continuous", v).apply()

    fun wakeLock(c: Context) = sp(c).getBoolean("wakelock", true)
    fun setWakeLock(c: Context, v: Boolean) = sp(c).edit().putBoolean("wakelock", v).apply()
}

fun isIgnoringBattery(c: Context): Boolean {
    val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(c.packageName)
}
