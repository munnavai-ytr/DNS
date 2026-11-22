package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.dns.GeminiDomainClassifier
import com.example.dns.UnsurePolicy
import com.example.protection.LockType
import com.example.protection.PersistedTamperState
import com.example.protection.ProtectionMode
import com.example.protection.ProtectionStateStore
import com.example.protection.ScreenGuardSensitivity
import com.example.protection.ScreenGuardSettings
import com.example.protection.WaitDurationOption
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
    val aiVerificationEnabled: Boolean = true,
    val geminiApiKey: String = "",
    val geminiModel: String = GeminiDomainClassifier.DEFAULT_MODEL,
    val whenUnsure: UnsurePolicy = UnsurePolicy.ALLOW,
    val wizardPromptedAfterOnboarding: Boolean = false,
    val screenGuardSettings: ScreenGuardSettings = ScreenGuardSettings(),
)

class ShieldPreferences(private val context: Context) : ProtectionStateStore {
    private object Keys {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val VPN_DESIRED_ENABLED = booleanPreferencesKey("vpn_desired_enabled")
        val TOTAL_BLOCKED = longPreferencesKey("total_blocked")
        val BLOCKED_TODAY = longPreferencesKey("blocked_today")
        val BLOCKED_TODAY_EPOCH_DAY = longPreferencesKey("blocked_today_epoch_day")
        val AI_VERIFICATION_ENABLED = booleanPreferencesKey("ai_verification_enabled")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val WHEN_UNSURE = stringPreferencesKey("when_unsure")

        // V3 Tamper Protection keys
        val PROTECTION_MODE = stringPreferencesKey("tamper_protection_mode")
        val MODE_EXPLICITLY_CHOSEN = booleanPreferencesKey("tamper_mode_explicitly_chosen")
        val LOCK_TYPE = stringPreferencesKey("tamper_lock_type")
        val PIN_SALT_BASE64 = stringPreferencesKey("tamper_pin_salt")
        val PIN_HASH_BASE64 = stringPreferencesKey("tamper_pin_hash")
        val WAIT_DURATION_MS = longPreferencesKey("tamper_wait_duration_ms")
        val WAIT_DURATION_CHOSEN = booleanPreferencesKey("tamper_wait_duration_chosen")
        val PROTECTION_ACTIVATED = booleanPreferencesKey("tamper_protection_activated")
        val WIZARD_PROMPTED_AFTER_ONBOARDING =
            booleanPreferencesKey("tamper_wizard_prompted_after_onboarding")
        val HIGHEST_WALL_CLOCK_MS = longPreferencesKey("tamper_highest_wall_clock_ms")
        val LAST_ELAPSED_REALTIME_MS = longPreferencesKey("tamper_last_elapsed_realtime_ms")
        val COUNTDOWN_ACTIVE = booleanPreferencesKey("tamper_countdown_active")
        val COUNTDOWN_REMAINING_MS = longPreferencesKey("tamper_countdown_remaining_ms")
        val COUNTDOWN_READY_FOR_SECOND_PIN =
            booleanPreferencesKey("tamper_countdown_ready_for_second_pin")
        val UNLOCK_WINDOW_ACTIVE = booleanPreferencesKey("tamper_unlock_window_active")
        val UNLOCK_WINDOW_REMAINING_MS = longPreferencesKey("tamper_unlock_window_remaining_ms")
        val FAILED_PIN_ATTEMPTS = intPreferencesKey("tamper_failed_pin_attempts")
        val LOCKOUT_TIER = intPreferencesKey("tamper_lockout_tier")
        val LOCKOUT_REMAINING_MS = longPreferencesKey("tamper_lockout_remaining_ms")

        // V4 Screen Guard keys
        val SG_MASTER_ENABLED = booleanPreferencesKey("sg_master_enabled")
        val SG_URL_CHECK_ENABLED = booleanPreferencesKey("sg_url_check_enabled")
        val SG_TEXT_SCORING_ENABLED = booleanPreferencesKey("sg_text_scoring_enabled")
        val SG_SEARCH_GUARD_ENABLED = booleanPreferencesKey("sg_search_guard_enabled")
        val SG_VISUAL_CHECK_ENABLED = booleanPreferencesKey("sg_visual_check_enabled")
        val SG_SENSITIVITY = stringPreferencesKey("sg_sensitivity")
        val SG_MONITOR_ALL_APPS = booleanPreferencesKey("sg_monitor_all_apps")
        val SG_ALLOWED_APPS = stringSetPreferencesKey("sg_allowed_apps")
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
                val storedModel =
                    prefs[Keys.GEMINI_MODEL]?.trim()?.ifEmpty { GeminiDomainClassifier.DEFAULT_MODEL }
                        ?: GeminiDomainClassifier.DEFAULT_MODEL
                ShieldStatsSnapshot(
                    onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
                    vpnDesiredEnabled = prefs[Keys.VPN_DESIRED_ENABLED] ?: false,
                    totalBlocked = prefs[Keys.TOTAL_BLOCKED] ?: 0L,
                    blockedToday = todayCount,
                    aiVerificationEnabled = prefs[Keys.AI_VERIFICATION_ENABLED] ?: true,
                    geminiApiKey = prefs[Keys.GEMINI_API_KEY] ?: "",
                    geminiModel = storedModel,
                    whenUnsure = UnsurePolicy.fromStored(prefs[Keys.WHEN_UNSURE]),
                    wizardPromptedAfterOnboarding =
                        prefs[Keys.WIZARD_PROMPTED_AFTER_ONBOARDING] ?: false,
                    screenGuardSettings =
                        ScreenGuardSettings(
                            masterEnabled = prefs[Keys.SG_MASTER_ENABLED] ?: true,
                            urlCheckEnabled = prefs[Keys.SG_URL_CHECK_ENABLED] ?: true,
                            textScoringEnabled = prefs[Keys.SG_TEXT_SCORING_ENABLED] ?: true,
                            searchGuardEnabled = prefs[Keys.SG_SEARCH_GUARD_ENABLED] ?: true,
                            visualCheckEnabled = prefs[Keys.SG_VISUAL_CHECK_ENABLED] ?: false,
                            sensitivity =
                                ScreenGuardSensitivity.fromStored(prefs[Keys.SG_SENSITIVITY]),
                            monitorAllApps = prefs[Keys.SG_MONITOR_ALL_APPS] ?: true,
                            allowedApps = prefs[Keys.SG_ALLOWED_APPS] ?: emptySet(),
                        ),
                )
            }

    override suspend fun readState(): PersistedTamperState {
        val prefs =
            context.shieldDataStore.data
                .catch { exception ->
                    if (exception is IOException) emit(emptyPreferences()) else throw exception
                }
                .first()
        return mapToTamperState(prefs)
    }

    override suspend fun writeState(state: PersistedTamperState) {
        context.shieldDataStore.edit { prefs ->
            prefs[Keys.PROTECTION_MODE] = state.protectionModeName
            prefs[Keys.MODE_EXPLICITLY_CHOSEN] = state.modeExplicitlyChosen
            prefs[Keys.LOCK_TYPE] = state.lockTypeName
            prefs[Keys.PIN_SALT_BASE64] = state.pinSaltBase64
            prefs[Keys.PIN_HASH_BASE64] = state.pinHashBase64
            prefs[Keys.WAIT_DURATION_MS] = state.waitDurationMs
            prefs[Keys.WAIT_DURATION_CHOSEN] = state.waitDurationExplicitlyChosen
            prefs[Keys.PROTECTION_ACTIVATED] = state.protectionActivated
            prefs[Keys.WIZARD_PROMPTED_AFTER_ONBOARDING] = state.wizardPromptedAfterOnboarding
            prefs[Keys.HIGHEST_WALL_CLOCK_MS] = state.highestWallClockMs
            prefs[Keys.LAST_ELAPSED_REALTIME_MS] = state.lastElapsedRealtimeMs
            prefs[Keys.COUNTDOWN_ACTIVE] = state.countdownActive
            prefs[Keys.COUNTDOWN_REMAINING_MS] = state.countdownRemainingMs
            prefs[Keys.COUNTDOWN_READY_FOR_SECOND_PIN] = state.countdownReadyForSecondPin
            prefs[Keys.UNLOCK_WINDOW_ACTIVE] = state.unlockWindowActive
            prefs[Keys.UNLOCK_WINDOW_REMAINING_MS] = state.unlockWindowRemainingMs
            prefs[Keys.FAILED_PIN_ATTEMPTS] = state.failedPinAttempts
            prefs[Keys.LOCKOUT_TIER] = state.lockoutTier
            prefs[Keys.LOCKOUT_REMAINING_MS] = state.lockoutRemainingMs
        }
    }

    private fun mapToTamperState(prefs: Preferences): PersistedTamperState {
        return PersistedTamperState(
            protectionModeName = prefs[Keys.PROTECTION_MODE] ?: ProtectionMode.VPN.name,
            modeExplicitlyChosen = prefs[Keys.MODE_EXPLICITLY_CHOSEN] ?: false,
            lockTypeName = prefs[Keys.LOCK_TYPE] ?: LockType.NONE.name,
            pinSaltBase64 = prefs[Keys.PIN_SALT_BASE64] ?: "",
            pinHashBase64 = prefs[Keys.PIN_HASH_BASE64] ?: "",
            waitDurationMs = prefs[Keys.WAIT_DURATION_MS] ?: WaitDurationOption.DEFAULT.durationMs,
            waitDurationExplicitlyChosen = prefs[Keys.WAIT_DURATION_CHOSEN] ?: false,
            protectionActivated = prefs[Keys.PROTECTION_ACTIVATED] ?: false,
            wizardPromptedAfterOnboarding =
                prefs[Keys.WIZARD_PROMPTED_AFTER_ONBOARDING] ?: false,
            highestWallClockMs = prefs[Keys.HIGHEST_WALL_CLOCK_MS] ?: 0L,
            lastElapsedRealtimeMs = prefs[Keys.LAST_ELAPSED_REALTIME_MS] ?: 0L,
            countdownActive = prefs[Keys.COUNTDOWN_ACTIVE] ?: false,
            countdownRemainingMs = prefs[Keys.COUNTDOWN_REMAINING_MS] ?: 0L,
            countdownReadyForSecondPin = prefs[Keys.COUNTDOWN_READY_FOR_SECOND_PIN] ?: false,
            unlockWindowActive = prefs[Keys.UNLOCK_WINDOW_ACTIVE] ?: false,
            unlockWindowRemainingMs = prefs[Keys.UNLOCK_WINDOW_REMAINING_MS] ?: 0L,
            failedPinAttempts = prefs[Keys.FAILED_PIN_ATTEMPTS] ?: 0,
            lockoutTier = prefs[Keys.LOCKOUT_TIER] ?: 0,
            lockoutRemainingMs = prefs[Keys.LOCKOUT_REMAINING_MS] ?: 0L,
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

    suspend fun setAiVerificationEnabled(enabled: Boolean) {
        context.shieldDataStore.edit { prefs -> prefs[Keys.AI_VERIFICATION_ENABLED] = enabled }
    }

    suspend fun setGeminiApiKey(apiKey: String) {
        context.shieldDataStore.edit { prefs -> prefs[Keys.GEMINI_API_KEY] = apiKey.trim() }
    }

    suspend fun setGeminiModel(model: String) {
        val cleaned = model.trim().ifEmpty { GeminiDomainClassifier.DEFAULT_MODEL }
        context.shieldDataStore.edit { prefs -> prefs[Keys.GEMINI_MODEL] = cleaned }
    }

    suspend fun setWhenUnsure(policy: UnsurePolicy) {
        context.shieldDataStore.edit { prefs -> prefs[Keys.WHEN_UNSURE] = policy.name }
    }

    suspend fun updateScreenGuardSettings(transform: (ScreenGuardSettings) -> ScreenGuardSettings) {
        context.shieldDataStore.edit { prefs ->
            val current =
                ScreenGuardSettings(
                    masterEnabled = prefs[Keys.SG_MASTER_ENABLED] ?: true,
                    urlCheckEnabled = prefs[Keys.SG_URL_CHECK_ENABLED] ?: true,
                    textScoringEnabled = prefs[Keys.SG_TEXT_SCORING_ENABLED] ?: true,
                    searchGuardEnabled = prefs[Keys.SG_SEARCH_GUARD_ENABLED] ?: true,
                    visualCheckEnabled = prefs[Keys.SG_VISUAL_CHECK_ENABLED] ?: false,
                    sensitivity = ScreenGuardSensitivity.fromStored(prefs[Keys.SG_SENSITIVITY]),
                    monitorAllApps = prefs[Keys.SG_MONITOR_ALL_APPS] ?: true,
                    allowedApps = prefs[Keys.SG_ALLOWED_APPS] ?: emptySet(),
                )
            val updated = transform(current)
            prefs[Keys.SG_MASTER_ENABLED] = updated.masterEnabled
            prefs[Keys.SG_URL_CHECK_ENABLED] = updated.urlCheckEnabled
            prefs[Keys.SG_TEXT_SCORING_ENABLED] = updated.textScoringEnabled
            prefs[Keys.SG_SEARCH_GUARD_ENABLED] = updated.searchGuardEnabled
            prefs[Keys.SG_VISUAL_CHECK_ENABLED] = updated.visualCheckEnabled
            prefs[Keys.SG_SENSITIVITY] = updated.sensitivity.name
            prefs[Keys.SG_MONITOR_ALL_APPS] = updated.monitorAllApps
            prefs[Keys.SG_ALLOWED_APPS] = updated.allowedApps
        }
    }

    suspend fun incrementBlockedCountersBy(delta: Long) {
        if (delta <= 0L) return
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
            prefs[Keys.BLOCKED_TODAY] = previousToday + delta
            prefs[Keys.TOTAL_BLOCKED] = previousTotal + delta
        }
    }
}
