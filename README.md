# Parent-Child-Protection-System
# IG Teen Guard (HK) — All-in-One Parental Protection System for Instagram

**IG Teen Guard** is a single-APK Android parental protection system designed to protect 12–16 year old teenagers from online grooming, strangers, and sexually explicit content on Instagram. It bridges critical gaps in Instagram's native supervision mode—particularly in regions such as Hong Kong where teens can still message accounts they follow back, view unblurred media, or disable built-in supervision without a parent code.

## Purpose & Core Objectives

1. **Full Stranger DM Visibility (Regardless of Follow Status):** Instagram's built-in supervision allows strangers to message teens freely once the teen follows them back, and hides message contents from parents. IG Teen Guard maintains a parent-controlled **Approved Friends Allowlist** so any message from an unapproved account triggers an immediate encrypted alert to the parent's phone.
2. **Real-Time Sexual & Grooming Interception (English + HK Cantonese):** Automatically detects explicit and grooming phrases (including Hong Kong Cantonese slang), immediately backs the child out of the chat thread, wipes explicit notifications from the child's lock screen, permanently locks that offender's chat on the child's device, and forwards every message from that offender to the parent.
3. **IG Mute Detection & Protected App Controls:** Detects Instagram mute/quiet-mode screens and protects in-app role changes with parent verification. Android controls its own permission settings and uninstallation; the reporting watchdog warns when a previously connected child stops reporting.

## Key Features & Architecture

| Feature Category | How It Works | Purpose |
| :--- | :--- | :--- |
| **All-in-One Parent & Child APK** | Both parent and child install the exact same APK and select **Parent Mode** or **Child Mode** during onboarding. Child monitoring hooks (`NotificationListenerService` & `AccessibilityService`) only run in Child Mode. | Eliminates third-party notification apps (such as `ntfy`) and provides a built-in **Parent Live Alert Inbox** and native high-priority Android notifications. |
| **Random Family Pairing & Authority Verification** | Generates a random UUID family token. V43 invitations carry a child password verifier and parent public key. Both deep-link and pasted invitations require explicit confirmation; compare the full security code on both phones, alongside the original four emojis. | Reduces accidental family collisions and lets the parent detect replacement of an invitation’s credentials or public key. |
| **End-to-End Encryption (`AES-256-GCM` over TLS)** | Encrypts every alert, heartbeat, allowlist sync, unlock code, and key-rotation command with **AES-256-GCM** (random 96-bit IV + 128-bit auth tag) over `HttpsURLConnection` before leaving the phone. | Prevents Man-in-the-Middle (MITM) attacks and ensures not even the relay server can read the child's DMs or handles. |
| **Dual-Layer DM Interception (Notifications + Active Chat Geometry)** | • **Background:** Unpacks Android `MessagingStyle` notification bundles (`EXTRA_MESSAGES`).<br>• **Foreground (1-on-1 DMs):** Uses screen coordinates (`getBoundsInScreen`) to read only the **bottom-left incoming message zone** (`left <= 27%` width, `22%` height above composer), ignoring right-aligned outgoing messages from the child.<br>• **Comments & Stories:** Ignores public post comments and stories; only inspects replies actively typed by the child (`isEditable`). | Captures stranger DMs whether the screen is off or the child is actively chatting inside Instagram, without false alarms from the child's own messages, `"Recently seen"` labels, or public post comments. |
| **Flagged Offenders Watchlist (`已封鎖高危帳號`)** | Once an account sends sexual or grooming text, it is added to `flagged_offenders_csv`. The Child Phone blocks opening that chat (`GLOBAL_ACTION_BACK`) and wipes its notifications, while **still forwarding every follow-up message from that offender to the Parent Phone**. | Prevents the child from interacting with a perpetrator while preserving 100% ongoing evidence and visibility for the parent. |
| **IG Mute Detection & Protected App Controls** | In child mode, the Instagram-only accessibility service backs out of detected mute/quiet screens and sends alerts. Parent authentication protects the app’s role-change and unlock controls. | Android Settings and uninstallation remain system-controlled. The app reports permission states and detects overdue reporting after the first authenticated child report. |
| **Random 30–95 Min Heartbeat & Offline Watchdog** | The Child Phone sends an encrypted heartbeat at random 30–95 minute intervals reporting permission states. The Parent Phone triggers an urgent offline/uninstall alert if more than **100 minutes** pass without a heartbeat. | Detects if the child powers off the phone, revokes permissions, or uninstalls the app. |
| **8+ Char Master Password, 15-Min Unlock Code & Remote Rescue** | • Requires an 8+ character alphanumeric **Parent Master Password**.<br>• Parent Phone generates a random 6-character **15-Minute Temporary Unlock Code**, delivered in a signed encrypted command (with 5-try brute-force lockout).<br>• Includes signed **Remote Key Rotation** and **Remote Child Reset** commands; failed rotations retain retry state. The child stores a password verifier instead of the parent’s plaintext password. | Protects against PIN guessing, lets parents grant temporary 15-minute access without revealing their Master Password, and allows remote unlock if the Master Password is forgotten. |

## Repository Structure & Cloud APK Build

This project compiles directly in the cloud via **GitHub Actions** with zero local Android Studio setup required:

* `MainActivity.kt` (or `MainActivity_v4.kt`): Kotlin source containing `GuardEngine`, `IgNotificationListener`, `IgAccessibilityService`, `ParentAlertService`, and `MainActivity`.
* `.github/workflows/build-apk.yml`: Assembles the Android Gradle project, uses private signing credentials from GitHub Actions Secrets when configured, and auto-increments `versionCode` so future updates install in-place without uninstalling the previous version.
* `ui-res/`: Native fonts, vector icons, light/dark themes and sheet animations used by the role-first family interface; copied into the generated Android project by the workflow. See [asset licences](UI_ASSET_LICENSES.md).

The app opens with role choice and retains existing protection until an authorised role change. Parent and child each have four navigation sections. Charts, alerts, connection states and family settings use real stored data; new installations have an empty trust list.

* `FamilySecurity.kt`: Native password-verifier and parent-command authentication primitives; no third-party runtime dependency.

Read [security migration](docs/security-review.md) before updating existing installations. V43 invitations keep the parent password and signing private key off child phones; parent controls require signatures and temporary codes require delivery. [Private signing setup](docs/signing.md) replaces the exposed historical signing key. Until replacement secrets are configured, Actions produces a clearly labelled unsigned APK requiring private signing before installation.
