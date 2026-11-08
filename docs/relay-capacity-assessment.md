# Relay capacity and replacement assessment

Status: the user selected Cloudflare Workers Free with Durable Objects and WebSockets after the capacity comparison, for around 100 parent–child families. The [Cloudflare design](superpowers/specs/2026-11-08-cloudflare-relay-design.md) is approved and implemented locally; live deployment and signed publication are tracked in the [verification report](relay-migration-report.md). Firebase is no longer the selected implementation; no Firebase project or client configuration is needed for this design.

## Confirmed failure

The child-device diagnostic reports `CP3-ERR: Publish HTTPS 429`. The relay rejected the upload before the parent could receive that message. The existing completion callback then reduced the outcome to a generic `未送達` message.

The sending, polling and incoming-message processing block is byte-identical between tags 4.7 and 4.8. This is not evidence of a changed pairing or encryption algorithm. HTTP 429 is sufficient to explain the failed delivery, but does not independently verify that every pairing value is correct.

Official ntfy source distinguishes request-rate exhaustion (`42901`) from daily message-quota exhaustion (`42908`). The supplied diagnostic contains only the HTTP status, so the exact limiter cannot be determined from that log. Published limits can depend on the deployment, account and shared network address; changing the family/topic does not establish a new independent quota.

Source: [ntfy server errors](https://github.com/binwiederhier/ntfy/blob/main/server/errors.go) and [server configuration](https://github.com/binwiederhier/ntfy/blob/main/server/config.go). These official files were read during the investigation. Their defaults are not asserted to be the current hosted-service configuration.

## Capacity and retry weaknesses in the current client

- Each captured record is published separately; long records require multiple relay messages. Parent receipts create additional publishes.
- The upload loop drains successive due batches without pacing. Ordinary archive context competes for the same external capacity as alerts and heartbeats.
- A relay-accepted record remains queued until its signed parent receipt arrives. It is eligible for retransmission while the receipt is missing.
- Forced retry resets pending retry times, including accepted records. Resume, reconnection and the manual test can therefore repeat already accepted traffic.
- The client neither reads the service's bounded error details nor honors `Retry-After`. Its generic failure message hides the difference between temporary throttling and a daily quota.
- The earlier automated suite covered queue durability, refused connections, injected relay records and signed receipts. It did not simulate a finite-capacity relay returning successful uploads followed by HTTP 429. Passing that suite did not prove that a full conversation workload fit the public relay.

The expanded 4.8 capture can increase traffic and expose these weaknesses. The log alone does not prove which traffic exhausted this user's actual quota or that the quota began with 4.8. Queued ciphertext must be preserved while fixing the transport; retries must respect provider limits.

## Replacement requirements

- Only IG Guard is installed on parent and child phones.
- No VPS or server for the developer or families to operate; no payment or automatic paid upgrade.
- Capacity for ordinary conversation capture, not just occasional risk notifications.
- Existing application encryption, signed parent commands, explicit child consent, durable local queue, receipt verification and family separation remain intact.
- State plainly whether the choice requires a developer-owned managed-cloud project. A managed database is a backend, even though there is no server to operate.

## Options

| Option | Fit for these constraints |
| --- | --- |
| Firebase Realtime Database on Spark with client authentication | Promising if a free Google-managed project is acceptable. Both apps can exchange encrypted queue records using the Android SDK without a developer-operated application server or another user app. Project-wide storage, downloads and concurrent-connection limits must be budgeted. |
| FCM alone | A push-delivery service, not a safe arbitrary client-to-client queue. Sending requires a trusted sender; service-account credentials must never be shipped in the APK. |
| Telegram Bot API / Discord webhooks | Notification and bot interfaces are not drop-in private bidirectional mailboxes. Account/channel setup, receiving semantics and protection of privileged bot credentials complicate a fully integrated consumer app. A common privileged token in every APK is unsuitable. |
| Self-hosted ntfy / Gotify | Free software does not eliminate hosting or operating a server. |
| Pushover | Does not meet the zero-cost requirement and normally uses its own receiving app. |
| Public MQTT test brokers | Can support an integrated technical prototype, but public test infrastructure is not a reliable production delivery commitment for this protection app. |
| Direct WebRTC / peer-to-peer | Requires signaling and often TURN for mobile networks; offline delivery requires storage somewhere. It does not guarantee reliable server-free delivery between arbitrary phones. |

## Capacity decision for around 100 families

The previous Realtime Database recommendation does not meet the newly clarified target with persistent SDK connections. Common Spark allowances are 100 simultaneous database connections, 1 GB stored and 10 GB downloaded monthly, shared by the whole project. One parent and one child for each of 100 families would require approximately 200 connections. Do not deploy that design as the solution for this audience. Do not assume switching to REST removes connection, bandwidth or battery constraints without verifying the actual behavior and budget.

Firestore Spark avoids that particular connection ceiling, but commonly provides 20,000 document writes, 50,000 reads and 20,000 deletes daily. The planning workload of 100 families × 500 captured records is already 50,000 records/day. One write per record exceeds the write allowance before receipts, fragmentation, retries, heartbeats or commands. Updating one shared document per message still counts as one write per update.

The existing heartbeat interval is randomly 30–95 minutes, averaging 62.5 minutes, or roughly 23 heartbeats/family/day. At 100 families, that is approximately 2,300 more events and 2,300 signed receipts daily. Routine traffic must be included rather than treated as free. The 500-record workload is a planning assumption, not measured user traffic; one record may require multiple encrypted frames.

Batching Firestore traffic can reduce document operations, but the benefit depends on how messages arrive. Messages spread across a day may not batch together. Delaying ordinary records for minutes would change archive freshness, while immediate alerts and signed receipts would still consume quota. This is not an equivalent, validated fix that can be substituted silently.

Firebase quota figures above are planning figures, not live pricing verification: the environment denied access to the Firebase documentation host. Confirm current [pricing](https://firebase.google.com/pricing), [Realtime Database limits](https://firebase.google.com/docs/database/usage/limits) and [Firestore quotas](https://firebase.google.com/docs/firestore/quotas) before deployment. A draft environment change adds `firebase.google.com` for review; it has not been applied or published.

## Alternative worth taking to design review

Cloudflare Workers Free with SQLite-backed Durable Objects and hibernating WebSockets was selected by the user for this workload. It needs a developer-owned free Cloudflare account/deployment, but no user accounts at Cloudflare, extra phone app, VPS or paid Firebase plan. The provider choice is accepted; the migration is not implemented yet.

Official Cloudflare documentation was read from its public source repository during this investigation:

- [Workers pricing](https://github.com/cloudflare/cloudflare-docs/blob/production/src/content/docs/workers/platform/pricing.mdx): 100,000 incoming requests/day; a WebSocket upgrade counts as a request, while WebSocket messages through the Worker do not count as additional Worker requests.
- [Durable Objects pricing](https://github.com/cloudflare/cloudflare-docs/blob/production/src/content/partials/durable-objects/durable-objects-pricing.mdx): 100,000 compute requests/day, 20 incoming WebSocket messages per metered request, 13,000 GB-seconds/day, 5 million SQLite rows read/day, 100,000 rows written/day and 5 GB stored. Deletes and alarm writes also count as writes. These are account-wide limits, not per-family allowances.
- [Hibernation and free-limit behavior](https://github.com/cloudflare/cloudflare-docs/blob/production/src/content/docs/durable-objects/platform/pricing.mdx): hibernation matters for duration; exceeding a free limit causes operations to fail, rather than providing unlimited capacity. Daily limits reset at 00:00 UTC.

A reasonable candidate is one private relay room per family, direct encrypted forwarding while the receiver is connected, and a bounded durable encrypted mailbox while it is disconnected. Preserve the phone's local outbox until a valid signed parent receipt. Cloud acceptance or a WebSocket connection alone must never be labelled parent delivery.

Illustrative envelope: 50,000 one-frame records + 50,000 receipts + approximately 4,600 heartbeat/receipt frames is 104,600 incoming frames/day. At 20:1 this represents about 5,230 Durable Object compute requests, plus connections and other operations. This only checks one dimension. Fragmentation, retransmission, socket maintenance, runtime restarts, CPU duration and offline storage must be measured too.

For example, if 20% of those frames need a stored row and later deletion, that alone is roughly 41,840 row writes/day. If all frames require both operations, approximately 209,200 writes would exceed the free storage-write allowance. Therefore this alternative also needs bounded mailboxes, deduplication, backoff, headroom and a failure state that keeps unsent data safely on the phone. A persistent socket is not a promise that Android will keep an app running or that mobile connectivity is always available. Do not advertise this estimate as a verified guarantee for 100 families.

The revised design reuses existing secure pairing data and parent signing identities, so current V43 pairings do not need a new invitation merely to change transport. Before release, test authorization, offline delivery, signed receipts, quota recovery and representative 100-family load, then verify the deployed service and both phones. If actual usage does not fit the free envelope, report that rather than enabling billing, dividing projects to bypass quotas, dropping captured data or hiding delivery delays.

Any managed relay can hold encrypted traffic and connection metadata. Update the existing ntfy-specific disclosures if transport changes. No family decryption key or privileged backend credential belongs in the cloud mailbox or APK.
