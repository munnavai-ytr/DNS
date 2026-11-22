package com.example.protection

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

object ProtectionSystemChecker {
    const val PRIVATE_DNS_REQUIRED_HOSTNAME = "family.cloudflare-dns.com"

    fun isDeviceAdminActive(context: Context): Boolean {
        return runCatching {
                val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
                val adminComponent = ComponentName(context, ShieldDeviceAdminReceiver::class.java)
                dpm.isAdminActive(adminComponent)
            }
            .getOrDefault(false)
    }

    fun isAccessibilityGuardEnabled(context: Context): Boolean {
        val expectedFull =
            "${context.packageName}/${ShieldAccessibilityService::class.java.name}"
        val expectedShort =
            "${context.packageName}/${ShieldAccessibilityService::class.java.name.removePrefix(context.packageName)}"

        val enabledInManager =
            runCatching {
                    val am =
                        context.getSystemService(AccessibilityManager::class.java) ?: return@runCatching false
                    val enabledServices =
                        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    enabledServices.any { info ->
                        val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                        serviceInfo.packageName == context.packageName &&
                            serviceInfo.name == ShieldAccessibilityService::class.java.name
                    }
                }
                .getOrDefault(false)

        if (enabledInManager) return true

        return runCatching {
                val enabled =
                    Settings.Secure.getString(
                        context.contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    ) ?: return@runCatching false
                enabled.split(':').any { entry ->
                    entry.equals(expectedFull, ignoreCase = true) ||
                        entry.equals(expectedShort, ignoreCase = true)
                }
            }
            .getOrDefault(false)
    }

    fun isPrivateDnsConfigured(context: Context): Boolean {
        return runCatching {
                val mode =
                    Settings.Global.getString(context.contentResolver, "private_dns_mode")
                        ?.trim()
                        .orEmpty()
                val specifier =
                    Settings.Global.getString(context.contentResolver, "private_dns_specifier")
                        ?.trim()
                        .orEmpty()
                mode.equals("hostname", ignoreCase = true) &&
                    specifier.equals(PRIVATE_DNS_REQUIRED_HOSTNAME, ignoreCase = true)
            }
            .getOrDefault(false)
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        return runCatching {
                val pm = context.getSystemService(PowerManager::class.java) ?: return false
                pm.isIgnoringBatteryOptimizations(context.packageName)
            }
            .getOrDefault(false)
    }

    fun hasVpnPermission(context: Context): Boolean {
        return runCatching { VpnService.prepare(context) == null }.getOrDefault(false)
    }

    fun getDefaultLauncherPackage(context: Context): String? {
        return runCatching {
                val intent =
                    Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
                val resolved =
                    context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                resolved?.activityInfo?.packageName
            }
            .getOrNull()
    }

    fun buildDeviceAdminIntent(context: Context): Intent {
        val adminComponent = ComponentName(context, ShieldDeviceAdminReceiver::class.java)
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Prevents DNS Shield from being uninstalled while Tamper Protection is ON.",
            )
        }
    }

    fun buildAppInfoIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    fun buildAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    fun buildVpnSettingsIntent(): Intent {
        return Intent(Settings.ACTION_VPN_SETTINGS)
    }

    fun buildNetworkSettingsIntent(): Intent {
        return Intent(Settings.ACTION_WIRELESS_SETTINGS)
    }

    fun buildBatteryOptimizationIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }
}
