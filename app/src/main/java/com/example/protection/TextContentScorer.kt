package com.example.protection

import android.content.Context
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

data class WeightedKeywordRule(
    val normalizedTerm: String,
    val collapsedTerm: String,
    val weight: Int,
    val isPhrase: Boolean,
    val allowSubstring: Boolean,
)

class TextContentScorer {
    private data class RuleSet(
        val keywords: List<WeightedKeywordRule> = emptyList(),
        val safeContextTerms: List<String> = emptyList(),
    )

    private val ruleSetRef = AtomicReference(RuleSet())

    fun loadFromAssets(context: Context) {
        val kwLines =
            runCatching {
                    context.assets.open("adult_keywords.txt").bufferedReader().use { it.readLines() }
                }
                .getOrDefault(emptyList())
        val safeLines =
            runCatching {
                    context.assets.open("safe_context.txt").bufferedReader().use { it.readLines() }
                }
                .getOrDefault(emptyList())
        loadFromLines(kwLines, safeLines)
    }

    fun loadFromStreams(keywordsStream: InputStream, safeContextStream: InputStream) {
        val kwLines = keywordsStream.bufferedReader().use { it.readLines() }
        val safeLines = safeContextStream.bufferedReader().use { it.readLines() }
        loadFromLines(kwLines, safeLines)
    }

    fun loadFromLines(keywordLines: Collection<String>, safeContextLines: Collection<String>) {
        val seenKeywords = LinkedHashMap<String, WeightedKeywordRule>()
        for (rawLine in keywordLines) {
            val cleanedLine = rawLine.substringBefore('#').trim()
            if (cleanedLine.isEmpty()) continue
            val parts = cleanedLine.split('|')
            if (parts.size < 2) continue
            val rawTerm = parts[0].trim()
            val weight = parts[1].trim().toIntOrNull()?.coerceIn(1, 5) ?: continue
            val normalized = ContentTextNormalizer.normalizeKeyword(rawTerm)
            if (normalized.isEmpty()) continue
            val collapsed = ContentTextNormalizer.collapseRepeats(normalized)
            val isPhrase = normalized.contains(' ')
            val allowSubstring =
                normalized == TEST_KEYWORD ||
                    normalized == "porn" ||
                    (!isPhrase && weight == 5 && normalized.length >= 6)

            val existing = seenKeywords[normalized]
            if (existing == null || weight > existing.weight) {
                seenKeywords[normalized] =
                    WeightedKeywordRule(
                        normalizedTerm = normalized,
                        collapsedTerm = collapsed,
                        weight = weight,
                        isPhrase = isPhrase,
                        allowSubstring = allowSubstring,
                    )
            }
        }

        val safeTerms =
            safeContextLines
                .mapNotNull { line ->
                    val cleaned = line.substringBefore('#').trim()
                    if (cleaned.isEmpty()) null
                    else ContentTextNormalizer.normalizeKeyword(cleaned).takeIf { it.isNotEmpty() }
                }
                .distinct()

        ruleSetRef.set(RuleSet(keywords = seenKeywords.values.toList(), safeContextTerms = safeTerms))
    }

    fun scoreNodes(
        nodes: List<TextNodeSnapshot>,
        sensitivity: ScreenGuardSensitivity,
    ): TextScoreResult {
        val rules = ruleSetRef.get()
        if (nodes.isEmpty() || rules.keywords.isEmpty()) {
            return TextScoreResult(
                rawScore = 0,
                safeContextReduction = 0,
                finalScore = 0,
                threshold = sensitivity.threshold,
                blocked = false,
                matchedTestTerm = false,
                matchedTermCount = 0,
                safeContextMatchCount = 0,
            )
        }

        val matchedWeightsByTerm = HashMap<String, Int>()
        val matchedSafeTerms = HashSet<String>()
        var matchedTestTerm = false

        for (node in nodes) {
            // Password fields must NEVER be scanned or scored
            if (node.isPassword || node.text.isBlank()) continue

            val views = ContentTextNormalizer.normalizeForMatching(node.text)
            if (views.tokens.isEmpty() && views.cleanSpaced.isEmpty()) continue

            val prominent = node.isProminent || node.isSearchField

            for (rule in rules.keywords) {
                if (matchesKeyword(views, rule)) {
                    if (rule.normalizedTerm == TEST_KEYWORD) {
                        matchedTestTerm = true
                    }
                    val effectiveWeight =
                        if (prominent) ((rule.weight * 3) + 1) / 2 else rule.weight
                    val prev = matchedWeightsByTerm[rule.normalizedTerm] ?: 0
                    if (effectiveWeight > prev) {
                        matchedWeightsByTerm[rule.normalizedTerm] = effectiveWeight
                    }
                }
            }

            for (safeTerm in rules.safeContextTerms) {
                if (matchesSafeContext(views, safeTerm)) {
                    matchedSafeTerms.add(safeTerm)
                }
            }
        }

        val rawScore = matchedWeightsByTerm.values.sum()
        val reduction = matchedSafeTerms.size * SAFE_CONTEXT_REDUCTION_PER_HIT
        val finalScore = (rawScore - reduction).coerceAtLeast(0)
        val blocked = matchedTestTerm || finalScore >= sensitivity.threshold

        return TextScoreResult(
            rawScore = rawScore,
            safeContextReduction = reduction,
            finalScore = finalScore,
            threshold = sensitivity.threshold,
            blocked = blocked,
            matchedTestTerm = matchedTestTerm,
            matchedTermCount = matchedWeightsByTerm.size,
            safeContextMatchCount = matchedSafeTerms.size,
        )
    }

    fun scoreText(
        text: String,
        sensitivity: ScreenGuardSensitivity,
        isPassword: Boolean = false,
        isProminent: Boolean = false,
    ): TextScoreResult {
        return scoreNodes(
            nodes =
                listOf(
                    TextNodeSnapshot(
                        text = text,
                        isPassword = isPassword,
                        isProminent = isProminent,
                    )
                ),
            sensitivity = sensitivity,
        )
    }

    fun containsBlockedSearchTerm(
        text: String,
        sensitivity: ScreenGuardSensitivity,
        isPassword: Boolean = false,
    ): Boolean {
        if (isPassword || text.isBlank()) return false
        val result =
            scoreNodes(
                nodes =
                    listOf(
                        TextNodeSnapshot(
                            text = text,
                            isPassword = false,
                            isProminent = true,
                            isEditable = true,
                            isSearchField = true,
                        )
                    ),
                sensitivity = sensitivity,
            )
        return result.blocked
    }

    private fun matchesKeyword(views: NormalizedTextViews, rule: WeightedKeywordRule): Boolean {
        if (rule.isPhrase) {
            return views.cleanSpaced.contains(rule.normalizedTerm) ||
                views.deobfuscatedSpaced.contains(rule.normalizedTerm) ||
                views.repeatCollapsedSpaced.contains(rule.collapsedTerm)
        }

        // Exact token match (on clean, leetspeak-normalized, or repeat-collapsed tokens)
        if (
            rule.normalizedTerm in views.tokens ||
                rule.collapsedTerm in views.repeatCollapsedTokens
        ) {
            return true
        }

        // Unambiguous stems & test keyword (including space-separated fragments >= 8 chars)
        if (rule.allowSubstring) {
            if (
                views.cleanSpaced.contains(rule.normalizedTerm) ||
                    views.deobfuscatedSpaced.contains(rule.normalizedTerm) ||
                    views.repeatCollapsedSpaced.contains(rule.collapsedTerm)
            ) {
                return true
            }
            if (
                rule.normalizedTerm.length >= 8 &&
                    (views.spaceCollapsedLong.contains(rule.normalizedTerm) ||
                        ContentTextNormalizer.collapseRepeats(views.spaceCollapsedLong)
                            .contains(rule.collapsedTerm))
            ) {
                return true
            }
        }
        return false
    }

    private fun matchesSafeContext(views: NormalizedTextViews, safeTerm: String): Boolean {
        return if (safeTerm.contains(' ')) {
            views.cleanSpaced.contains(safeTerm)
        } else {
            safeTerm in views.tokens
        }
    }

    companion object {
        const val TEST_KEYWORD = "dnsshieldtestword"
        const val SAFE_CONTEXT_REDUCTION_PER_HIT = 2
    }
}

/**
 * Coordinates per-package scan debouncing (400 ms), per-package block cooldown (5 s),
 * and 3-blocks-within-10-minutes 5-minute escalation cooldowns with an injectable clock.
 */
class ScreenGuardRateLimiter(
    private val clock: ProtectionClock = SystemProtectionClock,
) {
    private val lastScanByPackage = ConcurrentHashMap<String, Long>()
    private val lastBlockByPackage = ConcurrentHashMap<String, Long>()
    private val blockHistoryByPackage = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val cooldownUntilByPackage = ConcurrentHashMap<String, Long>()
    private var lastVisualCheckElapsedMs = 0L

    fun tryAcquireScanSlot(pkg: String): Boolean {
        val now = clock.elapsedRealtimeMs()
        val prev = lastScanByPackage[pkg] ?: 0L
        if (prev != 0L && now - prev < SCAN_DEBOUNCE_MS) {
            return false
        }
        lastScanByPackage[pkg] = now
        return true
    }

    fun tryAcquireVisualCheckSlot(): Boolean {
        val now = clock.elapsedRealtimeMs()
        if (lastVisualCheckElapsedMs != 0L && now - lastVisualCheckElapsedMs < VISUAL_CHECK_COOLDOWN_MS) {
            return false
        }
        lastVisualCheckElapsedMs = now
        return true
    }

    fun isInBlockCooldown(pkg: String): Boolean {
        val now = clock.elapsedRealtimeMs()
        val prev = lastBlockByPackage[pkg] ?: 0L
        return prev != 0L && now - prev < BLOCK_COOLDOWN_MS
    }

    fun getAppEscalationCooldownRemainingMs(pkg: String): Long {
        val until = cooldownUntilByPackage[pkg] ?: return 0L
        val rem = until - clock.elapsedRealtimeMs()
        if (rem <= 0L) {
            cooldownUntilByPackage.remove(pkg)
            return 0L
        }
        return rem
    }

    fun recordBlockWithoutEscalation(pkg: String) {
        lastBlockByPackage[pkg] = clock.elapsedRealtimeMs()
    }

    /**
     * Records a block event for [pkg]. Returns true if this triggers a 5-minute escalation cooldown
     * (3 blocks within 10 minutes).
     */
    fun recordBlockAndCheckEscalation(pkg: String): Boolean {
        val now = clock.elapsedRealtimeMs()
        lastBlockByPackage[pkg] = now

        val history = blockHistoryByPackage.getOrPut(pkg) { ArrayDeque() }
        synchronized(history) {
            while (history.isNotEmpty() && now - history.first() > ESCALATION_WINDOW_MS) {
                history.removeFirst()
            }
            history.addLast(now)
            if (history.size >= ESCALATION_BLOCK_THRESHOLD) {
                cooldownUntilByPackage[pkg] = now + ESCALATION_COOLDOWN_MS
                history.clear()
                return true
            }
        }
        return false
    }

    companion object {
        const val SCAN_DEBOUNCE_MS = 400L
        const val BLOCK_COOLDOWN_MS = 5000L
        const val VISUAL_CHECK_COOLDOWN_MS = 3000L
        const val ESCALATION_WINDOW_MS = 10L * 60L * 1000L
        const val ESCALATION_COOLDOWN_MS = 5L * 60L * 1000L
        const val ESCALATION_BLOCK_THRESHOLD = 3
    }
}
