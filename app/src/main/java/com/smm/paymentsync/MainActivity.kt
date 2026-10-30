package com.smm.paymentsync

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var db: LocalDb

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#090D16")
        db = LocalDb(this)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(Color.parseColor("#090D16"))
            webViewClient = WebViewClient()
            addJavascriptInterface(WebAppBridge(this@MainActivity), "AndroidBridge")
        }

        setContentView(webView)
        requestNecessaryPermissions()
        startBackgroundEngine()

        webView.loadDataWithBaseURL(null, buildCyberHtmlUI(), "text/html", "UTF-8", null)
    }

    private fun startBackgroundEngine() {
        val srv = Intent(this, SyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(srv)
        } else {
            startService(srv)
        }
    }

    private fun requestNecessaryPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val perms = mutableListOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), 101)
            }

            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            } catch (_: Exception) {}
        }
    }

    inner class WebAppBridge(private val ctx: Context) {

        @JavascriptInterface
        fun getDashboardData(): String {
            val prefs = ctx.getSharedPreferences("SmmPrefs", Context.MODE_PRIVATE)
            val stats = db.getStatsJson()
            val res = JSONObject().apply {
                put("api_url", prefs.getString("api_url", "https://hqsmmpanel.com/payment/bkash_sms_sync.php"))
                put("device_token", prefs.getString("device_token", ""))
                put("hmac_secret", prefs.getString("hmac_secret", ""))
                put("synced", stats.getInt("synced"))
                put("pending", stats.getInt("pending"))
                put("history", JSONObject("{\"list\":" + db.getAllHistoryJson() + "}").getJSONArray("list"))
            }
            return res.toString()
        }

        @JavascriptInterface
        fun saveConfig(url: String, token: String, secret: String): String {
            ctx.getSharedPreferences("SmmPrefs", Context.MODE_PRIVATE).edit().apply {
                putString("api_url", url.trim())
                putString("device_token", token.trim())
                putString("hmac_secret", secret.trim())
                apply()
            }
            Thread { db.syncPendingWithServer(ctx) }.start()
            runOnUiThread {
                Toast.makeText(ctx, "Server Settings Saved & Syncing!", Toast.LENGTH_SHORT).show()
            }
            return "OK"
        }

        @JavascriptInterface
        fun storeManualPayment(method: String, trxId: String, amountStr: String, senderNum: String): String {
            val amount = amountStr.toDoubleOrNull() ?: 0.0
            if (trxId.trim().length < 5 || amount <= 0) {
                return "ERROR: সঠিক TrxID এবং Amount দিন!"
            }
            val senderLabel = if (method.lowercase() == "nagad") "NAGAD" else "bKash"
            val timeNow = LocalDb.nowTime()
            val rawManual = "Manual Store [$senderLabel] Tk $amount from $senderNum TrxID ${trxId.uppercase()} at $timeNow"

            val inserted = db.insertPayment(
                sender = senderLabel,
                method = method.lowercase(),
                trxId = trxId.uppercase(),
                amount = amount,
                senderNum = senderNum.ifBlank { "01000000000" },
                balance = 0.0,
                smsTime = timeNow,
                rawSms = rawManual
            )

            if (!inserted) {
                return "ERROR: এই TrxID ইতিমধ্যে স্টোর করা আছে!"
            }

            Thread {
                db.syncPendingWithServer(ctx)
                runOnUiThread {
                    webView.evaluateJavascript("refreshApp();", null)
                }
            }.start()

            return "SUCCESS"
        }

        @JavascriptInterface
        fun triggerSyncNow() {
            Thread {
                val count = db.syncPendingWithServer(ctx)
                runOnUiThread {
                    Toast.makeText(ctx, "Sync Checked ($count newly verified)", Toast.LENGTH_SHORT).show()
                    webView.evaluateJavascript("refreshApp();", null)
                }
            }.start()
        }
    }

    private fun buildCyberHtmlUI(): String {
        val d = "$"
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<style>
    :root {
        --bg: #090D16;
        --card: rgba(19, 27, 46, 0.9);
        --border: rgba(0, 240, 255, 0.2);
        --neon: #00F0FF;
        --pink: #E2136E;
        --orange: #F7941D;
        --green: #10B981;
        --text: #F8FAFC;
        --muted: #94A3B8;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; font-family: 'Segoe UI', Roboto, sans-serif; -webkit-tap-highlight-color: transparent; }
    body { background: var(--bg); color: var(--text); overflow-x: hidden; padding-bottom: 30px; }
    
    .topbar {
        display: flex; align-items: center; justify-content: space-between;
        padding: 16px 18px; background: rgba(13, 19, 34, 0.95);
        border-bottom: 1px solid var(--border); position: sticky; top: 0; z-index: 50;
    }
    .menu-btn { font-size: 24px; color: var(--neon); background: none; border: none; cursor: pointer; }
    .brand { font-size: 17px; font-weight: 800; letter-spacing: 0.5px; color: #fff; }
    .brand span { color: var(--neon); }
    .live-badge {
        display: flex; align-items: center; gap: 6px; font-size: 11px;
        background: rgba(16, 185, 129, 0.15); color: var(--green);
        padding: 5px 10px; border-radius: 20px; border: 1px solid rgba(16,185,129,0.4);
    }
    .dot { width: 7px; height: 7px; background: var(--green); border-radius: 50%; box-shadow: 0 0 8px var(--green); }

    .overlay {
        position: fixed; inset: 0; background: rgba(0,0,0,0.65);
        display: none; z-index: 90; backdrop-filter: blur(3px);
    }
    .sidebar {
        position: fixed; top: 0; left: -290px; width: 280px; height: 100vh;
        background: #0D1322; border-right: 1px solid var(--border);
        z-index: 100; transition: 0.3s ease; display: flex; flex-direction: column;
        justify-content: space-between; padding: 20px 16px;
    }
    .sidebar.open { left: 0; }
    .overlay.open { display: block; }
    
    .sb-head { border-bottom: 1px solid rgba(255,255,255,0.08); padding-bottom: 15px; margin-bottom: 15px; }
    .sb-title { font-size: 19px; font-weight: 800; color: var(--neon); }
    .sb-ver { font-size: 11px; color: var(--muted); margin-top: 4px; }

    .nav-list { list-style: none; flex: 1; }
    .nav-item {
        padding: 13px 14px; border-radius: 10px; margin-bottom: 8px;
        color: var(--text); font-size: 14px; font-weight: 600; cursor: pointer;
        display: flex; align-items: center; gap: 10px; border: 1px solid transparent;
    }
    .nav-item.active, .nav-item:hover {
        background: rgba(0, 240, 255, 0.1); border-color: var(--border); color: var(--neon);
    }

    .dev-box {
        background: rgba(255,255,255,0.03); border: 1px solid rgba(255,255,255,0.08);
        border-radius: 12px; padding: 12px; font-size: 12px; line-height: 1.6;
    }
    .dev-box h4 { color: var(--neon); font-size: 13px; margin-bottom: 4px; }
    .dev-box p { color: var(--muted); }

    .container { padding: 16px; }
    .stats-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 18px; }
    .stat-card {
        background: var(--card); border: 1px solid var(--border);
        border-radius: 14px; padding: 15px; text-align: center;
    }
    .stat-val { font-size: 26px; font-weight: 800; margin-top: 4px; }
    .stat-lbl { font-size: 12px; color: var(--muted); }

    .card {
        background: var(--card); border: 1px solid var(--border);
        border-radius: 16px; padding: 18px; margin-bottom: 18px;
    }
    .card-title { font-size: 16px; font-weight: 700; margin-bottom: 14px; color: var(--neon); display: flex; justify-content: space-between; align-items: center; }

    label { display: block; font-size: 12px; color: var(--muted); margin-bottom: 6px; margin-top: 12px; }
    input {
        width: 100%; padding: 12px 14px; background: #070A12;
        border: 1px solid rgba(255,255,255,0.14); border-radius: 10px;
        color: #fff; font-size: 14px; outline: none;
    }
    input:focus { border-color: var(--neon); }

    .method-tabs { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 8px; }
    .m-tab {
        padding: 12px; text-align: center; border-radius: 10px; font-weight: 700;
        cursor: pointer; border: 2px solid rgba(255,255,255,0.1); background: #070A12;
    }
    .m-tab.bkash.active { border-color: var(--pink); background: rgba(226, 19, 110, 0.18); color: #ff7ab8; }
    .m-tab.nagad.active { border-color: var(--orange); background: rgba(247, 148, 29, 0.18); color: #ffbc6e; }

    .btn {
        width: 100%; padding: 13px; border: none; border-radius: 10px;
        background: linear-gradient(90deg, #00C6FF, #0072FF);
        color: #fff; font-size: 14px; font-weight: 700; cursor: pointer; margin-top: 16px;
    }
    .btn-sm { padding: 6px 12px; font-size: 12px; border-radius: 8px; background: rgba(0,240,255,0.15); color: var(--neon); border: 1px solid var(--border); cursor: pointer; }

    .h-item {
        background: rgba(7, 10, 18, 0.85); border: 1px solid rgba(255,255,255,0.07);
        border-radius: 12px; padding: 12px; margin-bottom: 10px;
    }
    .h-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
    .badge { padding: 3px 8px; border-radius: 6px; font-size: 11px; font-weight: 700; text-transform: uppercase; }
    .badge.bkash { background: rgba(226,19,110,0.2); color: #ff6da9; }
    .badge.nagad { background: rgba(247,148,29,0.2); color: #ffae42; }
    .badge.synced { background: rgba(16,185,129,0.2); color: #34d399; }
    .badge.pending { background: rgba(245,158,11,0.2); color: #fbbf24; }
    .h-trx { font-size: 15px; font-weight: 700; color: #fff; }
    .h-meta { font-size: 12px; color: var(--muted); margin-top: 4px; line-height: 1.5; }

    .page { display: none; }
    .page.active { display: block; }
</style>
</head>
<body>

<div class="topbar">
    <button class="menu-btn" onclick="toggleMenu()">☰</button>
    <div class="brand">HQ SMM <span>SYNC</span></div>
    <div class="live-badge"><div class="dot"></div> 24/7 ACTIVE</div>
</div>

<div class="overlay" id="overlay" onclick="toggleMenu()"></div>
<div class="sidebar" id="sidebar">
    <div>
        <div class="sb-head">
            <div class="sb-title">SMM PAYMENT SYNC</div>
            <div class="sb-ver">Version 2.5.0 Pro • Cyber Edition</div>
        </div>
        <ul class="nav-list">
            <li class="nav-item active" id="nav-home" onclick="showPage('home')">📊 Dashboard & History</li>
            <li class="nav-item" id="nav-store" onclick="showPage('store')">➕ ID Store Data (bKash/Nagad)</li>
            <li class="nav-item" id="nav-settings" onclick="showPage('settings')">⚙️ Server Settings</li>
        </ul>
    </div>

    <div class="dev-box">
        <h4>👨‍💻 Developer Details</h4>
        <p><b>Developer:</b> Emon</p>
        <p><b>Telegram:</b> @WeirdGuyEmon</p>
        <p><b>Phone/WA:</b> +8801864614704</p>
        <p><b>Email:</b> support@hqsmmpanel.com</p>
        <p><b>Platform:</b> hqsmmpanel.com</p>
    </div>
</div>

<div class="container">

    <div class="stats-grid">
        <div class="stat-card">
            <div class="stat-lbl">Verified Success</div>
            <div class="stat-val" id="st-synced" style="color:var(--green)">0</div>
        </div>
        <div class="stat-card">
            <div class="stat-lbl">Pending Offline Queue</div>
            <div class="stat-val" id="st-pending" style="color:#fbbf24">0</div>
        </div>
    </div>

    <div class="page active" id="page-home">
        <div class="card">
            <div class="card-title">
                <span>Payment Sync History</span>
                <button class="btn-sm" onclick="syncNow()">🔄 Sync Now</button>
            </div>
            <div id="history-box"></div>
        </div>
    </div>

    <div class="page" id="page-store">
        <div class="card">
            <div class="card-title">ID Store Data (Manual / Offline)</div>
            <p style="font-size:12px; color:var(--muted); margin-bottom:12px;">
                বিকাশ বা নগদ সিলেক্ট করে ডাটা স্টোর করুন। কারেন্ট বা নেট না থাকলেও এটি অ্যাপে জমা থাকবে এবং সার্ভার অনলাইনে আসলেই অটো ভেরিফাই হয়ে যাবে।
            </p>

            <label>Select Method</label>
            <div class="method-tabs">
                <div class="m-tab bkash active" id="tab-bkash" onclick="selectMethod('bkash')">bKash</div>
                <div class="m-tab nagad" id="tab-nagad" onclick="selectMethod('nagad')">Nagad</div>
            </div>

            <label>Transaction ID (TrxID)</label>
            <input type="text" id="m-trx" placeholder="Enter TrxID (e.g. BJS82KL90P)">

            <label>Amount (Tk)</label>
            <input type="number" id="m-amount" placeholder="Enter Amount (e.g. 500)">

            <label>Sender Number (Optional)</label>
            <input type="text" id="m-sender" placeholder="017XXXXXXXX">

            <button class="btn" onclick="submitManualStore()">💾 Store & Verify Data</button>
        </div>
    </div>

    <div class="page" id="page-settings">
        <div class="card">
            <div class="card-title">Server Connection Setup</div>
            <label>Server Sync URL</label>
            <input type="text" id="cfg-url" placeholder="https://hqsmmpanel.com/payment/bkash_sms_sync.php">

            <label>Device Pairing Token</label>
            <input type="text" id="cfg-token" placeholder="Paste Device Token">

            <label>HMAC Secret Key</label>
            <input type="text" id="cfg-secret" placeholder="Paste HMAC Secret Key">

            <button class="btn" onclick="saveServerConfig()">✅ Save Server Settings</button>
        </div>
    </div>

</div>

<script>
    let selectedMethod = 'bkash';

    function toggleMenu() {
        document.getElementById('sidebar').classList.toggle('open');
        document.getElementById('overlay').classList.toggle('open');
    }

    function showPage(p) {
        document.querySelectorAll('.page').forEach(el => el.classList.remove('active'));
        document.querySelectorAll('.nav-item').forEach(el => el.classList.remove('active'));
        document.getElementById('page-' + p).classList.add('active');
        document.getElementById('nav-' + p).classList.add('active');
        document.getElementById('sidebar').classList.remove('open');
        document.getElementById('overlay').classList.remove('open');
        refreshApp();
    }

    function selectMethod(m) {
        selectedMethod = m;
        document.getElementById('tab-bkash').classList.toggle('active', m === 'bkash');
        document.getElementById('tab-nagad').classList.toggle('active', m === 'nagad');
    }

    function submitManualStore() {
        const trx = document.getElementById('m-trx').value;
        const amt = document.getElementById('m-amount').value;
        const snd = document.getElementById('m-sender').value;
        const res = AndroidBridge.storeManualPayment(selectedMethod, trx, amt, snd);
        if (res === 'SUCCESS') {
            document.getElementById('m-trx').value = '';
            document.getElementById('m-amount').value = '';
            alert('ডাটা স্টোর হয়েছে! নেট থাকলে এখনই সার্ভারে ভেরিফাই হয়ে যাবে।');
            showPage('home');
        } else {
            alert(res);
        }
    }

    function saveServerConfig() {
        const u = document.getElementById('cfg-url').value;
        const t = document.getElementById('cfg-token').value;
        const s = document.getElementById('cfg-secret').value;
        AndroidBridge.saveConfig(u, t, s);
        refreshApp();
    }

    function syncNow() {
        AndroidBridge.triggerSyncNow();
    }

    function refreshApp() {
        const raw = AndroidBridge.getDashboardData();
        const data = JSON.parse(raw);

        document.getElementById('st-synced').innerText = data.synced;
        document.getElementById('st-pending').innerText = data.pending;

        if (!document.getElementById('cfg-url').value) {
            document.getElementById('cfg-url').value = data.api_url || '';
            document.getElementById('cfg-token').value = data.device_token || '';
            document.getElementById('cfg-secret').value = data.hmac_secret || '';
        }

        const box = document.getElementById('history-box');
        if (!data.history || data.history.length === 0) {
            box.innerHTML = '<p style="color:#94A3B8; font-size:13px; text-align:center; padding:20px;">এখনো কোনো ট্রানজেকশন স্টোর বা সিঙ্ক হয়নি।</p>';
            return;
        }

        let html = '';
        data.history.forEach(item => {
            const stClass = item.status === 'synced' ? 'synced' : 'pending';
            const stText = item.status === 'synced' ? '✓ SUCCESS' : '⏳ PENDING';
            html += `
            <div class="h-item">
                <div class="h-top">
                    <div>
                        <span class="badge ${d}{item.method}">${d}{item.method}</span>
                        <span class="h-trx" style="margin-left:6px;">${d}{item.trx_id}</span>
                    </div>
                    <span class="badge ${d}{stClass}">${d}{stText}</span>
                </div>
                <div class="h-meta">
                    <b>Amount:</b> ৳${d}{item.amount} &nbsp;|&nbsp; <b>Sender:</b> ${d}{item.sender_number}<br>
                    <span>🕒 ${d}{item.sms_time} • ${d}{item.server_msg}</span>
                </div>
            </div>`;
        });
        box.innerHTML = html;
    }

    refreshApp();
    setInterval(refreshApp, 4000);
</script>
</body>
</html>
        """.trimIndent()
    }
}
