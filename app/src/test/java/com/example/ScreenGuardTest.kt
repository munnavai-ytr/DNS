package com.example

import com.example.protection.ContentTextNormalizer
import com.example.protection.ProtectionClock
import com.example.protection.ScreenGuardExclusions
import com.example.protection.ScreenGuardRateLimiter
import com.example.protection.ScreenGuardSensitivity
import com.example.protection.ScreenGuardSettings
import com.example.protection.TextContentScorer
import com.example.protection.TextNodeSnapshot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScreenGuardTest {

    private class FakeClock(
        var wallMs: Long = 1_700_000_000_000L,
        var elapsedMs: Long = 10_000L,
    ) : ProtectionClock {
        override fun wallTimeMs(): Long = wallMs

        override fun elapsedRealtimeMs(): Long = elapsedMs

        fun advanceBy(deltaMs: Long) {
            wallMs += deltaMs
            elapsedMs += deltaMs
        }
    }

    private lateinit var scorer: TextContentScorer

    @Before
    fun setUp() {
        scorer = TextContentScorer()
        val kwFile =
            listOf(
                    File("src/main/assets/adult_keywords.txt"),
                    File("app/src/main/assets/adult_keywords.txt"),
                )
                .firstOrNull { it.exists() }
        val safeFile =
            listOf(
                    File("src/main/assets/safe_context.txt"),
                    File("app/src/main/assets/safe_context.txt"),
                )
                .firstOrNull { it.exists() }

        if (kwFile != null && safeFile != null) {
            scorer.loadFromStreams(kwFile.inputStream(), safeFile.inputStream())
        } else {
            scorer.loadFromLines(
                keywordLines =
                    listOf(
                        "dnsshieldtestword|5",
                        "porn|5",
                        "nude|4",
                        "nudity|4",
                        "sexy|2",
                        "bikini|1",
                        "পর্ন|5",
                        "যৌন|3",
                    ),
                safeContextLines =
                    listOf("medical", "health", "biology", "news", "education", "court", "চিকিৎসা", "স্বাস্থ্য"),
            )
        }
    }

    @Test
    fun normalizer_stripsLatinDiacriticsZeroWidthObfuscationsAndUnifiesBengaliDigits() {
        val normalized = ContentTextNormalizer.normalizeForMatching("p.0.r.n\u200B  póŗn  ১২৩")
        assertTrue(normalized.tokens.contains("porn"))
        assertTrue(normalized.tokens.contains("123"))

        val bengaliText = ContentTextNormalizer.normalizeForMatching("পর্নোগ্রাফি চিকিৎসা ২০২৬")
        assertTrue(bengaliText.tokens.contains("পর্নোগ্রাফি"))
        assertTrue(bengaliText.tokens.contains("চিকিৎসা"))
        assertTrue(bengaliText.tokens.contains("2026"))
    }

    @Test
    fun scorer_blocksFixedTestTermAtLowMediumAndHighSensitivities() {
        for (sensitivity in ScreenGuardSensitivity.entries) {
            val res = scorer.scoreText("Live test: dnsshieldtestword", sensitivity)
            assertTrue("Expected block at $sensitivity", res.blocked)
            assertTrue(res.matchedTestTerm)
        }
    }

    @Test
    fun scorer_blocksObfuscatedVariantsOfTestTerm() {
        val variants =
            listOf(
                "d.n.s.s.h.i.e.l.d.t.e.s.t.w.o.r.d",
                "d n s s h i e l d t e s t w o r d",
                "dnsshield-testword",
                "dn55hi3ld7e5tw0rd",
                "ddnnsssshhiieellddtteessttwworrrdd",
                "dnsshield\u200Btestword",
            )

        for (variant in variants) {
            val res = scorer.scoreText(variant, ScreenGuardSensitivity.LOW)
            assertTrue("Expected obfuscated variant '$variant' to block", res.blocked)
        }
    }

    @Test
    fun scorer_allowsOrdinarySentences() {
        val sentences =
            listOf(
                "Weather forecast for Dhaka today shows mild breeze and clear evening sky.",
                "Learn Kotlin coroutines and Jetpack Compose state management.",
                "আজকের আবহাওয়া এবং শহরের খবর পড়ুন।",
            )
        for (sentence in sentences) {
            val res = scorer.scoreText(sentence, ScreenGuardSensitivity.HIGH)
            assertFalse("Ordinary sentence should not block: $sentence", res.blocked)
            assertEquals(0, res.finalScore)
        }
    }

    @Test
    fun scorer_allowsMedicalAndNewsContextWithSingleModerateTermOnMedium() {
        val medicalRes =
            scorer.scoreText(
                "Medical health education article on human anatomy and nudity awareness in clinic",
                ScreenGuardSensitivity.MEDIUM,
            )
        assertFalse("Medical/education sentence should be reduced below Medium threshold", medicalRes.blocked)
        assertTrue(medicalRes.safeContextReduction >= 4)

        val newsRes =
            scorer.scoreText(
                "News report from court legal hearing about online harassment prevention law",
                ScreenGuardSensitivity.MEDIUM,
            )
        assertFalse("News/legal sentence should not block", newsRes.blocked)
    }

    @Test
    fun scorer_doesNotBlockSingleWeakTermOnMedium() {
        val weakRes = scorer.scoreText("Summer beach bikini fashion sale", ScreenGuardSensitivity.MEDIUM)
        assertFalse("Single weight-1 term should stay below Medium threshold (5)", weakRes.blocked)
        assertEquals(1, weakRes.finalScore)
    }

    @Test
    fun scorer_appliesSafeContextReduction() {
        val withoutSafe = scorer.scoreText("nudity", ScreenGuardSensitivity.LOW)
        val withSafe = scorer.scoreText("medical biology health nudity", ScreenGuardSensitivity.LOW)
        assertEquals(4, withoutSafe.finalScore)
        assertTrue(withSafe.safeContextMatchCount >= 2)
        assertEquals(0, withSafe.finalScore)
    }

    @Test
    fun scorer_skipsPasswordFieldsCompletely() {
        val res =
            scorer.scoreNodes(
                nodes =
                    listOf(
                        TextNodeSnapshot(
                            text = "dnsshieldtestword porn p0rn",
                            isPassword = true,
                            isProminent = true,
                        )
                    ),
                sensitivity = ScreenGuardSensitivity.HIGH,
            )
        assertFalse("Password fields must never trigger blocking", res.blocked)
        assertEquals(0, res.rawScore)
        assertFalse(res.matchedTestTerm)

        assertFalse(
            scorer.containsBlockedSearchTerm(
                text = "dnsshieldtestword",
                sensitivity = ScreenGuardSensitivity.HIGH,
                isPassword = true,
            )
        )
    }

    @Test
    fun rateLimiter_enforces400msPerPackageDebounceWithFakeClock() {
        val clock = FakeClock()
        val limiter = ScreenGuardRateLimiter(clock)

        assertTrue(limiter.tryAcquireScanSlot("com.android.chrome"))
        // Immediately after (0 ms) -> debounced
        assertFalse(limiter.tryAcquireScanSlot("com.android.chrome"))

        clock.advanceBy(399L)
        assertFalse(limiter.tryAcquireScanSlot("com.android.chrome"))

        // Different package has independent debounce slot
        assertTrue(limiter.tryAcquireScanSlot("com.google.android.youtube"))

        clock.advanceBy(1L) // 400 ms elapsed for Chrome
        assertTrue(limiter.tryAcquireScanSlot("com.android.chrome"))
    }

    @Test
    fun rateLimiter_triggersFiveMinuteCooldownAfterThreeBlocksInTenMinutes() {
        val clock = FakeClock()
        val limiter = ScreenGuardRateLimiter(clock)
        val pkg = "com.android.chrome"

        assertFalse(limiter.recordBlockAndCheckEscalation(pkg))
        assertEquals(0L, limiter.getAppEscalationCooldownRemainingMs(pkg))

        clock.advanceBy(60_000L) // +1m
        assertFalse(limiter.recordBlockAndCheckEscalation(pkg))
        assertEquals(0L, limiter.getAppEscalationCooldownRemainingMs(pkg))

        clock.advanceBy(120_000L) // +2m (3 blocks in 3m <= 10m)
        assertTrue(limiter.recordBlockAndCheckEscalation(pkg))
        assertEquals(ScreenGuardRateLimiter.ESCALATION_COOLDOWN_MS, limiter.getAppEscalationCooldownRemainingMs(pkg))

        clock.advanceBy(4L * 60_000L) // 1m remaining
        assertEquals(60_000L, limiter.getAppEscalationCooldownRemainingMs(pkg))

        clock.advanceBy(60_001L) // expired
        assertEquals(0L, limiter.getAppEscalationCooldownRemainingMs(pkg))
    }

    @Test
    fun exclusions_neverScanOwnAppLauncherBankingOrAllowedAppsUnlessTestActive() {
        val settings =
            ScreenGuardSettings(
                masterEnabled = true,
                monitorAllApps = true,
                allowedApps = setOf("com.safe.reader"),
            )
        assertFalse(
            ScreenGuardExclusions.shouldMonitorPackage(
                pkg = "com.example.dnsshield",
                settings = settings,
                ownPackage = "com.example.dnsshield",
                launcherPackage = "com.google.android.apps.nexuslauncher",
                allowOwnPackageForTest = false,
            )
        )
        assertTrue(
            ScreenGuardExclusions.shouldMonitorPackage(
                pkg = "com.example.dnsshield",
                settings = settings,
                ownPackage = "com.example.dnsshield",
                launcherPackage = "com.google.android.apps.nexuslauncher",
                allowOwnPackageForTest = true,
            )
        )
        assertFalse(
            ScreenGuardExclusions.shouldMonitorPackage(
                pkg = "com.bKash.customerapp",
                settings = settings,
                ownPackage = "com.example.dnsshield",
                launcherPackage = "com.google.android.apps.nexuslauncher",
            )
        )
        assertFalse(
            ScreenGuardExclusions.shouldMonitorPackage(
                pkg = "com.safe.reader",
                settings = settings,
                ownPackage = "com.example.dnsshield",
                launcherPackage = "com.google.android.apps.nexuslauncher",
            )
        )
        assertTrue(
            ScreenGuardExclusions.shouldMonitorPackage(
                pkg = "com.android.chrome",
                settings = settings,
                ownPackage = "com.example.dnsshield",
                launcherPackage = "com.google.android.apps.nexuslauncher",
            )
        )
    }
}
