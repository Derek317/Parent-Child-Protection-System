# Visual review and debugging — IG Guard 4.8

## Scope and sequence

The existing native Android screens were reviewed first, followed by interaction debugging and regression verification. The approved minimalist family design, single APK, existing functions, parental authorization, child acknowledgment, encrypted relay and signing-secret setup are preserved. This release also includes the previously unpublished [chat-reader repairs](chat-reader-repair-report.md).

## Visual findings and corrections

| Finding in the native app | Correction |
| --- | --- |
| Secondary actions resembled plain text; faint outlines disappeared into cards. | Outlined secondary actions and icon buttons, with control borders distinct from decorative dividers. |
| Pressed feedback could use the same colour as a primary button; custom backgrounds did not visibly mark keyboard focus. | Contrasting native ripple feedback and a two-dp focus outline. Inputs also retain a visible focus state. |
| Message bubbles opened full records but had no visible action cue. | A **查看完整記錄** label and chevron; the full bubble remains the touch target. |
| Four conversation filters became crowded at 320dp width / 150% text. | Two-column controls with flexible height, preserved filter actions and exposed saved-filter selection. |
| Long ordering explanations dominated the thread introduction. | A concise introduction and a clearly labelled **排序與擷取說明** action containing the full limits. |
| Custom rows, navigation tabs and role choices did not convey their control type consistently to Android accessibility. | Button roles for actions, checkable radio roles for role choices, and exposed theme selection. |

Secondary control-border contrast against cards increased from **1.26:1 to 4.77:1** in light mode and **1.48:1 to 7.86:1** in dark mode. Secondary labels measure **8.25:1** and **11.10:1** respectively. These palette measurements supplement inspection of the actual native renders.

Controls retain minimum touch targets of 48dp or larger. Text continues to scale with system settings. Filled primary actions, outlined secondary actions, selected states, labels and chevrons provide cues beyond colour alone.

## Behavioural debugging

The interaction tests exercise real Android views and accessibility actions. Their windows are attached before screen-coordinate and selection assertions; an initial incomplete fixture was corrected rather than used as evidence of a production defect. All six corrected interaction checks failed against the released activity before the fixes, as did the additional paging regression.

Debugging found that restoring an older thread page rejected negative relative screen-order positions. Such positions are legitimate when older messages are captured before the initial observation. Restoration now accepts the same lower bound as the capture protocol, preserving the selected page after recreation. Existing message timestamps and stored evidence remain unchanged.

## Verification record

- Before changes: 48 native screen captures across light/dark themes, 390dp normal text and 320dp / 150% text. The matrix covers role choice, parent/child setup, dashboard, family, settings, empty archive, thread, record details, child permissions, capture setup and unlock.
- Regression proof: **seven targeted tests failed** against the released activity in generated test sources; repository work was preserved.
- Focused UI/interaction suite: **42 passed**, including protected role changes, evidence actions, filter selection, theme selection, accessibility actions and negative-position page restoration.
- After changes: **56 native captures** in the same matrix, including focused/pressed control states. Selected examples below contain empty/setup states, with no private or sample messages.
- One fresh read-only whole-change review found **no confirmed Critical or Important issue**. Its remaining documentation note is resolved by recording the final verification results here. The reviewer traced capture, migration, parent storage, encrypted outbox, consent, lifecycle/session guards, UI callbacks and release configuration.
- Python workflow/signing/release/UI checks: **22 passed**. `actionlint` and whitespace checks passed.
- Complete Android suite: **181 passed across 22 classes**, with zero failures, errors or skipped tests.
- `assembleDebug`, `assembleRelease`, `lintDebug` and `testDebugUnitTest`: **BUILD SUCCESSFUL**. Android lint reports **zero errors and 57 warnings**, the same warning count as the published base; these warnings are not claimed resolved.
- Local release inspection confirms `app.family.igguard`, version name **4.8**, accessibility flags **0x11**, and no keystore files or audit/test fixture strings in the archive. Generated Kotlin sources match the reviewed repository files. External render fixtures were removed before the complete suite.

Validation logs and JSON results are retained in `/workspace/.android-dev/visual-review/`; native before/after captures are under `/workspace/outputs/app-visual-review/`.

Selected native screenshots: [light controls](images/4.8-controls-light.png), [dark controls](images/4.8-controls-dark.png), [320dp / 150% text](images/4.8-large-text.png).

Synthetic screenshot records are confined to external test fixtures and are not bundled with or seeded into the app. No private messages, signing keys or passwords are included in this report.

## Device and capture limits

Native rendering and automated tests do not replace a physical Samsung S26 / current Instagram two-phone check. No such device session was available here. The app cannot recover unloaded or missed Instagram history, verify identities from display names alone, or always align notification timestamps precisely within screen-capture fragments. Unknown direction remains unknown. Historical `返回` groups are preserved with a warning rather than assigned invented contacts.

Two-way capture still requires the parent's signed request, the child's explicit confirmation, and an authorized, connected accessibility service. Android restricted-settings, bank-app and Play Protect decisions remain controlled by those platforms. Existing evidence is retained until deletion on the parent phone.

## Release and signing

Version 4.8 is built by the existing GitHub tag workflow using the repository's signing secrets. The workflow verifies the signed APK before uploading it with SHA256SUMS. Local release validation uses an unsigned APK and does not handle the private release key. The release notes link this report and the capture repair report; a failed or unsigned build produces a notes-only release instead of presenting an unsigned APK as the signed download.

## Published release verification

- Release: [IG Guard 4.8](https://github.com/Derek317/Parent-Child-Protection-System/releases/tag/4.8); source commit `ebb8f78`.
- [Tag workflow](https://github.com/Derek317/Parent-Child-Protection-System/actions/runs/42458776227) and [main workflow](https://github.com/Derek317/Parent-Child-Protection-System/actions/runs/42458776278): **success**.
- Downloaded release APK: `app.family.igguard`, **version 4.8 / code 150**, not debuggable; APK Signature Scheme v2 verification passed.
- APK SHA-256: `75238e903163fc64273e49699d4c2f33e1ec9cd035061c3d089542926f85b6fa` — matches the published SHA256SUMS.
- Signing-certificate SHA-256: `0eb6f30787c2d6dc23136a28cfd7a08a7956e5fe0565ba142f6711be38ca3f0e` — independently matches the downloaded 4.7 APK, preserving signing compatibility. No signing-key replacement is required by this release.
- The downloaded archive contains no keystore files or audit/test fixture strings.
- [Machine-readable verification](releases/4.8-verification.json) records the checks and workflow links.

Historical contact/direction errors cannot be reliably corrected from text alone; the repair does not rewrite past evidence or silently clear protective flags. Parents should review uncertain historical records.
