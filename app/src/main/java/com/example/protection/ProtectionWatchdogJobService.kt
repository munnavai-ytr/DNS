package com.example.protection

import android.app.job.JobParameters
import android.app.job.JobService
import com.example.DnsShieldApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ProtectionWatchdogJobService : JobService() {
    private val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        val app = applicationContext as? DnsShieldApplication
        if (app == null) {
            return false
        }
        jobScope.launch {
            try {
                app.repository.refreshProtectionState(restartVpnIfProtectedAndDown = true)
            } catch (_: Throwable) {
                // Keep watchdog fault-tolerant
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        jobScope.cancel()
    }
}
