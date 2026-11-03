# Permission and dashboard changes

The app remains one APK. Child mode retains notification interception, Instagram screen inspection, anti-mute behavior, encrypted alerts, pairing, allowlists and temporary unlock. Parent mode disables the child-only components. The app does not hide its capabilities from banks or bypass Android security.

## Play Protect and Samsung S26

Restricted settings, Play Protect classification and HSBC security checks are separate systems. The reported HSBC warning occurs in child mode. Retaining Accessibility means a bank may still warn; there is no supported automatic permission grant or source-level guarantee of acceptance.

Implemented mitigations: prominent access explanations; conditional instructions for the attempted-toggle → App Info → retry flow; no unauthenticated temporary unlock from Settings navigation; accurate permission/connection status; child-only component activation; a visible ongoing monitoring notification when notification permission is granted; and the manifest declaration `isMonitoringTool=child_monitoring`. The workflow change is required for the manifest declaration; replacing only the Kotlin file does not add it.

The exact Play Protect warning, Android/One UI version and install source were not supplied. These changes cannot establish the cause or resolution of that warning. If an installed release is flagged, record the exact warning, package/version and SHA-256 of the flagged APK, and use Google's Play Protect developer review/appeal process. Do not disable Play Protect or spoof the installer. A trusted distribution channel and compliant release are separate release tasks, not a guarantee that a bank will accept Accessibility.

The repository already embeds release-signing material in its workflow. That is a release-key hygiene concern, not a confirmed cause of these warnings. It was not changed here because replacing the signer affects update compatibility.

## Statistics semantics

The overview shows today and six prior calendar days, using the timezone captured when statistics were initialized. That timezone is labelled and remains fixed so travel does not silently remap historical counters. Bars total safety alerts by category; the line totals all included types per day. Heartbeats, pairing success and test events are excluded. Offline alerts are saved to the inbox and counters.

Counters outlive the 60-entry inbox. Available old history is imported once and labelled incomplete. Missing/future timestamps use receipt time for new events; old entries with unknown timestamps cannot be assigned to historical days and are skipped during migration. Replay IDs are retained for eight days. Resetting charts or clearing the inbox does not remove replay protection. New families clear the family-specific history; rotating the same family's key preserves it.

## Repeatable checks

- `python3 tests/check_ui.py`: contrast, restricted-permission safety, monitoring declaration and no state lock during network polling.
- `bash tests/run_android.sh testDebugUnitTest assembleDebug assembleRelease`: JVM accounting tests, Robolectric Android lifecycle/navigation tests, native rendering and both build variants. It generates the project outside the checkout. Set `ANDROID_HOME`, `GRADLE_BIN`, `GRADLE_USER_HOME` and `IG_GUARD_BUILD_DIR` to override cloud paths.
- GitHub Actions runs the Python checks and JVM tests before the existing signed-release build. Robolectric, AndroidX test support and JUnit are test-only dependencies; none is added to the application runtime.

Local APKs use a development signer. They are not signed with the repository's release key and cannot be assumed to update an existing release installation. GitHub Actions retains the original release-signing behavior. The publishing follow-up commits the source and workflow to GitHub main; GitHub Actions produces the release-signed APK.

## Device checks still required

On the Samsung S26, record Android/One UI and installation source. Test each role separately:

1. Parent: child services disabled; notifications explicitly granted/denied; menu destinations and pairing work.
2. Child: explain and attempt each special-access toggle. If blocked, follow the device's actual message, open App Info only when appropriate, then return and retry. Verify the UI updates after returning.
3. Open permission help while locked: the child must remain locked. Exercise a valid parent unlock separately.
4. With the user's informed permission, enable both child capabilities and test foreground/background IG alerts, anti-mute, allowlist and parent delivery. Check the child-mode disclosure notification.
5. Reproduce the exact Play Protect/HSBC warning with the new release. Record its wording rather than treating a successful build as acceptance.
6. Test TalkBack, large system text, rotation, Back navigation, pending form text and alert arrival while editing. Compare both themes.

Cloud JVM rendering does not validate Samsung's Settings UI, Play Protect, HSBC, real Accessibility connection behavior, end-to-end relay delivery or an actual Instagram screen.

## Render fixtures

The native-render test produces light/dark dashboard and child-permission PNGs under the generated project's `app/build/test-screenshots/`. Their alert counts are synthetic test data, not a family's real alerts. The fixed header is outside the scrolling page; long inboxes and accessibility-sized content can still scroll within a destination.

## Verified result

18 JVM/Android tests passed with zero failures, errors or skips, including Android 14's restricted-settings help branch. Four Python checks passed. Debug and release APK builds and signatures passed. Native light/dark dashboard renders were inspected. A fresh independent code review identified and led to fixes for network-lock UI blocking, stale-role relay processing, atomic family switching, section scroll restoration, allowlist draft invalidation and midnight chart refresh. The remaining device checks above are unrun.

## GitHub workflow prerequisites

The workflow explicitly prepares Android SDK platform 34 and Build Tools 34.0.0, uses JDK 17 and Gradle 8.7, persists `android.useAndroidX=true` for all Gradle steps, and prefers the canonical `MainActivity.kt` source. The regression test files are part of the same commit and execute before release compilation. The existing release signer and increasing version-code scheme are retained. Repository-token permissions are limited to reading contents; uploading the APK uses the Actions artifact service.

Publishing verification: actionlint accepted the workflow. The actual project-generation, regression and release-compilation steps were executed outside the checkout with checksum-verified Temurin JDK 17.0.21, Gradle 8.7 and SDK 34. All 18 tests and four safety/contrast checks passed. The release APK built with the existing signer and passed signature verification. Only the cloud proxy routing was added to that local CI simulation.

GitHub-runner follow-up: the initial GitHub run failed inside the added Android setup action before compilation. Its default `tools` package is no longer listed in Google's SDK repository. The workflow now uses Ubuntu 24.04's preinstalled SDK directly, explicitly installs the required SDK 34 packages, and uses the Node 24 versions of checkout, Java setup and Gradle setup. The local generation helper selects the named project-generation step so earlier multiline SDK steps do not change its behavior.
