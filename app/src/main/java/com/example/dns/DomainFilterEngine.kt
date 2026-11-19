package com.example.dns

import android.content.Context
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class DomainFilterEngine {
    data class FilterSnapshot(
        val baseBlocked: HashSet<String> = HashSet(),
        val customBlocked: HashSet<String> = HashSet(),
        val whitelist: HashSet<String> = HashSet(),
    ) {
        val totalBlockRuleCount: Int
            get() = baseBlocked.size + customBlocked.size
    }

    private val snapshotRef = AtomicReference(FilterSnapshot())

    fun getSnapshot(): FilterSnapshot = snapshotRef.get()

    fun loadBaseBlocklistFromAssets(context: Context, assetName: String = "blocklist.txt"): Int {
        val loaded = HashSet<String>(128)
        runCatching {
            context.assets.open(assetName).bufferedReader().useLines { lines ->
                lines.forEach { rawLine ->
                    parseBlocklistLine(rawLine)?.let { domain -> loaded.add(domain) }
                }
            }
        }
        while (true) {
            val current = snapshotRef.get()
            val updated = current.copy(baseBlocked = loaded)
            if (snapshotRef.compareAndSet(current, updated)) break
        }
        return loaded.size
    }

    fun updateDynamicRules(customBlockedDomains: Collection<String>, whitelistedDomains: Collection<String>) {
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

    /**
     * Checks if [rawDomain] or any of its parent domains is blocked.
     * Whitelist entries (including parent domain matches) always override the blocklist.
     */
    fun isBlocked(rawDomain: String): Boolean {
        val domain = normalizeDomainFast(rawDomain) ?: return false
        val snapshot = snapshotRef.get()

        if (matchesDomainOrParent(domain, snapshot.whitelist)) {
            return false
        }
        return matchesDomainOrParent(domain, snapshot.baseBlocked) ||
            matchesDomainOrParent(domain, snapshot.customBlocked)
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

        /**
         * Parses either a plain domain line (`example.com`) or a hosts-file line
         * (`0.0.0.0 example.com` / `127.0.0.1 example.com`).
         */
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

            // Strip URL scheme and path if user pasted a full URL
            val schemeIdx = cleaned.indexOf("://")
            if (schemeIdx >= 0) {
                cleaned = cleaned.substring(schemeIdx + 3)
            }
            cleaned = cleaned.substringBefore('/').substringBefore('?').substringBefore('#')
            cleaned = cleaned.substringBefore(':') // strip port if any
            cleaned = cleaned.trim().trimEnd('.')

            if (cleaned.isEmpty() || cleaned.length > 253) return null
            if (cleaned == "localhost" || cleaned == "localhost.localdomain") return null
            if (!cleaned.contains('.')) return null

            // Validate characters (letters, digits, hyphens, dots)
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
