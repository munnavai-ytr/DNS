package com.example.data.repository

import android.content.Context
import com.example.data.local.BlockedLogEntity
import com.example.data.local.CustomRuleEntity
import com.example.data.local.DnsShieldDatabase
import com.example.data.local.ShieldPreferences
import com.example.data.local.ShieldStatsSnapshot
import com.example.dns.DomainFilterEngine
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
) {
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isVpnRunning = MutableStateFlow(false)
    val isVpnRunning: StateFlow<Boolean> = _isVpnRunning.asStateFlow()

    private val _baseRuleCount = MutableStateFlow(0)
    val baseRuleCount: StateFlow<Int> = _baseRuleCount.asStateFlow()

    val statsSnapshot: Flow<ShieldStatsSnapshot> = preferences.snapshotFlow

    val recentLogs: Flow<List<BlockedLogEntity>> = database.observeRecentLogs()

    val customBlockedRules: Flow<List<CustomRuleEntity>> =
        database.observeAllRules().map { list -> list.filter { !it.isWhitelist } }

    val whitelistRules: Flow<List<CustomRuleEntity>> =
        database.observeAllRules().map { list -> list.filter { it.isWhitelist } }

    init {
        repoScope.launch {
            val loadedCount = filterEngine.loadBaseBlocklistFromAssets(context)
            _baseRuleCount.value = loadedCount
        }
        repoScope.launch {
            database.observeAllRules().collect { rules ->
                val customBlocked = rules.filter { !it.isWhitelist }.map { it.domain }
                val whitelisted = rules.filter { it.isWhitelist }.map { it.domain }
                filterEngine.updateDynamicRules(customBlocked, whitelisted)
            }
        }
    }

    suspend fun ensureRulesLoaded(context: Context) {
        if (_baseRuleCount.value == 0) {
            _baseRuleCount.value = filterEngine.loadBaseBlocklistFromAssets(context)
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

    suspend fun recordBlockedQuery(domain: String, queryType: String) {
        preferences.incrementBlockedCounters()
        database.insertAndTrimLog(
            BlockedLogEntity(
                domain = domain,
                queryType = queryType,
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
