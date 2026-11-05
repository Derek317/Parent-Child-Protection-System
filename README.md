# Parent-Child-Protection-System
# IG Teen Guard (HK) — All-in-One Parental Protection System for Instagram

**IG Teen Guard** is a single-APK Android parental protection system designed to protect 12–16 year old teenagers from online grooming, strangers, and sexually explicit content on Instagram. It bridges critical gaps in Instagram's native supervision mode—particularly in regions such as Hong Kong where teens can still message accounts they follow back, view unblurred media, or disable built-in supervision without a parent code.

## Purpose & Core Objectives

1. **Approved-Contact DM Alerts:** A parent-controlled approved-friends list is independent of Instagram follow status. Captured incoming messages from unapproved account labels generate encrypted alerts. An unapproved label does not establish a sender's age, identity or intent, and capture is not comprehensive.
2. **Keyword-Based Text Alerts (English + HK Cantonese):** Rules flag selected sexual or grooming-related phrases. The child app attempts to leave flagged chats and clear their notifications while forwarding captured follow-ups. This does not classify images/audio, understand the full grooming process or block the account on Instagram's servers.
3. **IG Mute Detection & Protected App Controls:** Detects Instagram mute/quiet-mode screens and protects in-app role changes with parent verification. Android controls its own permission settings and uninstallation; the reporting watchdog warns when a previously connected child stops reporting.

## Key Features & Architecture

| Feature Category | How It Works | Purpose |
| :--- | :--- | :--- |
| **All-in-One Parent & Child APK** | Both parent and child install the exact same APK and select **Parent Mode** or **Child Mode** during onboarding. Child monitoring hooks (`NotificationListenerService` & `AccessibilityService`) only run in Child Mode. | Eliminates third-party notification apps (such as `ntfy`) and provides a built-in **Parent Live Alert Inbox** and native high-priority Android notifications. |
| **Random Family Pairing & Authority Verification** | Generates a random UUID family token. V43 invitations carry a child password verifier and parent public key. Both deep-link and pasted invitations require explicit confirmation; compare the full security code on both phones, alongside the original four emojis. | Reduces accidental family collisions and lets the parent detect replacement of an invitation’s credentials or public key. |
| **End-to-End Encryption (`AES-256-GCM` over TLS)** | Encrypts every alert, heartbeat, allowlist sync, unlock code, and key-rotation command with **AES-256-GCM** (random 96-bit IV + 128-bit auth tag) over `HttpsURLConnection` before leaving the phone. | Prevents Man-in-the-Middle (MITM) attacks and ensures not even the relay server can read the child's DMs or handles. |
| **Dual-Layer DM Interception (Notifications + Active Chat Geometry)** | • **Background:** Unpacks Android `MessagingStyle` notification bundles (`EXTRA_MESSAGES`).<br>• **Foreground (1-on-1 DMs):** Uses screen coordinates (`getBoundsInScreen`) to read only the **bottom-left incoming message zone** (`left <= 27%` width, `22%` height above composer), ignoring right-aligned outgoing messages from the child.<br>• **Comments & Stories:** Ignores public post comments and stories; only inspects replies actively typed by the child (`isEditable`). | Captures stranger DMs whether the screen is off or the child is actively chatting inside Instagram, without false alarms from the child's own messages, `"Recently seen"` labels, or public post comments. |
| **Flagged Offenders Watchlist (`已封鎖高危帳號`)** | Once an account sends sexual or grooming text, it is added to `flagged_offenders_csv`. The Child Phone blocks opening that chat (`GLOBAL_ACTION_BACK`) and wipes its notifications, while **still forwarding every follow-up message from that offender to the Parent Phone**. | Attempts to leave flagged chats and retain captured follow-up text for parent review; capture and delivery can still be incomplete. |
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
* `FamilyEvidence.kt`: Native SQLite evidence archive, encrypted delivery outbox and bounded long-message framing; no third-party runtime dependency.

Read [security migration](docs/security-review.md) before updating existing installations. V43 invitations keep the parent password and signing private key off child phones; parent controls require signatures and temporary codes require delivery. [Private signing setup](docs/signing.md) replaces the exposed historical signing key. Until replacement secrets are configured, Actions produces a clearly labelled unsigned APK requiring private signing before installation.


## Evidence archive and reliable delivery

- The parent archive uses native app-private SQLite. It keeps captured records until explicitly deleted, rather than deleting older text after 60 entries. Contacts are grouped; search, type/date filters, saved messages and 30-item pages keep the view bounded. Open a record for selectable full text or copy it explicitly.
- Existing retained JSON records migrate once and are marked as possibly truncated, including before a family change. Unparseable legacy data is preserved as a clearly labelled raw recovery record. Previously discarded records or truncated characters cannot be recovered. Instagram may expose only a notification snippet or a portion of the visible screen; this is an archive of **captured text**, not a complete or certified forensic conversation record.
- Long captured text is encoded as bounded encrypted parts with stable IDs and a full-payload digest. The transport safety bound is 1 MiB per serialized alert. Complete assemblies, deletion tombstones and queued ciphertext are durable; malformed/conflicting parts are rejected. Incomplete assemblies are identified in the parent interface.
- Failed uploads back off and retry through the existing child-service lifecycle. Securely paired updated phones use signed parent receipts: HTTP acceptance alone does not clear a message waiting for parent confirmation, and waiting for one receipt does not delay a new alert. Receipts confirm parent-device receipt, not that an adult read or acted on an alert. Legacy pairings without a pinned parent key have relay-only confirmation; create a fresh invitation to enable signed receipts.
- Update **both phones together** for long-message framing and signed receipts. A parent using the older app cannot reassemble the new long-message parts. The public relay's retention, capacity and availability are external limits; no monitoring can run after Android stops the app, required access is revoked or the app is uninstalled.
- Keyword alerts offer calm guidance to speak with the child and seek an appropriate school social worker or safeguarding professional. They are signals, not findings of abuse. Background alerts use Android notifications with generic lock-screen content, not overlays. Hong Kong immediate danger: 999.
- Outgoing DMs remain excluded. The existing child comment/story-reply path and unapproved-contact alert policy are unchanged. No microphone, screenshot, photo-library or new restricted access is added.
- Backups and device transfer remain disabled for app data. The archive is on this phone: uninstalling or clearing app data removes it. A new family does not see the old family's records; key rotation within the family retains history and queued delivery through a recoverable transition journal. Old isolated archives can be explicitly removed in parent settings.

This update improves retry/storage behavior; it does not certify Play Protect/banking acceptance or prove Instagram capture on every phone/version. The app cannot silently grant Android special access or make a bank trust an accessibility service.

See [image and voice feasibility](docs/media-detection-feasibility.md), the local [implementation design](docs/superpowers/specs/2026-11-06-evidence-reliability-design.md) and [validation and remaining device checks](docs/evidence-reliability-validation.md).
