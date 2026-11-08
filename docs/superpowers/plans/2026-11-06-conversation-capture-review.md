# Conversation capture and parent review implementation plan

> **For agentic workers:** Use superpowers:executing-plans. Track steps below; keep changes local and uncommitted for review.

**Goal:** Capture exposed two-way Instagram conversation records and present a DM-style contact list, chronological threads and separate actionable review queue.
**Architecture:** One focused native capture/rule helper; transactional schema upgrade in the existing SQLite store; existing encrypted delivery/receipt pipeline extended for captured records and a signed, locally acknowledged capture policy. Reuse existing Views and family/security boundaries.
**Tech Stack:** Kotlin, Android Views/SQLite, existing crypto, Robolectric/JUnit; no added runtime dependency.
**Spec:** docs/superpowers/specs/2026-11-06-conversation-capture-review-design.md

## Global constraints
- Approved incoming and outgoing captures include ordinary trusted contacts; no new DM draft/keystroke collection.
- All captured is not all Instagram history. Unknown attribution/source time stays unknown.
- Parent archive retains until deletion. No backend, GitHub push, signing/version change or unrelated redesign.
- Full capture requires authenticated updated-parent policy plus clear local child acknowledgment; legacy incoming protection remains meanwhile.
- User grouping/time clarification: conversation partner list newest activity first; bounded chronological message pages within each thread.

## Review focus
- Identical repeated texts with opposite directions and notification re-posts must not silently disappear or become falsely attributed.
- Group/unknown-account labels must not merge confidently into another known conversation.
- Routine context must never inflate alerts/charts/help, and outgoing keyword matches must not flag the partner.
- Queued plaintext scope decisions cannot cross a family switch; signed policy must not expand capture without local acknowledgment.
- Database upgrades must preserve old payloads/bookmarks/deletion state and keep old metadata unknown.

## Task 1: Capture records and archive
Files: create `ConversationCapture.kt`, `tests/ConversationCaptureTest.kt`, `tests/ConversationArchiveTest.kt`; modify `FamilyEvidence.kt` and source-copy lists.
Interfaces: `CapturedText` holds thread/account/author/direction/text/source/source-time/source-key; `ConversationCapture` normalizes, validates, labels signals and computes bounded snapshot overlap; `FamilyEvidence.conversations`, `timeline`, `review`, `captureWindow` and transactional capture enqueue provide scoped persistence.
- [x] Write and run failing normalization/classification/chronology/grouping/upgrade tests.
- [x] Add schema-v2 columns/indexes, review states, capture identity/window persistence and delivery priority, preserving legacy queries.
- [x] Implement the minimal helper and observe focused GREEN.

## Task 2: Live capture and protocol
Files: `MainActivity.kt`, `FamilySecurity.kt`, `FamilyEvidence.kt`, new `tests/ConversationProtocolTest.kt`, `tests/ConversationIngestionTest.kt`.
Interfaces: engine ingestion consumes `CapturedText`; captured wire type travels through durable framing/receipts. Signed `capture_policy` revision/mode is staged on child and locally acknowledged before `fullCaptureEnabled`; old behavior remains otherwise.
- [x] Add RED tests for batch direction/ordinary records, policy validation/local acknowledgment, normal-record chart/notification exclusion and partner-safe outgoing keywords.
- [x] Integrate notification bundle iteration and visible-bubble snapshot ingestion, preserving legacy path until full-mode acknowledgment.
- [x] Extend transport allowlist, priority/backoff, data validation and scope resets/rotation; run focused GREEN and existing suite.

## Task 3: DM-style parent UI and completion
Files: `MainActivity.kt`, `tests/ConversationUiTest.kt`, related README/data-flow/validation docs.
Interfaces: conversation groups keyed separately from authors; timeline pages sorted oldest to newest within the most recent/older 30-record window; review status stored per actionable event.
- [x] Add RED UI tests for contact grouping, time order, two-way author/source labels, filters/review state, new arrivals and family-scoped state.
- [x] Implement conversations/review/device destinations within the current evidence section, setup notice and full-capture status; retain existing aesthetic and controls.
- [x] Run focused and full tests, debug/unsigned release builds, lint and workflow checks; inspect native light/dark views with test-only data.
- [x] One fresh whole-change review; fix material findings with RED/GREEN regressions, then run final verification.
- [x] Document source/device limits and leave all edits local.

## Completion evidence — 8 November 2026
The resumed connection investigation and the fresh review findings were addressed within the existing capture/delivery/archive scope. See `docs/connection-and-conversation-validation.md` for the independently confirmed legacy defects, new expanded-capture regressions, repairs, and supported deployment steps. Final full run: 144 tests/20 classes, no failures/errors/skips, debug + unsigned release builds, lint 0 errors/57 warnings; 13 workflow checks and actionlint passed. Native light/dark views inspected; synthetic fixture removed and absent from APKs. No commit or push. Live handset/relay delivery is explicitly unverified; the cloud relay probe was denied by network policy.

## Requested completion follow-up — 8 November 2026

The approved feature was reviewed rather than reimplemented. A fresh focused review and debugging pass repaired delayed drafts crossing family sessions, explicit group/direct identity and approval collisions (including self-named groups), lost chronological page boundaries on recreation, and display-name truncation in the trust action. Nine new regression cases were added; all original 144 cases remain passing.

Latest complete verification: **153 tests / 20 classes, 0 failures/errors/skips**; debug and unsigned release builds; lint 0 errors / 57 warnings; 13 workflow checks and actionlint passed. Native previews include separate same-named group/direct rows in both themes; preview fixtures are absent from final tests and APKs. The feature and debugging report is `docs/conversation-capture-completion-report.md`. No commit, push, release or workflow dispatch; workspace preserved for review.

## Publication authorization — 8 November 2026

The user subsequently authorized pushing the reviewed work and updating the GitHub release, including a notes-only release if no APK is available. Publication targets version/tag **4.7**, retaining existing private signing configuration. A tag-triggered release job attaches only a successfully built and verified signed APK, with explicit notes-only/incomplete-upload fallbacks. Only that release job receives `contents: write`; normal builds retain read access. Publication checks pass 21 Python cases plus actionlint; the exact 4.7 tree passes all 153 Android tests, both local APK builds and lint (0 errors / 57 warnings). The 4.7 release notes are `docs/releases/4.7.md`.
