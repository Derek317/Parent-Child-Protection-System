# Reliable delivery and a readable evidence archive

## Intent and authorization
The initial implementation was authorized as local-only, keeping outgoing-DM exclusion for child privacy and alerts for unapproved contacts. After reviewing the result, the user authorized pushing to GitHub with the existing private signing setup. Archive retention is explicitly **until the parent deletes it** (chat reply). Image/voice detection was asked as a feasibility question, not authorized as expanded automatic capture. Existing native Android Views, family pairing, security boundaries, TC copy, minimalist light/dark design and one APK remain.

## Approaches
1. Increase the JSON inbox cap: smallest patch, but still loses old evidence, loads an ever-growing list and does not solve relay payload limits.
2. Native SQLite evidence/archive and ciphertext outbox, contact-first paginated UI: chosen implementation default under the user's authorization. No new runtime dependencies. Separates storage from what is shown; enables indexed search and reliable retries.
3. Cloud evidence storage or automatic screenshot/audio capture: additional privacy, hosting and permission scope; outside this change.

## Evidence and transport
- Preserve full **captured** text, not a promise of full original Instagram conversations. Existing truncation/missed captures cannot be recovered.
- Parent SQLite archive scoped to the current family; retain until explicit deletion. A small bounded recent query remains available to existing overview/statistics callers. Migrate retained legacy JSON once and identify it as possibly truncated. Migrate before family replacement too; preserve unparseable legacy bytes as a labelled raw recovery record instead of inventing message fields or silently dropping them.
- Search contact/text, filter type and date, save important messages, group by sender and paginate 30 items at a time. No synthetic product data. Keep preview short; details selectable and complete. Clearing requires a destructive confirmation; statistics/replay state persist.
- Keep snapshots in app-private storage with Android backup disabled; expose neither a new cloud endpoint nor broad storage permissions.
- The public relay has bounded message bodies. Serialize and split oversized alerts into bounded encrypted frames, with stable event IDs, persistent out-of-order reassembly and duplicate handling and a full-payload SHA-256 digest. Retain content-free event tombstones across explicit deletion, so relay replays cannot resurrect deleted text. Commit completed assembly and archive insertion together; update statistics idempotently/recoverably after storage. Each frame stays below 4 KiB; bound fragment count and reject malformed frames. Bound reassembly to 1024 parts of 1024 bytes each, reject conflicting frames and expose incomplete assemblies. Fetch available relay cache on reconnect; relay acceptance is not proof of parent receipt and cache expiry remains a limitation. Surface failure rather than silently crop. No cryptographic/control protocol weakening.
- Persist encrypted alert frames before sending; delete after authenticated parent-device receipt for secure pairings; legacy pairings retain HTTP-only confirmation. Retain failures and retry with capped backoff from existing child service checks/reconnect/activity paths. Never hold the engine state lock over network I/O. Discard old-family pending work on family replacement; retain old evidence isolated from the new family until explicitly deleted. Re-scope evidence and partial assemblies on key rotation; re-encrypt pending frames under the new token, preserving stable IDs. An old-scope ACK cannot delete a replacement frame. Distinguish relay acceptance from confirmed parent receipt.

## Reliability and permissions
- Preserve the current no-OS-settings-interception policy and explicit Android permission guidance. Cannot silently grant access or guarantee Play Protect/banking acceptance.
- Surface permission health, pending delivery and network status. Keep monitoring visibly disclosed. Reconnect existing components; no hidden capture and no new restricted permission.
- Tighten bounds/identification only where tests demonstrate an actual capture failure; do not invent a replacement Instagram UI parser without device evidence.

## Help prompt
- Trigger a calm parent help sheet for a newly archived sexual/grooming-keyword alert while foregrounded; background uses a normal Android notification with a unique family-scoped event identity and generic private lock-screen content opening that alert/help screen, never a system overlay.
- Explain a keyword match is a signal, not proof. Encourage a calm conversation, preserve relevant records and contact an appropriate safeguarding professional/school social worker. Immediate danger in Hong Kong: 999. Do not invent hotline numbers or claim a particular service offers treatment/chat.
- Persist acknowledgment per event so refresh/recreation does not repeatedly interrupt. Reopen advice from any relevant alert. Group bursts into one prompt. Outgoing-DM and stranger alert policies are unchanged.

## Verification
Red/green tests: >60 retained alerts, full Unicode content, legacy migration, contact/search/type/date/saved pagination, family isolation/rotation/deletion, encrypted frame size, out-of-order/replayed reassembly, failure/success retry persistence and stable IDs, no outgoing-DM regression, unknown benign message still alerts, help on eligible events and state restoration. Full existing Android suite, build, lint, TC/light/dark UI captures. Physical Samsung/HSBC/Instagram tests remain required; do not claim these are passed.

## Media feasibility (answer, not implementation)
Pretrained image classifiers/OCR and speech-to-text avoid training a foundation model. Current text hooks do not provide underlying image/audio files. Manual, consented media selection is a medium-complexity future route; automatic Instagram capture is high-complexity, device-dependent and privacy-sensitive. Local Cantonese evaluation data is still required; image classification/transcription alone does not identify grooming.

## Delivery receipt refinement
Relay ACK alone can outlive cache retention without parent receipt. Add a narrowly validated, family-scoped ECDSA-signed `alert_receipt` control. Updated secure pairings retain accepted ciphertext and re-post with capped backoff until the parent archives it and confirms. Out-of-order receipts are independent, unlike ordered configuration commands. Preserve receipt state through key rotation; reject unsigned, wrong-scope and malformed receipts. Both phones need this update; legacy pairing is explicitly relay-only.

## Review refinements
Receipt identifiers use exact equality, not a prefix or SQL pattern. Transport-failure backoff can defer a new upload; receipt waiting cannot. First-attempt completion callbacks wait for the actual attempt/defer/cancellation, including when another worker is active. Archive deletion persists initialized chart totals first and retains content-free replay tombstones. Key rotation writes an old/new-token journal before the SQLite transaction and recovers it before token-dependent work after a restart, covering both sides of the database/preferences boundary. Old-family archives remain isolated and have an explicit removal control.
