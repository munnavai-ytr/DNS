package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.shieldDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "dns_shield_prefs")

data class ShieldStatsSnapshot(
    val onboardingCompleted: Boolean = false,
    val vpnDesiredEnabled: Boolean = false,
    val totalBlocked: Long = 0L,
    val blockedToday: Long = 0L,
)

class ShieldPreferences(private val context: Context) {
    private object Keys {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val VPN_DESIRED_ENABLED = booleanPreferencesKey("vpn_desired_enabled")
        val TOTAL_BLOCKED = longPreferencesKey("total_blocked")
        val BLOCKED_TODAY = longPreferencesKey("blocked_today")
        val BLOCKED_TODAY_EPOCH_DAY = longPreferencesKey("blocked_today_epoch_day")
    }

    val snapshotFlow: Flow<ShieldStatsSnapshot> =
        context.shieldDataStore.data
            .catch { exception ->
                if (exception is IOException) emit(emptyPreferences()) else throw exception
            }
            .map { prefs ->
                val currentEpochDay = LocalDate.now().toEpochDay()
                val storedDay = prefs[Keys.BLOCKED_TODAY_EPOCH_DAY] ?: currentEpochDay
                val todayCount =
                    if (storedDay == currentEpochDay) {
                        prefs[Keys.BLOCKED_TODAY] ?: 0L
                    } else {
                        0L
                    }
                ShieldStatsSnapshot(
                    onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
                    vpnDesiredEnabled = prefs[Keys.VPN_DESIRED_ENABLED] ?: false,
                    totalBlocked = prefs[Keys.TOTAL_BLOCKED] ?: 0L,
                    blockedToday = todayCount,
                )
            }

    suspend fun isVpnDesiredEnabled(): Boolean {
        return snapshotFlow.first().vpnDesiredEnabled
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.shieldDataStore.edit { prefs -> prefs[Keys.ONBOARDING_COMPLETED] = completed }
    }

    suspend fun setVpnDesiredEnabled(enabled: Boolean) {
        context.shieldDataStore.edit { prefs -> prefs[Keys.VPN_DESIRED_ENABLED] = enabled }
    }

    suspend fun incrementBlockedCounters() {
        val currentEpochDay = LocalDate.now().toEpochDay()
        context.shieldDataStore.edit { prefs ->
            val storedDay = prefs[Keys.BLOCKED_TODAY_EPOCH_DAY] ?: currentEpochDay
            val previousToday =
                if (storedDay == currentEpochDay) {
                    prefs[Keys.BLOCKED_TODAY] ?: 0L
                } else {
                    0L
                }
            val previousTotal = prefs[Keys.TOTAL_BLOCKED] ?: 0L
            prefs[Keys.BLOCKED_TODAY_EPOCH_DAY] = currentEpochDay
            prefs[Keys.BLOCKED_TODAY] = previousToday + 1L
            prefs[Keys.TOTAL_BLOCKED] = previousTotal + 1L
        }
    }
}
