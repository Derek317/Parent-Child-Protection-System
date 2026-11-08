# Cloudflare Relay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Replace ntfy delivery with an authenticated free Cloudflare relay while preserving existing family data and signed delivery confirmation.

**Architecture:** One private SQLite-backed Durable Object per family, hibernating WebSockets, direct encrypted forwarding and bounded offline encrypted mailboxes. Existing phone-side encryption, parent signing identity and SQLite outbox remain authoritative. Configuration/deployment and release verification are part of the deliverable.

**Tech Stack:** Kotlin/Android API 26+, OkHttp 4.12, JavaScript/Web Crypto, Cloudflare Workers/Durable Objects, Node test runner and Miniflare.

**Spec:** [approved design](../specs/2026-11-08-cloudflare-relay-design.md).

## Global Constraints

- Workers Free and SQLite-backed Durable Objects; no paid plan, Firebase, FCM or privileged APK credential.
- Version name 4.8, higher version code, existing signing identity, retain the original 4.8 tag.
- Preserve existing secure pairing, child consent, capture features, local archives, ordering and unconfirmed outbox.
- 512 buffered packets per direction, seven-day cloud-copy expiry; local unconfirmed evidence is not expired.
- Authenticate ownership and direction; cloud acknowledgment never replaces a signed parent receipt.
- Release requires a valid deployed endpoint and verified workflow APK; do not overwrite a working asset with an unconfigured build.

## Review Focus

- Concurrent/replayed parent authorization after restart must not register a second owner or nonce.
- A lost parent receipt after successful local storage must recover without duplicate evidence or lost pending records.
- A long message exceeding the cloud mailbox must progress by consuming durable fragments.
- Key rotation/reset during an in-flight packet must not clear evidence in a different family or strand control delivery.
- Resume/manual tests and backend quota failures must preserve cooldown rather than cause a new retry storm.

---

### Task 1: Shared relay authentication and routing

**Files:** Create `FamilyRelay.kt`, `tests/FamilyRelayTest.kt`, `relay/src/protocol.js`, `relay/test/protocol.test.js`, `relay/package.json`, `relay/package-lock.json`; modify Android source copying/dependencies in `.github/workflows/build-apk.yml` and `tests/run_android.sh` as needed for compilation.

**Interfaces:** `FamilyRelay.room(publicKey, scope): String`, `childCapability(token): String`, `parentAuthorization(identity, scope, token, now, nonce): String`; backend `roomId(publicKey,scope)`, `verifyParentAuthorization(auth,room,now)`, `capabilityHash(capability)`. Authorization uses a fixed newline-delimited domain-separated message and canonical base64url SPKI; ECDSA signatures have explicitly converted DER/raw forms.

- [x] Write tests for distinct room/key/scope, non-key child capability, signature verification, tampered fields and expiry with shared literal vectors.
- [x] Run tests and observe missing behavior fail.
- [x] Implement minimal standard crypto helpers and pinned test/runtime tooling.
- [x] Run Android helper tests and Node protocol tests; commit.

### Task 2: Authenticated durable WebSocket backend

**Files:** Create `relay/src/worker.js`, `relay/wrangler.jsonc`, `relay/test/relay.test.js`, `relay/test/load.test.js`.

**Interfaces:** WSS `/v1/rooms/{room}` with `Authorization: IGGuard <base64url JSON>`. Client sends `publish{id,wire,kind}`, `consume{id,digest}`, `replay`; server sends `ready`, `accepted{id,mode}`, `message{id,delivery_id,wire,digest}`, `error{code,retry_after_ms}`. Roles determine inbox/outbox; stable packet keys and fresh delivery IDs are separate.

- [x] Write failing real-runtime tests for owner bootstrap, capability/role isolation, nonce replay/concurrency, offline queue, direct forwarding, duplicate/conflicting packets, consumption, queue bounds/expiry and restart.
- [x] Implement Worker routing, synchronous SQLite admission transactions, hibernation sockets and bounded cleanup/rate limits.
- [x] Run Miniflare tests, including fragments/queue progression and 100-room traffic/operation measurements; commit.

### Task 3: Android connection, receipt and lifecycle integration

**Files:** Create `CloudRelay.kt`, `tests/CloudRelayTest.kt`; modify transport/status methods in `MainActivity.kt`, retry methods in `FamilyEvidence.kt`, affected existing tests and build source copying.

**Interfaces:** `CloudRelay.connect(ctx)`, `publish(ctx,token,id,wire,kind,onComplete)`, `disconnect()`, `receive handler` captures immutable role/token/generation. `GuardEngine.processIncomingRelayLine` returns a consumed/rejected/retry outcome while retaining the existing decoded record/signature paths. Keep source storage until existing signed event receipt.

- [x] Write failing regressions for accepted-frame retry preservation, durable consume only after local commit, receipt recovery, stale sessions, child-first connection, configuration failure and controls/rotation.
- [x] Implement one active socket per session with bounded pending publishes, jittered reconnect and persistent provider cooldown. Replace ntfy stream/poll/publish call sites without changing capture logic.
- [x] Preserve critical parent control durability and old-room rotation recovery; prioritize unaccepted frames without blocking other events.
- [x] Run affected Android tests, then full Android tests/lint/build and backend suite; commit.

### Task 4: Deployment and release safeguards

**Files:** Modify `.github/workflows/build-apk.yml`, `scripts/publish-release.sh`, `tests/check_build.py`, `tests/check_release.py`, `docs/cloudflare-relay-setup.md`; add workflow/runtime checks only where needed.

**Interfaces:** public repository variable `RELAY_BASE_URL` supplies the reviewed HTTPS worker endpoint. Signing secrets remain unchanged. Release scripts accept an explicitly checked 4.8 hotfix source without moving tag 4.8; existing APK is retained if configuration/build verification fails.

- [x] Add failing script/workflow behavioral checks for missing/unsafe endpoint, hotfix provenance, failed build preserving assets and valid update upload.
- [x] Implement config generation/validation, backend tests in CI, deployment commands and signed artifact safeguards.
- [x] Run checks/actionlint, test deployment locally and document external account/endpoint requirements; commit.

### Task 5: Whole-change verification and publication

**Files:** Update `README.md`, `docs/privacy-data-flow.md`, `docs/releases/4.8.md`; create `docs/relay-migration-report.md` and update design status.

- [x] Run realistic end-to-end clients, offline/throttle/restart/rotation/fragment tests and 100-family load; record actual operation counts and limits.
- [ ] Run the full Android and backend suites, build/lint/workflow checks; request one independent whole-change review and fix important findings with regression tests.
- [ ] Deploy to the user's free account when authorized account access is available; verify the public endpoint and both-device delivery. Do independent code/tests/docs work while access is pending.
- [ ] Push verified source using existing Git authentication. Run the signed workflow with the real endpoint, download and verify package/version/certificate/checksum, then replace the existing 4.8 APK and release notes. If deployment/configuration remains unavailable, push a reviewable branch and report the exact release blocker; leave the published APK intact.

Execution: native in this session under the user's explicit approval to implement, push and replace 4.8. No further design/execution-method approval is needed. The live backend URL and account access are external prerequisites, not permission to guess an endpoint or publish an unconfigured APK.
