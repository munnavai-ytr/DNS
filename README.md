# DNS Shield

A local, privacy-first Android DNS filter built with Kotlin, Jetpack Compose (Material 3), and Android `VpnService`.

## What It Does
- **Two Protection Modes (V3)**:
  - **VPN Mode**: Routes only DNS traffic (`UDP` port `53`) through a blocking TUN interface (`Builder.setBlocking(true)`) with all V1/V2 features (local blocklists, AI verification, custom rules, logs). Note: Android allows only one VPN at a time, so starting another VPN turns DNS Shield off.
  - **Private DNS Mode (Lite)**: Runs no local VPN. Guides you to set Android Private DNS to `family.cloudflare-dns.com` and monitors `Settings.Global` (`private_dns_mode` / `private_dns_specifier`). Note: AI check and custom rules do not apply in this mode.
- **Instant Local Filtering (VPN Mode)**: Checks whitelists, built-in safe domains, blocklists, custom rules, adult keywords, and cached verdicts in `< 10 ms`. Blocked logs and counters are batched (flushed every 5 seconds or 20 items) and notifications are throttled to protect battery and thermals.
- **Cloudflare Family & AI Verification (V2, VPN Mode)**: Unknown domains are checked against `1.1.1.3` (fallback `1.0.0.3`) and, when enabled with an API key, verified within 3 seconds via Gemini (`gemini-2.5-flash` by default). Only the domain name is sent.
- **Tamper Protection & Guard Overlay (V3)**:
  - **Trusted PIN**: Give a 6-digit PBKDF2-hashed PIN to a trusted person. Requires PIN entry -> clock-tamper-resistant cooling-off countdown (`10m`, `1h`, `24h`, `72h`) -> second PIN entry to open a 15-minute unlock window. Raising wait time is allowed anytime; lowering wait time, changing PIN, or switching mode requires an active unlock window. 5 wrong attempts trigger exponential lockout starting at 30 minutes.
  - **Strict Lock**: Requires two confirmations including typing `I UNDERSTAND`. Has no PIN and no unlock path at all — only a factory reset removes the app.
  - **Accessibility Guard Overlay**: Uses an opaque `TYPE_ACCESSIBILITY_OVERLAY` window (`DNS Shield Guard`) to cover DNS Shield's App Info, Device Admin, Accessibility, VPN, Uninstaller, and Private DNS settings screens whenever protection is ON.
  - **Watchdog**: 15-minute `JobScheduler` / `AlarmManager` check posts `"Protection weakened, tap to fix"` if VPN/Private DNS, Device Admin, or Accessibility Guard is disabled.

## Important Setup Notes
- **Always-On VPN (VPN Mode)**: Turn ON **Always-on VPN** in Android VPN settings, but **do NOT** enable **"Block connections without VPN"** — only DNS traffic routes through DNS Shield, so enabling that toggle would block all internet access.
- **Enabling Accessibility Guard on Sideloaded Android 13+ APKs**:
  1. In **DNS Shield → Protection Setup (Step 4)**, tap **Open App Info**.
  2. Tap the **three-dot menu (⋮)** in the top right corner and choose **Allow restricted settings**.
  3. Return to DNS Shield, tap **Accessibility Settings**, select **DNS Shield Guard**, and turn it **ON**.

## Getting a Gemini API Key (Optional V2 AI Verification in VPN Mode)
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
