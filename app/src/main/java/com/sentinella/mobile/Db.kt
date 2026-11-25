package com.sentinella.mobile

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.sentinella.mobile.ui.ChartPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "sentinella.db", null, 2) {

    companion object {
        @Volatile
        private var inst: Db? = null

        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(ctx.applicationContext).also { inst = it }
        }

        /** Cambia ogni volta che i dati cambiano: l'interfaccia si ricarica. */
        val changes = MutableStateFlow(0L)
        fun bump() = changes.update { it + 1 }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE monitors(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                target TEXT NOT NULL,
                interval_sec INTEGER NOT NULL,
                timeout_sec INTEGER NOT NULL,
                retries INTEGER NOT NULL,
                active INTEGER NOT NULL,
                notify INTEGER NOT NULL,
                ignore_tls INTEGER NOT NULL,
                cert_expiry INTEGER,
                created INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE beats(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                monitor_id INTEGER NOT NULL,
                time INTEGER NOT NULL,
                status INTEGER NOT NULL,
                ping INTEGER,
                msg TEXT NOT NULL DEFAULT '',
                important INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE INDEX beats_mt ON beats(monitor_id, time)")
        createOutages(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createOutages(db)
            backfillOutages(db)
        }
    }

    private fun createOutages(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE outages(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                monitor_id INTEGER NOT NULL,
                start_time INTEGER NOT NULL,
                end_time INTEGER,
                reached_down INTEGER NOT NULL DEFAULT 0,
                msg TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL("CREATE INDEX outages_ms ON outages(monitor_id, start_time)")
    }

    /** Ricostruisce le interruzioni dai controlli già registrati (aggiornamento dalla 1.0.1). */
    private fun backfillOutages(db: SQLiteDatabase) {
        var mon = -1L
        var start: Long? = null
        var down = false
        var msg = ""
        fun flush(end: Long?) {
            val st = start
            if (st != null) {
                val cv = ContentValues().apply {
                    put("monitor_id", mon)
                    put("start_time", st)
                    if (end != null) put("end_time", end) else putNull("end_time")
                    put("reached_down", if (down) 1 else 0)
                    put("msg", msg)
                }
                db.insert("outages", null, cv)
            }
            start = null
            down = false
        }
        db.rawQuery("SELECT monitor_id, time, status, msg FROM beats ORDER BY monitor_id, time", null).use { c ->
            while (c.moveToNext()) {
                val m = c.getLong(0)
                val t = c.getLong(1)
                val st = c.getInt(2)
                if (m != mon) {
                    flush(null)
                    mon = m
                }
                if (st != ST_UP) {
                    if (start == null) {
                        start = t
                        msg = c.getString(3) ?: ""
                    }
                    if (st == ST_DOWN) down = true
                } else {
                    flush(t)
                }
            }
            flush(null)
        }
    }

    // ---------- monitor ----------

    private fun Cursor.toMonitor() = Monitor(
        id = getLong(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        type = getString(getColumnIndexOrThrow("type")),
        target = getString(getColumnIndexOrThrow("target")),
        intervalSec = getInt(getColumnIndexOrThrow("interval_sec")),
        timeoutSec = getInt(getColumnIndexOrThrow("timeout_sec")),
        retries = getInt(getColumnIndexOrThrow("retries")),
        active = getInt(getColumnIndexOrThrow("active")) != 0,
        notify = getInt(getColumnIndexOrThrow("notify")) != 0,
        ignoreTls = getInt(getColumnIndexOrThrow("ignore_tls")) != 0,
        certExpiry = getColumnIndexOrThrow("cert_expiry").let { if (isNull(it)) null else getLong(it) },
        created = getLong(getColumnIndexOrThrow("created")),
    )

    fun monitors(): List<Monitor> =
        readableDatabase.rawQuery("SELECT * FROM monitors ORDER BY name COLLATE NOCASE", null).use { c ->
            buildList { while (c.moveToNext()) add(c.toMonitor()) }
        }

    fun monitor(id: Long): Monitor? =
        readableDatabase.rawQuery("SELECT * FROM monitors WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.toMonitor() else null
        }

    fun saveMonitor(m: Monitor): Long {
        val cv = ContentValues().apply {
            put("name", m.name)
            put("type", m.type)
            put("target", m.target)
            put("interval_sec", m.intervalSec)
            put("timeout_sec", m.timeoutSec)
            put("retries", m.retries)
            put("active", if (m.active) 1 else 0)
            put("notify", if (m.notify) 1 else 0)
            put("ignore_tls", if (m.ignoreTls) 1 else 0)
        }
        return if (m.id == 0L) {
            cv.put("created", m.created)
            writableDatabase.insert("monitors", null, cv)
        } else {
            val old = monitor(m.id)
            if (old != null && (old.target != m.target || old.type != m.type)) cv.putNull("cert_expiry")
            writableDatabase.update("monitors", cv, "id=?", arrayOf(m.id.toString()))
            m.id
        }
    }

    fun setActive(id: Long, active: Boolean) {
        val cv = ContentValues().apply { put("active", if (active) 1 else 0) }
        writableDatabase.update("monitors", cv, "id=?", arrayOf(id.toString()))
        if (!active) closeOpenOutages(id, System.currentTimeMillis())
    }

    fun setCertExpiry(id: Long, t: Long) {
        val cv = ContentValues().apply { put("cert_expiry", t) }
        writableDatabase.update("monitors", cv, "id=?", arrayOf(id.toString()))
    }

    fun deleteMonitor(id: Long) {
        writableDatabase.delete("beats", "monitor_id=?", arrayOf(id.toString()))
        writableDatabase.delete("outages", "monitor_id=?", arrayOf(id.toString()))
        writableDatabase.delete("monitors", "id=?", arrayOf(id.toString()))
    }

    // ---------- controlli (heartbeat) ----------

    private fun Cursor.toBeat() = Beat(
        id = getLong(getColumnIndexOrThrow("id")),
        monitorId = getLong(getColumnIndexOrThrow("monitor_id")),
        time = getLong(getColumnIndexOrThrow("time")),
        status = getInt(getColumnIndexOrThrow("status")),
        ping = getColumnIndexOrThrow("ping").let { if (isNull(it)) null else getLong(it) },
        msg = getString(getColumnIndexOrThrow("msg")) ?: "",
    )

    fun addBeat(b: Beat, important: Boolean) {
        val cv = ContentValues().apply {
            put("monitor_id", b.monitorId)
            put("time", b.time)
            put("status", b.status)
            if (b.ping != null) put("ping", b.ping) else putNull("ping")
            put("msg", b.msg)
            put("important", if (important) 1 else 0)
        }
        writableDatabase.insert("beats", null, cv)
    }

    fun lastBeat(id: Long): Beat? = recentBeatsDesc(id, 1).firstOrNull()

    /** Ultimi controlli, dal più recente al più vecchio. */
    fun recentBeatsDesc(id: Long, limit: Int): List<Beat> =
        readableDatabase.rawQuery(
            "SELECT * FROM beats WHERE monitor_id=? ORDER BY time DESC LIMIT $limit",
            arrayOf(id.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(c.toBeat()) } }

    /** Ultimi controlli in ordine cronologico. */
    fun recentBeats(id: Long, limit: Int): List<Beat> = recentBeatsDesc(id, limit).asReversed()

    fun beatsSince(id: Long, since: Long): List<Beat> =
        readableDatabase.rawQuery(
            "SELECT * FROM beats WHERE monitor_id=? AND time>=? ORDER BY time",
            arrayOf(id.toString(), since.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(c.toBeat()) } }

    /** Ultimo stato "certo" (online/offline), escluso "in attesa". */
    fun lastDefiniteStatus(id: Long): Int? =
        readableDatabase.rawQuery(
            "SELECT status FROM beats WHERE monitor_id=? AND status!=$ST_PENDING ORDER BY time DESC LIMIT 1",
            arrayOf(id.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else null }

    /** Percentuale di controlli riusciti dal momento [since]. */
    fun uptime(id: Long, since: Long): Double? =
        readableDatabase.rawQuery(
            "SELECT COUNT(*), SUM(CASE WHEN status=$ST_DOWN THEN 1 ELSE 0 END) FROM beats WHERE monitor_id=? AND time>=?",
            arrayOf(id.toString(), since.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            val total = c.getLong(0)
            val down = if (c.isNull(1)) 0L else c.getLong(1)
            if (total == 0L) null else (total - down) * 100.0 / total
        }

    fun avgPing(id: Long, since: Long): Double? =
        readableDatabase.rawQuery(
            "SELECT AVG(ping) FROM beats WHERE monitor_id=? AND time>=? AND status=$ST_UP AND ping IS NOT NULL",
            arrayOf(id.toString(), since.toString())
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getDouble(0) else null }

    fun events(id: Long, limit: Int): List<Beat> =
        readableDatabase.rawQuery(
            "SELECT * FROM beats WHERE monitor_id=? AND important=1 ORDER BY time DESC LIMIT $limit",
            arrayOf(id.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(c.toBeat()) } }

    /** Cancella lo storico vecchio (oltre 31 giorni; gli eventi importanti restano 1 anno). */
    fun prune(now: Long = System.currentTimeMillis()) {
        writableDatabase.delete(
            "beats", "(important=0 AND time<?) OR time<?",
            arrayOf((now - 31 * DAY_MS).toString(), (now - 365 * DAY_MS).toString())
        )
        writableDatabase.delete(
            "outages", "end_time IS NOT NULL AND end_time<?", arrayOf((now - 365 * DAY_MS).toString())
        )
    }

    /** Tempi di risposta medi raggruppati in intervalli di [size] ms, per il grafico. */
    fun chartPoints(id: Long, from: Long, size: Long): List<ChartPoint> =
        readableDatabase.rawQuery(
            """SELECT (time-?)/? AS b, AVG(CASE WHEN status!=$ST_DOWN THEN ping END)
               FROM beats WHERE monitor_id=? AND time>=? GROUP BY b ORDER BY b""",
            arrayOf(from.toString(), size.toString(), id.toString(), from.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    if (c.isNull(1)) continue
                    add(ChartPoint(from + c.getLong(0) * size + size / 2, c.getDouble(1).toFloat()))
                }
            }
        }

    // ---------- interruzioni ----------

    private fun Cursor.toOutage() = Outage(
        id = getLong(getColumnIndexOrThrow("id")),
        monitorId = getLong(getColumnIndexOrThrow("monitor_id")),
        start = getLong(getColumnIndexOrThrow("start_time")),
        end = getColumnIndexOrThrow("end_time").let { if (isNull(it)) null else getLong(it) },
        reachedDown = getInt(getColumnIndexOrThrow("reached_down")) != 0,
        msg = getString(getColumnIndexOrThrow("msg")) ?: "",
    )

    private fun outageQuery(where: String, args: Array<String>, tail: String = ""): List<Outage> =
        readableDatabase.rawQuery("SELECT * FROM outages WHERE $where ORDER BY start_time DESC $tail", args)
            .use { c -> buildList { while (c.moveToNext()) add(c.toOutage()) } }

    fun openOutage(id: Long): Outage? =
        outageQuery("monitor_id=? AND end_time IS NULL", arrayOf(id.toString()), "LIMIT 1").firstOrNull()

    fun lastOutage(id: Long): Outage? =
        outageQuery("monitor_id=?", arrayOf(id.toString()), "LIMIT 1").firstOrNull()

    /** Le interruzioni più recenti, dalla più nuova. */
    fun outages(id: Long, limit: Int): List<Outage> =
        outageQuery("monitor_id=?", arrayOf(id.toString()), "LIMIT $limit")

    /** Interruzioni che toccano il periodo dal momento [since] a oggi. */
    fun outagesSince(id: Long, since: Long): List<Outage> =
        outageQuery("monitor_id=? AND (end_time IS NULL OR end_time>=?)", arrayOf(id.toString(), since.toString()))

    fun startOutage(monitorId: Long, start: Long, down: Boolean, msg: String) {
        val cv = ContentValues().apply {
            put("monitor_id", monitorId)
            put("start_time", start)
            putNull("end_time")
            put("reached_down", if (down) 1 else 0)
            put("msg", msg)
        }
        writableDatabase.insert("outages", null, cv)
    }

    fun markOutageDown(outageId: Long) {
        val cv = ContentValues().apply { put("reached_down", 1) }
        writableDatabase.update("outages", cv, "id=?", arrayOf(outageId.toString()))
    }

    fun closeOutage(outageId: Long, end: Long) {
        val cv = ContentValues().apply { put("end_time", end) }
        writableDatabase.update("outages", cv, "id=?", arrayOf(outageId.toString()))
    }

    fun closeOpenOutages(monitorId: Long, end: Long) {
        val cv = ContentValues().apply { put("end_time", end) }
        writableDatabase.update("outages", cv, "monitor_id=? AND end_time IS NULL", arrayOf(monitorId.toString()))
    }
}
