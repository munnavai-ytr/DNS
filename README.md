# DNS Shield

A local, privacy-first Android DNS filter built with Kotlin, Jetpack Compose (Material 3), and Android `VpnService`.

## What It Does
- **DNS-Only Local VPN**: Routes only DNS traffic (`UDP` port `53`) through the local TUN interface. All other internet traffic goes directly over your network.
- **Instant Local Filtering**: Checks whitelists, ~150 built-in safe domains, blocklists, custom rules, adult keywords, and cached verdicts in `< 10 ms`.
- **Cloudflare Family & AI Verification (V2)**: Unknown domains are checked against `1.1.1.3` (fallback `1.0.0.3`) and, when enabled with an API key, verified on-the-fly within 3 seconds via Gemini (`gemini-2.5-flash` by default). Only the domain name is sent.
- **Persistent Verdict Cache**: Stores up to 5,000 verified registrable domains (`eTLD+1`) in an LRU cache (`SAFE` for 7 days, `ADULT` for 30 days).
- **Blocked Log & Custom Rules**: Stores the last 200 blocked queries with block reason and latency, plus custom blocklist/whitelist overrides.
- **Always-On Resilience**: Runs as a foreground service with a persistent notification, restarts if killed, and auto-starts on `BOOT_COMPLETED` when enabled.

## Getting a Gemini API Key (Optional V2 AI Verification)
1. Visit **Google AI Studio** (`https://aistudio.google.com/apikey`) and sign in with your Google account.
2. Click **Create API key** and copy the generated key.
3. Open **DNS Shield → Settings → AI verification**, paste the key into **Gemini API Key**, tap **Save**, and use **Test AI connection** to verify.
4. Your key is stored locally in Android DataStore on your device and never hardcoded in the app.

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
