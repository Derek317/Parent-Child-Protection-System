# Free Firebase relay migration

Status: **on hold — the persistent Realtime Database approach below does not meet the newly clarified scale.** The user accepts a free managed backend and expects around 100 families (approximately 200 phones). Spark's commonly published 100-connection allowance would be another bottleneck. See the updated [capacity assessment](../../relay-capacity-assessment.md) for the Firestore write budget and a proposed Cloudflare alternative. Do not implement or release this draft as if the scale check had passed. No production code has been changed.

Firebase Spark and a new developer-owned project were accepted before the audience size was known. That acceptance does not approve another provider, billing, slower alert delivery or reduced capture. The original proposal is retained below so its security and migration requirements are not lost while the transport choice is revised.

## Goal and constraints

Replace the failing ntfy transport with a managed Firebase Realtime Database relay. Users install only IG Guard, do not create Firebase accounts, and do not operate servers. Keep the project on the no-billing Spark plan: no Cloud Functions, paid plan, FCM sending service or privileged credentials in the APK.

Preserve message capture, local parent archive, timestamps and observed ordering, review categories, signed parent commands, explicit child capture consent and authenticated delivery receipts. Keep the current app version name 4.8 and signing identity; the repaired build must have a higher version code and update the existing 4.8 release after verification.

## Why the current transport fails

The supplied child log confirms a rejected publish: HTTP 429. The code discards the relay's detailed error and shows a generic failed-delivery result. Ordinary messages, fragments, receipts and retries consume external capacity. Forced retries can republish relay-accepted records while their parent receipt is still missing. The transport block itself is unchanged from 4.7, so the HTTP status does not establish a pairing/encryption regression or identify which hosted quota was exhausted. See [capacity assessment](../../relay-capacity-assessment.md).

## Approaches

- **Selected: Realtime Database + Firebase Authentication.** Native Android SDKs provide authenticated real-time transport and token refresh without an application server. Use anonymous device authentication, private per-family rules and encrypted mailboxes.
- **FCM plus a trusted sender.** Requires a trusted sending component and is not a complete client-to-client queue. Excluded for this migration.
- **Public test brokers or bot/webhook APIs.** Do not provide the desired combination of integrated consumer setup, private bidirectional delivery and a suitable production operating model. Repairing ntfy retries alone would not remove its capacity constraint.

The published free-tier figures must be checked in the new project's console. Common Spark allowances include 100 simultaneous database connections, 1 GB stored and 10 GB downloaded monthly, shared by the entire project. One parent and one child with persistent connections imply about 50 concurrent families, not unlimited public deployment. SDK connection multiplexing, payload size and reconnect traffic affect the actual budget.

## Client and cloud boundaries

Use the Firebase Android Authentication and Realtime Database SDKs, without Analytics. Initialize from the Android client configuration for package `app.family.igguard`; the client API configuration is not a service-account credential. Retain the existing encrypted SQLite outbox and parent evidence store. Treat Firebase commit acknowledgment, parent-device receipt and parent reading as separate states.

Only encrypted frame bodies and minimal routing/delivery metadata go to the database. Never upload the family token, AES key, parent private signing key, password or verifier. Firebase's operator can access ciphertext and metadata, so update the existing ntfy-specific disclosures and central-storage descriptions.

Anonymous Firebase identities are device identities, not substitutes for parent authority. Parent control messages continue to require the pinned parent ECDSA signature. Do not share one Firebase Authentication account/password between parent and child, and do not use public `.read`/`.write` rules.

## Family authorization and invitations

Namespace each family under its authenticated parent UID and a hash of its existing family token. Only the authenticated parent may create/manage that namespace. A child joins using a separate, domain-separated one-way capability derived from the invitation secret; the cloud capability must not expose or serve as the AES key.

The owner UID must be supplied in an authenticated connection descriptor, rather than inferred from a first-writer-wins public directory. Extend the invitation with a parent-signed Firebase owner/project binding. Validate it against the invitation's parent key; for an existing paired child, require the same family token, password verifier and already-pinned parent key. Reject stale/rollback bindings and descriptors for a different configured project.

An existing locked child may explicitly accept a valid same-family transport update without unlocking or resetting the family. This operation only updates routing and invalidates old relay sessions; it must not change role, family encryption token, password, parent authority, capture policy, local consent, archive or queued messages. A genuinely different pairing retains the existing parent-verification requirement. Both phones need the repaired build and an updated parent invitation/connection descriptor.

## Mailboxes and rules

Conceptual structure:

```text
families/{parentUid}/{familyRoom}/
  metadata/                         parent-owned join capability and routing version
  members/{anonymousChildUid}/       validated invitation capability
  to_parent/{stableFrameKey}/        encrypted child frames
  to_child/{stableFrameKey}/         encrypted parent controls/receipts
```

Rules must deny access to unrelated authenticated users and cross-family readers/writers. Restrict parent-command publishing to the owner, child publishing to admitted members, and inbox deletion to its authorized receiver/owner. Validate packet shape, size, sender identity and a bounded mailbox size. No path grants a public listing of families or membership capabilities.

Use stable storage keys derived from local frame IDs so retries replace the same pending cloud packet. Each actual publish carries a fresh delivery ID, preserving replay/receipt recovery: a repeated upload must be able to trigger another signed receipt without inserting duplicate evidence.

Remove a received cloud frame only after its processing attempt has completed and any required durable local fragment/evidence storage has succeeded. Failed local storage must leave the cloud frame available. Invalid/authentication-failed frames produce no successful receipt. Signed parent receipts remain the authority for deleting the child's local encrypted outbox; cloud deletion alone never proves delivery.

Remote mailbox bounds must not deadlock long fragmented messages. The receiver may remove durably stored fragments before the whole message is assembled; remaining sender-side fragments must still be eligible for upload. Never block all remaining fragments merely because a fixed number of earlier fragments await an event-level receipt.

## Recovery, load and status

Retain captured records until valid receipt, family replacement or explicit data removal according to existing policy. Do not silently drop context to save bandwidth. Recheck pending receipts before replaying relay-accepted frames, and do not reset accepted-frame backoff on every resume/manual retry. Apply bounded backoff to failures and clear, separate status for configuration, authentication, connection, relay commit and parent receipt.

Use native incremental listeners rather than repeatedly downloading the complete mailbox/history. Reconnect and token refresh must preserve session/family guards. A family/role/routing change must detach old listeners and stop old scheduled work. SDK-owned pending writes must not later send new-family data to an old route.

Cloud storage is a temporary delivery queue, not the full archive. Bound queued packet size/count, delete successfully processed records, and document what happens while either phone is offline or project quota is exhausted. No promise of automatic server-side TTL cleanup is made on Spark; cleanup must actually be implemented by authorized clients. Offline encrypted records stay in the local outbox when a remote queue is full.

## Configuration and verification gates

Required user setup: create Spark project, register `app.family.igguard`, enable anonymous authentication, create Realtime Database in locked mode and provide `google-services.json`. Keep it locked until the reviewed rules are deployed. Do not request a service-account key or billing account.

A release build must fail clearly when valid production client configuration is missing. Test-only configuration and emulator endpoints must never become the release relay. Do not fall back silently to ntfy.

Verification must cover:

- two independent device identities, successful pairing, heartbeat, incoming/outgoing records and signed receipt recovery;
- denied cross-family access, wrong owner/project, altered or replayed migration bindings and attempted child parent-command writes;
- database-rule behavior against Firebase's emulator or an isolated test namespace in the actual configured project, not only a mock rules evaluator;
- offline/restart/reconnect, transient quota errors, accepted-frame retry control, duplicate cloud deliveries, partial fragments and mailbox backpressure;
- preserved SQLite archive/outbox and capture consent during same-family migration;
- the complete Android/build/lint/workflow suite, affected native status UI, and the downloaded signed APK's higher version code/certificate continuity.

Live Firebase configuration/rules verification is required before replacing the existing 4.8 download. Preserve the original 4.8 source tag; record the hotfix source commit explicitly when updating its release assets.
