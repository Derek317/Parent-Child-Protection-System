# Evidence and Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Steps use checkbox syntax for tracking.

**Goal:** Preserve captured evidence, make it easy to find and make delivery failure recoverable without expanding monitoring scope.
**Architecture:** Native SQLite archive/outbox; existing engine/service integration; native parent UI. Stable event IDs and bounded encrypted frames carry long text through the relay.
**Tech Stack:** Kotlin, Android SQLiteOpenHelper, Android Views, existing AES-GCM and Robolectric tests; no added runtime dependencies.
**Spec:** docs/superpowers/specs/2026-11-06-evidence-reliability-design.md

## Global constraints
- Initial implementation remained local in the existing cloud draft checkout, on `work`. The user subsequently authorized pushing to GitHub with the existing signing setup.
- Keep outgoing DMs excluded and unapproved-contact alerts active.
- Retain archive until explicit deletion. Keep private data family-scoped.
- Preserve signature checks, pairing confirmation, permission transparency and existing feature behavior.
- Image/voice capture is not implemented in this pass.

## Review focus
- Old-family async delivery or an open evidence sheet must not cross family boundaries.
- Long CJK/emoji/quotes/newlines must survive relay chunking without malformed Unicode or body overflow.
- A replay after retry/restart must not duplicate evidence, prompts or statistics.
- Archive search/pagination must not omit matching old records or reset while typing.
- Background notification opening must show advice without leaking message text on a locked screen or forcing overlays.

## Task 1: Archive, relay framing and recoverable delivery
Files: create `FamilyEvidence.kt`, `tests/EvidenceStoreTest.kt`, `tests/DeliveryReliabilityTest.kt`; modify engine paths in `MainActivity.kt`, workflow source-copy line and local test runner source-copy list.
Interfaces: `EvidenceStore` owns append/recent/search/contact groups/save/delete, pending encrypted frames and fragments. `GuardEngine` supplies current scope and encrypts/decrypts. Record stable event IDs, use stored evidence existence for permanent dedupe, keep statistics independently.
- [x] Add failing regression for retention beyond 60 and full long text; add framing/retry/isolation tests.
- [x] Run RED, implement native storage/frames and engine integration, run GREEN.
- [x] Migrate legacy JSON and guard family changes/retries; verify existing tests.

## Task 2: Contact-first parent UI and help prompts
Files: `MainActivity.kt`, `tests/EvidenceUiTest.kt`.
- [x] Add failing behavioral tests for search/contact detail, saved/date/type filters, full detail, state restoration, alert help acknowledgment and notification deep link.
- [x] Implement existing-style native controls with bounded 30-item pages, concise previews and selectable detail.
- [x] Add calm help sheet and per-event acknowledgment; keep background notification system-owned.
- [x] Run focused GREEN and full suite.

## Task 3: Reliability feedback and validation
Files: related service/status paths, scoped tests, README and validation notes.
- [x] Preserve child privacy/stranger-alert policies in regression tests; verify failed send remains pending and reconnect retries it.
- [x] Expose delivery/permission health without implying security-app acceptance.
- [x] Build, run lint/full suite and capture actual native light/dark UI with test-only data outside production.
- [x] One fresh whole-change review, fix material findings, rerun affected checks.
- [x] Leave all edits local and report physical-device limits and media feasibility clearly.

## Completion evidence
All planned local implementation and automated/native-view checks are complete. Final run: 97 tests, no failures/errors/skips; debug and unsigned release builds passed; lint has 0 errors and 54 warnings. The fresh review findings were reproduced and fixed. See `docs/evidence-reliability-validation.md` for scope, logs and remaining physical-device/live-service checks. These results were recorded before the subsequent authorization to push and run the signed build.
