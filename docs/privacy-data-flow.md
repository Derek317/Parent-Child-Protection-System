# Family use and data flow

Code inspection date: 8 November 2026. This document describes the Cloudflare relay migration from the original 4.8 APK and the existing two-way capture behavior. It is not a full privacy notice, penetration test or legal opinion. The new capture scope requires updated phones, a signed parent request and local acknowledgment on the child device.

## Intended use

IG Guard is intended for authorized private parent–child protection. It contains no school administration console or developer plaintext-message dashboard. The developer-deployed relay does temporarily store encrypted undelivered packets and routing metadata. Choosing the parent role does not verify a person's identity or parental responsibility. The existing child pairing flow requires explicit confirmation and supports comparing a full security code on both phones.

## Where information goes

| Stage | Implementation | Who normally has access |
| --- | --- | --- |
| Capture and matching | The child app processes Instagram notifications and selected accessible screen text locally. | The child device and its authorized app process. |
| Monitoring scope | The original incoming-alert mode remains until expanded-scope acknowledgment. Full mode processes exposed incoming/outgoing message bubbles and notification entries, including ordinary trusted contacts. The DM typing box is excluded. The separate comment/story-reply path can still forward an unsent draft and is labelled accordingly. The app does not classify image/audio files. | Accepted captures are forwarded to the paired parent. Ordinary records are separated from actionable review events. This is not a complete Instagram transcript and does not guarantee background access to outgoing messages. |
| Local pending delivery | Serialized alerts are encrypted before durable frames enter the child outbox. | The child app holds queued ciphertext and the family secret. |
| Delivery | Authenticated WSS connections use a developer-deployed Cloudflare Worker and a private Durable Object room. Ordinary records/receipts forward directly while the receiver is connected; otherwise bounded encrypted mailboxes buffer delivery. Critical controls are buffered before acceptance. | The developer can administer ciphertext and routing metadata. Cloudflare processes these and connection information. Neither is supplied the family token, AES key, parent private signing key or password verifier. |
| Parent archive | The parent app decrypts alerts and stores captured text in app-private SQLite. | The parent app/user. SQLite contents are not separately encrypted by this app; Android's app isolation and device security remain important. |
| Other local state | Private preferences hold settings, pairing material and bounded checkpoint logs; logs can include handles and delivery status. | The relevant phone/app. No automatic diagnostic-upload service was found. |
| User-directed disclosure | A user can copy a full record, or copy/share a pairing invitation through Android facilities. | Clipboard/share recipients chosen by that user, outside the archive's access controls. Pairing invitations are secret. |

Encrypted traffic includes captured text, conversation/account/author labels, direction and uncertainty, available source message time, capture time, rule-match reasons/phrases, child account handle and trusted-contact settings where included. Other exchanges include heartbeat/permission state, expanded-capture policy/acknowledgment, configuration and authenticated parent controls. Encryption does not make these categories nonexistent, nor hide all network metadata.

The last detected child handle is a display/configuration cache, not proof of the active account for every capture. Only a validated recipient prefix in that notification establishes its account label. Unprefixed notifications and visible DMs without account evidence are labelled unknown and kept separate from known-account threads. Old identity caches inferred from conversation titles are invalidated once. Reply/comment draft checks require a labelled reply composer; DM typing boxes remain excluded even when message bodies resemble screen controls.

Available direct/group/unknown conversation type is retained so an explicitly identified group cannot merge with a same-named direct contact. Group approval and follow-up classification use the observed author rather than inheriting an individual's settings from a group title; a group title cannot flag that individual. These labels still do not verify Instagram identities. Delayed reply observations carry their original family session and are rejected if it changes before capture/enqueue. Bounded saved navigation state contains cursor IDs/timestamps/order, not a new copy of message text.

## Developer access and keys

The reviewed sources contain no developer-controlled content-upload endpoint, message-retrieval dashboard, analytics/crash-reporting SDK or developer key escrow. The encryption key is derived locally from a family token generated on a device and passed through the pairing invitation. Parent control-signing keys are also generated/stored on the parent device. The APK signing key authenticates app packages; it does not decrypt family messages.

These are source-code findings about normal operation. They do not establish that a compromised device, leaked pairing invitation or deliberately modified app cannot disclose information. Do not send real messages, pairing invitations/tokens or keys to public GitHub issues. Voluntarily submitting material to support would be a separate disclosure, not an existing automatic collection feature.

## Retention and deletion

- Parent evidence remains until explicit deletion. App-data clearing/uninstallation also removes local app data. Old-family archives remain isolated and have an explicit removal control.
- Deleting archived text retains content-free replay tombstones and separate chart statistics. It does not request remote relay-cache deletion or erase clipboard/export copies.
- Securely paired updated child apps retain queued ciphertext until a valid parent-device receipt, or until the applicable pending work is discarded on family replacement/app-data removal. The new backend requires the pinned parent public key; legacy pairings need the normal adult-authorized invitation upgrade.
- The parent can retain incomplete received-message assemblies until completion or archive deletion, subject to the documented assembly bounds. Their fragment data has already passed transport decryption; app-private storage is not a claim of separate database encryption.
- Content-free child capture identities and bounded hashed screen windows suppress known re-observations without persisting a second plaintext conversation archive on the child device. Source identities can be incomplete; uncertain duplicates/attribution remain labelled rather than claiming a certified unique transcript.
- The manifest/data-extraction rules disable app cloud backup and device transfer. This is not a guarantee against all manual copies, OS/vendor behavior or device compromise.
- Each cloud mailbox is bounded to 512 encrypted packets per direction, reserving headroom for controls/urgent traffic. Cloud copies expire after seven days, with scheduled and connection-time cleanup; duplicates do not extend the stored copy’s expiry. A still-unconfirmed phone-side record may be submitted again after expiry. Local archive deletion does not remotely erase every cloud copy, provider backup or export.
- The server stores the parent public key, family scope, a child-capability hash and a bounded nonce-replay cache. Parent proof headers include signatures; child authorization uses a domain-separated capability, not the decryption token. No global privileged API credential is shipped in the APK. Backend code does not log message bodies or authorization, and Worker observability logging is disabled in configuration. Provider security/access logging, hosting regions and backups were not independently verified.
- The original 4.8 APK used ntfy polling/streaming. Migration does not erase any old relay cache. Existing encrypted phone queues and parent evidence are preserved; mixed old/new transport builds cannot communicate.

## Code references

- [Encryption and family key derivation](../MainActivity.kt#L291); [local family-token generation](../MainActivity.kt#L639).
- [Parent control-signing identity](../MainActivity.kt#L425); [pairing invitation containing the family token](../MainActivity.kt#L712).
- [Alert fields and encrypted outbox](../MainActivity.kt#L914); [authenticated WebSocket client](../CloudRelay.kt); [routing and capability derivation](../FamilyRelay.kt); [encrypted backend mailboxes](../relay/src/worker.js).
- [Local archive/outbox schema](../FamilyEvidence.kt#L21); [parent archive insertion/deletion](../MainActivity.kt#L1341).
- [Typed reply forwarding callback](../MainActivity.kt#L1586); [comment/story-reply capture and outgoing-DM exclusion](../MainActivity.kt#L1760).
- [Pairing confirmation](../MainActivity.kt#L2267); [conditional monitoring notification](../MainActivity.kt#L609).
- [Manifest and build dependencies](../.github/workflows/build-apk.yml); [backup/transfer exclusions](../ui-res/xml/private_data_rules.xml).

The monitoring notification is attempted only where Android notification permission permits it. The expanded mode also presents a scope explanation and requires a local action. Neither technical acknowledgment nor Android permission is proof of legal consent/authority in every circumstance. The earlier [legal assessment](hong-kong-privacy-assessment.md) discussed the narrower baseline; broader two-way/trusted-contact capture needs its own purpose/necessity assessment where applicable. No legal certification is implied.
