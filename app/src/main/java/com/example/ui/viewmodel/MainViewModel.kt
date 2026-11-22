package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.VpnService
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DnsShieldApplication
import com.example.data.local.BlockedLogEntity
import com.example.data.local.CustomRuleEntity
import com.example.data.local.ShieldStatsSnapshot
import com.example.data.repository.ProtectionUiSnapshot
import com.example.dns.DomainCheckSummary
import com.example.dns.DomainFilterEngine
import com.example.dns.DomainVerdict
import com.example.dns.GeminiDomainClassifier
import com.example.dns.UnsurePolicy
import com.example.protection.PinActionResult
import com.example.protection.ProtectionChecklistState
import com.example.protection.ProtectionLevel
import com.example.protection.ProtectionMode
import com.example.protection.ProtectionNotificationHelper
import com.example.protection.TamperProtectionState
import com.example.protection.WaitDurationOption
import com.example.protection.WaitTimeUpdateResult
import com.example.vpn.DnsVpnService
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface SelfTestState {
    data object Idle : SelfTestState

    data object Running : SelfTestState

    data class Working(val resolvedIp: String, val latencyMs: Long) : SelfTestState

    data class NotWorking(val reason: String) : SelfTestState
}

sealed interface AiTestState {
    data object Idle : AiTestState

    data object Running : AiTestState

    data class Result(val message: String, val isSuccess: Boolean) : AiTestState
}

sealed interface DomainCheckState {
    data object Idle : DomainCheckState

    data object Checking : DomainCheckState

    data class Result(val summary: DomainCheckSummary) : DomainCheckState
}

data class SystemPermissionState(
    val hasVpnPermission: Boolean = false,
    val isBatteryOptimizationIgnored: Boolean = false,
)

data class MainUiState(
    val isReady: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val wizardPromptedAfterOnboarding: Boolean = false,
    val isVpnRunning: Boolean = false,
    val vpnDesiredEnabled: Boolean = false,
    val blockedToday: Long = 0L,
    val totalBlocked: Long = 0L,
    val baseRuleCount: Int = 0,
    val safeRuleCount: Int = 0,
    val cacheSize: Int = 0,
    val aiVerificationEnabled: Boolean = true,
    val geminiApiKey: String = "",
    val geminiModel: String = GeminiDomainClassifier.DEFAULT_MODEL,
    val whenUnsure: UnsurePolicy = UnsurePolicy.ALLOW,
    val logs: List<BlockedLogEntity> = emptyList(),
    val customBlockedRules: List<CustomRuleEntity> = emptyList(),
    val whitelistRules: List<CustomRuleEntity> = emptyList(),
    val selfTestState: SelfTestState = SelfTestState.Idle,
    val aiTestState: AiTestState = AiTestState.Idle,
    val domainCheckState: DomainCheckState = DomainCheckState.Idle,
    val permissions: SystemPermissionState = SystemPermissionState(),
    val tamperState: TamperProtectionState = TamperProtectionState(),
    val checklistState: ProtectionChecklistState = ProtectionChecklistState(),
    val protectionLevel: ProtectionLevel = ProtectionLevel.OFF,
    val ruleFeedbackMessage: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as DnsShieldApplication
    private val repository = app.repository

    private val selfTestState = MutableStateFlow<SelfTestState>(SelfTestState.Idle)
    private val aiTestState = MutableStateFlow<AiTestState>(AiTestState.Idle)
    private val domainCheckState = MutableStateFlow<DomainCheckState>(DomainCheckState.Idle)
    private val permissionState = MutableStateFlow(readSystemPermissions(application))
    private val ruleFeedback = MutableStateFlow<String?>(null)

    init {
        // Low-overhead timer refresh: only wakes every 5 seconds when a timer is active,
        // avoiding unnecessary DataStore/Notification churn.
        viewModelScope.launch {
            var wasUnlockActive = false
            while (isActive) {
                val snap = repository.protectionUiSnapshot.value
                val state = snap.tamperState
                val hasActiveTimer =
                    state.isCountdownActive || state.isUnlockWindowActive || state.isLockedOut
                if (hasActiveTimer) {
                    val minRem =
                        listOfNotNull(
                                state.countdownRemainingMs.takeIf { state.isCountdownActive },
                                state.unlockWindowRemainingMs.takeIf { state.isUnlockWindowActive },
                                state.lockoutRemainingMs.takeIf { state.isLockedOut },
                            )
                            .minOrNull() ?: 5000L
                    delay(minRem.coerceIn(1000L, 5000L))
                    val updated =
                        repository.refreshProtectionState(
                            restartVpnIfProtectedAndDown = wasUnlockActive
                        )
                    wasUnlockActive = updated.tamperState.isUnlockWindowActive
                } else {
                    if (wasUnlockActive) {
                        repository.refreshProtectionState(restartVpnIfProtectedAndDown = true)
                    }
                    wasUnlockActive = false
                    delay(5000L)
                }
            }
        }
    }

    private val baseStateFlow =
        combine(
            repository.statsSnapshot,
            repository.isVpnRunning,
            repository.baseRuleCount,
            repository.recentLogs,
            repository.customBlockedRules,
        ) { stats, running, baseCount, logs, customBlocked ->
            BaseSlice(stats, running, baseCount, logs, customBlocked)
        }

    private val secondaryStateFlow =
        combine(
            repository.whitelistRules,
            selfTestState,
            permissionState,
            ruleFeedback,
            repository.cacheSize,
        ) { whitelist, selfTest, perms, feedback, cacheCount ->
            SecondarySlice(whitelist, selfTest, perms, feedback, cacheCount)
        }

    private val v2AndV3SliceFlow =
        combine(
            repository.safeRuleCount,
            aiTestState,
            domainCheckState,
            repository.protectionUiSnapshot,
        ) { safeCount, aiTest, domainCheck, protection ->
            V2AndV3Slice(safeCount, aiTest, domainCheck, protection)
        }

    val uiState: StateFlow<MainUiState> =
        combine(baseStateFlow, secondaryStateFlow, v2AndV3SliceFlow) { base, secondary, extra ->
                MainUiState(
                    isReady = true,
                    onboardingCompleted = base.stats.onboardingCompleted,
                    wizardPromptedAfterOnboarding =
                        base.stats.wizardPromptedAfterOnboarding ||
                            extra.protection.wizardPromptedAfterOnboarding,
                    isVpnRunning = base.running,
                    vpnDesiredEnabled = base.stats.vpnDesiredEnabled,
                    blockedToday = base.stats.blockedToday,
                    totalBlocked = base.stats.totalBlocked,
                    baseRuleCount = base.baseCount,
                    safeRuleCount = extra.safeCount,
                    cacheSize = secondary.cacheCount,
                    aiVerificationEnabled = base.stats.aiVerificationEnabled,
                    geminiApiKey = base.stats.geminiApiKey,
                    geminiModel = base.stats.geminiModel,
                    whenUnsure = base.stats.whenUnsure,
                    logs = base.logs,
                    customBlockedRules = base.customBlocked,
                    whitelistRules = secondary.whitelist,
                    selfTestState = secondary.selfTest,
                    aiTestState = extra.aiTest,
                    domainCheckState = extra.domainCheck,
                    permissions = secondary.perms,
                    tamperState = extra.protection.tamperState,
                    checklistState = extra.protection.checklist,
                    protectionLevel = extra.protection.protectionLevel,
                    ruleFeedbackMessage = secondary.feedback,
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = MainUiState(isReady = false),
            )

    fun refreshSystemPermissions() {
        permissionState.value = readSystemPermissions(app)
        viewModelScope.launch { repository.refreshProtectionState(restartVpnIfProtectedAndDown = false) }
    }

    fun startVpn() {
        viewModelScope.launch {
            if (repository.getCachedProtectionMode() == ProtectionMode.PRIVATE_DNS) {
                ruleFeedback.value =
                    "Private DNS mode is active. Switch to VPN mode inside an unlock window to run the local VPN."
                return@launch
            }
            repository.setVpnDesiredEnabled(true)
            DnsVpnService.start(app)
        }
    }

    fun stopVpn() {
        viewModelScope.launch {
            if (repository.isGuardBlockingNow()) {
                ruleFeedback.value =
                    if (repository.getCachedLockType().name == "STRICT_LOCK") {
                        "Strict Lock is active. VPN cannot be turned off."
                    } else {
                        "Tamper protection is ON. Request to turn off protection first."
                    }
                return@launch
            }
            repository.setVpnDesiredEnabled(false)
            DnsVpnService.stop(app)
            selfTestState.value = SelfTestState.Idle
        }
    }

    fun completeOnboarding(activateShield: Boolean) {
        viewModelScope.launch {
            repository.setOnboardingCompleted(true)
            if (
                activateShield &&
                    repository.getCachedProtectionMode() == ProtectionMode.VPN &&
                    VpnService.prepare(app) == null
            ) {
                repository.setVpnDesiredEnabled(true)
                DnsVpnService.start(app)
            }
            repository.refreshProtectionState(restartVpnIfProtectedAndDown = false)
        }
    }

    fun markWizardPromptedAfterOnboarding() {
        viewModelScope.launch { repository.markWizardPromptedAfterOnboarding() }
    }

    suspend fun selectProtectionMode(mode: ProtectionMode): WaitTimeUpdateResult {
        return repository.setProtectionMode(mode)
    }

    suspend fun setInitialPin(pin: String): PinActionResult {
        return repository.setInitialPin(pin)
    }

    suspend fun activateStrictLock(confirmationPhrase: String): PinActionResult {
        return repository.activateStrictLock(confirmationPhrase)
    }

    suspend fun changePin(currentPin: String, newPin: String): PinActionResult {
        return repository.changePin(currentPin, newPin)
    }

    suspend fun selectWaitDuration(option: WaitDurationOption): WaitTimeUpdateResult {
        return repository.setWaitDuration(option)
    }

    fun confirmWaitDurationStep() {
        viewModelScope.launch { repository.confirmWaitDurationSelection() }
    }

    suspend fun requestTurnOffProtection(pin: String): PinActionResult {
        return repository.requestTurnOffProtection(pin)
    }

    suspend fun confirmUnlockWindow(pin: String): PinActionResult {
        return repository.confirmUnlockWindow(pin)
    }

    fun cancelTurnOffRequest() {
        viewModelScope.launch {
            repository.cancelTurnOffRequest()
            ruleFeedback.value = "Turn-off request cancelled"
        }
    }

    fun lockProtectionAgain() {
        viewModelScope.launch {
            repository.lockProtectionAgain()
            ruleFeedback.value = "Tamper protection locked again"
        }
    }

    fun deactivateProtectionInUnlockWindow() {
        viewModelScope.launch {
            val ok = repository.deactivateProtectionInUnlockWindow()
            ruleFeedback.value =
                if (ok) "Tamper protection turned OFF"
                else "Cannot deactivate protection outside an active unlock window"
        }
    }

    fun formatActionError(result: PinActionResult): String? {
        return when (result) {
            PinActionResult.Success -> null
            is PinActionResult.InvalidPin ->
                "Incorrect PIN. ${result.attemptsRemaining} attempt(s) remaining before lockout."
            is PinActionResult.LockedOut ->
                "PIN locked for ${ProtectionNotificationHelper.formatDurationShort(result.remainingMs)}."
            is PinActionResult.Rejected -> result.reason
        }
    }

    fun runSelfTest() {
        if (repository.getCachedProtectionMode() == ProtectionMode.PRIVATE_DNS) {
            selfTestState.value =
                SelfTestState.NotWorking(
                    "Private DNS mode does not run the local VPN. Verify family.cloudflare-dns.com in your Protection Checklist."
                )
            return
        }
        if (!repository.isVpnRunning.value) {
            selfTestState.value =
                SelfTestState.NotWorking("DNS Shield is OFF. Turn it ON to test.")
            return
        }

        selfTestState.value = SelfTestState.Running
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    val startNs = System.nanoTime()
                    val resolved =
                        withTimeoutOrNull(3500L) {
                            runCatching {
                                    InetAddress.getAllByName(DomainFilterEngine.SELF_TEST_DOMAIN)
                                }
                                .getOrNull()
                        }
                    val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)

                    if (resolved.isNullOrEmpty()) {
                        SelfTestState.NotWorking(
                            "DNS query timed out or failed to reach the local filter."
                        )
                    } else {
                        val blockedAddr =
                            resolved.firstOrNull { addr ->
                                val host = addr.hostAddress ?: ""
                                addr.isAnyLocalAddress ||
                                    host == "0.0.0.0" ||
                                    host == "::" ||
                                    host == "0:0:0:0:0:0:0:0"
                            }
                        if (blockedAddr != null) {
                            SelfTestState.Working(
                                resolvedIp = blockedAddr.hostAddress ?: "0.0.0.0",
                                latencyMs = elapsedMs,
                            )
                        } else {
                            val firstIp = resolved.first().hostAddress ?: "unknown"
                            SelfTestState.NotWorking("Resolved to $firstIp instead of 0.0.0.0.")
                        }
                    }
                }
            selfTestState.value = result
        }
    }

    fun setAiVerificationEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setAiVerificationEnabled(enabled) }
    }

    fun saveGeminiApiKey(apiKey: String) {
        viewModelScope.launch {
            repository.setGeminiApiKey(apiKey)
            ruleFeedback.value =
                if (apiKey.isBlank()) "Gemini API key cleared" else "Gemini API key saved"
        }
    }

    fun saveGeminiModel(model: String) {
        viewModelScope.launch {
            repository.setGeminiModel(model)
            ruleFeedback.value = "Model updated"
        }
    }

    fun setWhenUnsure(policy: UnsurePolicy) {
        viewModelScope.launch { repository.setWhenUnsure(policy) }
    }

    fun clearVerdictCache() {
        repository.clearVerdictCache()
        ruleFeedback.value = "Verdict cache cleared"
    }

    fun testAiConnection() {
        aiTestState.value = AiTestState.Running
        viewModelScope.launch {
            val (result, elapsedMs) = repository.testAiConnection()
            aiTestState.value =
                if (result.errorMessage != null) {
                    AiTestState.Result(message = result.errorMessage, isSuccess = false)
                } else {
                    AiTestState.Result(
                        message = "Working, ${result.verdict.name}, $elapsedMs ms",
                        isSuccess = result.verdict != DomainVerdict.UNSURE,
                    )
                }
        }
    }

    fun checkDomain(rawDomain: String) {
        if (rawDomain.isBlank()) {
            ruleFeedback.value = "Enter a domain to check"
            return
        }
        domainCheckState.value = DomainCheckState.Checking
        viewModelScope.launch {
            val summary = withContext(Dispatchers.IO) { repository.inspectDomainManually(rawDomain) }
            domainCheckState.value = DomainCheckState.Result(summary)
        }
    }

    fun addCustomRule(rawDomain: String, isWhitelist: Boolean) {
        viewModelScope.launch {
            val added = repository.addCustomRule(rawDomain, isWhitelist)
            ruleFeedback.value =
                if (added) {
                    if (isWhitelist) "Added to whitelist" else "Added to custom blocklist"
                } else {
                    "Enter a valid domain (e.g. example.com)"
                }
        }
    }

    fun removeCustomRule(domain: String) {
        viewModelScope.launch {
            repository.removeCustomRule(domain)
            ruleFeedback.value = "Removed $domain"
        }
    }

    fun whitelistFromLog(domain: String) {
        viewModelScope.launch {
            repository.addCustomRule(domain, isWhitelist = true)
            ruleFeedback.value = "Whitelisted $domain"
        }
    }

    fun clearLogs() {
        viewModelScope.launch { repository.clearLogs() }
    }

    fun showFeedback(message: String) {
        ruleFeedback.value = message
    }

    fun clearRuleFeedback() {
        ruleFeedback.value = null
    }

    private fun readSystemPermissions(context: Context): SystemPermissionState {
        val vpnPrepared = runCatching { VpnService.prepare(context) == null }.getOrDefault(false)
        val powerManager = context.getSystemService(PowerManager::class.java)
        val batteryIgnored =
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        return SystemPermissionState(
            hasVpnPermission = vpnPrepared,
            isBatteryOptimizationIgnored = batteryIgnored,
        )
    }

    private data class BaseSlice(
        val stats: ShieldStatsSnapshot,
        val running: Boolean,
        val baseCount: Int,
        val logs: List<BlockedLogEntity>,
        val customBlocked: List<CustomRuleEntity>,
    )

    private data class SecondarySlice(
        val whitelist: List<CustomRuleEntity>,
        val selfTest: SelfTestState,
        val perms: SystemPermissionState,
        val feedback: String?,
        val cacheCount: Int,
    )

    private data class V2AndV3Slice(
        val safeCount: Int,
        val aiTest: AiTestState,
        val domainCheck: DomainCheckState,
        val protection: ProtectionUiSnapshot,
    )
}
