package com.smmpaysync.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        if (!prefs.isConfigured) return Result.success()
        val db = Db.get(ctx)

        for (e in db.pending()) {
            val body = JSONObject()
                .put("sender", "bKash")
                .put("raw_sms", e.raw)
                .put("local_event_id", e.localId)
                .toString()

            val res = try {
                Http.post(prefs.url, prefs.token, prefs.secret, body)
            } catch (ex: Exception) {
                prefs.lastResult = "Network error"
                return Result.retry()
            }
            val code = res.first
            val text = res.second

            when {
                code == 200 -> {
                    val st = try {
                        JSONObject(text).optString("status")
                    } catch (x: Exception) {
                        ""
                    }
                    val newStatus = when (st) {
                        "duplicate" -> "DUPLICATE"
                        "flagged" -> "FLAGGED"
                        else -> "SYNCED"
                    }
                    db.mark(e.localId, newStatus, null)
                    prefs.lastSync = System.currentTimeMillis()
                    prefs.lastResult = "OK"
                }
                code == 401 || code == 403 -> {
                    db.mark(e.localId, "FAILED", "Invalid token/secret (HTTP $code)")
                    prefs.lastResult = "Invalid token/secret"
                    return Result.success() // user must fix settings, then tap Retry
                }
                code == 422 -> {
                    val msg = try {
                        JSONObject(text).optString("message")
                    } catch (x: Exception) {
                        ""
                    }
                    db.mark(e.localId, "REJECTED", if (msg.isEmpty()) "Rejected by server" else msg)
                    prefs.lastResult = "OK"
                }
                else -> {
                    prefs.lastResult = "Server error HTTP $code"
                    return Result.retry()
                }
            }
        }
        return Result.success()
    }

    companion object {
        private fun constraints(): Constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniqueWork("sync_now", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints())
                .build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniquePeriodicWork("sync_periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
