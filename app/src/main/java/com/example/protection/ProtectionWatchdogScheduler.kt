package com.example.protection

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock

object ProtectionWatchdogScheduler {
    private const val WATCHDOG_JOB_ID = 3001
    private const val WATCHDOG_INTERVAL_MS = 15L * 60L * 1000L
    private const val ALARM_WATCHDOG_CODE = 3101
    private const val ALARM_BOUNDARY_CODE = 3102

    fun ensurePeriodicWatchdog(context: Context) {
        val appContext = context.applicationContext
        scheduleJobSchedulerWatchdog(appContext)
        scheduleBackupAlarmWatchdog(appContext)
    }

    fun syncBoundaryTimer(context: Context, state: TamperProtectionState) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return

        val pendingIntent =
            PendingIntent.getBroadcast(
                appContext,
                ALARM_BOUNDARY_CODE,
                Intent(appContext, ProtectionWatchdogReceiver::class.java).apply {
                    action = ProtectionWatchdogReceiver.ACTION_TIMER_BOUNDARY
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val delayMs: Long? =
            when {
                state.isUnlockWindowActive && state.unlockWindowRemainingMs > 0L -> {
                    state.unlockWindowRemainingMs.coerceAtMost(60_000L)
                }
                state.isCountdownActive && state.countdownRemainingMs > 0L -> {
                    state.countdownRemainingMs.coerceAtMost(60_000L)
                }
                else -> null
            }

        if (delayMs == null) {
            alarmManager.cancel(pendingIntent)
        } else {
            val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(1_000L)
            runCatching {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
            }
                .onFailure {
                    alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
                }
        }
    }

    private fun scheduleJobSchedulerWatchdog(context: Context) {
        runCatching {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val existing = scheduler.getPendingJob(WATCHDOG_JOB_ID)
            if (existing != null) return

            val component = ComponentName(context, ProtectionWatchdogJobService::class.java)
            val jobInfo =
                JobInfo.Builder(WATCHDOG_JOB_ID, component)
                    .setPeriodic(WATCHDOG_INTERVAL_MS)
                    .setPersisted(true)
                    .build()
            scheduler.schedule(jobInfo)
        }
    }

    private fun scheduleBackupAlarmWatchdog(context: Context) {
        runCatching {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val intent =
                Intent(context, ProtectionWatchdogReceiver::class.java).apply {
                    action = ProtectionWatchdogReceiver.ACTION_WATCHDOG_AUDIT
                }
            val pendingIntent =
                PendingIntent.getBroadcast(
                    context,
                    ALARM_WATCHDOG_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val firstTrigger = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS
            alarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                firstTrigger,
                WATCHDOG_INTERVAL_MS,
                pendingIntent,
            )
        }
    }
}
