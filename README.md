# Parent-Child-Protection-System
# IG Teen Guard (HK) — All-in-One Parental Protection System for Instagram

**IG Teen Guard** is a single-APK Android parental protection system designed to protect 12–16 year old teenagers from online grooming, strangers, and sexually explicit content on Instagram. It bridges critical gaps in Instagram's native supervision mode—particularly in regions such as Hong Kong where teens can still message accounts they follow back, view unblurred media, or disable built-in supervision without a parent code.

## Purpose & Core Objectives

1. **Full Stranger DM Visibility (Regardless of Follow Status):** Instagram's built-in supervision allows strangers to message teens freely once the teen follows them back, and hides message contents from parents. IG Teen Guard maintains a parent-controlled **Approved Friends Allowlist** so any message from an unapproved account triggers an immediate encrypted alert to the parent's phone.
2. **Real-Time Sexual & Grooming Interception (English + HK Cantonese):** Automatically detects explicit and grooming phrases (including Hong Kong Cantonese slang), immediately backs the child out of the chat thread, wipes explicit notifications from the child's lock screen, permanently locks that offender's chat on the child's device, and forwards every message from that offender to the parent.
3. **Anti-Tamper & Forced Notification Lock:** Prevents teenagers from bypassing supervision by muting Instagram chats, pausing Instagram notifications, disabling Notification/Accessibility permissions in Android Settings, or silently uninstalling the app.

## Key Features & Architecture

| Feature Category | How It Works | Purpose |
| :--- | :--- | :--- |
| **All-in-One Parent & Child APK** | Both parent and child install the exact same APK and select **Parent Mode** or **Child Mode** during onboarding. Child monitoring hooks (`NotificationListenerService` & `AccessibilityService`) only run in Child Mode. | Eliminates third-party notification apps (such as `ntfy`) and provides a built-in **Parent Live Alert Inbox** and native high-priority Android notifications. |
| **128-Bit Collision-Free Pairing & 4-Emoji Check** | Generates a cryptographically random 128-bit UUID Family Key shared via a 1-tap WhatsApp/SMS invite (`igguard://pair` deep link or clipboard auto-paste) and displays a 4-emoji fingerprint (e.g., `🐼 🦁 🚀 🍀`) on both phones. | Guarantees two families can never accidentally collide on the same PIN or pairing code. |
| **End-to-End Encryption (`AES-256-GCM` over TLS)** | Encrypts every alert, heartbeat, allowlist sync, unlock code, and key-rotation command with **AES-256-GCM** (random 96-bit IV + 128-bit auth tag) over `HttpsURLConnection` before leaving the phone. | Prevents Man-in-the-Middle (MITM) attacks and ensures not even the relay server can read the child's DMs or handles. |
| **Dual-Layer DM Interception (Notifications + Active Chat Geometry)** | • **Background:** Unpacks Android `MessagingStyle` notification bundles (`EXTRA_MESSAGES`).<br>• **Foreground (1-on-1 DMs):** Uses screen coordinates (`getBoundsInScreen`) to read only the **bottom-left incoming message zone** (`left <= 27%` width, `22%` height above composer), ignoring right-aligned outgoing messages from the child.<br>• **Comments & Stories:** Ignores public post comments and stories; only inspects replies actively typed by the child (`isEditable`). | Captures stranger DMs whether the screen is off or the child is actively chatting inside Instagram, without false alarms from the child's own messages, `"Recently seen"` labels, or public post comments. |
| **Flagged Offenders Watchlist (`已封鎖高危帳號`)** | Once an account sends sexual or grooming text, it is added to `flagged_offenders_csv`. The Child Phone blocks opening that chat (`GLOBAL_ACTION_BACK`) and wipes its notifications, while **still forwarding every follow-up message from that offender to the Parent Phone**. | Prevents the child from interacting with a perpetrator while preserving 100% ongoing evidence and visibility for the parent. |
| **Anti-Tamper Lock & Forced Notifications** | Blocks access to Android Settings (`Instagram`, `IG Guard`, `Notification access`) and Instagram's in-app mute buttons (`Pause all`, `Mute messages`), auto-toggling unchecked notification switches back ON and exiting the settings screen. | Stops teens from muting chats or turning off Instagram notifications to evade monitoring. |
| **Random 30–95 Min Heartbeat & Offline Watchdog** | The Child Phone sends an encrypted heartbeat at random 30–95 minute intervals reporting permission states. The Parent Phone triggers an urgent offline/uninstall alert if more than **100 minutes** pass without a heartbeat. | Detects if the child powers off the phone, revokes permissions, or uninstalls the app. |
| **8+ Char Master Password, 15-Min Unlock Code & Remote Rescue** | • Requires an 8+ character alphanumeric **Parent Master Password**.<br>• Parent Phone generates a 6-character alphanumeric **15-Minute Temporary Unlock Code** (with 5-try brute-force lockout).<br>• Includes **Remote Key Rotation** and **Remote Child Reset** buttons on the Parent Phone. | Protects against PIN guessing, lets parents grant temporary 15-minute access without revealing their Master Password, and allows remote unlock if the Master Password is forgotten. |

## Repository Structure & Cloud APK Build

This project compiles directly in the cloud via **GitHub Actions** with zero local Android Studio setup required:

* `MainActivity.kt` (or `MainActivity_v4.kt`): Single-file Kotlin source containing `GuardEngine`, `IgNotificationListener`, `IgAccessibilityService`, `ParentAlertService`, and `MainActivity`.
* `.github/workflows/build-apk.yml`: Assembles the Android Gradle project, signs the APK with a persistent 2048-bit RSA keystore, and auto-increments `versionCode` so future updates install in-place without uninstalling the previous version.
* `ui-res/`: Native fonts, vector icons, light/dark themes and sheet animations used by the role-first family interface; copied into the generated Android project by the workflow. See [asset licences](UI_ASSET_LICENSES.md).

The app opens with role choice and retains existing protection until an authorised role change. Parent and child each have four navigation sections. Charts, alerts, connection states and family settings use real stored data; new installations have an empty trust list.
