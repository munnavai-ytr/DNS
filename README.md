# DNS Shield

A local, privacy-first Android DNS filter built with Kotlin, Jetpack Compose (Material 3), and Android `VpnService`.

## What It Does
- **DNS-Only Local VPN**: Routes only DNS traffic (`UDP` port `53`) through the local TUN interface. All other internet traffic goes directly over your network.
- **Instant Local Blocking**: Parses incoming DNS queries and replies immediately (`< 10 ms`) with `0.0.0.0` (`A`) or `::` (`AAAA`) when a domain or parent domain matches the blocklist.
- **Cloudflare Family Upstream**: Allowed queries are forwarded over a protected socket to `1.1.1.3` (fallback `1.0.0.3`).
- **Self Test**: Verifies end-to-end filtering by resolving `blocktest.dnsshield.test` locally without contacting any adult site.
- **Blocked Log & Custom Rules**: Stores the last 200 blocked queries locally in Room and supports custom blocklist entries and whitelist overrides.
- **Always-On Resilience**: Runs as a foreground service with a persistent notification, restarts if killed, and auto-starts on `BOOT_COMPLETED` when enabled.

## Build Instructions

### Local Build
1. Ensure **JDK 21**, **Gradle 9.3.1**, and the **Android SDK** (`ANDROID_SDK_ROOT`) are installed.
2. Run:
   ```bash
   gradle assembleDebug
   ```
3. The debug APK will be generated at:
   `app/build/outputs/apk/debug/app-debug.apk`

### GitHub Actions
Push to any branch or trigger **Actions → Build DNS Shield Debug APK → Run workflow** (`workflow_dispatch`) to build and download the `DNSShield-debug-apk` artifact.
