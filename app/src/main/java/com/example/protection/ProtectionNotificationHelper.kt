package com.example.protection

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

object ProtectionNotificationHelper {
    const val EXTRA_OPEN_DESTINATION = "open_destination"
    const val DEST_CHECKLIST = "protection_checklist"
    const val DEST_UNLOCK = "unlock_flow"

    const val ACTION_CANCEL_COUNTDOWN = "com.example.protection.ACTION_CANCEL_COUNTDOWN"
    const val ACTION_LOCK_NOW = "com.example.protection.ACTION_LOCK_NOW"

    private const val CHANNEL_PROTECTION_STATUS = "dns_shield_protection_status"
    private const val CHANNEL_PROTECTION_ALERT = "dns_shield_protection_alert"

    private const val NOTIFICATION_ID_TIMER = 1002
    private const val NOTIFICATION_ID_WEAKENED = 1003

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val statusChannel =
            NotificationChannel(
                CHANNEL_PROTECTION_STATUS,
                context.getString(R.string.notification_protection_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
                .apply {
                    description = context.getString(R.string.notification_protection_channel_desc)
                    setShowBadge(false)
                }

        val alertChannel =
            NotificationChannel(
                CHANNEL_PROTECTION_ALERT,
                context.getString(R.string.notification_alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            )
                .apply {
                    description = context.getString(R.string.notification_alert_channel_desc)
                }

        manager.createNotificationChannel(statusChannel)
        manager.createNotificationChannel(alertChannel)
    }

    fun syncTimerNotification(context: Context, state: TamperProtectionState) {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        when {
            state.isUnlockWindowActive -> {
                val formatted = formatDurationShort(state.unlockWindowRemainingMs)
                val openIntent = buildMainActivityIntent(context, DEST_UNLOCK, requestCode = 201)
                val lockNowIntent =
                    PendingIntent.getBroadcast(
                        context,
                        202,
                        Intent(context, ProtectionWatchdogReceiver::class.java).apply {
                            action = ACTION_LOCK_NOW
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )

                val notification =
                    NotificationCompat.Builder(context, CHANNEL_PROTECTION_STATUS)
                        .setSmallIcon(R.drawable.ic_shield_notification)
                        .setContentTitle(context.getString(R.string.notification_unlock_title))
                        .setContentText("Tamper guard paused • $formatted remaining")
                        .setOngoing(true)
                        .setSilent(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setContentIntent(openIntent)
                        .addAction(
                            R.drawable.ic_shield_notification,
                            context.getString(R.string.lock_again_button),
                            lockNowIntent,
                        )
                        .build()
                manager.notify(NOTIFICATION_ID_TIMER, notification)
            }

            state.isCountdownActive || state.isCountdownReadyToUnlock -> {
                val openIntent = buildMainActivityIntent(context, DEST_UNLOCK, requestCode = 203)
                val cancelIntent =
                    PendingIntent.getBroadcast(
                        context,
                        204,
                        Intent(context, ProtectionWatchdogReceiver::class.java).apply {
                            action = ACTION_CANCEL_COUNTDOWN
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )

                val title =
                    if (state.isCountdownReadyToUnlock) {
                        context.getString(R.string.notification_countdown_ready_title)
                    } else {
                        context.getString(R.string.notification_countdown_title)
                    }
                val text =
                    if (state.isCountdownReadyToUnlock) {
                        context.getString(R.string.notification_countdown_ready_desc)
                    } else {
                        "Remaining: ${formatDurationShort(state.countdownRemainingMs)} • Tap for details"
                    }

                val notification =
                    NotificationCompat.Builder(context, CHANNEL_PROTECTION_STATUS)
                        .setSmallIcon(R.drawable.ic_shield_notification)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setOngoing(true)
                        .setSilent(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setContentIntent(openIntent)
                        .addAction(
                            R.drawable.ic_shield_notification,
                            context.getString(R.string.cancel_request_button),
                            cancelIntent,
                        )
                        .build()
                manager.notify(NOTIFICATION_ID_TIMER, notification)
            }

            else -> {
                manager.cancel(NOTIFICATION_ID_TIMER)
            }
        }
    }

    fun syncWeakenedNotification(
        context: Context,
        level: ProtectionLevel,
        checklist: ProtectionChecklistState,
    ) {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (level != ProtectionLevel.WEAK) {
            manager.cancel(NOTIFICATION_ID_WEAKENED)
            return
        }

        val missing = buildList {
            if (!checklist.vpnRunning) add("VPN filter")
            if (!checklist.deviceAdminEnabled) add("Device Admin")
            if (!checklist.accessibilityEnabled) add("Accessibility Guard")
        }
        val detail =
            if (missing.isEmpty()) {
                "One or more tamper protections need attention."
            } else {
                "Disabled: ${missing.joinToString(", ")}"
            }

        val tapIntent = buildMainActivityIntent(context, DEST_CHECKLIST, requestCode = 205)
        val notification =
            NotificationCompat.Builder(context, CHANNEL_PROTECTION_ALERT)
                .setSmallIcon(R.drawable.ic_shield_notification)
                .setContentTitle(context.getString(R.string.notification_weakened_title))
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(tapIntent)
                .build()
        manager.notify(NOTIFICATION_ID_WEAKENED, notification)
    }

    private fun buildMainActivityIntent(
        context: Context,
        destination: String,
        requestCode: Int,
    ): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_DESTINATION, destination)
            }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun formatDurationShort(durationMs: Long): String {
        val totalSec = (durationMs.coerceAtLeast(0L) + 999L) / 1000L
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return if (hours > 0L) {
            String.format("%dh %02dm %02ds", hours, minutes, seconds)
        } else {
            String.format("%02dm %02ds", minutes, seconds)
        }
    }
}
