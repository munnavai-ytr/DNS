package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.example.DnsShieldApplication
import com.example.vpn.DnsVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        val app = context.applicationContext as? DnsShieldApplication
        if (app == null) {
            pendingResult.finish()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val guardActive = app.repository.isGuardBlockingNow()
                val shouldRun = guardActive || app.repository.isVpnDesiredEnabled()
                if (shouldRun && VpnService.prepare(context) == null) {
                    DnsVpnService.start(context)
                } else {
                    app.repository.refreshProtectionState(restartVpnIfProtectedAndDown = false)
                }
            } catch (_: Throwable) {
                // Ignore transient restart errors
            } finally {
                pendingResult.finish()
            }
        }
    }
}
