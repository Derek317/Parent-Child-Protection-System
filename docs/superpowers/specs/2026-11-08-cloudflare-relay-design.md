# Cloudflare relay migration for IG Guard 4.8

Status: **approved by the user for implementation, GitHub publication and replacement of the 4.8 APK**. The backend and Android integration are implemented; final verification, deployed-endpoint checks and signed release publication are tracked in the [migration report](../../relay-migration-report.md). The configured endpoint is `https://parent-child-protection-system.dere2005317.workers.dev`. This design is not a guarantee of unlimited capacity or bug-free operation.

Builder check: an independent Android/backend reviewer read this design and the connected implementation on 2026-11-08 and reported **“nothing important left”** to clarify before planning. This checks design readiness, not correctness of code that has not been written. User approval was subsequently supplied in the conversation.

## Purpose and boundaries

Repair child-to-parent delivery rejected by ntfy with HTTP 429. Both phones install only IG Guard. The developer may use a managed free backend; neither families nor the developer operate a VPS. Stay on Workers Free with SQLite-backed Durable Objects. Do not enable a paid plan or add Firebase, FCM, Analytics, another consumer app or a privileged credential to the APK.

Preserve the existing capture modes, child confirmation, parent control signatures, encrypted local outbox, full parent archive, conversation grouping, chronological ordering, review queue, charts, heartbeat schedule, notifications and remote controls. This is a transport change and the directly connected reliability/status work, not a UI redesign or a capture rewrite.

Keep version name 4.8, use a higher version code, retain the existing signing identity and update the existing 4.8 release only after verification. Preserve the original 4.8 source tag and identify the hotfix source separately. A deployment, build or phone-level limitation must be reported, not labelled a successful end-to-end test.

## Approaches and selection

- **Selected by the user: Cloudflare Workers Free + SQLite-backed Durable Objects + hibernating WebSockets.** One private room per family, integrated bidirectional delivery and temporary encrypted offline mailboxes. Requires a free developer account and one backend deployment.
- **Firebase Spark Realtime Database.** Native SDKs would be simple, but about 200 connected phones exceed its commonly published 100-connection allowance. The previous draft is on hold.
- **Firestore Spark / ntfy-only retry repairs.** One Firestore write per captured record does not fit the planning volume of 50,000 records/day against its commonly published 20,000 free writes/day. Batching changes the result only for compatible arrival patterns and may introduce delay. Pacing ntfy would improve recovery without establishing sufficient daily capacity.

The existing assessment records sources, assumptions and limits: [relay capacity assessment](../../relay-capacity-assessment.md).

## Architecture and data flow

1. Existing Android capture and consent checks produce the same records as today.
2. The child durably stores encrypted frames in its existing SQLite outbox before attempting delivery.
3. A WSS connection authenticates the phone to its family relay room. The backend receives ciphertext and routing/delivery metadata only.
4. If the receiving phone is connected, ordinary data and receipts are forwarded immediately. If it is disconnected, accepted packets enter a bounded durable encrypted mailbox. Critical control commands always enter that mailbox before acceptance, even when the receiver appears connected.
5. The receiving phone decrypts and validates the packet, then commits the appropriate local evidence, fragment or control state. Its transport acknowledgment allows the backend to delete a buffered packet.
6. The parent separately produces the existing ECDSA-signed application receipt. Only that valid receipt clears a securely paired child's local evidence outbox. A relay acknowledgment, connected socket or cloud deletion is never proof that the parent received or read an alert.

There are therefore three distinct states: encrypted record kept on the phone; relay accepted/forwarded/buffered it; parent phone confirmed it. Parent reading remains a separate human action.

## Family identity and authorization

Reuse the existing parent P-256 signing identity and the child's pinned public key. Reuse the existing family token only on the phones. Secure V43 invitations already contain these values; no new invitation format, cloud account per family or new connection code is needed for those pairings.

Use a protocol-versioned room ID derived from the canonical parent public-key bytes and the existing family scope. The backend recomputes and validates this binding. Learning a room ID or public key does not authorize reading it or creating its owner state. A different key cannot register the same derived room.

Parent connection authorization uses a separately domain-separated signed request containing protocol version, role, room, scope, timestamp, random nonce and the child's capability hash. Require a valid parent signature, a short timestamp window and a one-time nonce. Persist the bounded recent-nonce set with the room's owner metadata; a backend restart must not make a still-valid parent request replayable. Encoding and ECDSA DER/P1363 conversion must be specified with shared Android/backend test vectors.

Nonce checking and insertion must be atomic after asynchronous signature verification. Concurrent identical requests must not both succeed. Clean expired nonces without evicting a still-valid one merely to admit another connection; reject admission if the bounded replay cache is full.

Derive a distinct high-entropy child relay capability on the phones using HMAC-SHA-256 with the family token and a fixed relay-specific domain. The parent registers only SHA-256(capability), signed with its owner request. The child supplies the capability in authorization over TLS; compare its hash with the registered value. The capability is not the token, AES key, password verifier or a credential for parent authority. Never put it in a URL, diagnostic, release log or repository. Family secrets must retain their existing secure random generation; do not introduce shorter human-chosen tokens.

Authenticate during the WebSocket upgrade, before accepting an application socket or disclosing mailbox contents. Use bounded authorization headers and frames. Parent-authenticated sockets may receive child data and publish parent controls/receipts; child-authenticated sockets may receive parent packets and publish child records/acknowledgments. Neither role may read, delete or publish into another room or impersonate the other role. Parent signatures on commands are still checked independently on the child after decryption.

Only a valid parent request may bootstrap a room. If the child connects first, show that the updated parent must connect once, then retry with backoff. Never let the first unauthenticated writer claim the room. Existing legacy invitations without a parent public key require the normal adult-authorized pairing upgrade; preserve their archives and queued ciphertext and explain the requirement instead of bypassing the child lock.

The backend uses platform TLS and standard Web Crypto; no decryption or global application secret is needed there. Avoid accounts, JWT infrastructure and service-account keys when the existing parent signing identity supplies the required authority.

## Wire protocol and encrypted mailboxes

Use a versioned HTTPS/WSS endpoint and small JSON messages. The app endpoint is developer build configuration, not a URL supplied by a received message. Production accepts WSS with a validated host, no URL user-info or arbitrary redirects. Debug-only tests may use loopback; release builds must reject missing configuration and test endpoints.

Each publish includes a stable local frame/command ID, an encrypted body, and a bounded class: ordinary data, receipt, or control. The server derives direction from the authenticated socket, not a client-supplied destination. Packet bodies remain below the existing 4 KiB encrypted-frame bound; set a separate small bound for the complete protocol envelope. Validate lengths, identifiers, permitted fields and classes before database work. Class labels confer no authority to bypass receiver validation.

Use stable storage keys per room, direction and local ID. Repeating the same pending ciphertext must not create another mailbox row. Reject a conflicting body for the same stored ID rather than silently overwriting it. Assign a fresh delivery ID for each forward/replay attempt, while retaining the stable storage key for acknowledgment. This preserves receipt recovery: the existing parent replay guard must not suppress regeneration of a lost signed receipt forever.

Parent controls such as rotation, reset, capture policy, trusted-list sync and temporary unlock must be durably buffered before a successful publish callback. Otherwise a lost direct-forward packet followed by a parent key rotation could strand the child. Receipts and ordinary records may use direct forwarding because their source evidence remains queued until the signed receipt; a missing receipt can be regenerated after duplicate delivery. Child configuration acknowledgments and identity sync need durable or explicitly regenerated delivery as well.

For a buffered packet, acknowledge cloud consumption only after the receiver has durably stored the required evidence/partial fragment or committed valid control state. Refactor the receiving boundary to distinguish consumed, permanently rejected and retryable local failure; a caught storage exception or an early return is not successful storage. Rejected ciphertext may be removed as rejected but must never produce a successful application receipt. Deleted evidence retains its tombstone and returns the existing signed discarded receipt without resurrecting content.

Transport deletion is restricted to the authenticated receiving direction and a packet that it can consume. It does not acknowledge the source evidence event. A crash between local commit and cloud deletion must yield a safe duplicate on reconnect; a crash before local commit must leave the cloud packet intact.

## Bounds, offline behavior and free-plan budget

Start with a bounded mailbox per room and direction, bounded packet lifetime and reserved headroom for control/urgent traffic. Proposed initial bounds are 512 encrypted packets per direction and seven days of cloud retention; shorter signed-command lifetimes remain enforced by the app. Expired packets are cleaned by a bounded scheduled cleanup path and ordinary authenticated activity. Test the actual alarm lifecycle; do not promise TTL cleanup without implementing and verifying it.

Deduplication must not continually extend a buffered copy's original expiry. A record still retained on the source phone can be resubmitted after a cloud copy expires; document that possibility rather than promising a seven-day lifetime for all copies of a message. Backend deletion is logical queue removal, not a promise about provider backups or physical media erasure.

These cloud bounds do not limit the local archive or silently delete unconfirmed child evidence. Refuse excess cloud admission with a retryable queue-full status; retain the local encrypted record. A partial long message may occupy more frames than the mailbox bound. The parent can durably store and consume individual fragments, freeing cloud space for the rest, while the child waits for the final signed event receipt. Do not cap all local in-flight fragments on receipt count or starve unaccepted remaining fragments behind retries of the accepted prefix.

Use direct forwarding for online ordinary traffic to avoid a storage write and deletion for every record. This preserves eventual delivery through the child's durable outbox if a socket fails immediately after forwarding. A successful direct send means forwarded, not durably buffered or parent-confirmed. Critical controls follow the durable path described above.

The 100-family planning envelope assumes 500 captured records/family/day. With one frame per record and one signed receipt, plus the existing random 30–95-minute heartbeats and their receipts, that is approximately 104,600 incoming frames/day. The documented 20:1 Durable Object WebSocket request ratio represents about 5,230 compute requests before connection/setup/cleanup traffic. It does not eliminate CPU-duration or SQLite limits.

Measure index maintenance and metadata/nonce/cleanup writes in addition to packet insertions and deletions. The simple packet arithmetic below is not the complete SQL operation bill.

Documented free allowances include 100,000 Worker requests/day, 100,000 Durable Object compute requests/day, 13,000 GB-seconds/day, 100,000 SQLite rows written/day including deletes/alarms, five million rows read/day and 5 GB stored. They are shared by the account. With 20% of the illustrative frames buffered and later deleted, about 41,840 row writes are used before metadata and other work; if all frames need buffering and deletion, about 209,200 writes exceed the allowance. Fragmentation and retries increase usage. The 20% assumption is not measured user behavior.

Use the hibernation WebSocket API with automatic ping responses. Never keep rooms awake with perpetual timers or ordinary WebSocket accept handlers. Bound socket counts, replay batches, memory, validation work and per-room traffic; authenticate before access and avoid logging packet bodies or authorization. A public backend still has shared free quotas: load testing and operational usage monitoring are release requirements, not a guarantee against abuse or provider outages.

On backend limits, queue-full, network loss or storage failure, use bounded exponential backoff with jitter. Honor a server retry time and persist provider cooldown so app restart/resume/manual test cannot reset it. Daily-exhaustion cooldown must not be treated as a one-second reconnect loop. If offline usage persistently exceeds the free envelope, report the limit and retain data; do not enable billing, split projects to evade quotas, drop records or conceal delays.

## Android lifecycle and retry changes

Use OkHttp's supported Android WebSocket client; Android does not provide the required portable `java.net.http.WebSocket` API at the app's minimum API. Add no generic transport factory or parallel Firebase implementation. Keep the relay code in a focused file and reuse the existing serialization/encryption and evidence store.

Maintain one active socket per app role/family session. Close it and fail obsolete callbacks when role, family token, parent key, configuration or relay generation changes. Capture those values before network work; never hold the evidence/state lock during I/O. Reconnect with jitter and restore receive/replay state. Coalesce existing poll requests into connect/flush requests rather than retaining ntfy polling alongside WebSockets. Stop old service work after role changes.

Preserve accepted-frame retry backoff when the UI resumes or the manual test is pressed. Prioritize new urgent records without repeatedly re-uploading the accepted prefix of a long event. Use bounded publish concurrency/flow control, and distinguish newly accepted, buffered and already awaiting a receipt. New captures must not be blocked by one old missing receipt. A peer-online event/reconnect can request mailbox replay without blindly republishing the entire local outbox.

Persist important parent control attempts before reporting success; retain the existing pending-rotation recovery. For key rotation, publish durably into the old room, derive/register the new room with the new family scope, and preserve the existing archive/outbox re-encryption transaction. The child commits the new token and may send the final consumption acknowledgment on the captured old socket before it closes. Old-room acknowledgments cannot clear new-scope evidence. Explicitly test rotation retries after either process restarts and reset during receipt processing.

Keep Android's existing visible services, notification permissions and battery guidance. A WebSocket does not bypass Doze, a force-stop or Samsung background restrictions. Keep the heartbeat schedule and missing-heartbeat behavior; do not claim real-time delivery while Android has stopped the app.

## Migration, configuration and privacy

For current securely paired 4.8 phones: update both APKs; open the parent app once to register its room, then reconnect the child. Keep token, safety code, pinned key, password verifier, capture policy/child confirmation, archive and pending evidence. Derive routing from those existing values; never invoke ordinary re-pairing just to change transport. An old APK still talks to ntfy, so a mixed-version pair will not share this new relay; show update-both instructions.

Provide a small backend project, pinned deployment tooling, a free-plan deployment guide and no credentials in tracked files. The developer creates a free Cloudflare account, deploys the reviewed worker and supplies the resulting public `workers.dev` URL through repository/build configuration. Cloudflare login/API credentials belong only in the operator's login or a narrowly scoped deployment secret, never chat, pairing codes or the APK. The public relay URL itself is safe to share.

Update the existing privacy/data-flow documents to say that a developer-deployed Cloudflare relay processes ciphertext and connection metadata and temporarily stores undelivered encrypted packets. The operator has backend administration access to ciphertext/metadata but is not supplied the family decryption key. The local parent archive remains until the parent deletes it. Cloud queue retention and local deletion are different; do not claim deletion from one erases the other. Disable body/auth logging and disclose what operational metadata is retained.

## Verification and release gates

Run tests against the actual Worker/Durable Object runtime locally, not just a mocked mailbox class. Required checks include parent ownership/bootstrap, wrong key/capability, replayed/stale authorization after hibernation/restart, cross-room access/deletion, role restrictions, bounded malformed inputs and Android/Web Crypto signature vectors.

Exercise two independent clients for heartbeat, parent receipt, incoming/outgoing captured records, duplicate/lost receipt, replay after local commit, storage failure before commit, child-first connection, receiver offline, reconnect/restart, long messages exceeding mailbox capacity, queue-full, provider throttling/cooldown, key rotation/reset, stale socket callbacks and preserved existing pairing/consent/archive/outbox.

Run a representative 100-family workload with online and offline intervals, fragmented messages, reconnects and controls. Measure actual compute duration, storage operations, row count, memory, latency, retries and queued records; report the workload and assumptions. Local runtime tests can establish protocol behavior but cannot certify Cloudflare account quotas or Samsung behavior. Verify the deployed endpoint and two devices before publishing a replacement APK; report any unavailable check explicitly.

Complete Android unit/regression tests, debug/release assembly, affected lint and workflow/script validation. Test status UI for configuration missing, room not registered, relay connected, queued/backed off and parent-confirmed states. A missing endpoint must fail release configuration instead of silently falling back to ntfy or shipping test data.

Use the existing signing secrets without extracting them. Verify the workflow-produced APK's package/version code and certificate continuity, download it and record its checksum. Update the existing 4.8 release with accurate changes, setup/migration instructions, test results and limits. Do not force-move the original source tag or replace its APK with an unconfigured or unverified build.
