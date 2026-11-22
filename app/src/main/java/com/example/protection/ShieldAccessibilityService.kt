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

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayLockTypeShown: LockType? = null
    private var statusTextView: TextView? = null
    private var pinInputView: EditText? = null
    private var submitPinButton: Button? = null

    @Volatile private var cachedLauncherPackage: String? = null
    @Volatile private var lastBounceElapsedMs = 0L
    @Volatile private var lastToastElapsedMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WindowManager::class.java)
        cachedLauncherPackage = ProtectionSystemChecker.getDefaultLauncherPackage(applicationContext)
        ProtectionWatchdogReceiver.triggerAudit(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val app = applicationContext as? DnsShieldApplication ?: return
        val repository = app.repository

        // Never guard when protection is OFF or when a Trusted PIN unlock window is active
        if (!repository.isGuardBlockingNowSync()) {
            removeOverlay()
            return
        }

        val eventPkg = event?.packageName?.toString().orEmpty()
        // If user is interacting with our own overlay view (e.g., typing PIN), keep overlay intact
        if (overlayView != null && eventPkg == packageName) {
            return
        }

        val root = runCatching { rootInActiveWindow }.getOrNull()
        if (root == null) {
            // Transient null root when IME pops up over overlay: do not blindly dismiss overlay;
            // if switching away to a known non-guarded package, dismiss safely.
            if (
                overlayView != null &&
                    event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    eventPkg.isNotBlank() &&
                    eventPkg !in PROTECTED_SETTINGS_PACKAGES &&
                    !isSoftKeyboardPackage(eventPkg)
            ) {
                removeOverlay()
            }
            return
        }

        try {
            val activePkg = root.packageName?.toString().orEmpty().ifBlank { eventPkg }
            val launcherPkg = cachedLauncherPackage

            // Never cover own app, launcher, emergency calling apps, or non-guarded packages
            if (
                activePkg.isBlank() ||
                    activePkg == packageName ||
                    activePkg == launcherPkg ||
                    activePkg in SAFE_BYPASS_PACKAGES ||
                    activePkg !in PROTECTED_SETTINGS_PACKAGES
            ) {
                removeOverlay()
                return
            }

            val mode = repository.getCachedProtectionMode()
            val lockType = repository.getCachedLockType()
            val shouldGuard = matchesGuardedScreen(root, mode)

            if (shouldGuard) {
                showOrUpdateOverlay(lockType, repository)
            } else {
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
    }

    override fun onDestroy() {
        removeOverlay()
        serviceScope.cancel()
        super.onDestroy()
        runCatching { ProtectionWatchdogReceiver.triggerAudit(applicationContext) }
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
        } catch (_: Throwable) {
            // Fallback to GLOBAL_ACTION_HOME if overlay cannot be shown
            overlayView = null
            overlayLockTypeShown = null
            fallbackGoHome()
        }
    }

    private fun removeOverlay() {
        val current = overlayView ?: return
        overlayView = null
        overlayLockTypeShown = null
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
            FrameLayout(ctx).apply {
                setBackgroundColor(Color.parseColor("#0B111E"))
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
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
        return pkg.contains("inputmethod", ignoreCase = true) ||
            pkg.contains("keyboard", ignoreCase = true) ||
            pkg.contains("gboard", ignoreCase = true) ||
            pkg.contains("honeyboard", ignoreCase = true)
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

        private val SAFE_BYPASS_PACKAGES =
            setOf(
                "com.android.phone",
                "com.android.server.telecom",
                "com.android.incallui",
                "com.google.android.dialer",
                "com.samsung.android.incallui",
                "com.android.dialer",
            )
    }
}
