package com.example.protection

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.example.data.repository.ShieldRepository
import com.example.dns.DomainFilterEngine
import com.example.dns.DomainVerdict
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class ScreenGuardPipeline(
    private val service: AccessibilityService,
    private val repository: ShieldRepository,
    private val launcherPackageProvider: () -> String?,
    private val onTriggerBlock:
        (
            pkg: String,
            reason: ScreenGuardBlockReason,
            hostOrPackage: String,
            durationMs: Long,
            isTestScreen: Boolean,
        ) -> Unit,
    private val onTriggerCooldownOverlay: (pkg: String, remainingMs: Long) -> Unit,
    private val onClearSearchField: (pkg: String) -> Unit,
) {
    private val analysisScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val powerManager: PowerManager? by lazy {
        service.getSystemService(PowerManager::class.java)
    }

    val rateLimiter = ScreenGuardRateLimiter(SystemProtectionClock)

    private val lastVisualContentHashByPackage = ConcurrentHashMap<String, Int>()
    private val lastUrlCheckByPackage = ConcurrentHashMap<String, Pair<String, Long>>()

    private data class CollectedScreenData(
        val activePackage: String,
        val isTestScreen: Boolean,
        val addressBarText: String?,
        val snapshots: List<TextNodeSnapshot>,
        val contentHash: Int,
    )

    fun onAccessibilityEvent(eventPkg: String) {
        try {
            if (powerManager?.isInteractive == false) {
                return
            }

            val settings = repository.getCachedScreenGuardSettings()
            if (!settings.masterEnabled) {
                return
            }

            val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return
            val collected =
                try {
                    collectFromRoot(root, eventPkg, settings)
                } finally {
                    recycleSafely(root)
                } ?: return

            if (!collected.isTestScreen) {
                val cooldownRemaining =
                    rateLimiter.getAppEscalationCooldownRemainingMs(collected.activePackage)
                if (cooldownRemaining > 0L) {
                    onTriggerCooldownOverlay(collected.activePackage, cooldownRemaining)
                    return
                }
            }

            if (rateLimiter.isInBlockCooldown(collected.activePackage)) {
                return
            }

            if (!rateLimiter.tryAcquireScanSlot(collected.activePackage)) {
                return
            }

            analysisScope.launch {
                try {
                    analyzeCollectedData(collected, settings)
                } catch (_: Throwable) {
                    // Never crash AccessibilityService background workers
                }
            }
        } catch (_: Throwable) {
            // Never lag or crash the accessibility service
        }
    }

    fun isRootStillAdult(root: AccessibilityNodeInfo?, targetPkg: String): Boolean {
        if (root == null) return false
        return try {
            val settings = repository.getCachedScreenGuardSettings()
            if (!settings.masterEnabled) return false
            val collected = collectFromRoot(root, targetPkg, settings) ?: return false
            if (collected.activePackage != targetPkg) return false

            if (settings.searchGuardEnabled) {
                val searchHit =
                    collected.snapshots.any { snap ->
                        snap.isEditable &&
                            !snap.isPassword &&
                            snap.text.isNotBlank() &&
                            repository.textContentScorer.containsBlockedSearchTerm(
                                text = snap.text,
                                sensitivity = settings.sensitivity,
                                isPassword = false,
                            )
                    }
                if (searchHit) return true
            }

            if (settings.textScoringEnabled) {
                val score =
                    repository.textContentScorer.scoreNodes(
                        nodes = collected.snapshots,
                        sensitivity = settings.sensitivity,
                    )
                if (score.blocked) return true
            }

            if (
                settings.urlCheckEnabled &&
                    ScreenGuardExclusions.isKnownBrowserPackage(targetPkg) &&
                    !collected.addressBarText.isNullOrBlank()
            ) {
                val host = extractHostFromAddressBar(collected.addressBarText)
                if (host != null && repository.filterEngine.matchInstantBlock(host) != null) {
                    return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    fun clearBlockedSearchFieldsInRoot(
        root: AccessibilityNodeInfo?,
        targetPkg: String,
        sensitivity: ScreenGuardSensitivity,
    ) {
        if (root == null) return
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < MAX_INSPECTED_NODES) {
                val (node, depth) = queue.removeFirst()
                visited++
                val isRoot = node === root
                try {
                    if (node.isPassword) {
                        continue
                    }
                    val nodePkg = node.packageName?.toString().orEmpty()
                    if (node.isEditable && (nodePkg.isEmpty() || nodePkg == targetPkg)) {
                        val txt = node.text?.toString().orEmpty()
                        if (
                            txt.isNotBlank() &&
                                repository.textContentScorer.containsBlockedSearchTerm(
                                    text = txt,
                                    sensitivity = sensitivity,
                                    isPassword = false,
                                )
                        ) {
                            val args =
                                Bundle().apply {
                                    putCharSequence(
                                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                        "",
                                    )
                                }
                            runCatching {
                                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                            }
                        }
                    }

                    if (depth < MAX_TRAVERSAL_DEPTH) {
                        val count = node.childCount.coerceAtMost(MAX_CHILDREN_PER_NODE)
                        for (i in 0 until count) {
                            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                            queue.add(child to (depth + 1))
                        }
                    }
                } catch (_: Throwable) {
                    // Ignore inaccessible nodes
                } finally {
                    if (!isRoot) {
                        recycleSafely(node)
                    }
                }
            }
        } finally {
            while (queue.isNotEmpty()) {
                val (rem, _) = queue.removeFirst()
                if (rem !== root) recycleSafely(rem)
            }
        }
    }

    fun shutdown() {
        analysisScope.cancel()
    }

    private fun collectFromRoot(
        root: AccessibilityNodeInfo,
        eventPkg: String,
        settings: ScreenGuardSettings,
    ): CollectedScreenData? {
        val windowType = runCatching { root.window?.type }.getOrNull()
        if (
            windowType != null &&
                windowType != AccessibilityWindowInfo.TYPE_APPLICATION
        ) {
            return null
        }

        val activePkg = root.packageName?.toString().orEmpty().ifBlank { eventPkg }
        val allowOwnForTest = repository.isScreenGuardTestScreenOpen()
        if (
            !ScreenGuardExclusions.shouldMonitorPackage(
                pkg = activePkg,
                settings = settings,
                ownPackage = service.packageName,
                launcherPackage = launcherPackageProvider(),
                allowOwnPackageForTest = allowOwnForTest,
            )
        ) {
            return null
        }

        val isTestScreen = allowOwnForTest && activePkg == service.packageName
        val isBrowser = ScreenGuardExclusions.isKnownBrowserPackage(activePkg)

        val snapshots = ArrayList<TextNodeSnapshot>(48)
        var explicitAddressBarText: String? = null
        var fallbackAddressBarText: String? = null
        var fallbackAddressBarTop = Int.MAX_VALUE
        var contentHash = 17

        val bounds = Rect()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0

        try {
            while (queue.isNotEmpty() && visited < MAX_INSPECTED_NODES) {
                val (node, depth) = queue.removeFirst()
                visited++
                val isRoot = node === root
                try {
                    // Never scan password fields or any children under a password container
                    if (node.isPassword) {
                        continue
                    }

                    val viewId = runCatching { node.viewIdResourceName }.getOrNull()
                    val rawText = node.text?.toString()?.trim().orEmpty()
                    val rawDesc = node.contentDescription?.toString()?.trim().orEmpty()
                    val combinedText =
                        when {
                            rawText.isEmpty() -> rawDesc
                            rawDesc.isEmpty() || rawDesc.equals(rawText, ignoreCase = true) -> rawText
                            else -> "$rawText $rawDesc"
                        }

                    val isEditable = node.isEditable
                    runCatching { node.getBoundsInScreen(bounds) }
                    val topPx = bounds.top

                    if (combinedText.isNotEmpty()) {
                        contentHash = 31 * contentHash + combinedText.length
                        contentHash = 31 * contentHash + combinedText.hashCode()

                        val isHeading =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                node.isHeading
                            } else {
                                false
                            }
                        val isSearchField =
                            isEditable &&
                                (viewIdLooksLikeSearch(viewId) ||
                                    node.className?.toString()?.contains("EditText", ignoreCase = true) == true)
                        val isAddressBar =
                            isBrowser &&
                                (isKnownAddressBarId(viewId) ||
                                    (isEditable &&
                                        topPx in 0..ADDRESS_BAR_MAX_TOP_PX &&
                                        looksLikeUrlOrHost(rawText.ifEmpty { combinedText })))

                        if (isBrowser) {
                            val candidateUrlText = rawText.ifEmpty { rawDesc }
                            if (isKnownAddressBarId(viewId) && candidateUrlText.isNotBlank()) {
                                explicitAddressBarText = candidateUrlText
                            } else if (
                                explicitAddressBarText == null &&
                                    isEditable &&
                                    !candidateUrlText.contains('\n') &&
                                    topPx in 0..ADDRESS_BAR_MAX_TOP_PX &&
                                    topPx < fallbackAddressBarTop &&
                                    looksLikeUrlOrHost(candidateUrlText)
                            ) {
                                fallbackAddressBarTop = topPx
                                fallbackAddressBarText = candidateUrlText
                            }
                        }

                        val isProminent =
                            isHeading ||
                                viewIdLooksProminent(viewId) ||
                                isSearchField ||
                                (topPx in 0..PROMINENT_TOP_PX && combinedText.length in 3..140)

                        snapshots.add(
                            TextNodeSnapshot(
                                text = combinedText.take(MAX_NODE_TEXT_CHARS),
                                isPassword = false,
                                isProminent = isProminent,
                                isEditable = isEditable,
                                isSearchField = isSearchField,
                                isAddressBarCandidate = isAddressBar,
                                viewId = viewId,
                                topPx = topPx,
                            )
                        )
                    }

                    if (depth < MAX_TRAVERSAL_DEPTH) {
                        val count = node.childCount.coerceAtMost(MAX_CHILDREN_PER_NODE)
                        for (i in 0 until count) {
                            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                            queue.add(child to (depth + 1))
                        }
                    }
                } catch (_: Throwable) {
                    // Skip unreadable nodes
                } finally {
                    if (!isRoot) {
                        recycleSafely(node)
                    }
                }
            }
        } finally {
            while (queue.isNotEmpty()) {
                val (rem, _) = queue.removeFirst()
                if (rem !== root) recycleSafely(rem)
            }
        }

        if (snapshots.isEmpty()) {
            return null
        }

        return CollectedScreenData(
            activePackage = activePkg,
            isTestScreen = isTestScreen,
            addressBarText = explicitAddressBarText ?: fallbackAddressBarText,
            snapshots = snapshots,
            contentHash = contentHash,
        )
    }

    private suspend fun analyzeCollectedData(
        collected: CollectedScreenData,
        settings: ScreenGuardSettings,
    ) {
        val startNs = System.nanoTime()
        val pkg = collected.activePackage

        // Layer A: Browser URL check (uses existing blocklist, custom rules, keyword match, cache, 3s AI)
        if (
            !collected.isTestScreen &&
                settings.urlCheckEnabled &&
                ScreenGuardExclusions.isKnownBrowserPackage(pkg) &&
                !collected.addressBarText.isNullOrBlank()
        ) {
            val host = extractHostFromAddressBar(collected.addressBarText)
            if (host != null) {
                val now = SystemClock.elapsedRealtime()
                val prev = lastUrlCheckByPackage[pkg]
                val recentlyCheckedSameHost =
                    prev != null && prev.first == host && (now - prev.second) < URL_RECHECK_COOLDOWN_MS
                if (!recentlyCheckedSameHost) {
                    lastUrlCheckByPackage[pkg] = host to now
                    val summary = repository.decisionEngine.evaluateUrlDomain(host)
                    if (summary != null && summary.verdict == DomainVerdict.ADULT) {
                        onTriggerBlock(
                            pkg,
                            ScreenGuardBlockReason.URL,
                            summary.domain,
                            elapsedMs(startNs),
                            false,
                        )
                        return
                    }
                }
            }
        }

        // Layer C: Search Box Guard
        if (settings.searchGuardEnabled) {
            val searchHit =
                collected.snapshots.any { snap ->
                    snap.isEditable &&
                        !snap.isPassword &&
                        snap.text.isNotBlank() &&
                        repository.textContentScorer.containsBlockedSearchTerm(
                            text = snap.text,
                            sensitivity = settings.sensitivity,
                            isPassword = false,
                        )
                }
            if (searchHit) {
                onClearSearchField(pkg)
                onTriggerBlock(
                    pkg,
                    ScreenGuardBlockReason.SEARCH,
                    pkg,
                    elapsedMs(startNs),
                    collected.isTestScreen,
                )
                return
            }
        }

        // Layer B: On-Screen Text Scoring (strictly on-device)
        if (settings.textScoringEnabled) {
            val scoreResult =
                repository.textContentScorer.scoreNodes(
                    nodes = collected.snapshots,
                    sensitivity = settings.sensitivity,
                )
            if (scoreResult.blocked) {
                onTriggerBlock(
                    pkg,
                    ScreenGuardBlockReason.SCREEN_TEXT,
                    pkg,
                    elapsedMs(startNs),
                    collected.isTestScreen,
                )
                return
            }
        }

        // Layer D: Optional Visual Check (default OFF, API 30+, monitored browsers/video/social only)
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !collected.isTestScreen &&
                settings.visualCheckEnabled &&
                repository.getCurrentApiKey().isNotBlank() &&
                pkg !in ShieldAccessibilityService.PROTECTED_SETTINGS_PACKAGES &&
                ScreenGuardExclusions.isMonitoredVisualPackage(pkg)
        ) {
            val prevHash = lastVisualContentHashByPackage[pkg]
            if (prevHash != collected.contentHash && rateLimiter.tryAcquireVisualCheckSlot()) {
                lastVisualContentHashByPackage[pkg] = collected.contentHash
                val base64Jpeg = captureDownscaledJpegBase64()
                if (base64Jpeg != null) {
                    val visualResult = repository.classifyVisualThumbnail(base64Jpeg)
                    if (visualResult.adult && visualResult.confidence >= VISUAL_CONFIDENCE_THRESHOLD) {
                        onTriggerBlock(
                            pkg,
                            ScreenGuardBlockReason.VISUAL,
                            pkg,
                            elapsedMs(startNs),
                            false,
                        )
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureDownscaledJpegBase64(): String? {
        return withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                try {
                    service.takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        service.mainExecutor,
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                                var hwBitmap: Bitmap? = null
                                var softBitmap: Bitmap? = null
                                var scaledBitmap: Bitmap? = null
                                val encoded =
                                    try {
                                        val buffer = screenshot.hardwareBuffer
                                        val colorSpace = screenshot.colorSpace
                                        hwBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                                        buffer.close()
                                        val wrapped = hwBitmap
                                        if (wrapped == null) {
                                            null
                                        } else {
                                            softBitmap = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                                            wrapped.recycle()
                                            hwBitmap = null
                                            val bmp = softBitmap
                                            if (bmp == null || isBitmapBlank(bmp)) {
                                                null
                                            } else {
                                                val maxSide = maxOf(bmp.width, bmp.height)
                                                val target =
                                                    if (maxSide > VISUAL_MAX_DIMENSION_PX && maxSide > 0) {
                                                        val scale =
                                                            VISUAL_MAX_DIMENSION_PX.toFloat() /
                                                                maxSide.toFloat()
                                                        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
                                                        val h = (bmp.height * scale).toInt().coerceAtLeast(1)
                                                        scaledBitmap = Bitmap.createScaledBitmap(bmp, w, h, true)
                                                        scaledBitmap
                                                    } else {
                                                        bmp
                                                    }
                                                ByteArrayOutputStream().use { out ->
                                                    target.compress(
                                                        Bitmap.CompressFormat.JPEG,
                                                        VISUAL_JPEG_QUALITY,
                                                        out,
                                                    )
                                                    val bytes = out.toByteArray()
                                                    if (bytes.isNotEmpty()) {
                                                        Base64.encodeToString(bytes, Base64.NO_WRAP)
                                                    } else {
                                                        null
                                                    }
                                                }
                                            }
                                        }
                                    } catch (_: Throwable) {
                                        null
                                    } finally {
                                        runCatching { hwBitmap?.recycle() }
                                        runCatching { scaledBitmap?.recycle() }
                                        runCatching { softBitmap?.recycle() }
                                    }
                                if (continuation.isActive) {
                                    continuation.resume(encoded)
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                // Handles ERROR_TAKE_SCREENSHOT_SECURE_WINDOW and rate limits cleanly
                                if (continuation.isActive) {
                                    continuation.resume(null)
                                }
                            }
                        },
                    )
                } catch (_: Throwable) {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        }
    }

    private fun isBitmapBlank(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return true
        val stepX = (w / 6).coerceAtLeast(1)
        val stepY = (h / 6).coerceAtLeast(1)
        val firstPixel = bitmap.getPixel(w / 2, h / 2)
        var nonUniformFound = false
        var x = stepX / 2
        while (x < w) {
            var y = stepY / 2
            while (y < h) {
                val p = bitmap.getPixel(x, y)
                if (p != firstPixel) {
                    nonUniformFound = true
                    break
                }
                y += stepY
            }
            if (nonUniformFound) break
            x += stepX
        }
        return !nonUniformFound
    }

    private fun isKnownAddressBarId(viewId: String?): Boolean {
        if (viewId.isNullOrBlank()) return false
        if (viewId in ScreenGuardExclusions.KNOWN_BROWSER_ADDRESS_BAR_IDS) return true
        return ScreenGuardExclusions.ADDRESS_BAR_ID_SUFFIXES.any { suffix ->
            viewId.endsWith(suffix, ignoreCase = true)
        }
    }

    private fun viewIdLooksLikeSearch(viewId: String?): Boolean {
        if (viewId.isNullOrBlank()) return false
        val lower = viewId.lowercase(Locale.ROOT)
        return lower.contains("search") ||
            lower.contains("query") ||
            lower.contains("url_bar") ||
            lower.contains("omnibox") ||
            lower.contains("omnibar")
    }

    private fun viewIdLooksProminent(viewId: String?): Boolean {
        if (viewId.isNullOrBlank()) return false
        val lower = viewId.lowercase(Locale.ROOT)
        return lower.contains("title") ||
            lower.contains("heading") ||
            lower.contains("header") ||
            lower.contains("caption") ||
            lower.contains("video_title") ||
            lower.contains("description") ||
            lower.contains("search")
    }

    private fun looksLikeUrlOrHost(raw: String): Boolean {
        val t = raw.trim()
        if (t.isEmpty() || t.contains(' ') || t.length > 255) return false
        if (t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)) {
            return true
        }
        return t.contains('.') && !t.startsWith('.') && !t.endsWith('.')
    }

    private fun extractHostFromAddressBar(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.contains(' ')) return null
        val lower = trimmed.lowercase(Locale.ROOT)
        if (
            lower.startsWith("about:") ||
                lower.startsWith("chrome:") ||
                lower.startsWith("javascript:") ||
                lower.startsWith("file:")
        ) {
            return null
        }
        val withoutScheme =
            when {
                lower.startsWith("https://") -> trimmed.substring(8)
                lower.startsWith("http://") -> trimmed.substring(7)
                else -> trimmed
            }
        val hostPart =
            withoutScheme
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('@')
                .substringBefore(':')
        return DomainFilterEngine.normalizeDomain(hostPart)
    }

    private fun recycleSafely(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            runCatching { node.recycle() }
        }
    }

    private fun elapsedMs(startNs: Long): Long =
        ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)

    companion object {
        private const val MAX_TRAVERSAL_DEPTH = 20
        private const val MAX_INSPECTED_NODES = 300
        private const val MAX_CHILDREN_PER_NODE = 64
        private const val MAX_NODE_TEXT_CHARS = 500
        private const val ADDRESS_BAR_MAX_TOP_PX = 450
        private const val PROMINENT_TOP_PX = 750
        private const val URL_RECHECK_COOLDOWN_MS = 4000L
        private const val SCREENSHOT_TIMEOUT_MS = 1800L
        private const val VISUAL_MAX_DIMENSION_PX = 256
        private const val VISUAL_JPEG_QUALITY = 60
        private const val VISUAL_CONFIDENCE_THRESHOLD = 0.8
    }
}
