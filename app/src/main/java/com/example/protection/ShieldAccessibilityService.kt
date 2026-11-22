package com.example.protection

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.example.DnsShieldApplication
import com.example.R

class ShieldAccessibilityService : AccessibilityService() {
    @Volatile private var lastBounceElapsedMs = 0L
    @Volatile private var lastToastElapsedMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        ProtectionWatchdogReceiver.triggerAudit(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val app = applicationContext as? DnsShieldApplication ?: return
        val repository = app.repository

        // Fast non-blocking check: only guard when Protection is ON and no unlock window is active
        if (!repository.isGuardBlockingNowSync()) {
            return
        }

        val eventPkg = event?.packageName?.toString().orEmpty()
        if (eventPkg == packageName) return

        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        try {
            val activePkg = root.packageName?.toString().orEmpty().ifBlank { eventPkg }
            if (activePkg.isBlank() || activePkg == packageName) return
            if (activePkg !in PROTECTED_SETTINGS_PACKAGES) return

            if (containsTargetBrandText(root)) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBounceElapsedMs >= BOUNCE_COOLDOWN_MS) {
                    lastBounceElapsedMs = now
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    if (now - lastToastElapsedMs >= TOAST_COOLDOWN_MS) {
                        lastToastElapsedMs = now
                        Toast.makeText(
                                applicationContext,
                                getString(R.string.protected_by_dns_shield),
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                    }
                }
            }
        } catch (_: Throwable) {
            // Never crash or lag the accessibility service
        } finally {
            recycleSafely(root)
        }
    }

    override fun onInterrupt() {
        // Required override; no-op
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { ProtectionWatchdogReceiver.triggerAudit(applicationContext) }
    }

    private fun containsTargetBrandText(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_INSPECTED_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++
            val isRoot = node === root
            try {
                val textMatch = node.text?.toString()?.contains(TARGET_PHRASE, ignoreCase = true) == true
                val descMatch =
                    node.contentDescription
                        ?.toString()
                        ?.contains(TARGET_PHRASE, ignoreCase = true) == true
                if (textMatch || descMatch) {
                    // Clean up remaining queued child nodes before returning
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
        private const val TARGET_PHRASE = "DNS Shield"
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
                "com.android.permissioncontroller".replace("com.android", "com.google.android"),
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
