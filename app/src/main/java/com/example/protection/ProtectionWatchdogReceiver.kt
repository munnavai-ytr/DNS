package com.example.protection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.DnsShieldApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ProtectionWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        val app = context.applicationContext as? DnsShieldApplication
        if (app == null) {
            pendingResult.finish()
            return
        }

        val action = intent?.action ?: ACTION_WATCHDOG_AUDIT
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ProtectionNotificationHelper.ACTION_CANCEL_COUNTDOWN -> {
                        app.repository.cancelTurnOffRequest()
                    }
                    ProtectionNotificationHelper.ACTION_LOCK_NOW -> {
                        app.repository.lockProtectionAgain()
                    }
                    ACTION_TIMER_BOUNDARY,
                    ACTION_WATCHDOG_AUDIT -> {
                        app.repository.refreshProtectionState(restartVpnIfProtectedAndDown = true)
                    }
                    else -> {
                        app.repository.refreshProtectionState(restartVpnIfProtectedAndDown = false)
                    }
                }
            } catch (_: Throwable) {
                // Never crash broadcast receiver
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_WATCHDOG_AUDIT = "com.example.protection.ACTION_WATCHDOG_AUDIT"
        const val ACTION_TIMER_BOUNDARY = "com.example.protection.ACTION_TIMER_BOUNDARY"

        fun triggerAudit(context: Context) {
            val intent =
                Intent(context.applicationContext, ProtectionWatchdogReceiver::class.java).apply {
                    action = ACTION_WATCHDOG_AUDIT
                }
            runCatching { context.applicationContext.sendBroadcast(intent) }
        }
    }
}
