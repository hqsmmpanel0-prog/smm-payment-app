package com.smmpaysync.app

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Ev(
    val localId: String,
    val trxId: String,
    val amount: Double,
    val sender: String,
    val smsTime: String,
    val raw: String,
    val status: String,
    val error: String?,
    val createdAt: Long
)

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "events.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE events (" +
                "localId TEXT PRIMARY KEY, " +
                "trxId TEXT NOT NULL UNIQUE, " +
                "amount REAL NOT NULL, " +
                "sender TEXT, " +
                "smsTime TEXT, " +
                "raw TEXT, " +
                "status TEXT NOT NULL, " +
                "error TEXT, " +
                "attempts INTEGER NOT NULL DEFAULT 0, " +
                "createdAt INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** Returns false when the same TrxID already exists (local duplicate guard). */
    fun insert(e: Ev): Boolean {
        val cv = ContentValues()
        cv.put("localId", e.localId)
        cv.put("trxId", e.trxId)
        cv.put("amount", e.amount)
        cv.put("sender", e.sender)
        cv.put("smsTime", e.smsTime)
        cv.put("raw", e.raw)
        cv.put("status", e.status)
        cv.put("createdAt", e.createdAt)
        return writableDatabase.insertWithOnConflict(
            "events", null, cv, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    private fun read(where: String?, order: String, limit: String): List<Ev> {
        val out = ArrayList<Ev>()
        val sql = "SELECT localId, trxId, amount, sender, smsTime, raw, status, error, createdAt " +
            "FROM events " + (if (where != null) "WHERE $where " else "") +
            "ORDER BY $order LIMIT $limit"
        readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Ev(
                        c.getString(0), c.getString(1), c.getDouble(2),
                        c.getString(3) ?: "", c.getString(4) ?: "", c.getString(5) ?: "",
                        c.getString(6), c.getString(7), c.getLong(8)
                    )
                )
            }
        }
        return out
    }

    fun pending(): List<Ev> = read("status='PENDING'", "createdAt ASC", "20")

    fun recent(limit: Int): List<Ev> = read(null, "createdAt DESC", limit.toString())

    fun mark(id: String, status: String, error: String?) {
        val cv = ContentValues()
        cv.put("status", status)
        cv.put("error", error)
        // Privacy: once the server has decided, we no longer need the SMS text.
        if (status != "PENDING" && status != "FAILED") cv.put("raw", "")
        writableDatabase.update("events", cv, "localId=?", arrayOf(id))
        writableDatabase.execSQL(
            "UPDATE events SET attempts = attempts + 1 WHERE localId = ?", arrayOf<Any>(id)
        )
    }

    fun retryFailed() {
        writableDatabase.execSQL("UPDATE events SET status='PENDING', error=NULL WHERE status='FAILED'")
    }

    fun count(where: String?): Int =
        DatabaseUtils.queryNumEntries(readableDatabase, "events", where).toInt()

    fun purge(beforeMillis: Long) {
        writableDatabase.delete(
            "events",
            "createdAt < ? AND status NOT IN ('PENDING','FAILED')",
            arrayOf(beforeMillis.toString())
        )
    }

    companion object {
        @Volatile
        private var inst: Db? = null

        fun get(ctx: Context): Db =
            inst ?: synchronized(this) {
                inst ?: Db(ctx.applicationContext).also { inst = it }
            }
    }
}
