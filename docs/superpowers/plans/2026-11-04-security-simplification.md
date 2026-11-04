# Security and simplification implementation plan

> Execution: inline under Superpowers executing-plans, TDD and one fresh final review.

**Goal:** simplify state handling and fix reproduced security/correctness defects without removing protection, pairing, charts, navigation, recovery or unlock features.
**Architecture:** retain native Views and the single APK. Move small cryptographic/protocol primitives into `FamilySecurity.kt`, using Java/Android cryptography only. Remove split volatile caches and duplicate parsing rather than add cache infrastructure.
**Spec:** the user's review/debug/push request in chat; baseline review at `833fe7e`.

## Constraints and review focus

- Reject unencrypted relay commands and malformed invitations before state changes.
- Parent authority must not be derivable from child invitations. New invitations carry a salted password verifier and a parent public key; parent commands are signed. Existing installations need new invitations and a new master password because old invitation contents cannot be recalled.
- Keep legacy monitoring active during migration; reject unsigned parent commands. Temporary codes require delivery to the child before use. Keep the master-password recovery path.
- Only exact sender identity establishes trust; body text cannot establish identity.
- Family replacement clears old authorization state; key rotation preserves deliberate history.
- Heartbeat deadlines use authenticated send time and are checked while the stream stays open.
- Local tests must not contact the production relay.
- No signing secrets in source. Until replacement GitHub Secrets exist, publish an explicitly unsigned build; do not disguise it as a release signed by a private identity.

## Tasks

- [x] 1. Add `FamilySecurity.kt` primitives and behavior tests: password verification without disclosing plaintext, signed command validation including family/freshness/replay boundaries, strict invitation decoding, random expiring unlock codes. Run RED, implement minimal changes, run GREEN. Wire both local/CI project generators.
- [x] 2. Simplify `GuardEngine` caches and parsing, route signed commands through one validation boundary, clear family authorization state on replacement, reject body-derived trust. Add regression tests for plaintext/tampered commands, malformed pairing, stale codes and family changes. Preserve valid monitoring/crypto behavior.
- [x] 3. Consume and stage external invitations once for explicit acceptance; preserve structured notification identity; repair watchdog/send-time accounting and failed rotation handling. Reproduce each defect with tests, then run the full suite.
- [x] 4. Remove exposed APK signer, use optional protected GitHub Secrets, disable app backups/cleartext transport, and label unsigned artifacts accurately. Verify workflow generation and Android lint/debug/release builds. Document key/pairing migration and test limits.
- [x] 5. Fresh whole-branch review and RED/GREEN fixes; prepare verified changes for the authorised main-branch push. Publication and the resulting Actions run are reported in chat after this verification commit.

Ledger:
- User authorises fixes and push. Existing isolated `work` checkout is clean at baseline; do not reapply earlier UI work.
- User reports no replacement signing key. No secret contents are requested or generated into the repository. Signing migration is an explicit external setup requirement.
- Baseline audit identified plaintext relay acceptance, shared child-invite parent authority, sender spoofing, stale/absent heartbeat accounting, premature key rotation, invitation replay/weak parsing, retained family authorization state and split-cache races.

- Completed final review: no Critical findings; two Important findings reproduced and fixed in one pass. `oldChannelReportDoesNotErasePendingRotationRetry` and `manualInviteRequiresBoundCodeConfirmationBeforeChangingFamily` failed before their fixes and pass afterward.
- Final validation: 54 Android tests, zero failures/errors; seven build/contrast tests; actionlint; Android lint with zero errors (51 non-fatal resource/API/style warnings); debug and unsigned release builds succeed. A separate disposable-key run exercised the real signed packaging branch, verified the APK certificate, removed the temporary workflow key, and deleted its fixture-signed APK.
- Ruling: real-device Instagram identity metadata, Samsung permissions, background scheduling, physical clock skew and live relay delivery require device validation — cloud regression coverage does not establish those results — failures can affect monitoring and timely alerts.
- Ruling: actual replacement signing-secret configuration and installation migration remain owner setup — the user has no replacement key and no signing secrets are available — CI intentionally provides an unsigned APK until setup, with reinstall/rotation implications documented.
- No additional actionable Minor findings from final source review. Do not re-review the same fix pass.
