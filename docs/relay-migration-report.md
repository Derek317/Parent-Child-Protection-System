# Cloudflare relay migration verification

Status: **published and independently verified**. The Cloudflare relay passed live health, authentication, offline ciphertext delivery and signed-receipt checks. The existing 4.8 release now provides the verified signed hotfix APK (version code 159).

## Root cause and repaired path

The supplied child log shows ntfy rejecting upload with HTTP 429. The 4.7/4.8 transport block was identical; the status establishes upload rejection, not a changed pairing algorithm or signing-key failure. The original client also drained unpaced batches, reset retry times on forced retries and retransmitted accepted records while waiting for receipts. The log omitted enough error detail to distinguish ntfy's request limiter from its daily limiter.

The replacement uses private Cloudflare rooms with parent ownership proved by the existing signing key and child membership authorized by a separate derived capability. Both roles use one integrated WebSocket client. The backend buffers offline encrypted packets, forwards ordinary online traffic and stores critical controls before acceptance. Local SQLite remains authoritative until an authenticated parent receipt; no UI success is inferred from a socket connection alone.

Relevant fixes include durable parent-control attempts, role-scoped outboxes, typed receive outcomes, durable partial-fragment consumption, stale-session guards, database first-open/migration serialization and retry cooldown preservation. Capture classification, conversation grouping, evidence ordering, consent, charts and unrelated UI remain covered by the existing regressions.

## Evidence collected

- Android full suite: **205 tests, 26 classes, zero failures/errors/skips** including migration, rotation, context-mailbox pressure and persistent reconnect backoff. Both debug and unsigned release APK assembly and debug lint completed successfully. Lint reported zero errors and 60 warnings, including existing platform deprecations and intentional synchronous durability checks.
- Backend: three crypto/vector tests, eight real Worker/SQLite/WebSocket integration tests, a 100-family workload and the live smoke command exercised over verified local TLS. Tests use Cloudflare's actual local runtime, not a substitute mailbox implementation.
- Full load simulation: **100 independent families, 200 sockets, 50,000 encrypted records and 50,000 verified signed receipts**; 9,600 records were buffered during receiver-offline intervals. There were 100,400 publish attempts, including 400 queue-full refusals retained and retried by the source; no rate-throttle responses. Approximately 79.7 MB of encrypted bodies were published in 67.6 seconds. All simulated pending records reached zero and archive ordering was checked.
- Python signing/configuration/release checks: **30 passing checks**. Actionlint and whitespace checks passed before the final review. Hotfix tests prove the original tag is retained and a failed/unverified hotfix does not mutate existing notes/assets.

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

The implementation and reviewed recovery fixes were pushed to `main` at `fc17278`. Subsequent changes through `640324f` concern CI and live-verification diagnostics; Android behavior is unchanged by those commits.

- [Initial run](https://github.com/Derek317/Parent-Child-Protection-System/actions/runs/42500665435): backend tests and deployment passed, but Cloudflare rejected the Python HTTP health request with 403. APK compilation/signing did not start, so this was not a signing-key failure.
- The deployed and configured URL were confirmed identical. The user received the expected health JSON in both ordinary and private browser windows, without a required Cloudflare login.
- [Separated boundary checks](https://github.com/Derek317/Parent-Child-Protection-System/actions/runs/42502215214): the Python health request still received 403 while the actual authenticated WebSocket, offline ciphertext delivery, decryption and signed parent receipt all passed from CI.
- [Verified build](https://github.com/Derek317/Parent-Child-Protection-System/actions/runs/42502346523): adding the honest `IGGuard-Relay-Check/1.0` User-Agent to the health request resolved the rejection. Both health and live protocol checks passed, followed by Android regression checks, compilation, signing verification and artifact upload. No TLS, response validation, authentication or release checks were waived. Cloudflare's exact internal rule was not observable; the verified difference is explicit health-client identification.
- [Release workflow](https://github.com/Derek317/Parent-Child-Protection-System/actions/runs/42502726914): source tag `4.8-hotfix.2` targets `640324f471557587dec13e9caca4e0e28cbd7bf0`. The first hotfix tag failed safely before publication. The original `4.8` tag remains `ebb8f783f80531bb44ec16ecf7dc7e230c2512c1`.

The live health and protocol gates now run independently and both must pass. Regression checks prove an HTTP failure cannot mask the socket result or allow publication. The real-TLS smoke test also verifies safe diagnostics for a denied WebSocket handshake. Account credentials remain in GitHub Actions; selecting the Cloudflare connector did not expose deployment tools to this chat.

## Published artifact verification

The [release workflow](https://github.com/Derek317/Parent-Child-Protection-System/actions/runs/42502726914) completed successfully. The published [IG-Guard-4.8.apk](https://github.com/Derek317/Parent-Child-Protection-System/releases/download/4.8/IG-Guard-4.8.apk) was downloaded independently and verified with Android `apksigner`, `aapt`, SHA-256 and a DEX configuration check.

| Check | Verified result |
| --- | --- |
| Version | 4.8, version code **159** (previously 150) |
| Package | `app.family.igguard` |
| APK SHA-256 | `43f58b11edf3ea8cbc4304c859bd2923fed3c5b3150651d5dfd36110e3cb55fa` |
| Signing certificate SHA-256 | `0eb6f30787c2d6dc23136a28cfd7a08a7956e5fe0565ba142f6711be38ca3f0e`; unchanged from the previous 4.8 APK |
| Debuggable | No |
| Embedded relay URL | `https://parent-child-protection-system.dere2005317.workers.dev` |
| File size | 1,348,032 bytes |
| Source | `640324f471557587dec13e9caca4e0e28cbd7bf0`, tag `4.8-hotfix.2` |
| Original 4.8 tag | `ebb8f783f80531bb44ec16ecf7dc7e230c2512c1`; unchanged |

The release's `SHA256SUMS` matches the downloaded APK. The release notes identify the hotfix source and successful workflow. The artifact differs from the previous APK; it was not merely a notes update.

Update **both phones in place** with this APK, open the parent app first, then reconnect the child app. Keep the existing secure pairing, consent and app data. Do not uninstall or erase data to migrate. A legacy pairing without a pinned parent key still needs the normal authorized invitation upgrade.

Direct access from this development workspace remains restricted by its unapplied network draft; the live checks ran in GitHub Actions. That workspace restriction is not a phone setup step.

No physical Samsung S26/Instagram two-phone test has been performed here. The implemented capture still depends on Android exposing Instagram notifications/loaded accessible text and keeping the visible services running; the relay does not bypass Android permissions, force-stop or bank-app checks. Passing tests is not a guarantee that software has no bugs.
