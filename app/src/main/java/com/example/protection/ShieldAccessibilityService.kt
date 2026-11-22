package com.example.protection

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.DnsShieldApplication
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Centralized phrases matched by DNS Shield Guard.
 * Kept in one list in code so additional languages can be added easily later.
 */
object GuardedScreenPhrases {
    val APP_IDENTITY_PHRASES =
        listOf(
            "dns shield",
        )

    val PRIVATE_DNS_PHRASES =
        listOf(
            "private dns",
        )
}

class ShieldAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var screenGuardPipeline: ScreenGuardPipeline? = null

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayLockTypeShown: LockType? = null
    private var overlayShownAtMs: Long = 0L
    private var lastProcessedElapsedMs: Long = 0L
    private var statusTextView: TextView? = null
    private var pinInputView: EditText? = null
    private var submitPinButton: Button? = null

    // V4 Screen Guard content-block / 5-minute cooldown overlay (non-focusable, separate from V3 overlay)
    private var contentBlockOverlayView: View? = null
    private var blockedOverlayPackage: String? = null
    private var isCooldownOverlayShowing: Boolean = false
    private var cooldownOverlayPackage: String? = null
    private var cooldownMessageTextView: TextView? = null
    private var pendingDismissBlockRunnable: Runnable? = null
    private var pendingBackRecheckRunnable: Runnable? = null
    private var pendingCooldownTickRunnable: Runnable? = null

    private var timerTicker: ProtectionTimerTicker? = null

    @Volatile private var cachedLauncherPackage: String? = null
    @Volatile private var lastBounceElapsedMs = 0L
    @Volatile private var lastToastElapsedMs = 0L
    @Volatile private var lastTimerEventRefreshElapsedMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WindowManager::class.java)
        cachedLauncherPackage = ProtectionSystemChecker.getDefaultLauncherPackage(applicationContext)
        val app = applicationContext as? DnsShieldApplication
        if (app != null) {
            app.repository.markScreenGuardHeartbeat()
            ensureScreenGuardPipeline(app.repository)
            timerTicker?.stop()
            timerTicker =
                ProtectionTimerTicker(scope = serviceScope, repository = app.repository).also {
                    it.startObserving()
                }
        }
        ProtectionWatchdogReceiver.triggerAudit(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val app = applicationContext as? DnsShieldApplication ?: return
        val repository = app.repository
        repository.markScreenGuardHeartbeat()
        val pipeline = ensureScreenGuardPipeline(repository)

        val tamperState = repository.protectionUiSnapshot.value.tamperState
        if (ProtectionWatchdogScheduler.hasActiveTimer(tamperState)) {
            val nowElapsed = SystemClock.elapsedRealtime()
            if (nowElapsed - lastTimerEventRefreshElapsedMs >= TIMER_EVENT_REFRESH_INTERVAL_MS) {
                lastTimerEventRefreshElapsedMs = nowElapsed
                val wasUnlockActive = tamperState.isUnlockWindowActive
                serviceScope.launch {
                    repository.refreshProtectionState(
                        restartVpnIfProtectedAndDown = wasUnlockActive
                    )
                }
            }
        }

        val eventType = event?.eventType
        val eventPkg = event?.packageName?.toString().orEmpty()

        // --- V3 Tamper Guard (settings / uninstaller lock) ---
        val isV3Blocking = repository.isGuardBlockingNowSync()
        if (!isV3Blocking) {
            removeOverlay()
        } else {
            // If V3 overlay is showing and the event comes from our own package or a soft keyboard,
            // return immediately without removing the overlay or walking the node tree.
            if (overlayView != null && (eventPkg == packageName || isSoftKeyboardPackage(eventPkg))) {
                return
            }

            if (
                eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ) {
                handleV3TamperGuard(eventType = eventType, eventPkg = eventPkg, repository = repository)
            }
        }

        // If V3 Tamper Guard overlay is active, do not run V4 content scanning underneath it
        if (overlayView != null) {
            return
        }

        // Dismiss V4 overlay immediately when the user switches to a different app
        if (
            contentBlockOverlayView != null &&
                eventPkg.isNotBlank() &&
                eventPkg != packageName &&
                !isSoftKeyboardPackage(eventPkg)
        ) {
            val activeTarget =
                if (isCooldownOverlayShowing) cooldownOverlayPackage else blockedOverlayPackage
            if (activeTarget != null && eventPkg != activeTarget) {
                removeContentBlockOverlay()
            }
        }

        // --- V4 In-App Screen Guard Pipeline ---
        if (
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            pipeline.onAccessibilityEvent(eventPkg)
        }
    }

    private fun handleV3TamperGuard(
        eventType: Int?,
        eventPkg: String,
        repository: com.example.data.repository.ShieldRepository,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (now - lastProcessedElapsedMs < CONTENT_CHANGED_DEBOUNCE_MS) {
                return
            }
        }
        lastProcessedElapsedMs = now

        val launcherPkg = resolveLauncherPackage()
        val root = runCatching { rootInActiveWindow }.getOrNull()
        if (root == null) {
            if (
                overlayView != null &&
                    eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    eventPkg.isNotBlank()
            ) {
                val isLauncher = launcherPkg != null && eventPkg == launcherPkg
                val isExternalNonGuarded =
                    eventPkg != packageName &&
                        !isSoftKeyboardPackage(eventPkg) &&
                        eventPkg !in PROTECTED_SETTINGS_PACKAGES
                val elapsedSinceShown = now - overlayShownAtMs
                if (
                    isLauncher ||
                        (isExternalNonGuarded && elapsedSinceShown >= OVERLAY_HYSTERESIS_MS)
                ) {
                    removeOverlay()
                }
            }
            return
        }

        try {
            val activePkg = root.packageName?.toString().orEmpty().ifBlank { eventPkg }

            // If overlay is already showing and the active window is our own package or a soft
            // keyboard, return immediately without removing the overlay or walking the node tree.
            if (
                overlayView != null &&
                    (activePkg == packageName || isSoftKeyboardPackage(activePkg))
            ) {
                return
            }

            // Never guard own app or soft keyboard when overlay is not showing
            if (activePkg == packageName || isSoftKeyboardPackage(activePkg)) {
                return
            }

            // Default launcher always dismisses the overlay immediately (even within hysteresis)
            if (launcherPkg != null && activePkg == launcherPkg) {
                removeOverlay()
                return
            }

            val elapsedSinceShown = SystemClock.elapsedRealtime() - overlayShownAtMs

            // Non-guarded package (neither own package, nor keyboard, nor in PROTECTED_SETTINGS_PACKAGES)
            if (activePkg.isBlank() || activePkg !in PROTECTED_SETTINGS_PACKAGES) {
                if (overlayView != null && elapsedSinceShown >= OVERLAY_HYSTERESIS_MS) {
                    removeOverlay()
                }
                return
            }

            val mode = repository.getCachedProtectionMode()
            val lockType = repository.getCachedLockType()
            val shouldGuard = matchesGuardedScreen(root, mode)

            if (shouldGuard) {
                showOrUpdateOverlay(lockType, repository)
            } else if (overlayView != null && elapsedSinceShown >= OVERLAY_HYSTERESIS_MS) {
                removeOverlay()
            }
        } catch (_: Throwable) {
            // Never lag or crash the accessibility service
        } finally {
            recycleSafely(root)
        }
    }

    override fun onInterrupt() {
        removeOverlay()
        removeContentBlockOverlay()
        screenGuardPipeline?.shutdown()
    }

    override fun onDestroy() {
        removeOverlay()
        removeContentBlockOverlay()
        screenGuardPipeline?.shutdown()
        screenGuardPipeline = null
        timerTicker?.stop()
        timerTicker = null
        serviceScope.cancel()
        super.onDestroy()
        runCatching { ProtectionWatchdogReceiver.triggerAudit(applicationContext) }
    }

    private fun resolveLauncherPackage(): String? {
        return cachedLauncherPackage
            ?: ProtectionSystemChecker.getDefaultLauncherPackage(applicationContext)?.also {
                cachedLauncherPackage = it
            }
    }

    private fun ensureScreenGuardPipeline(
        repository: com.example.data.repository.ShieldRepository
    ): ScreenGuardPipeline {
        val existing = screenGuardPipeline
        if (existing != null) return existing
        return ScreenGuardPipeline(
                service = this,
                repository = repository,
                launcherPackageProvider = { resolveLauncherPackage() },
                onTriggerBlock = { pkg, reason, hostOrPackage, durationMs, isTestScreen ->
                    mainHandler.post {
                        handleScreenGuardBlock(
                            repository = repository,
                            pkg = pkg,
                            reason = reason,
                            hostOrPackage = hostOrPackage,
                            durationMs = durationMs,
                            isTestScreen = isTestScreen,
                        )
                    }
                },
                onTriggerCooldownOverlay = { pkg, remainingMs ->
                    mainHandler.post { showAppCooldownOverlay(pkg, remainingMs) }
                },
                onClearSearchField = { pkg ->
                    mainHandler.post {
                        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return@post
                        try {
                            screenGuardPipeline?.clearBlockedSearchFieldsInRoot(
                                root = root,
                                targetPkg = pkg,
                                sensitivity = repository.getCachedScreenGuardSettings().sensitivity,
                            )
                        } finally {
                            recycleSafely(root)
                        }
                    }
                },
            )
            .also { screenGuardPipeline = it }
    }

    private fun handleScreenGuardBlock(
        repository: com.example.data.repository.ShieldRepository,
        pkg: String,
        reason: ScreenGuardBlockReason,
        hostOrPackage: String,
        durationMs: Long,
        isTestScreen: Boolean,
    ) {
        val pipeline = screenGuardPipeline ?: return
        if (pipeline.rateLimiter.isInBlockCooldown(pkg)) {
            return
        }

        val escalated =
            if (!isTestScreen) {
                pipeline.rateLimiter.recordBlockAndCheckEscalation(pkg)
            } else {
                pipeline.rateLimiter.recordBlockWithoutEscalation(pkg)
                false
            }

        serviceScope.launch {
            repository.recordScreenGuardBlock(
                reason = reason,
                hostOrPackage = hostOrPackage,
                packageName = pkg,
                durationMs = durationMs,
            )
        }

        if (escalated) {
            val remaining =
                pipeline.rateLimiter
                    .getAppEscalationCooldownRemainingMs(pkg)
                    .coerceAtLeast(ScreenGuardRateLimiter.ESCALATION_COOLDOWN_MS)
            showAppCooldownOverlay(pkg, remaining)
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        showTransientContentBlockOverlay(pkg)
        performGlobalAction(GLOBAL_ACTION_BACK)

        pendingBackRecheckRunnable?.let { mainHandler.removeCallbacks(it) }
        val recheckRunnable =
            Runnable {
                pendingBackRecheckRunnable = null
                val root = runCatching { rootInActiveWindow }.getOrNull() ?: return@Runnable
                try {
                    val activePkg = root.packageName?.toString().orEmpty()
                    if (activePkg == pkg && pipeline.isRootStillAdult(root, pkg)) {
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    }
                } finally {
                    recycleSafely(root)
                }
            }
        pendingBackRecheckRunnable = recheckRunnable
        mainHandler.postDelayed(recheckRunnable, BACK_RECHECK_DELAY_MS)
    }

    private fun showTransientContentBlockOverlay(pkg: String) {
        if (isCooldownOverlayShowing && cooldownOverlayPackage == pkg) {
            return
        }
        removeContentBlockOverlay()

        val view = buildContentBlockOverlayView()
        val params =
            WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE,
                )
                .apply { gravity = Gravity.CENTER }

        try {
            val wm = windowManager ?: getSystemService(WindowManager::class.java)
            wm?.addView(view, params)
            windowManager = wm
            contentBlockOverlayView = view
            blockedOverlayPackage = pkg
            isCooldownOverlayShowing = false

            val dismissRunnable =
                Runnable {
                    pendingDismissBlockRunnable = null
                    if (!isCooldownOverlayShowing) {
                        removeContentBlockOverlay()
                    }
                }
            pendingDismissBlockRunnable = dismissRunnable
            mainHandler.postDelayed(dismissRunnable, BLOCK_OVERLAY_DURATION_MS)
        } catch (_: Throwable) {
            contentBlockOverlayView = null
            blockedOverlayPackage = null
            fallbackGoHome()
        }
    }

    private fun showAppCooldownOverlay(pkg: String, remainingMs: Long) {
        if (
            contentBlockOverlayView != null &&
                isCooldownOverlayShowing &&
                cooldownOverlayPackage == pkg
        ) {
            cooldownMessageTextView?.text = formatCooldownMessage(remainingMs)
            return
        }
        removeContentBlockOverlay()

        val view = buildCooldownOverlayView(remainingMs)
        val params =
            WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE,
                )
                .apply { gravity = Gravity.CENTER }

        try {
            val wm = windowManager ?: getSystemService(WindowManager::class.java)
            wm?.addView(view, params)
            windowManager = wm
            contentBlockOverlayView = view
            cooldownOverlayPackage = pkg
            isCooldownOverlayShowing = true
            scheduleCooldownCountdownTick(pkg)
        } catch (_: Throwable) {
            contentBlockOverlayView = null
            cooldownOverlayPackage = null
            isCooldownOverlayShowing = false
            fallbackGoHome()
        }
    }

    private fun scheduleCooldownCountdownTick(pkg: String) {
        pendingCooldownTickRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable =
            object : Runnable {
                override fun run() {
                    if (!isCooldownOverlayShowing || cooldownOverlayPackage != pkg) return
                    val rem =
                        screenGuardPipeline?.rateLimiter?.getAppEscalationCooldownRemainingMs(pkg)
                            ?: 0L
                    if (rem <= 0L) {
                        removeContentBlockOverlay()
                    } else {
                        cooldownMessageTextView?.text = formatCooldownMessage(rem)
                        mainHandler.postDelayed(this, 1000L)
                    }
                }
            }
        pendingCooldownTickRunnable = runnable
        mainHandler.postDelayed(runnable, 1000L)
    }

    private fun formatCooldownMessage(remainingMs: Long): String {
        val totalSeconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(1L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(
            "Screen Guard blocked adult content 3 times within 10 minutes. Cool down remaining: %02d:%02d.",
            minutes,
            seconds,
        )
    }

    private fun removeContentBlockOverlay() {
        pendingDismissBlockRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingDismissBlockRunnable = null
        pendingCooldownTickRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingCooldownTickRunnable = null
        val current = contentBlockOverlayView ?: return
        contentBlockOverlayView = null
        blockedOverlayPackage = null
        cooldownOverlayPackage = null
        isCooldownOverlayShowing = false
        cooldownMessageTextView = null
        runCatching { windowManager?.removeViewImmediate(current) }
            .onFailure { runCatching { windowManager?.removeView(current) } }
    }

    private fun buildContentBlockOverlayView(): View {
        val ctx: Context = this
        val rootContainer =
            FrameLayout(ctx).apply {
                setBackgroundColor(Color.parseColor("#0B111E"))
                isClickable = true
            }
        val card =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val pad = dp(24)
                setPadding(pad, pad, pad, pad)
                background =
                    GradientDrawable().apply {
                        setColor(Color.parseColor("#131C2E"))
                        cornerRadius = dp(20).toFloat()
                        setStroke(dp(1), Color.parseColor("#FB7185"))
                    }
            }
        val titleView =
            TextView(ctx).apply {
                text = getString(R.string.screen_guard_blocked_overlay)
                setTextColor(Color.parseColor("#F8FAFC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
        card.addView(titleView)

        val subtitleView =
            TextView(ctx).apply {
                text = "Returning to a safe screen…"
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
            }
        card.addView(subtitleView)

        val cardLp =
            FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                )
                .apply {
                    val margin = dp(24)
                    setMargins(margin, margin, margin, margin)
                }
        rootContainer.addView(card, cardLp)
        return rootContainer
    }

    private fun buildCooldownOverlayView(remainingMs: Long): View {
        val ctx: Context = this
        val rootContainer =
            FrameLayout(ctx).apply {
                setBackgroundColor(Color.parseColor("#0B111E"))
                isClickable = true
            }
        val card =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val pad = dp(24)
                setPadding(pad, pad, pad, pad)
                background =
                    GradientDrawable().apply {
                        setColor(Color.parseColor("#131C2E"))
                        cornerRadius = dp(20).toFloat()
                        setStroke(dp(1), Color.parseColor("#FBBF24"))
                    }
            }
        val titleView =
            TextView(ctx).apply {
                text = getString(R.string.screen_guard_cooldown_title)
                setTextColor(Color.parseColor("#F8FAFC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
        card.addView(titleView)

        val msgView =
            TextView(ctx).apply {
                text = formatCooldownMessage(remainingMs)
                setTextColor(Color.parseColor("#CBD5E1"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(18))
            }
        cooldownMessageTextView = msgView
        card.addView(msgView)

        val goHomeBtn =
            Button(ctx).apply {
                text = getString(R.string.overlay_go_home)
                isAllCaps = false
                setTextColor(Color.parseColor("#042F2E"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
                background =
                    GradientDrawable().apply {
                        setColor(Color.parseColor("#34D399"))
                        cornerRadius = dp(12).toFloat()
                    }
                minimumHeight = dp(48)
                setOnClickListener {
                    removeContentBlockOverlay()
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }
        card.addView(
            goHomeBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val cardLp =
            FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                )
                .apply {
                    val margin = dp(24)
                    setMargins(margin, margin, margin, margin)
                }
        rootContainer.addView(card, cardLp)
        return rootContainer
    }

    private fun showOrUpdateOverlay(
        lockType: LockType,
        repository: com.example.data.repository.ShieldRepository,
    ) {
        if (overlayView != null && overlayLockTypeShown == lockType) {
            return
        }
        if (overlayView != null) {
            removeOverlay()
        }

        val view = buildOverlayView(lockType, repository)
        val params =
            WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE,
                )
                .apply {
                    gravity = Gravity.CENTER
                    softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                }

        try {
            val wm = windowManager ?: getSystemService(WindowManager::class.java)
            wm?.addView(view, params)
            windowManager = wm
            overlayView = view
            overlayLockTypeShown = lockType
            overlayShownAtMs = SystemClock.elapsedRealtime()
            view.requestFocus()
            view.post {
                if (overlayView === view && !view.hasFocus()) {
                    view.requestFocus()
                }
            }
        } catch (_: Throwable) {
            // Fallback to GLOBAL_ACTION_HOME if overlay cannot be shown
            overlayView = null
            overlayLockTypeShown = null
            overlayShownAtMs = 0L
            fallbackGoHome()
        }
    }

    private fun removeOverlay() {
        val current = overlayView ?: return
        overlayView = null
        overlayLockTypeShown = null
        overlayShownAtMs = 0L
        statusTextView = null
        pinInputView = null
        submitPinButton = null
        runCatching { windowManager?.removeViewImmediate(current) }
            .onFailure { runCatching { windowManager?.removeView(current) } }
    }

    private fun fallbackGoHome() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBounceElapsedMs >= BOUNCE_COOLDOWN_MS) {
            lastBounceElapsedMs = now
            performGlobalAction(GLOBAL_ACTION_HOME)
            showToastThrottled(getString(R.string.protected_by_dns_shield))
        }
    }

    private fun showToastThrottled(message: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastToastElapsedMs >= TOAST_COOLDOWN_MS) {
            lastToastElapsedMs = now
            mainHandler.post {
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun buildOverlayView(
        lockType: LockType,
        repository: com.example.data.repository.ShieldRepository,
    ): View {
        val ctx: Context = this
        val rootContainer =
            object : FrameLayout(ctx) {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                            removeOverlay()
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        }
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }
            }.apply {
                setBackgroundColor(Color.parseColor("#0B111E"))
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
                setOnKeyListener { _, keyCode, keyEvent ->
                    if (keyCode == KeyEvent.KEYCODE_BACK) {
                        if (keyEvent.action == KeyEvent.ACTION_UP && !keyEvent.isCanceled) {
                            removeOverlay()
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        }
                        true
                    } else {
                        false
                    }
                }
            }

        val card =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val pad = dp(24)
                setPadding(pad, pad, pad, pad)
                background =
                    GradientDrawable().apply {
                        setColor(Color.parseColor("#131C2E"))
                        cornerRadius = dp(20).toFloat()
                        setStroke(dp(1), Color.parseColor("#2A3B5C"))
                    }
            }

        val titleView =
            TextView(ctx).apply {
                text = getString(R.string.protected_by_dns_shield)
                setTextColor(Color.parseColor("#F1F5F9"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
        card.addView(titleView)

        val messageView =
            TextView(ctx).apply {
                text =
                    if (lockType == LockType.STRICT_LOCK) {
                        "Strict Lock is active. This protected settings screen is blocked. There is no PIN or unlock path; only a factory reset removes DNS Shield."
                    } else {
                        "Tamper Protection is active. Enter your 6-digit Trusted PIN to start the cooling-off countdown, or go home."
                    }
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(16))
            }
        card.addView(messageView)

        val feedbackView =
            TextView(ctx).apply {
                setTextColor(Color.parseColor("#FB7185"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                visibility = View.GONE
                setPadding(0, 0, 0, dp(12))
            }
        statusTextView = feedbackView
        card.addView(feedbackView)

        if (lockType == LockType.TRUSTED_PIN) {
            val pinEdit =
                EditText(ctx).apply {
                    hint = "Enter 6-digit PIN"
                    setHintTextColor(Color.parseColor("#64748B"))
                    setTextColor(Color.parseColor("#F8FAFC"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    gravity = Gravity.CENTER
                    inputType =
                        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    transformationMethod = PasswordTransformationMethod.getInstance()
                    filters = arrayOf(InputFilter.LengthFilter(PinHasher.PIN_LENGTH))
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    background =
                        GradientDrawable().apply {
                            setColor(Color.parseColor("#0F172A"))
                            cornerRadius = dp(12).toFloat()
                            setStroke(dp(1), Color.parseColor("#334155"))
                        }
                }
            pinInputView = pinEdit
            val editLp =
                LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    .apply { bottomMargin = dp(12) }
            card.addView(pinEdit, editLp)

            val submitBtn =
                Button(ctx).apply {
                    text = "Start Turn-Off Countdown"
                    isAllCaps = false
                    setTextColor(Color.parseColor("#042F2E"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.DEFAULT_BOLD
                    background =
                        GradientDrawable().apply {
                            setColor(Color.parseColor("#34D399"))
                            cornerRadius = dp(12).toFloat()
                        }
                    minimumHeight = dp(48)
                    setOnClickListener {
                        val pin = pinInputView?.text?.toString().orEmpty()
                        if (!PinHasher.isValidPinFormat(pin)) {
                            feedbackView.text = "Enter a valid 6-digit PIN."
                            feedbackView.visibility = View.VISIBLE
                            return@setOnClickListener
                        }
                        isEnabled = false
                        serviceScope.launch {
                            val result = repository.requestTurnOffProtection(pin)
                            withContext(Dispatchers.Main) {
                                isEnabled = true
                                when (result) {
                                    PinActionResult.Success -> {
                                        removeOverlay()
                                        performGlobalAction(GLOBAL_ACTION_HOME)
                                        Toast.makeText(
                                                applicationContext,
                                                "Cooling-off countdown started.",
                                                Toast.LENGTH_LONG,
                                            )
                                            .show()
                                    }
                                    is PinActionResult.InvalidPin -> {
                                        pinInputView?.setText("")
                                        feedbackView.text =
                                            "Incorrect PIN. ${result.attemptsRemaining} attempt(s) left."
                                        feedbackView.visibility = View.VISIBLE
                                    }
                                    is PinActionResult.LockedOut -> {
                                        pinInputView?.setText("")
                                        val rem =
                                            ProtectionNotificationHelper.formatDurationShort(
                                                result.remainingMs
                                            )
                                        feedbackView.text =
                                            "Too many wrong attempts. Locked for $rem."
                                        feedbackView.visibility = View.VISIBLE
                                    }
                                    is PinActionResult.Rejected -> {
                                        feedbackView.text = result.reason
                                        feedbackView.visibility = View.VISIBLE
                                    }
                                }
                            }
                        }
                    }
                }
            submitPinButton = submitBtn
            val submitLp =
                LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    .apply { bottomMargin = dp(10) }
            card.addView(submitBtn, submitLp)
        }

        val goHomeBtn =
            Button(ctx).apply {
                text = getString(R.string.overlay_go_home)
                isAllCaps = false
                setTextColor(Color.parseColor("#E2E8F0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
                background =
                    GradientDrawable().apply {
                        setColor(Color.parseColor("#1E293B"))
                        cornerRadius = dp(12).toFloat()
                        setStroke(dp(1), Color.parseColor("#475569"))
                    }
                minimumHeight = dp(48)
                setOnClickListener {
                    removeOverlay()
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }
        card.addView(
            goHomeBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val cardLp =
            FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                )
                .apply {
                    val margin = dp(24)
                    setMargins(margin, margin, margin, margin)
                }
        rootContainer.addView(card, cardLp)
        return rootContainer
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value.toFloat(),
                resources.displayMetrics,
            )
            .toInt()
    }

    private fun isSoftKeyboardPackage(pkg: String): Boolean {
        return ScreenGuardExclusions.isSoftKeyboardPackage(pkg)
    }

    private fun matchesGuardedScreen(
        root: AccessibilityNodeInfo,
        mode: ProtectionMode,
    ): Boolean {
        val phrases =
            if (mode == ProtectionMode.PRIVATE_DNS) {
                GuardedScreenPhrases.APP_IDENTITY_PHRASES +
                    GuardedScreenPhrases.PRIVATE_DNS_PHRASES
            } else {
                GuardedScreenPhrases.APP_IDENTITY_PHRASES
            }

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_INSPECTED_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++
            val isRoot = node === root
            try {
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                val matched =
                    phrases.any { phrase ->
                        text.contains(phrase, ignoreCase = true) ||
                            desc.contains(phrase, ignoreCase = true)
                    }
                if (matched) {
                    while (queue.isNotEmpty()) {
                        val (rem, _) = queue.removeFirst()
                        if (rem !== root) recycleSafely(rem)
                    }
                    return true
                }

                if (depth < MAX_TRAVERSAL_DEPTH) {
                    val count = node.childCount.coerceAtMost(MAX_CHILDREN_PER_NODE)
                    for (i in 0 until count) {
                        val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                        queue.add(child to (depth + 1))
                    }
                }
            } catch (_: Throwable) {
                // Skip unreadable nodes gracefully
            } finally {
                if (!isRoot) {
                    recycleSafely(node)
                }
            }
        }

        while (queue.isNotEmpty()) {
            val (rem, _) = queue.removeFirst()
            if (rem !== root) recycleSafely(rem)
        }
        return false
    }

    private fun recycleSafely(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            runCatching { node.recycle() }
        }
    }

    companion object {
        private const val MAX_TRAVERSAL_DEPTH = 20
        private const val MAX_INSPECTED_NODES = 200
        private const val MAX_CHILDREN_PER_NODE = 64
        private const val BOUNCE_COOLDOWN_MS = 750L
        private const val TOAST_COOLDOWN_MS = 2000L
        private const val OVERLAY_HYSTERESIS_MS = 1000L
        private const val CONTENT_CHANGED_DEBOUNCE_MS = 200L
        private const val BLOCK_OVERLAY_DURATION_MS = 2000L
        private const val BACK_RECHECK_DELAY_MS = 1000L
        private const val TIMER_EVENT_REFRESH_INTERVAL_MS = 3000L

        val PROTECTED_SETTINGS_PACKAGES =
            setOf(
                "com.android.settings",
                "com.android.packageinstaller",
                "com.google.android.packageinstaller",
                "com.android.permissioncontroller",
                "com.google.android.permissioncontroller",
                "com.samsung.android.packageinstaller",
                "com.miui.securitycenter",
                "com.miui.packageinstaller",
                "com.coloros.safecenter",
                "com.oplus.securitypermission",
                "com.vivo.permissionmanager",
                "com.iqoo.secure",
                "com.vivo.settings",
            )
    }
}
