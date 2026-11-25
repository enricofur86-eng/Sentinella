package com.sentinella.mobile

import android.content.Context
import com.sentinella.mobile.ui.fmtDuration
import com.sentinella.mobile.ui.fmtWhen
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/** Decide quali monitor controllare e salva i risultati. */
object Engine {

    private val inFlight: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    fun due(db: Db, now: Long = System.currentTimeMillis()): List<Monitor> =
        db.monitors().filter { m ->
            m.active && m.id !in inFlight &&
                (db.lastBeat(m.id)?.time ?: 0L) + m.intervalSec * 1000L <= now
        }

    suspend fun runOne(ctx: Context, m: Monitor) {
        if (!inFlight.add(m.id)) return
        try {
            val db = Db.get(ctx)
            val t0 = System.currentTimeMillis() // ora del controllo (inizio)
            val r = Checker.check(m)
            val cur = db.monitor(m.id) ?: return // eliminato nel frattempo
            if (!cur.active) return

            val recent = db.recentBeatsDesc(cur.id, cur.retries + 1)
            val prev = recent.firstOrNull()
            val status = when {
                r.ok -> ST_UP
                prev?.status == ST_DOWN -> ST_DOWN
                else -> {
                    val pending = recent.takeWhile { it.status == ST_PENDING }.size
                    if (pending >= cur.retries) ST_DOWN else ST_PENDING
                }
            }
            val lastDefinite = db.lastDefiniteStatus(cur.id)
            val important = status != ST_PENDING && status != lastDefinite

            db.addBeat(
                Beat(monitorId = cur.id, time = t0, status = status, ping = r.ping, msg = r.msg),
                important
            )

            // periodo di interruzione: si apre al primo controllo fallito, si chiude al primo riuscito
            val open = db.openOutage(cur.id)
            if (status == ST_UP) {
                if (open != null) db.closeOutage(open.id, t0)
            } else if (open == null) {
                db.startOutage(cur.id, t0, status == ST_DOWN, r.msg)
            } else if (status == ST_DOWN && !open.reachedDown) {
                db.markOutageDown(open.id)
            }

            r.certExpiry?.let { if (it != cur.certExpiry) db.setCertExpiry(cur.id, it) }
            Db.bump()

            val firstUp = status == ST_UP && lastDefinite == null
            if (important && cur.notify && !firstUp) {
                if (status == ST_DOWN) {
                    val since = open?.start ?: t0
                    Notifier.statusChanged(ctx, cur, true, "Non risponde dalle ${fmtWhen(since)} · ${r.msg}")
                } else {
                    val text = if (open != null) "Non ha risposto per ${fmtDuration(t0 - open.start)} " +
                        "(${fmtWhen(open.start)} → ${fmtWhen(t0)})" else r.msg
                    Notifier.statusChanged(ctx, cur, false, text)
                }
            }
        } finally {
            inFlight.remove(m.id)
        }
    }

    suspend fun runDueAndWait(ctx: Context) = coroutineScope {
        due(Db.get(ctx)).map { m -> async { runOne(ctx, m) } }.awaitAll()
    }

    /** Testo per la notifica fissa. */
    fun summary(db: Db): String {
        val active = db.monitors().filter { it.active }
        if (active.isEmpty()) return "Nessun monitor attivo"
        val down = active.filter { db.lastBeat(it.id)?.status == ST_DOWN }
        return if (down.isEmpty()) "Tutto online · ${active.size} monitor"
        else "${down.size} offline: " + down.joinToString(", ") { it.name }
    }
}
