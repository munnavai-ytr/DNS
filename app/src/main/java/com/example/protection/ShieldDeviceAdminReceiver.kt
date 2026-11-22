package com.example.protection

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.example.R

class ShieldDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return context.getString(R.string.device_admin_disable_warning)
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        ProtectionWatchdogReceiver.triggerAudit(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        ProtectionWatchdogReceiver.triggerAudit(context)
    }
}
