package com.example.protection

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.example.MainActivity
import com.example.data.repository.ShieldRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object ProtectionWatchdogScheduler {
    private const val WATCHDOG_JOB_ID = 3001
    private const val WATCHDOG_INTERVAL_MS = 15L * 60L * 1000L
    private const val ALARM_WATCHDOG_CODE = 3101
    private const val ALARM_BOUNDARY_CODE = 3102
    private const val ALARM_BOUNDARY_FALLBACK_CODE = 3103
    private const val ALARM_SHOW_INTENT_CODE = 3104
    const val BOUNDARY_FALLBACK_OFFSET_MS = 2_000L

    fun hasActiveTimer(state: TamperProtectionState): Boolean {
        if (state.isStrictLock) return false
        return state.isCountdownActive || state.isUnlockWindowActive || state.isLockedOut
    }

    /**
     * Returns the exact remaining time in milliseconds until the nearest active timer boundary
     * (countdown end, unlock window end, or PIN lockout end), with no artificial cap.
     * Returns null when no timer is active.
     */
    fun computeNearestTimerRemainingMs(state: TamperProtectionState): Long? {
        if (state.isStrictLock) return null
        val candidates = buildList {
            if (state.isCountdownActive && state.countdownRemainingMs > 0L) {
                add(state.countdownRemainingMs)
            }
            if (state.isUnlockWindowActive && state.unlockWindowRemainingMs > 0L) {
                add(state.unlockWindowRemainingMs)
            }
            if (state.isLockedOut && state.lockoutRemainingMs > 0L) {
                add(state.lockoutRemainingMs)
            }
        }
        return candidates.minOrNull()
    }

    fun ensurePeriodicWatchdog(context: Context) {
        val appContext = context.applicationContext
        scheduleJobSchedulerWatchdog(appContext)
        scheduleBackupAlarmWatchdog(appContext)
    }

    fun syncBoundaryTimer(context: Context, state: TamperProtectionState) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return

        val boundaryIntent =
            PendingIntent.getBroadcast(
                appContext,
                ALARM_BOUNDARY_CODE,
                Intent(appContext, ProtectionWatchdogReceiver::class.java).apply {
                    action = ProtectionWatchdogReceiver.ACTION_TIMER_BOUNDARY
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val fallbackIntent =
            PendingIntent.getBroadcast(
                appContext,
                ALARM_BOUNDARY_FALLBACK_CODE,
                Intent(appContext, ProtectionWatchdogReceiver::class.java).apply {
                    action = ProtectionWatchdogReceiver.ACTION_TIMER_BOUNDARY
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val delayMs = computeNearestTimerRemainingMs(state)
        if (delayMs == null) {
            runCatching { alarmManager.cancel(boundaryIntent) }
            runCatching { alarmManager.cancel(fallbackIntent) }
            return
        }

        val triggerWallMs = System.currentTimeMillis() + delayMs
        val showIntent =
            PendingIntent.getActivity(
                appContext,
                ALARM_SHOW_INTENT_CODE,
                Intent(appContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(
                        ProtectionNotificationHelper.EXTRA_OPEN_DESTINATION,
                        ProtectionNotificationHelper.DEST_UNLOCK,
                    )
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        runCatching {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerWallMs, showIntent),
                boundaryIntent,
            )
        }

        val fallbackTriggerElapsedMs =
            SystemClock.elapsedRealtime() + delayMs + BOUNDARY_FALLBACK_OFFSET_MS
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                fallbackTriggerElapsedMs,
                fallbackIntent,
            )
        } catch (_: SecurityException) {
            // SCHEDULE_EXACT_ALARM may be absent on Android 12+; setAlarmClock covers exact wakeups.
        } catch (_: Throwable) {
            // Keep boundary scheduling fault-tolerant on OEM devices.
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

/**
 * Service-side ticker that refreshes tamper protection state every 5 seconds using
 * [SystemClock.elapsedRealtime]-anchored delays ONLY while a countdown, unlock window, or PIN
 * lockout timer is active. Stops completely when no timer is active.
 */
class ProtectionTimerTicker(
    private val scope: CoroutineScope,
    private val repository: ShieldRepository,
) {
    private var observeJob: Job? = null
    private var tickerJob: Job? = null

    fun startObserving() {
        if (observeJob?.isActive == true) return
        observeJob =
            scope.launch {
                repository.protectionUiSnapshot
                    .map { ProtectionWatchdogScheduler.hasActiveTimer(it.tamperState) }
                    .distinctUntilChanged()
                    .collect { active ->
                        if (active) {
                            startTickerIfNeeded()
                        } else {
                            stopTicker()
                        }
                    }
            }
    }

    fun stop() {
        observeJob?.cancel()
        observeJob = null
        stopTicker()
    }

    private fun startTickerIfNeeded() {
        if (tickerJob?.isActive == true) return
        tickerJob =
            scope.launch {
                var wasUnlockActive =
                    repository.protectionUiSnapshot.value.tamperState.isUnlockWindowActive
                while (isActive) {
                    val state = repository.protectionUiSnapshot.value.tamperState
                    if (!ProtectionWatchdogScheduler.hasActiveTimer(state)) {
                        if (wasUnlockActive) {
                            runCatching {
                                repository.refreshProtectionState(restartVpnIfProtectedAndDown = true)
                            }
                        }
                        break
                    }
                    if (state.isUnlockWindowActive) {
                        wasUnlockActive = true
                    }
                    val nearestRemaining =
                        ProtectionWatchdogScheduler.computeNearestTimerRemainingMs(state)
                    val waitMs =
                        if (nearestRemaining != null && nearestRemaining < TICK_INTERVAL_MS) {
                            nearestRemaining.coerceAtLeast(MIN_WAIT_MS)
                        } else {
                            TICK_INTERVAL_MS
                        }
                    val targetElapsed = SystemClock.elapsedRealtime() + waitMs
                    while (isActive) {
                        val remainingWait = targetElapsed - SystemClock.elapsedRealtime()
                        if (remainingWait <= 0L) break
                        delay(remainingWait)
                    }
                    if (!isActive) break
                    val updated =
                        runCatching {
                            repository.refreshProtectionState(
                                restartVpnIfProtectedAndDown = wasUnlockActive
                            )
                        }.getOrNull()
                    if (updated != null) {
                        wasUnlockActive = updated.tamperState.isUnlockWindowActive
                    }
                }
            }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    companion object {
        private const val TICK_INTERVAL_MS = 5_000L
        private const val MIN_WAIT_MS = 250L
    }
}
