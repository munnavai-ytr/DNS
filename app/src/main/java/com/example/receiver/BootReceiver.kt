package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.example.DnsShieldApplication
import com.example.protection.ProtectionMode
import com.example.protection.ProtectionWatchdogScheduler
import com.example.vpn.DnsVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
                action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
                action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val pendingResult = goAsync()
        val app = context.applicationContext as? DnsShieldApplication
        if (app == null) {
            pendingResult.finish()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                ProtectionWatchdogScheduler.ensurePeriodicWatchdog(context)
                val snapshot =
                    app.repository.refreshProtectionState(restartVpnIfProtectedAndDown = false)
                ProtectionWatchdogScheduler.syncBoundaryTimer(context, snapshot.tamperState)
                val isVpnMode = snapshot.tamperState.protectionMode == ProtectionMode.VPN
                val shouldStart =
                    isVpnMode &&
                        (snapshot.tamperState.isGuardActive || app.repository.isVpnDesiredEnabled())
                if (shouldStart && VpnService.prepare(context) == null) {
                    DnsVpnService.start(context)
                }
            } catch (_: Throwable) {
                // Never crash on boot broadcast
            } finally {
                pendingResult.finish()
            }
        }
    }
}
