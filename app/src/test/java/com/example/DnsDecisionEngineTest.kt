package com.example

import com.example.dns.BlockReason
import com.example.dns.ClassificationResult
import com.example.dns.DecisionSettings
import com.example.dns.DnsDecision
import com.example.dns.DnsDecisionEngine
import com.example.dns.DnsWireCodec
import com.example.dns.DomainClassifier
import com.example.dns.DomainFilterEngine
import com.example.dns.DomainUtils
import com.example.dns.DomainVerdict
import com.example.dns.UnsurePolicy
import com.example.dns.UpstreamDnsResult
import com.example.dns.VerdictCache
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsDecisionEngineTest {

    private class FakeClassifier(
        private val handler: suspend (String) -> ClassificationResult,
    ) : DomainClassifier {
        val callCount = AtomicInteger(0)

        override suspend fun classify(domain: String): ClassificationResult {
            callCount.incrementAndGet()
            return handler(domain)
        }
    }

    private fun fakeUpstream(bytes: ByteArray = byteArrayOf(1, 2, 3), blocked: Boolean = false) =
        UpstreamDnsResult(responseBytes = bytes, isUpstreamBlocked = blocked)

    @Test
    fun `extractRegistrableDomain handles standard and two-level public suffixes`() {
        assertEquals("example.com", DomainUtils.extractRegistrableDomain("sub.www.example.com"))
        assertEquals("bbc.co.uk", DomainUtils.extractRegistrableDomain("static.news.bbc.co.uk"))
        assertEquals("portal.com.bd", DomainUtils.extractRegistrableDomain("cdn.portal.com.bd"))
        assertEquals("wikipedia.org", DomainUtils.extractRegistrableDomain("wikipedia.org"))
    }

    @Test
    fun `safe-list allows without calling AI classifier`() = runBlocking {
        val filter = DomainFilterEngine().apply { updateSafeDomainsForTesting(listOf("google.com")) }
        val cache = VerdictCache(storageFile = null)
        val classifier = FakeClassifier { ClassificationResult(DomainVerdict.ADULT, 1.0) }
        val engine =
            DnsDecisionEngine(
                filterEngine = filter,
                verdictCache = cache,
                classifier = classifier,
                settingsProvider = { DecisionSettings(aiVerificationEnabled = true, hasApiKey = true) },
            )

        val decision = engine.evaluate("maps.google.com") { fakeUpstream() }
        assertTrue(decision is DnsDecision.AllowWithUpstream)
        assertEquals(0, classifier.callCount.get())
    }

    @Test
    fun `blocklist and keyword rules block instantly without calling AI or upstream`() = runBlocking {
        val filter =
            DomainFilterEngine().apply {
                updateBaseBlockedForTesting(listOf("blocked-seed.test"))
                updateDynamicRules(listOf("custom-bad.org"), emptyList())
            }
        val cache = VerdictCache(storageFile = null)
        val classifier = FakeClassifier { ClassificationResult(DomainVerdict.SAFE, 1.0) }
        val upstreamCalls = AtomicInteger(0)
        val engine =
            DnsDecisionEngine(
                filterEngine = filter,
                verdictCache = cache,
                classifier = classifier,
                settingsProvider = { DecisionSettings(aiVerificationEnabled = true, hasApiKey = true) },
            )

        val blocklistDecision =
            engine.evaluate("sub.blocked-seed.test") {
                upstreamCalls.incrementAndGet()
                fakeUpstream()
            }
        assertTrue(blocklistDecision is DnsDecision.Block)
        assertEquals(BlockReason.BLOCKLIST, (blocklistDecision as DnsDecision.Block).reason)

        val customDecision =
            engine.evaluate("custom-bad.org") {
                upstreamCalls.incrementAndGet()
                fakeUpstream()
            }
        assertTrue(customDecision is DnsDecision.Block)
        assertEquals(BlockReason.CUSTOM, (customDecision as DnsDecision.Block).reason)

        val keywordDecision =
            engine.evaluate("free-porn-videos.example") {
                upstreamCalls.incrementAndGet()
                fakeUpstream()
            }
        assertTrue(keywordDecision is DnsDecision.Block)
        assertEquals(BlockReason.KEYWORD, (keywordDecision as DnsDecision.Block).reason)

        // Keyword false-positive check: essex.co.uk must NOT trigger keyword hit
        val safePlaceDecision = engine.evaluate("essex.co.uk") { fakeUpstream() }
        assertFalse(safePlaceDecision is DnsDecision.Block)
        assertEquals(0, upstreamCalls.get())
    }

    @Test
    fun `cache hit uses cached verdict instantly and skips AI`() = runBlocking {
        var nowMs = 1_000_000L
        val filter = DomainFilterEngine()
        val cache = VerdictCache(storageFile = null, clock = { nowMs })
        val classifier = FakeClassifier { ClassificationResult(DomainVerdict.ADULT, 0.95) }
        val engine =
            DnsDecisionEngine(
                filterEngine = filter,
                verdictCache = cache,
                classifier = classifier,
                settingsProvider = { DecisionSettings(aiVerificationEnabled = true, hasApiKey = true) },
            )

        val first = engine.evaluate("a.unknown-adult.org") { fakeUpstream() }
        assertTrue(first is DnsDecision.Block)
        assertEquals(1, classifier.callCount.get())

        // Subdomain shares registrable domain cache entry
        val second = engine.evaluate("cdn.unknown-adult.org") { fakeUpstream() }
        assertTrue(second is DnsDecision.Block)
        assertEquals(1, classifier.callCount.get())

        // Expire after 31 days -> triggers re-classification
        nowMs += 31L * 24L * 60L * 60L * 1000L
        val third = engine.evaluate("cdn.unknown-adult.org") { fakeUpstream() }
        assertTrue(third is DnsDecision.Block)
        assertEquals(2, classifier.callCount.get())
    }

    @Test
    fun `AI ADULT high confidence blocks and low confidence falls back to unsure`() = runBlocking {
        val filter = DomainFilterEngine()
        val cache = VerdictCache(storageFile = null)
        val classifier = FakeClassifier { domain ->
            if (domain == "adult-high.org") ClassificationResult(DomainVerdict.ADULT, 0.85)
            else ClassificationResult(DomainVerdict.ADULT, 0.5) // below 0.7 -> UNSURE
        }
        val engine =
            DnsDecisionEngine(
                filterEngine = filter,
                verdictCache = cache,
                classifier = classifier,
                settingsProvider = {
                    DecisionSettings(
                        aiVerificationEnabled = true,
                        hasApiKey = true,
                        whenUnsure = UnsurePolicy.ALLOW,
                    )
                },
            )

        val blocked = engine.evaluate("adult-high.org") { fakeUpstream() }
        assertTrue(blocked is DnsDecision.Block)
        assertEquals(BlockReason.AI, (blocked as DnsDecision.Block).reason)

        val lowConfidence = engine.evaluate("ambiguous-site.org") { fakeUpstream() }
        assertTrue(lowConfidence is DnsDecision.AllowWithUpstream)
    }

    @Test
    fun `AI timeout applies When unsure Allow or Block and does not cache UNSURE`() = runBlocking {
        val filter = DomainFilterEngine()
        val cache = VerdictCache(storageFile = null)
        val classifier = FakeClassifier {
            delay(400L)
            ClassificationResult(DomainVerdict.SAFE, 1.0)
        }
        var currentUnsurePolicy = UnsurePolicy.ALLOW
        val engine =
            DnsDecisionEngine(
                filterEngine = filter,
                verdictCache = cache,
                classifier = classifier,
                settingsProvider = {
                    DecisionSettings(
                        aiVerificationEnabled = true,
                        hasApiKey = true,
                        whenUnsure = currentUnsurePolicy,
                    )
                },
                aiTimeoutMs = 50L,
            )

        val allowedOnTimeout = engine.evaluate("slow-domain.com") { fakeUpstream() }
        assertTrue(allowedOnTimeout is DnsDecision.AllowWithUpstream)
        assertEquals(null, cache.get("slow-domain.com"))

        currentUnsurePolicy = UnsurePolicy.BLOCK
        val blockedOnTimeout = engine.evaluate("slow-domain.com") { fakeUpstream() }
        assertTrue(blockedOnTimeout is DnsDecision.Block)
        assertEquals(BlockReason.AI, (blockedOnTimeout as DnsDecision.Block).reason)
    }

    @Test
    fun `same registrable domain requested twice concurrently verifies only once`() = runBlocking {
        val filter = DomainFilterEngine()
        val cache = VerdictCache(storageFile = null)
        val classifier = FakeClassifier {
            delay(80L)
            ClassificationResult(DomainVerdict.ADULT, 0.92)
        }
        val engine =
            DnsDecisionEngine(
                filterEngine = filter,
                verdictCache = cache,
                classifier = classifier,
                settingsProvider = { DecisionSettings(aiVerificationEnabled = true, hasApiKey = true) },
            )

        val results =
            listOf(
                    async { engine.evaluate("img1.concurrent-site.co.uk") { fakeUpstream() } },
                    async { engine.evaluate("img2.concurrent-site.co.uk") { fakeUpstream() } },
                )
                .awaitAll()

        assertTrue(results.all { it is DnsDecision.Block })
        assertEquals(1, classifier.callCount.get())
    }

    @Test
    fun `always allows generativelanguage googleapis com without AI check`() = runBlocking {
        val filter = DomainFilterEngine()
        val cache = VerdictCache(storageFile = null)
        val classifier = FakeClassifier { ClassificationResult(DomainVerdict.ADULT, 1.0) }
        val engine =
            DnsDecisionEngine(
                filterEngine = filter,
                verdictCache = cache,
                classifier = classifier,
                settingsProvider = {
                    DecisionSettings(
                        aiVerificationEnabled = true,
                        hasApiKey = true,
                        whenUnsure = UnsurePolicy.BLOCK,
                    )
                },
            )

        val decision = engine.evaluate("generativelanguage.googleapis.com") { fakeUpstream() }
        assertTrue(decision is DnsDecision.AllowWithUpstream)
        assertEquals(0, classifier.callCount.get())
    }

    @Test
    fun `upstream 0_0_0_0 answer is detected and cached as UPSTREAM_FILTER`() = runBlocking {
        val query = buildMinimalDnsQuery("upstream-adult.org", DnsWireCodec.QTYPE_A)
        val parsed = DnsWireCodec.parseQuery(query, query.size)!!
        val zeroResponse = DnsWireCodec.buildBlockedResponse(query, parsed)
        assertTrue(DnsWireCodec.isZeroIpAnswer(zeroResponse, zeroResponse.size))

        val filter = DomainFilterEngine()
        val cache = VerdictCache(storageFile = null)
        val classifier = FakeClassifier { ClassificationResult(DomainVerdict.SAFE, 1.0) }
        val engine =
            DnsDecisionEngine(
                filterEngine = filter,
                verdictCache = cache,
                classifier = classifier,
                settingsProvider = { DecisionSettings(aiVerificationEnabled = true, hasApiKey = true) },
            )

        val decision =
            engine.evaluate("upstream-adult.org") {
                UpstreamDnsResult(zeroResponse, isUpstreamBlocked = true)
            }
        assertTrue(decision is DnsDecision.Block)
        assertEquals(BlockReason.UPSTREAM_FILTER, (decision as DnsDecision.Block).reason)
        assertEquals(0, classifier.callCount.get())
        assertEquals(DomainVerdict.ADULT, cache.get("upstream-adult.org")?.verdict)
    }

    private fun buildMinimalDnsQuery(domain: String, qtype: Int): ByteArray {
        val labels = domain.split('.')
        val qnameBytes = mutableListOf<Byte>()
        for (label in labels) {
            qnameBytes.add(label.length.toByte())
            for (ch in label) qnameBytes.add(ch.code.toByte())
        }
        qnameBytes.add(0)

        val packet = ByteArray(12 + qnameBytes.size + 4)
        packet[0] = 0x12
        packet[1] = 0x34
        packet[2] = 0x01
        packet[5] = 0x01
        System.arraycopy(qnameBytes.toByteArray(), 0, packet, 12, qnameBytes.size)
        val typeOffset = 12 + qnameBytes.size
        packet[typeOffset] = ((qtype ushr 8) and 0xFF).toByte()
        packet[typeOffset + 1] = (qtype and 0xFF).toByte()
        packet[typeOffset + 3] = 0x01
        return packet
    }
}
