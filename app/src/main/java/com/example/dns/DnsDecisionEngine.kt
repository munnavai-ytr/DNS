package com.example.dns

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class DecisionSettings(
    val aiVerificationEnabled: Boolean = true,
    val hasApiKey: Boolean = false,
    val whenUnsure: UnsurePolicy = UnsurePolicy.ALLOW,
)

data class UpstreamDnsResult(
    val responseBytes: ByteArray?,
    val isUpstreamBlocked: Boolean,
)

sealed interface DnsDecision {
    val latencyMs: Long

    data class AllowImmediate(
        val reasonLabel: String,
        override val latencyMs: Long,
    ) : DnsDecision

    data class AllowWithUpstream(
        val upstreamBytes: ByteArray,
        val reasonLabel: String,
        override val latencyMs: Long,
    ) : DnsDecision

    data class Block(
        val reason: BlockReason,
        override val latencyMs: Long,
    ) : DnsDecision

    data class Drop(override val latencyMs: Long) : DnsDecision
}

data class DomainCheckSummary(
    val domain: String,
    val registrableDomain: String,
    val verdict: DomainVerdict,
    val reasonLabel: String,
    val latencyMs: Long,
    val errorDetail: String? = null,
)

class DnsDecisionEngine(
    private val filterEngine: DomainFilterEngine,
    private val verdictCache: VerdictCache,
    private val classifier: DomainClassifier,
    private val settingsProvider: () -> DecisionSettings,
    private val verificationScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val aiTimeoutMs: Long = DEFAULT_AI_TIMEOUT_MS,
) {
    private data class SingleFlightResult(
        val verdict: DomainVerdict,
        val confidence: Double,
        val errorMessage: String? = null,
    )

    private val inFlightVerifications =
        ConcurrentHashMap<String, CompletableDeferred<SingleFlightResult>>()

    /**
     * Fast synchronous check for Steps 0, 1, 2, and cached ADULT hits so blocked responses
     * stay well under 10 ms without touching the upstream network or waiting on coroutines.
     */
    fun checkInstantDecision(rawDomain: String): DnsDecision? {
        val startNs = System.nanoTime()
        val domain = DomainUtils.normalizeFast(rawDomain) ?: return null

        // Step 0: Always allow Gemini API host immediately to avoid deadlocks
        if (filterEngine.isAlwaysAllowedInternalHost(domain)) {
            return null // Forward upstream directly without holding
        }

        // Step 1: Whitelist or built-in safe list -> allow instantly
        if (filterEngine.isWhitelisted(domain)) {
            return DnsDecision.AllowImmediate("Whitelist", elapsedMs(startNs))
        }
        if (filterEngine.isSafeListed(domain)) {
            return DnsDecision.AllowImmediate("Safe list", elapsedMs(startNs))
        }

        // Step 2: Blocklist, custom block rules, or keyword/pattern hit -> block instantly
        val instantBlockReason = filterEngine.matchInstantBlock(domain)
        if (instantBlockReason != null) {
            return DnsDecision.Block(instantBlockReason, elapsedMs(startNs))
        }

        // Step 3: Verdict cache hit (ADULT blocks instantly in < 10 ms)
        val registrableDomain = DomainUtils.extractRegistrableDomain(domain)
        val cached = verdictCache.get(registrableDomain)
        if (cached != null && cached.verdict == DomainVerdict.ADULT) {
            return DnsDecision.Block(cached.reason ?: BlockReason.AI, elapsedMs(startNs))
        }

        return null
    }

    /**
     * Full asynchronous decision flow for a DNS query.
     */
    suspend fun evaluate(
        rawDomain: String,
        forwardToUpstream: suspend () -> UpstreamDnsResult,
    ): DnsDecision {
        val startNs = System.nanoTime()
        val domain = DomainUtils.normalizeFast(rawDomain) ?: return DnsDecision.Drop(0L)

        // Step 0: Internal Gemini API endpoint never waits on the engine
        if (filterEngine.isAlwaysAllowedInternalHost(domain)) {
            val upstream = forwardToUpstream()
            val bytes = upstream.responseBytes ?: return DnsDecision.Drop(elapsedMs(startNs))
            return DnsDecision.AllowWithUpstream(bytes, "Internal API", elapsedMs(startNs))
        }

        // Step 1: Whitelist or built-in safe list -> allow instantly
        if (filterEngine.isWhitelisted(domain)) {
            val upstream = forwardToUpstream()
            val bytes = upstream.responseBytes ?: return DnsDecision.Drop(elapsedMs(startNs))
            return DnsDecision.AllowWithUpstream(bytes, "Whitelist", elapsedMs(startNs))
        }
        if (filterEngine.isSafeListed(domain)) {
            val upstream = forwardToUpstream()
            val bytes = upstream.responseBytes ?: return DnsDecision.Drop(elapsedMs(startNs))
            return DnsDecision.AllowWithUpstream(bytes, "Safe list", elapsedMs(startNs))
        }

        // Step 2: Blocklist, custom block rules, or keyword/pattern hit -> block instantly
        val instantBlockReason = filterEngine.matchInstantBlock(domain)
        if (instantBlockReason != null) {
            return DnsDecision.Block(instantBlockReason, elapsedMs(startNs))
        }

        // Step 3: Verdict cache hit
        val registrableDomain = DomainUtils.extractRegistrableDomain(domain)
        val cached = verdictCache.get(registrableDomain)
        if (cached != null) {
            if (cached.verdict == DomainVerdict.ADULT) {
                return DnsDecision.Block(cached.reason ?: BlockReason.AI, elapsedMs(startNs))
            }
            if (cached.verdict == DomainVerdict.SAFE) {
                val upstream = forwardToUpstream()
                val bytes = upstream.responseBytes ?: return DnsDecision.Drop(elapsedMs(startNs))
                return DnsDecision.AllowWithUpstream(bytes, "Cache (SAFE)", elapsedMs(startNs))
            }
        }

        // Step 4a: Forward to upstream 1.1.1.3 first
        val upstream = forwardToUpstream()
        val upstreamBytes = upstream.responseBytes
        if (upstream.isUpstreamBlocked) {
            verdictCache.put(
                registrableDomain = registrableDomain,
                verdict = DomainVerdict.ADULT,
                confidence = 1.0,
                reason = BlockReason.UPSTREAM_FILTER,
            )
            return DnsDecision.Block(BlockReason.UPSTREAM_FILTER, elapsedMs(startNs))
        }
        if (upstreamBytes == null) {
            return DnsDecision.Drop(elapsedMs(startNs))
        }

        // Step 4b / 4c: Check if AI verification is enabled and API key is set
        val settings = settingsProvider()
        if (!settings.aiVerificationEnabled || !settings.hasApiKey) {
            return DnsDecision.AllowWithUpstream(upstreamBytes, "Upstream", elapsedMs(startNs))
        }

        val aiOutcome = verifyRegistrableDomainSingleFlight(registrableDomain)
        return when {
            aiOutcome.verdict == DomainVerdict.ADULT && aiOutcome.confidence >= ADULT_THRESHOLD -> {
                DnsDecision.Block(BlockReason.AI, elapsedMs(startNs))
            }
            aiOutcome.verdict == DomainVerdict.SAFE -> {
                DnsDecision.AllowWithUpstream(upstreamBytes, "AI (SAFE)", elapsedMs(startNs))
            }
            else -> {
                // UNSURE or timeout or error (or ADULT below 0.7 threshold)
                if (settings.whenUnsure == UnsurePolicy.BLOCK) {
                    DnsDecision.Block(BlockReason.AI, elapsedMs(startNs))
                } else {
                    DnsDecision.AllowWithUpstream(
                        upstreamBytes,
                        "Unsure (Allowed)",
                        elapsedMs(startNs),
                    )
                }
            }
        }
    }

    /**
     * Manual domain check used by Settings ("Check a domain" box). Never opens or visits the domain.
     */
    suspend fun inspectDomainManually(rawDomain: String): DomainCheckSummary {
        val startNs = System.nanoTime()
        val normalized = DomainFilterEngine.normalizeDomain(rawDomain)
            ?: return DomainCheckSummary(
                domain = rawDomain,
                registrableDomain = rawDomain,
                verdict = DomainVerdict.UNSURE,
                reasonLabel = "Invalid domain",
                latencyMs = elapsedMs(startNs),
            )

        if (filterEngine.isAlwaysAllowedInternalHost(normalized) || filterEngine.isWhitelisted(normalized)) {
            return DomainCheckSummary(
                domain = normalized,
                registrableDomain = DomainUtils.extractRegistrableDomain(normalized),
                verdict = DomainVerdict.SAFE,
                reasonLabel = "Whitelist",
                latencyMs = elapsedMs(startNs),
            )
        }
        if (filterEngine.isSafeListed(normalized)) {
            return DomainCheckSummary(
                domain = normalized,
                registrableDomain = DomainUtils.extractRegistrableDomain(normalized),
                verdict = DomainVerdict.SAFE,
                reasonLabel = "Safe list",
                latencyMs = elapsedMs(startNs),
            )
        }
        val instantBlock = filterEngine.matchInstantBlock(normalized)
        if (instantBlock != null) {
            return DomainCheckSummary(
                domain = normalized,
                registrableDomain = DomainUtils.extractRegistrableDomain(normalized),
                verdict = DomainVerdict.ADULT,
                reasonLabel = instantBlock.label,
                latencyMs = elapsedMs(startNs),
            )
        }

        val registrable = DomainUtils.extractRegistrableDomain(normalized)
        val cached = verdictCache.get(registrable)
        if (cached != null) {
            return DomainCheckSummary(
                domain = normalized,
                registrableDomain = registrable,
                verdict = cached.verdict,
                reasonLabel = "Cache (${cached.reason?.label ?: cached.verdict.name})",
                latencyMs = elapsedMs(startNs),
            )
        }

        val settings = settingsProvider()
        if (!settings.aiVerificationEnabled || !settings.hasApiKey) {
            return DomainCheckSummary(
                domain = normalized,
                registrableDomain = registrable,
                verdict = DomainVerdict.UNSURE,
                reasonLabel = if (!settings.aiVerificationEnabled) "AI verification off" else "No API key set",
                latencyMs = elapsedMs(startNs),
            )
        }

        val aiOutcome = verifyRegistrableDomainSingleFlight(registrable)
        val effectiveVerdict =
            when {
                aiOutcome.verdict == DomainVerdict.ADULT && aiOutcome.confidence >= ADULT_THRESHOLD ->
                    DomainVerdict.ADULT
                aiOutcome.verdict == DomainVerdict.SAFE -> DomainVerdict.SAFE
                else -> DomainVerdict.UNSURE
            }
        return DomainCheckSummary(
            domain = normalized,
            registrableDomain = registrable,
            verdict = effectiveVerdict,
            reasonLabel =
                if (aiOutcome.errorMessage != null) "AI (Unsure)"
                else "AI (${String.format("%.2f", aiOutcome.confidence)})",
            latencyMs = elapsedMs(startNs),
            errorDetail = aiOutcome.errorMessage,
        )
    }

    private suspend fun verifyRegistrableDomainSingleFlight(
        registrableDomain: String
    ): SingleFlightResult {
        val created = CompletableDeferred<SingleFlightResult>()
        val existing = inFlightVerifications.putIfAbsent(registrableDomain, created)
        if (existing != null) {
            return try {
                withTimeoutOrNull(aiTimeoutMs + 200L) { existing.await() }
                    ?: SingleFlightResult(DomainVerdict.UNSURE, 0.0, "Timed out waiting")
            } catch (_: CancellationException) {
                SingleFlightResult(DomainVerdict.UNSURE, 0.0, "Cancelled")
            }
        }

        verificationScope.launch {
            try {
                val result =
                    withTimeoutOrNull(aiTimeoutMs) { classifier.classify(registrableDomain) }
                        ?: ClassificationResult(
                            verdict = DomainVerdict.UNSURE,
                            confidence = 0.0,
                            errorMessage = "Timed out after ${aiTimeoutMs} ms",
                        )

                val outcome =
                    when {
                        result.verdict == DomainVerdict.ADULT &&
                            result.confidence >= ADULT_THRESHOLD -> {
                            verdictCache.put(
                                registrableDomain = registrableDomain,
                                verdict = DomainVerdict.ADULT,
                                confidence = result.confidence,
                                reason = BlockReason.AI,
                            )
                            SingleFlightResult(DomainVerdict.ADULT, result.confidence, null)
                        }
                        result.verdict == DomainVerdict.SAFE -> {
                            verdictCache.put(
                                registrableDomain = registrableDomain,
                                verdict = DomainVerdict.SAFE,
                                confidence = result.confidence,
                                reason = null,
                            )
                            SingleFlightResult(DomainVerdict.SAFE, result.confidence, null)
                        }
                        else -> {
                            // UNSURE is never cached
                            SingleFlightResult(
                                DomainVerdict.UNSURE,
                                result.confidence,
                                result.errorMessage,
                            )
                        }
                    }
                created.complete(outcome)
            } catch (e: Throwable) {
                created.complete(
                    SingleFlightResult(DomainVerdict.UNSURE, 0.0, e.message ?: "Error")
                )
            } finally {
                inFlightVerifications.remove(registrableDomain, created)
            }
        }

        return withTimeoutOrNull(aiTimeoutMs + 200L) { created.await() }
            ?: SingleFlightResult(DomainVerdict.UNSURE, 0.0, "Timed out")
    }

    private fun elapsedMs(startNs: Long): Long =
        ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)

    companion object {
        const val ADULT_THRESHOLD = 0.7
        const val DEFAULT_AI_TIMEOUT_MS = 3000L
    }
}
