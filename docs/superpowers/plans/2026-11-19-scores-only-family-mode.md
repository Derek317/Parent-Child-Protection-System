# Scores-only family mode: implementation plan

**Goal:** Build the mode in which the child's phone stores recent one-to-one messages, scores them with the local model while charging, and sends the parent scores and content-free alerts only.

**Spec (source of truth):** [`docs/superpowers/specs/2026-11-19-automatic-on-phone-scoring-design.md`](../specs/2026-11-19-automatic-on-phone-scoring-design.md), approved by Derek on 19 November 2026. The scoring contract is fixed by the research (`research/igrisk/scorers.py`, `research/train_lora.py`).

**Branch:** `app/local-risk-scoring` (the remote `experiment/d1-grooming-2026-11-17` branch plus `research/local-risk-model`).

**Architecture:** Pure-Kotlin contract code (`RiskModel.kt`), a thin JNI library over llama.cpp b12503 (`native/risk`, `RiskNative.kt`), an engine file for the child-side store, mode state, batch scoring and relay records (`RiskPipeline.kt` with small hooks in `MainActivity.kt` and `FamilyEvidence.kt`), and two UI files for the child and parent screens. Tests use a fake scorer; the native library is never loaded in unit tests.

## Global constraints

- Model file: `qwen3-1.7b-lora-v3-Q4_K_M.gguf`, 1,107,408,736 bytes, SHA-256 `14f2176d569772bcc8e0e0dcd97e59f7ac60d8c4f6e1cb5f6a45159b1892c293`. Imported by hand on the child's phone. Anything else is refused. No download code.
- In this mode no message text, quote or summary reaches the parent's phone.
- Nothing is stored or scored before the child accepts; a visible status is always shown; pause works at once and the parent is told.
- Store limits: latest 60 messages per thread, nothing older than 14 days. One-to-one threads only: kind `direct`, or kind `unknown` with at most one distinct incoming author (Derek's decision, 19 November 2026, a stated departure from the design's "threads whose kind is unknown are not stored or scored"). Group threads are never stored; an unknown-kind thread that shows a second incoming author is treated as a group, deleted, excluded and counted.
- A thread is scored on its latest 30 messages; its reported score is the highest seen in the last 14 days.
- Runs while idle and charging; after 48 hours without a run, charging only.
- Phones with less than about 8 GB of memory: mode unavailable, and the app says so.
- Android Views in code, no Compose, reuse `ui-res`. Traditional Chinese and English wording.
- Wording never claims Meta approval or legal clearance. Legal drafts are marked as needing Derek's and a Hong Kong privacy lawyer's review.
- The parent's existing archive is never deleted silently.

## Builder decisions (for Derek to confirm)

1. **Records on the relay.**
   - Parent to child, signed like `capture_policy`: `scores_mode_policy` with `policy_id` and `enabled`.
   - Child to parent, control-wrapped like `capture_ack`: `scores_mode_report` with a `state` object (`policy_id`, `model`: valid, missing, mismatch, unsupported_device (memory or CPU) or engine_unavailable (the analysis library could not be loaded); `consent`: none, accepted or withdrawn; `active` (true only while analysis can actually run, so "agreed but not running" is visible); `paused` and `paused_at_ms`; `last_run_ms`; `scored_count`; `unknown_threads`) and a `conversations` array of `{account_label, score or null, review_reason or null, scored_at_ms}`. Nothing else.
2. **Account label and thread kind** come from the existing capture record: `conversation_label` and `conversation_kind`. Kind `direct` is stored, and so is kind `unknown` while it shows at most one distinct incoming author (Derek's decision of 19 November 2026; a stated departure from the design, which did not store unknown-kind threads). A second distinct incoming author makes an unknown thread a group: its stored messages and results are deleted at once and it is excluded for the 14-day window and counted in `unknown_threads`, which now means conversations not analysed because they look like group chats or their type could not be confirmed. Screen captures record no incoming author at all (see "Open questions"), so a screen thread cannot be told from a group; notification threads can.
3. **Content-free alerts** in this mode are ordinary `alert` records with the rule category in `reason`, the account label in `sender`, the time, an empty `text` and `"content_free": true` (only alerts whose text the mode removed carry this field; the parent UI uses it to say that the text was withheld); keyword matches and signals carry the category tag only, never the matched phrase.
4. **After the child withdraws** the phone does not fall back to forwarding text. Alerts stay content-free, the parent sees "withdrawn by your child", and only the parent switching the mode off (with the change-of-mode page on both phones) returns the family to the earlier mode.
5. **Memory check:** total RAM reported by `ActivityManager.MemoryInfo.totalMem` of at least 7 GB counts as "about 8 GB", because phones sold as 8 GB report slightly less.
6. **First-reaches-50 notification** fires once per conversation per 14-day window, on the parent's phone, with no contact name in the notification text.

## Tasks

Each task ends with its tests passing in the Robolectric suite (`tests/run_android.sh`) and the Python checks.

1. **Contract code and report reader.** Done: `RiskModel.kt`, `RiskScoreReport.kt` (accepts the Qwen3 model with its own revision), fixture tests against the harness.
2. **Native scorer.** `native/risk` core and JNI, `RiskNative.kt`, CMake and workflow wiring for `arm64-v8a`, host check against the harness's llama-server path on the base model.
3. **Child engine.**
   - Model import, verification and state; memory check.
   - Mode state: policy received, child consent receipt (separate from the Terms receipt), paused, withdrawn. `active` only when every condition holds.
   - Encrypted store with the 60-message and 14-day limits; `direct` threads, and `unknown` threads with at most one incoming author; groups excluded and counted.
   - In active mode: captured messages go to the store and are not queued for the parent; keyword and unapproved-contact alerts are queued without text.
   - Batch scoring with the fake-able scorer; highest score in 14 days; interrupted thread restarts.
   - Job scheduling: idle and charging; charging only after 48 hours.
   - `scores_mode_report` after each run and on every state change (model imported or removed, accepted, paused, resumed, withdrawn).
   - Pause stops storing and scoring at once, including a run in progress. Withdraw empties the store and unsent scores.
   - Tests: the list in the design's "Left to the builder" plus no-text checks on every outgoing record.
4. **Parent engine.** Signed `scores_mode_policy` sender; strict `scores_mode_report` receiver; score and state tables with the archive's deletion rules; first-reaches-50 notification; 72-hour staleness.
5. **Child screens.** Model import with clear refusal screens; one-time consent page and receipt; always-visible status with Pause, Resume, Withdraw and "What is analysed?"; change-of-mode page; "not available on this phone".
6. **Parent screens.** Switch (disabled until the child reports a valid model); change-of-mode page; score page with band label and icon, score, account label and date; states for waiting, paused, withdrawn, out of date, model missing or wrong, empty and scoring; the model-limits notice and next steps.
7. **Documents.** Draft updates to the Terms of Service, Privacy Policy and `docs/privacy-data-flow.md`, each marked as a draft needing review; `CHANGELOG.md`.
8. **Review and pull request.** Whole-suite run, lint, Python checks, native build for `arm64-v8a`, then a pull request that lists status, open questions and everything not verified on a phone.

## Open questions

- **Unknown thread kind. Resolved (Derek, 19 November 2026): treat unknown-kind threads as one-to-one while only one other author appears.** This replaces the design's rule. Limit that remains: the screen reader records no incoming author (incoming bubbles carry an empty `author`, outgoing ones the child's label), so a group read only from the screen shows no second author and is analysed as if it were one-to-one. Only notification records carry an author. The "not analysed" count therefore under-reports groups seen on screen.
- **Parent password for the model on the child's phone. Resolved: yes (Derek, 19 November 2026).** Importing and removing the model on the child's phone need the parent's password, using the same check as switching the phone's role; cancelling or a wrong password changes nothing.
- **Terms receipt.** The Terms file is hashed into each consent receipt and existing users are never asked again. The design adds a separate consent page for the child; whether parents must also re-accept changed Terms is a legal question.
- **Version.** The app version (`4.9-beta.4`) is pinned in three places and is not changed by this work.

## Not verifiable here

Anything that needs a physical phone: the native library running on a device, speed, memory, heat, battery, how often idle-and-charging windows occur, importing a 1.1 GB file through the system picker, notifications as the user sees them, and TalkBack behaviour on real hardware.
