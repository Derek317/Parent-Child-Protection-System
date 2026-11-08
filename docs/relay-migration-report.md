# Cloudflare relay migration verification

Status: implementation and local verification complete; independent review and production publication pending. Production deployment and replacement of the 4.8 download require the workflow's live checks and signed artifact verification; no production success is asserted here yet.

## Root cause and repaired path

The supplied child log shows ntfy rejecting upload with HTTP 429. The 4.7/4.8 transport block was identical; the status establishes upload rejection, not a changed pairing algorithm or signing-key failure. The original client also drained unpaced batches, reset retry times on forced retries and retransmitted accepted records while waiting for receipts. The log omitted enough error detail to distinguish ntfy's request limiter from its daily limiter.

The replacement uses private Cloudflare rooms with parent ownership proved by the existing signing key and child membership authorized by a separate derived capability. Both roles use one integrated WebSocket client. The backend buffers offline encrypted packets, forwards ordinary online traffic and stores critical controls before acceptance. Local SQLite remains authoritative until an authenticated parent receipt; no UI success is inferred from a socket connection alone.

Relevant fixes include durable parent-control attempts, role-scoped outboxes, typed receive outcomes, durable partial-fragment consumption, stale-session guards, database first-open/migration serialization and retry cooldown preservation. Capture classification, conversation grouping, evidence ordering, consent, charts and unrelated UI remain covered by the existing regressions.

## Evidence collected

- Android full suite: **201 tests, 26 classes, zero failures/errors/skips** including migration, rotation, context-mailbox pressure and persistent reconnect backoff. Both debug and unsigned release APK assembly and debug lint completed successfully. Lint reported zero errors and 60 warnings, including existing platform deprecations and intentional synchronous durability checks.
- Backend: three crypto/vector tests, eight real Worker/SQLite/WebSocket integration tests, a 100-family workload and the live smoke command exercised over verified local TLS. Tests use Cloudflare's actual local runtime, not a substitute mailbox implementation.
- Full load simulation: **100 independent families, 200 sockets, 50,000 encrypted records and 50,000 verified signed receipts**; 9,600 records were buffered during receiver-offline intervals. There were 100,400 publish attempts, including 400 queue-full refusals retained and retried by the source; no rate-throttle responses. Approximately 79.7 MB of encrypted bodies were published in 67.6 seconds. All simulated pending records reached zero and archive ordering was checked.
- Python signing/configuration/release checks: **28 passing checks**. Actionlint and whitespace checks passed before the final review. Hotfix tests prove the original tag is retained and a failed/unverified hotfix does not mutate existing notes/assets.

The pinned local tooling uses compatible security overrides for sharp 0.35.5 and undici 7.30.2. The final npm audit reported zero known advisories across severities; the full backend suite passed after the update. This is dependency-audit evidence, not a security certification.

The database startup test reproduced concurrent first-open failure before the creation gate. The long-message runtime test exposed throttling of consumption acknowledgments; production throttling now applies to publishers instead. Test diagnostics also separated a closing socket from an observed offline receiver; loss during that interval still requires the source's signed-receipt recovery.

## Capacity and privacy limits

The 500-record/family/day workload is a planning assumption. The local burst test checks protocol behavior and data recovery; it does not measure Cloudflare production billing, account quotas, edge CPU duration, provider uptime or battery behavior. Cloudflare free allowances are shared by the account, and prolonged offline traffic can exhaust storage writes. The phone retains unconfirmed records for later retry when the queue or service refuses admission.

The developer's backend can hold ciphertext and metadata. It is not a claim of no central encrypted storage. See the updated [data flow](privacy-data-flow.md), [capacity assessment](relay-capacity-assessment.md) and [deployment steps](cloudflare-relay-setup.md).

## Remaining release checks

The exact configured endpoint is `https://parent-child-protection-system.dere2005317.workers.dev`. Direct access from this cloud workspace is currently denied by its network policy; a domain draft is saved but unapplied. The normal deployment workflow must independently verify its health, authorization rejection, offline ciphertext delivery and signed receipt before packaging. Cloudflare account credentials remain in GitHub Actions, not this repository or chat.

The workflow-produced APK must match signing-certificate SHA-256 `0eb6f30787c2d6dc23136a28cfd7a08a7956e5fe0565ba142f6711be38ca3f0e`, package `app.family.igguard`, version name 4.8, a version code above 150 and a non-debuggable release. Download and checksum verification follow publication. Keep the old APK available if any required gate fails.

No physical Samsung S26/Instagram two-phone test has been performed here. The implemented capture still depends on Android exposing Instagram notifications/loaded accessible text and keeping the visible services running; the relay does not bypass Android permissions, force-stop or bank-app checks. Passing tests is not a guarantee that software has no bugs.
