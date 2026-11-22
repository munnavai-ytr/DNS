package com.example.dns

import android.content.Context
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class DomainFilterEngine {
    data class FilterSnapshot(
        val baseBlocked: HashSet<String> = HashSet(),
        val customBlocked: HashSet<String> = HashSet(),
        val whitelist: HashSet<String> = HashSet(),
        val safeDomains: HashSet<String> = HashSet(),
    ) {
        val totalBlockRuleCount: Int
            get() = baseBlocked.size + customBlocked.size

        val safeRuleCount: Int
            get() = safeDomains.size
    }

    private val snapshotRef = AtomicReference(FilterSnapshot())

    fun getSnapshot(): FilterSnapshot = snapshotRef.get()

    fun loadBaseBlocklistFromAssets(context: Context, assetName: String = "blocklist.txt"): Int {
        val loaded = loadDomainSetFromAsset(context, assetName)
        while (true) {
            val current = snapshotRef.get()
            val updated = current.copy(baseBlocked = loaded)
            if (snapshotRef.compareAndSet(current, updated)) break
        }
        return loaded.size
    }

    fun loadSafeDomainsFromAssets(context: Context, assetName: String = "safe_domains.txt"): Int {
        val loaded = loadDomainSetFromAsset(context, assetName)
        while (true) {
            val current = snapshotRef.get()
            val updated = current.copy(safeDomains = loaded)
            if (snapshotRef.compareAndSet(current, updated)) break
        }
        return loaded.size
    }

    fun updateSafeDomainsForTesting(safeDomains: Collection<String>) {
        val set = HashSet<String>(safeDomains.size)
        safeDomains.forEach { raw -> normalizeDomain(raw)?.let { set.add(it) } }
        while (true) {
            val current = snapshotRef.get()
            val updated = current.copy(safeDomains = set)
            if (snapshotRef.compareAndSet(current, updated)) break
        }
    }

    fun updateBaseBlockedForTesting(baseBlocked: Collection<String>) {
        val set = HashSet<String>(baseBlocked.size)
        baseBlocked.forEach { raw -> normalizeDomain(raw)?.let { set.add(it) } }
        while (true) {
            val current = snapshotRef.get()
            val updated = current.copy(baseBlocked = set)
            if (snapshotRef.compareAndSet(current, updated)) break
        }
    }

    fun updateDynamicRules(
        customBlockedDomains: Collection<String>,
        whitelistedDomains: Collection<String>,
    ) {
        val customSet = HashSet<String>(customBlockedDomains.size)
        customBlockedDomains.forEach { raw -> normalizeDomain(raw)?.let { customSet.add(it) } }

        val whiteSet = HashSet<String>(whitelistedDomains.size)
        whitelistedDomains.forEach { raw -> normalizeDomain(raw)?.let { whiteSet.add(it) } }

        while (true) {
            val current = snapshotRef.get()
            val updated = current.copy(customBlocked = customSet, whitelist = whiteSet)
            if (snapshotRef.compareAndSet(current, updated)) break
        }
    }

    fun isAlwaysAllowedInternalHost(rawDomain: String): Boolean {
        val domain = normalizeDomainFast(rawDomain) ?: return false
        return domain == GeminiDomainClassifier.GEMINI_API_HOST
    }

    fun isWhitelisted(rawDomain: String): Boolean {
        val domain = normalizeDomainFast(rawDomain) ?: return false
        return matchesDomainOrParent(domain, snapshotRef.get().whitelist)
    }

    fun isSafeListed(rawDomain: String): Boolean {
        val domain = normalizeDomainFast(rawDomain) ?: return false
        return matchesDomainOrParent(domain, snapshotRef.get().safeDomains)
    }

    /**
     * Checks Step 2 instant block rules in priority order:
     * Custom block rules -> Blocklist -> Adult keyword/pattern hit.
     */
    fun matchInstantBlock(rawDomain: String): BlockReason? {
        val domain = normalizeDomainFast(rawDomain) ?: return null
        val snapshot = snapshotRef.get()
        if (matchesDomainOrParent(domain, snapshot.customBlocked)) {
            return BlockReason.CUSTOM
        }
        if (matchesDomainOrParent(domain, snapshot.baseBlocked)) {
            return BlockReason.BLOCKLIST
        }
        if (DomainUtils.containsAdultKeyword(domain)) {
            return BlockReason.KEYWORD
        }
        return null
    }

    /**
     * Checks if [rawDomain] is allowed by whitelist/safe-list or blocked by instant rules.
     */
    fun isBlocked(rawDomain: String): Boolean {
        val domain = normalizeDomainFast(rawDomain) ?: return false
        if (isAlwaysAllowedInternalHost(domain)) return false
        if (isWhitelisted(domain) || isSafeListed(domain)) return false
        return matchInstantBlock(domain) != null
    }

    private fun loadDomainSetFromAsset(context: Context, assetName: String): HashSet<String> {
        val loaded = HashSet<String>(256)
        runCatching {
            context.assets.open(assetName).bufferedReader().useLines { lines ->
                lines.forEach { rawLine ->
                    parseBlocklistLine(rawLine)?.let { domain -> loaded.add(domain) }
                }
            }
        }
        return loaded
    }

    private fun matchesDomainOrParent(domain: String, set: HashSet<String>): Boolean {
        if (set.isEmpty()) return false
        var startIndex = 0
        while (startIndex >= 0 && startIndex < domain.length) {
            val candidate = if (startIndex == 0) domain else domain.substring(startIndex)
            if (set.contains(candidate)) {
                return true
            }
            val dotIndex = domain.indexOf('.', startIndex)
            if (dotIndex < 0) break
            startIndex = dotIndex + 1
        }
        return false
    }

    private fun normalizeDomainFast(raw: String): String? {
        val trimmed = raw.trim().trimEnd('.').lowercase(Locale.ROOT)
        return if (trimmed.isEmpty()) null else trimmed
    }

    companion object {
        const val SELF_TEST_DOMAIN = "blocktest.dnsshield.test"

        fun parseBlocklistLine(rawLine: String): String? {
            val noComment = rawLine.substringBefore('#').trim()
            if (noComment.isEmpty()) return null

            val tokens = noComment.split(Regex("\\s+"))
            val candidate =
                when {
                    tokens.size >= 2 &&
                        (tokens[0] == "0.0.0.0" ||
                            tokens[0] == "127.0.0.1" ||
                            tokens[0] == "::" ||
                            tokens[0] == "::1") -> tokens[1]
                    tokens.size == 1 -> tokens[0]
                    else -> tokens.last()
                }
            return normalizeDomain(candidate)
        }

        fun normalizeDomain(input: String): String? {
            var cleaned = input.trim().lowercase(Locale.ROOT)
            if (cleaned.isEmpty()) return null

            val schemeIdx = cleaned.indexOf("://")
            if (schemeIdx >= 0) {
                cleaned = cleaned.substring(schemeIdx + 3)
            }
            cleaned = cleaned.substringBefore('/').substringBefore('?').substringBefore('#')
            cleaned = cleaned.substringBefore(':')
            cleaned = cleaned.trim().trimEnd('.')

            if (cleaned.isEmpty() || cleaned.length > 253) return null
            if (cleaned == "localhost" || cleaned == "localhost.localdomain") return null
            if (!cleaned.contains('.')) return null

            for (ch in cleaned) {
                val valid =
                    (ch in 'a'..'z') || (ch in '0'..'9') || ch == '-' || ch == '.' || ch == '_'
                if (!valid) return null
            }
            if (cleaned.startsWith('.') || cleaned.contains("..")) return null
            return cleaned
        }
    }
}
