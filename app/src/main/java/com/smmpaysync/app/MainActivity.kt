package com.smmpaysync.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var db: Db
    private lateinit var statusBox: LinearLayout
    private lateinit var tvHistory: TextView
    private lateinit var etUrl: EditText
    private lateinit var etToken: EditText
    private lateinit var etSecret: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        db = Db.get(this)

        statusBox = findViewById(R.id.statusBox)
        tvHistory = findViewById(R.id.tvHistory)
        etUrl = findViewById(R.id.etUrl)
        etToken = findViewById(R.id.etToken)
        etSecret = findViewById(R.id.etSecret)

        etUrl.setText(prefs.url)
        etToken.setText(prefs.token)
        etSecret.setText(prefs.secret)

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnPing).setOnClickListener { ping() }
        findViewById<Button>(R.id.btnSync).setOnClickListener {
            SyncWorker.enqueue(this)
            toast("Sync started")
            refresh()
        }
        findViewById<Button>(R.id.btnRetry).setOnClickListener {
            db.retryFailed()
            SyncWorker.enqueue(this)
            toast("Retrying failed events")
            refresh()
        }
        findViewById<Button>(R.id.btnPerm).setOnClickListener {
            if (hasSms()) {
                toast("SMS permission already granted")
            } else {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.RECEIVE_SMS), 1
                )
            }
        }
        findViewById<Button>(R.id.btnAppInfo).setOnClickListener {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            i.data = Uri.parse("package:$packageName")
            startActivity(i)
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                toast("Open phone Settings > Battery manually")
            }
        }
        findViewById<Button>(R.id.btnDisconnect).setOnClickListener {
            prefs.clear()
            etUrl.setText("")
            etToken.setText("")
            etSecret.setText("")
            toast("Device disconnected")
            refresh()
        }

        Thread {
            try {
                db.purge(System.currentTimeMillis() - 30L * 24 * 3600 * 1000)
            } catch (e: Exception) {
            }
        }.start()

        SyncWorker.schedulePeriodic(this)

        if (!hasSms()) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECEIVE_SMS), 1
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun hasSms(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun save() {
        val url = etUrl.text.toString().trim()
        val token = etToken.text.toString().trim()
        val secret = etSecret.text.toString().trim()
        if (!url.startsWith("https://")) {
            toast("URL must start with https://")
            return
        }
        if (token.isEmpty() || secret.isEmpty()) {
            toast("Token and Secret are required")
            return
        }
        prefs.url = url
        prefs.token = token
        prefs.secret = secret
        prefs.lastResult = ""
        toast("Saved")
        refresh()
    }

    private fun ping() {
        if (!prefs.isConfigured) {
            toast("Save URL, Token and Secret first")
            return
        }
        toast("Testing connection...")
        Thread {
            val msg = try {
                val r = Http.post(prefs.url, prefs.token, prefs.secret, "{\"action\":\"ping\"}")
                if (r.first == 200) {
                    prefs.lastResult = "OK"
                    "Connected successfully"
                } else {
                    prefs.lastResult = "HTTP ${r.first}"
                    "Failed: HTTP ${r.first} ${r.second.take(100)}"
                }
            } catch (e: Exception) {
                prefs.lastResult = "Network error"
                "Error: ${e.message}"
            }
            runOnUiThread {
                toast(msg)
                refresh()
            }
        }.start()
    }

    private fun row(label: String, value: String, level: Int) {
        val tv = TextView(this)
        tv.text = "$label: $value"
        tv.textSize = 15f
        tv.setPadding(0, 6, 0, 6)
        val color = when (level) {
            0 -> 0xFF2E7D32.toInt()   // green
            1 -> 0xFFEF6C00.toInt()   // orange
            2 -> 0xFFC62828.toInt()   // red
            else -> 0xFF212121.toInt()
        }
        tv.setTextColor(color)
        statusBox.addView(tv)
    }

    private fun refresh() {
        statusBox.removeAllViews()

        val sms = hasSms()
        val configured = prefs.isConfigured
        val active = sms && configured
        row("SMS monitor", if (active) "Active" else "Inactive", if (active) 0 else 2)
        row(
            "SMS permission",
            if (sms) "Granted" else "Missing - tap Grant SMS permission",
            if (sms) 0 else 2
        )
        row(
            "Device pairing",
            if (configured) "Configured" else "Not configured",
            if (configured) 0 else 2
        )

        val online = isOnline()
        row("Internet", if (online) "Connected" else "Offline (events wait in queue)", if (online) 0 else 1)

        val res = prefs.lastResult
        row(
            "Backend",
            if (res.isEmpty()) "Not tested yet" else res,
            if (res == "OK") 0 else if (res.isEmpty()) 1 else 2
        )

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val unrestricted = pm.isIgnoringBatteryOptimizations(packageName)
        row(
            "Battery",
            if (unrestricted) "Unrestricted" else "Phone may stop the app - tap Battery settings",
            if (unrestricted) 0 else 1
        )

        val last = prefs.lastSync
        row(
            "Last successful sync",
            if (last == 0L) "Never"
            else SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date(last)),
            if (last == 0L) 1 else 0
        )

        val total = db.count(null)
        val pending = db.count("status='PENDING'")
        val failed = db.count("status IN ('FAILED','REJECTED')")
        val flagged = db.count("status='FLAGGED'")
        row("Total SMS detected", total.toString(), -1)
        row("Pending events", pending.toString(), if (pending > 0) 1 else 0)
        row("Failed / rejected", failed.toString(), if (failed > 0) 2 else 0)
        row("Flagged (admin review)", flagged.toString(), if (flagged > 0) 1 else 0)

        val events = db.recent(50)
        tvHistory.text = if (events.isEmpty()) {
            "No bKash payment SMS detected yet."
        } else {
            events.joinToString("\n\n") { e ->
                val err = if (e.error.isNullOrEmpty()) "" else "\n" + e.error
                String.format(Locale.US, "%s   Tk %.2f\n%s  |  %s%s", e.trxId, e.amount, e.status, e.smsTime, err)
            }
        }
    }
}
