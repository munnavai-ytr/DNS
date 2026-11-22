package com.example.dns

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class CachedVerdict(
    val domain: String,
    val verdict: DomainVerdict,
    val confidence: Double,
    val reason: BlockReason?,
    val cachedAtMs: Long,
    val expiresAtMs: Long,
)

class VerdictCache(
    private val storageFile: File? = null,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lruMap =
        object : LinkedHashMap<String, CachedVerdict>(256, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, CachedVerdict>?
            ): Boolean = size > maxEntries
        }

    private val _cacheSize = MutableStateFlow(0)
    val cacheSize: StateFlow<Int> = _cacheSize.asStateFlow()

    @Volatile private var savePending = false

    fun loadFromDisk() {
        val file = storageFile ?: return
        if (!file.exists()) return
        val now = clock()
        runCatching {
            val content = file.readText()
            if (content.isBlank()) return
            val array = JSONArray(content)
            synchronized(lock) {
                lruMap.clear()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val domain = obj.optString("domain", "")
                    val verdictName = obj.optString("verdict", "")
                    val expiresAt = obj.optLong("expiresAtMs", 0L)
                    if (domain.isEmpty() || expiresAt <= now) continue
                    val verdict =
                        runCatching { DomainVerdict.valueOf(verdictName) }.getOrNull() ?: continue
                    if (verdict == DomainVerdict.UNSURE) continue
                    val reasonName = obj.optString("reason", "")
                    val reason =
                        if (reasonName.isNotEmpty()) {
                            runCatching { BlockReason.valueOf(reasonName) }.getOrNull()
                        } else {
                            null
                        }
                    lruMap[domain] =
                        CachedVerdict(
                            domain = domain,
                            verdict = verdict,
                            confidence = obj.optDouble("confidence", 1.0),
                            reason = reason,
                            cachedAtMs = obj.optLong("cachedAtMs", now),
                            expiresAtMs = expiresAt,
                        )
                }
                _cacheSize.value = lruMap.size
            }
        }
    }

    fun get(registrableDomain: String): CachedVerdict? {
        val now = clock()
        var expired = false
        val entry =
            synchronized(lock) {
                val found = lruMap[registrableDomain] ?: return@synchronized null
                if (now >= found.expiresAtMs) {
                    lruMap.remove(registrableDomain)
                    _cacheSize.value = lruMap.size
                    expired = true
                    null
                } else {
                    found
                }
            }
        if (expired) {
            scheduleBackgroundSave()
        }
        return entry
    }

    fun put(
        registrableDomain: String,
        verdict: DomainVerdict,
        confidence: Double,
        reason: BlockReason?,
    ) {
        if (verdict == DomainVerdict.UNSURE || registrableDomain.isEmpty()) return
        val now = clock()
        val ttlMs = if (verdict == DomainVerdict.SAFE) SAFE_TTL_MS else ADULT_TTL_MS
        val entry =
            CachedVerdict(
                domain = registrableDomain,
                verdict = verdict,
                confidence = confidence,
                reason = reason,
                cachedAtMs = now,
                expiresAtMs = now + ttlMs,
            )
        synchronized(lock) {
            lruMap[registrableDomain] = entry
            _cacheSize.value = lruMap.size
        }
        scheduleBackgroundSave()
    }

    fun clear() {
        synchronized(lock) {
            lruMap.clear()
            _cacheSize.value = 0
        }
        val file = storageFile ?: return
        ioScope.launch { runCatching { if (file.exists()) file.delete() } }
    }

    private fun scheduleBackgroundSave() {
        val file = storageFile ?: return
        if (savePending) return
        savePending = true
        ioScope.launch {
            try {
                savePending = false
                val snapshot = synchronized(lock) { lruMap.values.toList() }
                val array = JSONArray()
                for (item in snapshot) {
                    val obj =
                        JSONObject().apply {
                            put("domain", item.domain)
                            put("verdict", item.verdict.name)
                            put("confidence", item.confidence)
                            put("reason", item.reason?.name ?: "")
                            put("cachedAtMs", item.cachedAtMs)
                            put("expiresAtMs", item.expiresAtMs)
                        }
                    array.put(obj)
                }
                val parent = file.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(array.toString())
                if (!tmp.renameTo(file)) {
                    file.writeText(array.toString())
                    tmp.delete()
                }
            } catch (_: Throwable) {
                // Never crash on background cache write
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 5000
        const val SAFE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
        const val ADULT_TTL_MS = 30L * 24L * 60L * 60L * 1000L
    }
}
