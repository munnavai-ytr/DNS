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
import com.example.dns.DomainCheckSummary
import com.example.dns.DomainFilterEngine
import com.example.dns.DomainVerdict
import com.example.dns.GeminiDomainClassifier
import com.example.dns.UnsurePolicy
import com.example.vpn.DnsVpnService
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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

    private val v2ToolStateFlow =
        combine(
            repository.safeRuleCount,
            aiTestState,
            domainCheckState,
        ) { safeCount, aiTest, domainCheck ->
            V2ToolSlice(safeCount, aiTest, domainCheck)
        }

    val uiState: StateFlow<MainUiState> =
        combine(baseStateFlow, secondaryStateFlow, v2ToolStateFlow) { base, secondary, v2 ->
                MainUiState(
                    isReady = true,
                    onboardingCompleted = base.stats.onboardingCompleted,
                    isVpnRunning = base.running,
                    vpnDesiredEnabled = base.stats.vpnDesiredEnabled,
                    blockedToday = base.stats.blockedToday,
                    totalBlocked = base.stats.totalBlocked,
                    baseRuleCount = base.baseCount,
                    safeRuleCount = v2.safeCount,
                    cacheSize = secondary.cacheCount,
                    aiVerificationEnabled = base.stats.aiVerificationEnabled,
                    geminiApiKey = base.stats.geminiApiKey,
                    geminiModel = base.stats.geminiModel,
                    whenUnsure = base.stats.whenUnsure,
                    logs = base.logs,
                    customBlockedRules = base.customBlocked,
                    whitelistRules = secondary.whitelist,
                    selfTestState = secondary.selfTest,
                    aiTestState = v2.aiTest,
                    domainCheckState = v2.domainCheck,
                    permissions = secondary.perms,
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
    }

    fun startVpn() {
        viewModelScope.launch {
            repository.setVpnDesiredEnabled(true)
            DnsVpnService.start(app)
        }
    }

    fun stopVpn() {
        viewModelScope.launch {
            repository.setVpnDesiredEnabled(false)
            DnsVpnService.stop(app)
            selfTestState.value = SelfTestState.Idle
        }
    }

    fun completeOnboarding(activateShield: Boolean) {
        viewModelScope.launch {
            repository.setOnboardingCompleted(true)
            if (activateShield && VpnService.prepare(app) == null) {
                repository.setVpnDesiredEnabled(true)
                DnsVpnService.start(app)
            }
        }
    }

    fun runSelfTest() {
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

    private data class V2ToolSlice(
        val safeCount: Int,
        val aiTest: AiTestState,
        val domainCheck: DomainCheckState,
    )
}
