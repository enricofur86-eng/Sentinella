package com.sentinella.mobile

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "sentinella.db", null, 1) {

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

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
    }

    fun setCertExpiry(id: Long, t: Long) {
        val cv = ContentValues().apply { put("cert_expiry", t) }
        writableDatabase.update("monitors", cv, "id=?", arrayOf(id.toString()))
    }

    fun deleteMonitor(id: Long) {
        writableDatabase.delete("beats", "monitor_id=?", arrayOf(id.toString()))
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
    }
}
