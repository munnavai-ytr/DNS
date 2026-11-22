package com.example.data.repository

import android.content.Context
import com.example.data.local.BlockedLogEntity
import com.example.data.local.CustomRuleEntity
import com.example.data.local.DnsShieldDatabase
import com.example.data.local.ShieldPreferences
import com.example.data.local.ShieldStatsSnapshot
import com.example.dns.BlockReason
import com.example.dns.ClassificationResult
import com.example.dns.DecisionSettings
import com.example.dns.DnsDecisionEngine
import com.example.dns.DomainCheckSummary
import com.example.dns.DomainFilterEngine
import com.example.dns.GeminiDomainClassifier
import com.example.dns.UnsurePolicy
import com.example.dns.VerdictCache
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ShieldRepository(
    context: Context,
    private val database: DnsShieldDatabase,
    private val preferences: ShieldPreferences,
    val filterEngine: DomainFilterEngine,
    val verdictCache: VerdictCache,
    private val classifier: GeminiDomainClassifier,
) {
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val currentApiKeyRef = AtomicReference("")
    private val currentModelRef = AtomicReference(GeminiDomainClassifier.DEFAULT_MODEL)
    private val currentDecisionSettingsRef = AtomicReference(DecisionSettings())

    val decisionEngine: DnsDecisionEngine =
        DnsDecisionEngine(
            filterEngine = filterEngine,
            verdictCache = verdictCache,
            classifier = classifier,
            settingsProvider = { currentDecisionSettingsRef.get() },
        )

    private val _isVpnRunning = MutableStateFlow(false)
    val isVpnRunning: StateFlow<Boolean> = _isVpnRunning.asStateFlow()

    private val _baseRuleCount = MutableStateFlow(0)
    val baseRuleCount: StateFlow<Int> = _baseRuleCount.asStateFlow()

    private val _safeRuleCount = MutableStateFlow(0)
    val safeRuleCount: StateFlow<Int> = _safeRuleCount.asStateFlow()

    val cacheSize: StateFlow<Int> = verdictCache.cacheSize

    val statsSnapshot: Flow<ShieldStatsSnapshot> = preferences.snapshotFlow

    val recentLogs: Flow<List<BlockedLogEntity>> = database.observeRecentLogs()

    val customBlockedRules: Flow<List<CustomRuleEntity>> =
        database.observeAllRules().map { list -> list.filter { !it.isWhitelist } }

    val whitelistRules: Flow<List<CustomRuleEntity>> =
        database.observeAllRules().map { list -> list.filter { it.isWhitelist } }

    init {
        repoScope.launch {
            _baseRuleCount.value = filterEngine.loadBaseBlocklistFromAssets(context)
            _safeRuleCount.value = filterEngine.loadSafeDomainsFromAssets(context)
            verdictCache.loadFromDisk()
        }
        repoScope.launch {
            database.observeAllRules().collect { rules ->
                val customBlocked = rules.filter { !it.isWhitelist }.map { it.domain }
                val whitelisted = rules.filter { it.isWhitelist }.map { it.domain }
                filterEngine.updateDynamicRules(customBlocked, whitelisted)
            }
        }
        repoScope.launch {
            preferences.snapshotFlow.collect { snapshot ->
                currentApiKeyRef.set(snapshot.geminiApiKey)
                currentModelRef.set(snapshot.geminiModel)
                currentDecisionSettingsRef.set(
                    DecisionSettings(
                        aiVerificationEnabled = snapshot.aiVerificationEnabled,
                        hasApiKey = snapshot.geminiApiKey.isNotBlank(),
                        whenUnsure = snapshot.whenUnsure,
                    )
                )
            }
        }
    }

    fun getCurrentApiKey(): String = currentApiKeyRef.get()

    fun getCurrentModel(): String = currentModelRef.get()

    suspend fun ensureRulesLoaded(context: Context) {
        if (_baseRuleCount.value == 0) {
            _baseRuleCount.value = filterEngine.loadBaseBlocklistFromAssets(context)
        }
        if (_safeRuleCount.value == 0) {
            _safeRuleCount.value = filterEngine.loadSafeDomainsFromAssets(context)
        }
        val rules = database.getAllRulesOnce()
        filterEngine.updateDynamicRules(
            customBlockedDomains = rules.filter { !it.isWhitelist }.map { it.domain },
            whitelistedDomains = rules.filter { it.isWhitelist }.map { it.domain },
        )
    }

    fun updateVpnRunning(running: Boolean) {
        _isVpnRunning.value = running
    }

    suspend fun setVpnDesiredEnabled(enabled: Boolean) {
        preferences.setVpnDesiredEnabled(enabled)
    }

    suspend fun isVpnDesiredEnabled(): Boolean = preferences.isVpnDesiredEnabled()

    suspend fun setOnboardingCompleted(completed: Boolean) {
        preferences.setOnboardingCompleted(completed)
    }

    suspend fun setAiVerificationEnabled(enabled: Boolean) {
        preferences.setAiVerificationEnabled(enabled)
    }

    suspend fun setGeminiApiKey(apiKey: String) {
        preferences.setGeminiApiKey(apiKey)
    }

    suspend fun setGeminiModel(model: String) {
        preferences.setGeminiModel(model)
    }

    suspend fun setWhenUnsure(policy: UnsurePolicy) {
        preferences.setWhenUnsure(policy)
    }

    fun clearVerdictCache() {
        verdictCache.clear()
    }

    suspend fun testAiConnection(): Pair<ClassificationResult, Long> {
        val startNs = System.nanoTime()
        val result = classifier.classify("wikipedia.org")
        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
        return result to elapsedMs
    }

    suspend fun inspectDomainManually(rawDomain: String): DomainCheckSummary {
        return decisionEngine.inspectDomainManually(rawDomain)
    }

    suspend fun recordBlockedQuery(
        domain: String,
        queryType: String,
        reason: BlockReason,
        durationMs: Long,
    ) {
        preferences.incrementBlockedCounters()
        database.insertAndTrimLog(
            BlockedLogEntity(
                domain = domain,
                queryType = queryType,
                reason = reason.label,
                durationMs = durationMs,
                timestamp = System.currentTimeMillis(),
            )
        )
    }

    suspend fun addCustomRule(rawInput: String, isWhitelist: Boolean): Boolean {
        val normalized = DomainFilterEngine.normalizeDomain(rawInput) ?: return false
        database.upsertRule(
            CustomRuleEntity(
                domain = normalized,
                isWhitelist = isWhitelist,
                createdAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    suspend fun removeCustomRule(domain: String) {
        database.deleteRuleByDomain(domain)
    }

    suspend fun clearLogs() {
        database.clearLogs()
    }
}
