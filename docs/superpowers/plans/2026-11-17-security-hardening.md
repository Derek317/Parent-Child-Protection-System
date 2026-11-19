# Security hardening implementation plan

**Goal:** Implement the approved incremental security proposal and an isolated d1 feasibility experiment without replacing current detection.
**Architecture:** Android-native access gates and Keystore identity, centralized capture/transfer policy, bounded local/relay retention; a separate synthetic-data model harness.
**Tech Stack:** Kotlin/JCA/Android API 26+, SQLite, Cloudflare Worker JavaScript, Python/PyTorch experiment.
**Spec:** docs/superpowers/specs/2026-11-17-security-hardening-design.md

## Global constraints
No pushes/deployments/publication. Existing archives never silently erased on migration. Locked UI does not stop delivery. No plaintext fallback or key reset. No real child data in model tests. New source files included in generated Android build and test harness.

## Review focus
Legacy signing-key migration; queued/in-flight packets after withdrawal; expired data resurfacing; app resuming after failed/cancelled authentication; prototype scores mistaken for validated grooming probabilities.

### Task 1: Baseline and isolated branches
- [x] Run existing Android and relay tests, retain logs. Security branch and experiment branch start at 7160668.

### Task 2: Identity and compromise recovery
- [x] Add failing tests for non-exportable identities, unchanged trust on legacy migration, and recovery never publishing new secret through old channel.
- [x] Implement Keystore signing with existing command/relay API compatibility, fresh-pairing recovery and protected invitation handling. Verify regression suites.

### Task 3: Collection control and parent access
- [x] Add failing tests for locked UI, background receipt, withdrawal and re-consent, scope choices and in-flight callbacks.
- [x] Implement native authentication/secure screens/generic notifications and central persistent capture/transfer gate. Keep delivery independent of UI lock.

### Task 4: Retention and relay erasure
- [x] Add failing storage/relay tests for expiry, preservation, legacy preview, no resurrection and authenticated retirement.
- [x] Implement 30-day new archives, 72-hour queued records, 90-day explicit preservation; integrate settings and deletion UI (relay-side lifetime and retirement changes deferred pending compatibility review).

### Task 5: d1 experiment branch
- [x] Inspect pinned model licence/code, implement synthetic multilingual benchmark with rules baseline and metrics, run real inference if runtime permits, document Android runtime evidence and limits.

### Task 6: Integrated debug and review
- [x] Run full Android tests/build/lint and relay tests; focused adversarial/regression sessions for identity, UI lifecycle and retention/withdrawal. Fix failures with reproductions.
- [x] Update consistent terms/privacy/README/release notes, record actual results and independent review, leave local branches ready for review.

Completed locally 18 November 2026 (Hong Kong). Full checks:315 Android,25 relay,33 packaging/config/UI; debug APK and lint pass (74 warnings). Model:90 synthetic inferences,8 harness checks; Android performance unmeasured. Release notes record limits. No push/deploy/main merge.
