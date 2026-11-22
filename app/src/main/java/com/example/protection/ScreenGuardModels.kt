package com.example.protection

import com.example.dns.BlockReason
import java.util.Locale

enum class ScreenGuardSensitivity(
    val label: String,
    val level: Int,
    val threshold: Int,
) {
    LOW(label = "Low", level = 0, threshold = 7),
    MEDIUM(label = "Medium", level = 1, threshold = 5),
    HIGH(label = "High", level = 2, threshold = 3);

    companion object {
        val DEFAULT = MEDIUM

        fun fromStored(raw: String?): ScreenGuardSensitivity {
            return entries.firstOrNull { it.name == raw } ?: DEFAULT
        }
    }
}

enum class ScreenGuardBlockReason(val label: String) {
    URL("URL"),
    SCREEN_TEXT("Screen text"),
    SEARCH("Search"),
    VISUAL("Visual"),
}

data class ScreenGuardSettings(
    val masterEnabled: Boolean = true,
    val urlCheckEnabled: Boolean = true,
    val textScoringEnabled: Boolean = true,
    val searchGuardEnabled: Boolean = true,
    val visualCheckEnabled: Boolean = false,
    val sensitivity: ScreenGuardSensitivity = ScreenGuardSensitivity.DEFAULT,
    val monitorAllApps: Boolean = true,
    val allowedApps: Set<String> = emptySet(),
)

data class TextNodeSnapshot(
    val text: String,
    val isPassword: Boolean = false,
    val isProminent: Boolean = false,
    val isEditable: Boolean = false,
    val isSearchField: Boolean = false,
    val isAddressBarCandidate: Boolean = false,
    val viewId: String? = null,
    val topPx: Int = Int.MAX_VALUE,
)

data class TextScoreResult(
    val rawScore: Int,
    val safeContextReduction: Int,
    val finalScore: Int,
    val threshold: Int,
    val blocked: Boolean,
    val matchedTestTerm: Boolean,
    val matchedTermCount: Int,
    val safeContextMatchCount: Int,
)

data class UrlCheckDecision(
    val shouldBlock: Boolean,
    val host: String?,
    val blockReason: BlockReason? = null,
)

/**
 * Single centralized registry of excluded sensitive packages (banking, wallets, authenticators,
 * system/launcher/dialer apps), monitored browsers, and monitored video/social packages.
 */
object ScreenGuardExclusions {
    val SENSITIVE_BANKING_AND_AUTH_PACKAGES: Set<String> =
        setOf(
            // Authenticators & Password Managers
            "com.google.android.apps.authenticator2",
            "com.azure.authenticator",
            "com.authy.authy",
            "org.fedorahosted.freeotp",
            "com.duosecurity.duomobile",
            "com.agilebits.onepassword",
            "com.x8bit.bitwarden",
            "com.lastpass.lpandroid",
            "com.dashlane",
            "com.kunzisoft.keepass.free",
            "keepass2android.keepass2android",
            // Wallets & Payments
            "com.google.android.apps.walletnfcrel",
            "com.google.android.apps.nbu.paisa.user",
            "com.samsung.android.spay",
            "com.paypal.android.p2pmobile",
            "com.venmo",
            "com.squareup.cash",
            "com.zellepay.zelle",
            "com.bkash.customerapp",
            "com.konasl.nagad",
            "com.dbbl.nexus.pay",
            "com.upay.customer",
            "com.sslwireless.rocket",
            // Banking Apps
            "com.chase.sig.android",
            "com.infonow.bofa",
            "com.wf.wellsfargomobile",
            "com.citi.citimobile",
            "com.konylabs.capitalone",
            "com.revolut.revolut",
            "com.transferwise.android",
            "co.uk.getmondo",
            "uk.co.hsbc.hsbcukmobilebanking",
            "com.scb.breezemobile.bd",
            "com.islamibankbd.cellfin",
            "com.citybank.citytouch",
            "com.bracbank.astha",
            "com.dbbl.mibanking",
        )

    val NEVER_SCAN_SYSTEM_PACKAGES: Set<String> =
        setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.incallui",
            "com.google.android.dialer",
            "com.samsung.android.incallui",
            "com.android.dialer",
        )

    val KNOWN_BROWSER_PACKAGES: Set<String> =
        setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.fenix",
            "org.mozilla.focus",
            "com.sec.android.app.sbrowser",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.opera.gx",
            "com.vivaldi.browser",
            "com.duckduckgo.mobile.android",
            "com.kiwibrowser.browser",
            "mark.via.gp",
            "mark.via",
        )

    val MONITORED_SOCIAL_VIDEO_PACKAGES: Set<String> =
        setOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.kids",
            "app.revanced.android.youtube",
            "com.instagram.android",
            "com.facebook.katana",
            "com.facebook.lite",
            "com.facebook.orca",
            "com.twitter.android",
            "com.reddit.frontpage",
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.thunderdog.challegram",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.snapchat.android",
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.pinterest",
            "com.tumblr",
            "com.discord",
        )

    val KNOWN_BROWSER_ADDRESS_BAR_IDS: Set<String> =
        setOf(
            "com.android.chrome:id/url_bar",
            "com.chrome.beta:id/url_bar",
            "com.chrome.dev:id/url_bar",
            "com.chrome.canary:id/url_bar",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "org.mozilla.firefox:id/url_bar_title",
            "org.mozilla.fenix:id/mozac_browser_toolbar_url_view",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.brave.browser:id/url_bar",
            "com.microsoft.emmx:id/url_bar",
            "com.opera.browser:id/url_field",
            "com.opera.mini.native:id/url_field",
            "com.vivaldi.browser:id/url_bar",
            "com.duckduckgo.mobile.android:id/omnibarTextInput",
            "com.kiwibrowser.browser:id/url_bar",
            "mark.via.gp:id/url_bar",
        )

    val ADDRESS_BAR_ID_SUFFIXES: List<String> =
        listOf(
            ":id/url_bar",
            ":id/url_field",
            ":id/omnibarTextInput",
            ":id/location_bar_edit_text",
            ":id/mozac_browser_toolbar_url_view",
            ":id/url_bar_title",
        )

    fun isKnownBrowserPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val lower = pkg.lowercase(Locale.ROOT)
        return lower in KNOWN_BROWSER_PACKAGES || lower.contains("browser")
    }

    fun isMonitoredVisualPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val lower = pkg.lowercase(Locale.ROOT)
        return isKnownBrowserPackage(lower) || lower in MONITORED_SOCIAL_VIDEO_PACKAGES
    }

    fun isSoftKeyboardPackage(pkg: String): Boolean {
        val lower = pkg.lowercase(Locale.ROOT)
        return lower.contains("inputmethod") ||
            lower.contains("keyboard") ||
            lower.contains("gboard") ||
            lower.contains("honeyboard") ||
            lower.contains("swiftkey")
    }

    fun isExcludedBankingOrAuthPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val lower = pkg.lowercase(Locale.ROOT)
        if (lower in SENSITIVE_BANKING_AND_AUTH_PACKAGES) return true
        return lower.contains("authenticator") ||
            lower.contains("wallet") ||
            lower.contains(".bank") ||
            lower.contains("banking")
    }

    fun shouldNeverScanPackage(
        pkg: String,
        ownPackage: String,
        launcherPackage: String?,
        allowOwnPackageForTest: Boolean = false,
    ): Boolean {
        if (pkg.isBlank()) return true
        if (pkg == ownPackage) return !allowOwnPackageForTest
        if (launcherPackage != null && pkg == launcherPackage) return true
        if (pkg in NEVER_SCAN_SYSTEM_PACKAGES) return true
        if (isSoftKeyboardPackage(pkg)) return true
        if (isExcludedBankingOrAuthPackage(pkg)) return true
        return false
    }

    fun shouldMonitorPackage(
        pkg: String,
        settings: ScreenGuardSettings,
        ownPackage: String,
        launcherPackage: String?,
        allowOwnPackageForTest: Boolean = false,
    ): Boolean {
        if (
            shouldNeverScanPackage(
                pkg = pkg,
                ownPackage = ownPackage,
                launcherPackage = launcherPackage,
                allowOwnPackageForTest = allowOwnPackageForTest,
            )
        ) {
            return false
        }
        if (pkg == ownPackage && allowOwnPackageForTest) {
            return true
        }
        val lower = pkg.lowercase(Locale.ROOT)
        if (lower in settings.allowedApps) {
            return false
        }
        return if (settings.monitorAllApps) {
            true
        } else {
            isKnownBrowserPackage(pkg) || pkg in MONITORED_SOCIAL_VIDEO_PACKAGES
        }
    }
}
