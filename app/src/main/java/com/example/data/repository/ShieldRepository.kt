package com.example.data.repository

import android.content.Context
import android.os.SystemClock
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
import com.example.protection.LockType
import com.example.protection.PinActionResult
import com.example.protection.ProtectionChecklistState
import com.example.protection.ProtectionLevel
import com.example.protection.ProtectionMode
import com.example.protection.ProtectionNotificationHelper
import com.example.protection.ProtectionSystemChecker
import com.example.protection.ProtectionWatchdogScheduler
import com.example.protection.TamperProtectionEngine
import com.example.protection.TamperProtectionState
import com.example.protection.WaitDurationOption
import com.example.protection.WaitTimeUpdateResult
import com.example.vpn.DnsVpnService
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ProtectionUiSnapshot(
    val tamperState: TamperProtectionState = TamperProtectionState(),
    val checklist: ProtectionChecklistState = ProtectionChecklistState(),
    val protectionLevel: ProtectionLevel = ProtectionLevel.OFF,
    val wizardPromptedAfterOnboarding: Boolean = false,
)

class ShieldRepository(
    context: Context,
    private val database: DnsShieldDatabase,
    private val preferences: ShieldPreferences,
    val filterEngine: DomainFilterEngine,
    val verdictCache: VerdictCache,
    private val classifier: GeminiDomainClassifier,
) {
    private val appContext = context.applicationContext
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val currentApiKeyRef = AtomicReference("")
    private val currentModelRef = AtomicReference(GeminiDomainClassifier.DEFAULT_MODEL)
    private val currentDecisionSettingsRef = AtomicReference(DecisionSettings())

    val tamperEngine = TamperProtectionEngine(store = preferences)

    // Batched log & counter buffer (flush every 5s or 20 items)
    private val logBatchMutex = Mutex()
    private val pendingLogs = ArrayList<BlockedLogEntity>(LOG_BATCH_LIMIT)
    private var pendingCounterDelta = 0L
    private var flushTimerJob: Job? = null

    // Fast volatile cache so AccessibilityService and DnsVpnService never block on disk I/O
    @Volatile private var cachedProtectionActivated = false
    @Volatile private var cachedProtectionMode = ProtectionMode.VPN
    @Volatile private var cachedLockType = LockType.NONE
    @Volatile private var cachedUnlockEndElapsedMs = 0L

    val decisionEngine: DnsDecisionEngine =
        DnsDecisionEngine(
            filterEngine = filterEngine,
            verdictCache = verdictCache,
            classifier = classifier,
            settingsProvider = { currentDecisionSettingsRef.get() },
        )

    private val _isVpnRunning = MutableStateFlow(false)
    val isVpnRunning: StateFlow<Boolean> = _isVpnRunning.asStateFlow()

    private val _isVpnAlwaysOn = MutableStateFlow(false)

    private val _protectionUiSnapshot = MutableStateFlow(ProtectionUiSnapshot())
    val protectionUiSnapshot: StateFlow<ProtectionUiSnapshot> = _protectionUiSnapshot.asStateFlow()

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
            _baseRuleCount.value = filterEngine.loadBaseBlocklistFromAssets(appContext)
            _safeRuleCount.value = filterEngine.loadSafeDomainsFromAssets(appContext)
            verdictCache.loadFromDisk()
            refreshProtectionState(restartVpnIfProtectedAndDown = false)
            ProtectionWatchdogScheduler.ensurePeriodicWatchdog(appContext)
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

    fun getCachedProtectionMode(): ProtectionMode = cachedProtectionMode

    fun getCachedLockType(): LockType = cachedLockType

    fun isGuardBlockingNowSync(): Boolean {
        if (!cachedProtectionActivated) return false
        if (cachedLockType == LockType.STRICT_LOCK) return true
        val unlockEnd = cachedUnlockEndElapsedMs
        return unlockEnd == 0L || SystemClock.elapsedRealtime() >= unlockEnd
    }

    suspend fun isGuardBlockingNow(): Boolean {
        val state = tamperEngine.getState()
        updateFastGuardCache(state)
        return state.isGuardActive
    }

    suspend fun refreshProtectionState(
        restartVpnIfProtectedAndDown: Boolean = false
    ): ProtectionUiSnapshot {
        DnsVpnService.refreshFlagsIfRunning()
        val rawBeforeSync = tamperEngine.getRawState()
        val modeBefore = ProtectionMode.fromStored(rawBeforeSync.protectionModeName)
        val lockBefore = LockType.fromStored(rawBeforeSync.lockTypeName)

        val checklistBefore =
            buildChecklist(
                mode = modeBefore,
                modeConfigured =
                    rawBeforeSync.modeExplicitlyChosen || rawBeforeSync.protectionActivated,
                lockType = lockBefore,
                pinConfigured = rawBeforeSync.pinHashBase64.isNotBlank(),
                waitConfigured =
                    rawBeforeSync.waitDurationExplicitlyChosen ||
                        rawBeforeSync.protectionActivated ||
                        rawBeforeSync.pinHashBase64.isNotBlank(),
            )
        val syncedState = tamperEngine.syncCoreProtectionActivation(checklistBefore.isCoreStepsComplete)
        val rawAfterSync = tamperEngine.getRawState()

        val checklist =
            buildChecklist(
                mode = syncedState.protectionMode,
                modeConfigured = syncedState.modeConfigured,
                lockType = syncedState.lockType,
                pinConfigured = syncedState.isPinConfigured,
                waitConfigured =
                    rawAfterSync.waitDurationExplicitlyChosen ||
                        syncedState.protectionSetupDone ||
                        syncedState.isPinConfigured,
            )
        val level = tamperEngine.computeProtectionLevel(checklist, syncedState)
        val snapshot =
            ProtectionUiSnapshot(
                tamperState = syncedState,
                checklist = checklist,
                protectionLevel = level,
                wizardPromptedAfterOnboarding = rawAfterSync.wizardPromptedAfterOnboarding,
            )

        updateFastGuardCache(syncedState)
        _protectionUiSnapshot.value = snapshot

        ProtectionNotificationHelper.syncTimerNotification(appContext, syncedState)
        ProtectionNotificationHelper.syncWeakenedNotification(appContext, level, checklist)
        ProtectionWatchdogScheduler.ensurePeriodicWatchdog(appContext)
        ProtectionWatchdogScheduler.syncBoundaryTimer(appContext, syncedState)
        DnsVpnService.refreshNotificationIfRunning(appContext)

        if (
            restartVpnIfProtectedAndDown &&
                syncedState.protectionMode == ProtectionMode.VPN &&
                syncedState.isGuardActive &&
                !checklist.vpnRunning &&
                ProtectionSystemChecker.hasVpnPermission(appContext)
        ) {
            preferences.setVpnDesiredEnabled(true)
            DnsVpnService.start(appContext)
        }
        return snapshot
    }

    private fun buildChecklist(
        mode: ProtectionMode,
        modeConfigured: Boolean,
        lockType: LockType,
        pinConfigured: Boolean,
        waitConfigured: Boolean,
    ): ProtectionChecklistState {
        return ProtectionChecklistState(
            protectionMode = mode,
            modeConfigured = modeConfigured,
            lockType = lockType,
            pinConfigured = pinConfigured,
            waitDurationConfigured = waitConfigured,
            deviceAdminEnabled = ProtectionSystemChecker.isDeviceAdminActive(appContext),
            accessibilityEnabled = ProtectionSystemChecker.isAccessibilityGuardEnabled(appContext),
            vpnRunning = _isVpnRunning.value,
            vpnAlwaysOn = _isVpnAlwaysOn.value,
            privateDnsConfigured = ProtectionSystemChecker.isPrivateDnsConfigured(appContext),
            batteryOptimizationIgnored = ProtectionSystemChecker.isBatteryOptimizationIgnored(appContext),
        )
    }

    private fun updateFastGuardCache(state: TamperProtectionState) {
        cachedProtectionActivated = state.protectionSetupDone
        cachedProtectionMode = state.protectionMode
        cachedLockType = state.lockType
        cachedUnlockEndElapsedMs =
            if (
                state.lockType == LockType.TRUSTED_PIN &&
                    state.isUnlockWindowActive &&
                    state.unlockWindowRemainingMs > 0L
            ) {
                SystemClock.elapsedRealtime() + state.unlockWindowRemainingMs
            } else {
                0L
            }
    }

    suspend fun markWizardPromptedAfterOnboarding() {
        tamperEngine.markWizardPromptedAfterOnboarding()
        refreshProtectionState(restartVpnIfProtectedAndDown = false)
    }

    suspend fun setProtectionMode(mode: ProtectionMode): WaitTimeUpdateResult {
        val result = tamperEngine.setProtectionMode(mode)
        if (result == WaitTimeUpdateResult.Success && mode == ProtectionMode.PRIVATE_DNS) {
            // Private DNS mode runs no local VPN
            preferences.setVpnDesiredEnabled(false)
            DnsVpnService.stop(appContext)
        }
        refreshProtectionState(restartVpnIfProtectedAndDown = false)
        return result
    }

    suspend fun setInitialPin(pin: String): PinActionResult {
        val result = tamperEngine.setInitialPin(pin)
        refreshProtectionState(restartVpnIfProtectedAndDown = false)
        return result
    }

    suspend fun activateStrictLock(confirmationPhrase: String): PinActionResult {
        val result = tamperEngine.activateStrictLock(confirmationPhrase)
        refreshProtectionState(restartVpnIfProtectedAndDown = false)
        return result
    }

    suspend fun changePin(currentPin: String, newPin: String): PinActionResult {
        val result = tamperEngine.changePin(currentPin, newPin)
        refreshProtectionState(restartVpnIfProtectedAndDown = false)
        return result
    }

    suspend fun setWaitDuration(option: WaitDurationOption): WaitTimeUpdateResult {
        val result = tamperEngine.setWaitDuration(option)
        refreshProtectionState(restartVpnIfProtectedAndDown = false)
        return result
    }

    suspend fun confirmWaitDurationSelection() {
        tamperEngine.confirmWaitDurationSelection()
        refreshProtectionState(restartVpnIfProtectedAndDown = false)
    }

    suspend fun requestTurnOffProtection(pin: String): PinActionResult {
        val result = tamperEngine.requestTurnOffProtection(pin)
        refreshProtectionState(restartVpnIfProtectedAndDown = false)
        return result
    }

    suspend fun confirmUnlockWindow(pin: String): PinActionResult {
        val result = tamperEngine.confirmUnlockWindow(pin)
        refreshProtectionState(restartVpnIfProtectedAndDown = false)
        return result
    }

    suspend fun cancelTurnOffRequest() {
        tamperEngine.cancelTurnOffRequest()
        refreshProtectionState(restartVpnIfProtectedAndDown = false)
    }

    suspend fun lockProtectionAgain() {
        tamperEngine.lockAgainNow()
        refreshProtectionState(restartVpnIfProtectedAndDown = true)
    }

    suspend fun deactivateProtectionInUnlockWindow(): Boolean {
        val ok = tamperEngine.deactivateProtectionInUnlockWindow()
        refreshProtectionState(restartVpnIfProtectedAndDown = false)
        return ok
    }

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
        val changed = _isVpnRunning.value != running
        _isVpnRunning.value = running
        if (changed) {
            repoScope.launch { refreshProtectionState(restartVpnIfProtectedAndDown = false) }
        }
    }

    fun updateVpnAlwaysOn(alwaysOn: Boolean) {
        val changed = _isVpnAlwaysOn.value != alwaysOn
        _isVpnAlwaysOn.value = alwaysOn
        if (changed) {
            repoScope.launch { refreshProtectionState(restartVpnIfProtectedAndDown = false) }
        }
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
        val entry =
            BlockedLogEntity(
                domain = domain,
                queryType = queryType,
                reason = reason.label,
                durationMs = durationMs,
                timestamp = System.currentTimeMillis(),
            )
        var immediateFlushEntries: List<BlockedLogEntity>? = null
        var immediateCounterDelta = 0L

        logBatchMutex.withLock {
            pendingLogs.add(entry)
            pendingCounterDelta += 1L
            if (pendingLogs.size >= LOG_BATCH_LIMIT) {
                immediateFlushEntries = pendingLogs.toList()
                pendingLogs.clear()
                immediateCounterDelta = pendingCounterDelta
                pendingCounterDelta = 0L
                flushTimerJob?.cancel()
                flushTimerJob = null
            } else if (flushTimerJob?.isActive != true) {
                flushTimerJob =
                    repoScope.launch {
                        delay(LOG_FLUSH_INTERVAL_MS)
                        flushPendingBlockedLogs()
                    }
            }
        }

        val batch = immediateFlushEntries
        if (batch != null) {
            persistBatch(batch, immediateCounterDelta)
        }
    }

    suspend fun flushPendingBlockedLogs() {
        var batch: List<BlockedLogEntity> = emptyList()
        var delta = 0L
        logBatchMutex.withLock {
            flushTimerJob?.cancel()
            flushTimerJob = null
            if (pendingLogs.isNotEmpty() || pendingCounterDelta > 0L) {
                batch = pendingLogs.toList()
                pendingLogs.clear()
                delta = pendingCounterDelta
                pendingCounterDelta = 0L
            }
        }
        if (batch.isNotEmpty() || delta > 0L) {
            persistBatch(batch, delta)
        }
    }

    private suspend fun persistBatch(entries: List<BlockedLogEntity>, counterDelta: Long) {
        if (counterDelta > 0L) {
            preferences.incrementBlockedCountersBy(counterDelta)
        }
        if (entries.isNotEmpty()) {
            database.insertLogsBatchAndTrim(entries)
        }
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
        logBatchMutex.withLock {
            pendingLogs.clear()
            pendingCounterDelta = 0L
            flushTimerJob?.cancel()
            flushTimerJob = null
        }
        database.clearLogs()
    }

    companion object {
        private const val LOG_BATCH_LIMIT = 20
        private const val LOG_FLUSH_INTERVAL_MS = 5000L
    }
}
