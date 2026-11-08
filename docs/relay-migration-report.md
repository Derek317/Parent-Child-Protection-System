# Cloudflare relay migration verification

Status: implementation and local verification complete; independent review findings fixed and verified; Cloudflare deployment succeeded but live readiness and APK publication are blocked by HTTP 403. Production deployment and replacement of the 4.8 download require the workflow's live checks and signed artifact verification; no production success is asserted here yet.

## Root cause and repaired path

The supplied child log shows ntfy rejecting upload with HTTP 429. The 4.7/4.8 transport block was identical; the status establishes upload rejection, not a changed pairing algorithm or signing-key failure. The original client also drained unpaced batches, reset retry times on forced retries and retransmitted accepted records while waiting for receipts. The log omitted enough error detail to distinguish ntfy's request limiter from its daily limiter.

The replacement uses private Cloudflare rooms with parent ownership proved by the existing signing key and child membership authorized by a separate derived capability. Both roles use one integrated WebSocket client. The backend buffers offline encrypted packets, forwards ordinary online traffic and stores critical controls before acceptance. Local SQLite remains authoritative until an authenticated parent receipt; no UI success is inferred from a socket connection alone.

Relevant fixes include durable parent-control attempts, role-scoped outboxes, typed receive outcomes, durable partial-fragment consumption, stale-session guards, database first-open/migration serialization and retry cooldown preservation. Capture classification, conversation grouping, evidence ordering, consent, charts and unrelated UI remain covered by the existing regressions.

## Evidence collected

- Android full suite: **205 tests, 26 classes, zero failures/errors/skips** including migration, rotation, context-mailbox pressure and persistent reconnect backoff. Both debug and unsigned release APK assembly and debug lint completed successfully. Lint reported zero errors and 60 warnings, including existing platform deprecations and intentional synchronous durability checks.
- Backend: three crypto/vector tests, eight real Worker/SQLite/WebSocket integration tests, a 100-family workload and the live smoke command exercised over verified local TLS. Tests use Cloudflare's actual local runtime, not a substitute mailbox implementation.
- Full load simulation: **100 independent families, 200 sockets, 50,000 encrypted records and 50,000 verified signed receipts**; 9,600 records were buffered during receiver-offline intervals. There were 100,400 publish attempts, including 400 queue-full refusals retained and retried by the source; no rate-throttle responses. Approximately 79.7 MB of encrypted bodies were published in 67.6 seconds. All simulated pending records reached zero and archive ordering was checked.
- Python signing/configuration/release checks: **29 passing checks**. Actionlint and whitespace checks passed before the final review. Hotfix tests prove the original tag is retained and a failed/unverified hotfix does not mutate existing notes/assets.

The pinned local tooling uses compatible security overrides for sharp 0.35.5 and undici 7.30.2. The final npm audit reported zero known advisories across severities; the full backend suite passed after the update. This is dependency-audit evidence, not a security certification.

The database startup test reproduced concurrent first-open failure before the creation gate. The long-message runtime test exposed throttling of consumption acknowledgments; production throttling now applies to publishers instead. Test diagnostics also separated a closing socket from an observed offline receiver; loss during that interval still requires the source's signed-receipt recovery.

## Independent review and recovery fixes

A fresh read-only review of the entire migration found two important issues and no critical issues. Both were reproduced before fixing them:

- New ordinary captures originally entered the outbox as urgent before their kind changed to data. This bypassed a data-only mailbox-full cooldown. Captures now enter with their final kind, preserving the correct cooldown while leaving new urgent evidence eligible.
- A successfully retried rotation originally depended on its first UI callback to change the parent token. That callback could already have completed with failure or disappeared on restart. The pending command identity is now committed before enqueueing, accepted frames stay in SQLite until the journaled token/history transition commits, and startup resumes that transition. Tests cover failure then background success, partial-frame acceptance, both crash boundaries, stable retries, and child verification of the original signed command.

No second review was substituted for regression verification. The notification listener, accessibility capture service and activity UI source blocks remain byte-for-byte identical to the baseline.

Implementation decisions and review limits:

- Use the existing managed branch and native execution under the user's existing approval; an unexpected concurrent edit would require Git conflict resolution before push.
- Reuse SQLite with explicit outbox role/kind and retain old-scope signed parent controls through rotation; an incorrect migration could delay queued evidence or controls, so migration and isolation regressions are required.
- Forward ordinary online ciphertext without writing a cloud copy; the phone retains it until a signed receipt, so a lost direct transmission can require a retry.
- Production deployment, credentials, quotas and signed artifacts need workflow/live evidence before publication; local simulation alone cannot establish them.
- Physical Samsung, Doze and force-stop behavior still needs real-phone acceptance. The migration does not independently recertify unchanged app features or primitives; existing limitations can remain.
- Preserve the existing release upload fallback and independently verify the published download; an interrupted upload can still require release-asset repair.

Deferred minor findings: none.

## Capacity and privacy limits

The 500-record/family/day workload is a planning assumption. The local burst test checks protocol behavior and data recovery; it does not measure Cloudflare production billing, account quotas, edge CPU duration, provider uptime or battery behavior. Cloudflare free allowances are shared by the account, and prolonged offline traffic can exhaust storage writes. The phone retains unconfirmed records for later retry when the queue or service refuses admission.

The developer's backend can hold ciphertext and metadata. It is not a claim of no central encrypted storage. See the updated [data flow](privacy-data-flow.md), [capacity assessment](relay-capacity-assessment.md) and [deployment steps](cloudflare-relay-setup.md).

## Production workflow result

The implementation and reviewed recovery fixes were pushed to `main` at `fc17278`. Subsequent CI-only diagnostics were pushed through `53d3bf8`; Android behavior is unchanged by those diagnostic commits.

- [Initial run](https://github.com/Derek317/Parent-Child-Protection-System/actions/runs/42500665435): backend tests and Cloudflare deployment completed successfully; the public health check failed, so APK build and release were skipped.
- [Final diagnostic run](https://github.com/Derek317/Parent-Child-Protection-System/actions/runs/42501288915): the deployed and configured URL both equal `https://parent-child-protection-system.dere2005317.workers.dev`. The public `/health` request returns **HTTP 403**, with **Server: cloudflare**. No challenge header or recognized numeric provider error was reported. The endpoint syntax and deployment credentials are therefore not the observed blocker; the precise Cloudflare access restriction still needs account-side/error-page evidence.

The reviewed Worker returns HTTP 200 for an ordinary GET `/health` before family authentication. The observed 403 prevents that intended health response; it is not an APK signing error. APK compilation/signing never started in these runs. Do not weaken the health/authentication checks or publish an APK pointing at a relay that has not passed them.

The browser response from the account owner is needed to distinguish a Cloudflare route/account restriction, Access policy or other edge denial. Account/deployment tools from the selected Cloudflare connector are not exposed to this chat; deployment used the user's GitHub Actions credentials. The separate local cloud workspace also denies direct access through its egress proxy until the saved host allowlist draft is applied. That local proxy denial must not be confused with the workflow's Cloudflare 403.

No `4.8-hotfix.N` tag was created and this work has not modified the existing 4.8 release notes or APK assets. After live verification succeeds, create an unused hotfix source tag at the verified runtime source commit, run the signed workflow and independently verify the published APK/checksum/certificate. Keep the original `4.8` source tag unchanged.

## Remaining release checks

The exact configured endpoint is `https://parent-child-protection-system.dere2005317.workers.dev`. Direct access from this cloud workspace is currently denied by its network policy; a domain draft is saved but unapplied. The normal deployment workflow must independently verify its health, authorization rejection, offline ciphertext delivery and signed receipt before packaging. Cloudflare account credentials remain in GitHub Actions, not this repository or chat.

The workflow-produced APK must match signing-certificate SHA-256 `0eb6f30787c2d6dc23136a28cfd7a08a7956e5fe0565ba142f6711be38ca3f0e`, package `app.family.igguard`, version name 4.8, a version code above 150 and a non-debuggable release. Download and checksum verification follow publication. Keep the old APK available if any required gate fails.

No physical Samsung S26/Instagram two-phone test has been performed here. The implemented capture still depends on Android exposing Instagram notifications/loaded accessible text and keeping the visible services running; the relay does not bypass Android permissions, force-stop or bank-app checks. Passing tests is not a guarantee that software has no bugs.
