package com.smm.paymentsync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class LocalDb(context: Context) : SQLiteOpenHelper(context, "smm_cyber_sync.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE payments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                local_event_id TEXT,
                sender TEXT,
                method TEXT,
                trx_id TEXT UNIQUE,
                amount REAL,
                sender_number TEXT,
                balance_after REAL,
                sms_time TEXT,
                raw_sms TEXT,
                status TEXT,
                server_msg TEXT
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        db.execSQL("DROP TABLE IF EXISTS payments")
        onCreate(db)
    }

    fun insertPayment(
        sender: String,
        method: String,
        trxId: String,
        amount: Double,
        senderNum: String,
        balance: Double,
        smsTime: String,
        rawSms: String
    ): Boolean {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("local_event_id", UUID.randomUUID().toString())
            put("sender", sender)
            put("method", method.lowercase())
            put("trx_id", trxId.uppercase().trim())
            put("amount", amount)
            put("sender_number", senderNum.trim())
            put("balance_after", balance)
            put("sms_time", smsTime)
            put("raw_sms", rawSms)
            put("status", "pending")
            put("server_msg", "Waiting in queue for server sync...")
        }
        val res = db.insertWithOnConflict("payments", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        return res != -1L
    }

    fun getAllHistoryJson(): String {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM payments ORDER BY id DESC LIMIT 100", null)
        val arr = JSONArray()
        while (cursor.moveToNext()) {
            val obj = JSONObject().apply {
                put("id", cursor.getLong(cursor.getColumnIndexOrThrow("id")))
                put("method", cursor.getString(cursor.getColumnIndexOrThrow("method")))
                put("trx_id", cursor.getString(cursor.getColumnIndexOrThrow("trx_id")))
                put("amount", cursor.getDouble(cursor.getColumnIndexOrThrow("amount")))
                put("sender_number", cursor.getString(cursor.getColumnIndexOrThrow("sender_number")))
                put("sms_time", cursor.getString(cursor.getColumnIndexOrThrow("sms_time")))
                put("status", cursor.getString(cursor.getColumnIndexOrThrow("status")))
                put("server_msg", cursor.getString(cursor.getColumnIndexOrThrow("server_msg")))
            }
            arr.put(obj)
        }
        cursor.close()
        return arr.toString()
    }

    fun getStatsJson(): JSONObject {
        val db = readableDatabase
        var synced = 0
        var pending = 0
        val c1 = db.rawQuery("SELECT COUNT(*) FROM payments WHERE status='synced'", null)
        if (c1.moveToFirst()) synced = c1.getInt(0)
        c1.close()

        val c2 = db.rawQuery("SELECT COUNT(*) FROM payments WHERE status='pending'", null)
        if (c2.moveToFirst()) pending = c2.getInt(0)
        c2.close()

        return JSONObject().apply {
            put("synced", synced)
            put("pending", pending)
        }
    }

    @Synchronized
    fun syncPendingWithServer(context: Context): Int {
        val prefs = context.getSharedPreferences("SmmPrefs", Context.MODE_PRIVATE)
        val apiUrl = prefs.getString("api_url", "")?.trim() ?: ""
        val deviceToken = prefs.getString("device_token", "")?.trim() ?: ""
        val hmacSecret = prefs.getString("hmac_secret", "")?.trim() ?: ""

        if (apiUrl.isEmpty() || deviceToken.isEmpty() || hmacSecret.isEmpty()) return 0

        val db = writableDatabase
        val cursor = db.rawQuery("SELECT * FROM payments WHERE status = 'pending' ORDER BY id ASC", null)
        var syncedCount = 0

        while (cursor.moveToNext()) {
            val id = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
            val localEventId = cursor.getString(cursor.getColumnIndexOrThrow("local_event_id"))
            val sender = cursor.getString(cursor.getColumnIndexOrThrow("sender"))
            val method = cursor.getString(cursor.getColumnIndexOrThrow("method"))
            val trxId = cursor.getString(cursor.getColumnIndexOrThrow("trx_id"))
            val amount = cursor.getDouble(cursor.getColumnIndexOrThrow("amount"))
            val senderNum = cursor.getString(cursor.getColumnIndexOrThrow("sender_number"))
            val balance = cursor.getDouble(cursor.getColumnIndexOrThrow("balance_after"))
            val smsTime = cursor.getString(cursor.getColumnIndexOrThrow("sms_time"))
            val rawSms = cursor.getString(cursor.getColumnIndexOrThrow("raw_sms"))

            try {
                val jsonPayload = JSONObject().apply {
                    put("action", "sync_sms")
                    put("local_event_id", localEventId)
                    put("sender", sender)
                    put("method", method)
                    put("trx_id", trxId)
                    put("amount", amount)
                    put("sender_number", senderNum)
                    put("balance_after", balance)
                    put("sms_time", smsTime)
                    put("raw_sms", rawSms)
                }

                val bodyString = jsonPayload.toString()
                val signature = hmacSha256(bodyString, hmacSecret)

                val url = URL(apiUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("X-Device-Token", deviceToken)
                    setRequestProperty("X-Signature", signature)
                    doOutput = true
                }

                conn.outputStream.use { os ->
                    os.write(bodyString.toByteArray(Charsets.UTF_8))
                }

                val code = conn.responseCode
                val respText = try {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                }

                if (code in 200..299) {
                    val cv = ContentValues().apply {
                        put("status", "synced")
                        put("server_msg", "Verified & Synced with Server")
                    }
                    db.update("payments", cv, "id=?", arrayOf(id.toString()))
                    syncedCount++
                } else {
                    val cv = ContentValues().apply {
                        put("server_msg", "Server HTTP $code: ${respText.take(60)}")
                    }
                    db.update("payments", cv, "id=?", arrayOf(id.toString()))
                }
            } catch (e: Exception) {
                val cv = ContentValues().apply {
                    put("server_msg", "Offline / Waiting for Net (${e.javaClass.simpleName})")
                }
                db.update("payments", cv, "id=?", arrayOf(id.toString()))
            }
        }
        cursor.close()
        return syncedCount
    }

    private fun hmacSha256(data: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(keySpec)
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun nowTime(): String {
            return SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date())
        }
    }
}
