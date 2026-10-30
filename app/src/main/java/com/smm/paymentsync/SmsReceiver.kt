package com.smm.paymentsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony

class SmsReceiver : BroadcastReceiver() {

    private val BKASH_REGEX = Regex(
        """You have received Tk\s*([\d,]+(?:\.\d{1,2})?)\s+from\s+(01\d{9}).*?Balance\s+Tk\s*([\d,]+(?:\.\d{1,2})?).*?TrxID\s+([A-Z0-9]{8,15})\s+at\s+(\d{2}/\d{2}/\d{4}\s+\d{2}:\d{2})""",
        RegexOption.IGNORE_CASE
    )

    private val NAGAD_REGEX = Regex(
        """Money Received\.\s*Amount:\s*Tk\s*([\d,]+(?:\.\d{1,2})?)\s*Sender:\s*(01\d{9}).*?(?:TxnID|TrxID):\s*([A-Z0-9]{8,15})\s*Balance:\s*Tk\s*([\d,]+(?:\.\d{1,2})?)\s*(\d{2}/\d{2}/\d{4}\s+\d{2}:\d{2})""",
        RegexOption.IGNORE_CASE
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            startSyncService(context)
            return
        }

        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            val db = LocalDb(context)

            for (sms in messages) {
                val sender = (sms.displayOriginatingAddress ?: "").trim()
                val body = (sms.messageBody ?: "").replace("\r\n", " ").replace("\n", " ").trim()
                val cleanSender = sender.uppercase()

                if (cleanSender.contains("BKASH")) {
                    val match = BKASH_REGEX.find(body)
                    if (match != null) {
                        val amount = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
                        val senderNum = match.groupValues[2]
                        val balance = match.groupValues[3].replace(",", "").toDoubleOrNull() ?: 0.0
                        val trxId = match.groupValues[4].uppercase()
                        val time = match.groupValues[5]

                        if (amount > 0 && trxId.isNotEmpty()) {
                            db.insertPayment("bKash", "bkash", trxId, amount, senderNum, balance, time, body)
                            startSyncService(context)
                        }
                    }
                }

                if (cleanSender.contains("NAGAD") || cleanSender.contains("16167")) {
                    val match = NAGAD_REGEX.find(body)
                    if (match != null) {
                        val amount = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
                        val senderNum = match.groupValues[2]
                        val trxId = match.groupValues[3].uppercase()
                        val balance = match.groupValues[4].replace(",", "").toDoubleOrNull() ?: 0.0
                        val time = match.groupValues[5]

                        if (amount > 0 && trxId.isNotEmpty()) {
                            db.insertPayment("NAGAD", "nagad", trxId, amount, senderNum, balance, time, body)
                            startSyncService(context)
                        }
                    }
                }
            }
        }
    }

    private fun startSyncService(context: Context) {
        val srv = Intent(context, SyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(srv)
        } else {
            context.startService(srv)
        }
    }
}
