# Family use and data flow

Code inspection date: 8 November 2026. The preceding published baseline was app commit `a5b87c6`; this document also describes the 4.7 two-way capture update. It is not a full privacy notice, penetration test or legal opinion. The new capture scope requires updated phones, a signed parent request and local acknowledgment on the child device.

## Intended use

IG Guard is intended for authorized private parent–child protection. It contains no school administration console, developer-operated family-message database or developer message-viewing dashboard. Choosing the parent role does not verify a person's identity or parental responsibility. The existing child pairing flow requires explicit confirmation and supports comparing a full security code on both phones.

## Where information goes

| Stage | Implementation | Who normally has access |
| --- | --- | --- |
| Capture and matching | The child app processes Instagram notifications and selected accessible screen text locally. | The child device and its authorized app process. |
| Monitoring scope | The original incoming-alert mode remains until expanded-scope acknowledgment. Full mode processes exposed incoming/outgoing message bubbles and notification entries, including ordinary trusted contacts. The DM typing box is excluded. The separate comment/story-reply path can still forward an unsent draft and is labelled accordingly. The app does not classify image/audio files. | Accepted captures are forwarded to the paired parent. Ordinary records are separated from actionable review events. This is not a complete Instagram transcript and does not guarantee background access to outgoing messages. |
| Local pending delivery | Serialized alerts are encrypted before durable frames enter the child outbox. | The child app holds queued ciphertext and the family secret. |
| Delivery | HTTPS requests send application-encrypted bodies to `ntfy.sh`. Polling/streaming requests retrieve available relay messages. | The independent relay processes ciphertext, topic identifiers and connection information. The app does not send it the family token/decryption key. |
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
- Securely paired updated child apps retain queued ciphertext until a valid parent-device receipt, or until the applicable pending work is discarded on family replacement/app-data removal. Legacy pairings use relay acceptance instead of signed parent receipts.
- The parent can retain incomplete received-message assemblies until completion or archive deletion, subject to the documented assembly bounds. Their fragment data has already passed transport decryption; app-private storage is not a claim of separate database encryption.
- Content-free child capture identities and bounded hashed screen windows suppress known re-observations without persisting a second plaintext conversation archive on the child device. Source identities can be incomplete; uncertain duplicates/attribution remain labelled rather than claiming a certified unique transcript.
- The manifest/data-extraction rules disable app cloud backup and device transfer. This is not a guarantee against all manual copies, OS/vendor behavior or device compromise.
- The app requests available relay cache with `since=all`. The relay's current cache duration, access logs, hosting location, backups and deletion policy were not verified in this review. No fixed relay-retention promise is made.

## Code references

- [Encryption and family key derivation](../MainActivity.kt#L291); [local family-token generation](../MainActivity.kt#L639).
- [Parent control-signing identity](../MainActivity.kt#L425); [pairing invitation containing the family token](../MainActivity.kt#L712).
- [Alert fields and encrypted outbox](../MainActivity.kt#L914); [relay uploads](../MainActivity.kt#L1062); [cached relay retrieval](../MainActivity.kt#L1109).
- [Local archive/outbox schema](../FamilyEvidence.kt#L21); [parent archive insertion/deletion](../MainActivity.kt#L1341).
- [Typed reply forwarding callback](../MainActivity.kt#L1586); [comment/story-reply capture and outgoing-DM exclusion](../MainActivity.kt#L1760).
- [Pairing confirmation](../MainActivity.kt#L2267); [conditional monitoring notification](../MainActivity.kt#L609).
- [Manifest and build dependencies](../.github/workflows/build-apk.yml); [backup/transfer exclusions](../ui-res/xml/private_data_rules.xml).

The monitoring notification is attempted only where Android notification permission permits it. The expanded mode also presents a scope explanation and requires a local action. Neither technical acknowledgment nor Android permission is proof of legal consent/authority in every circumstance. The earlier [legal assessment](hong-kong-privacy-assessment.md) discussed the narrower baseline; broader two-way/trusted-contact capture needs its own purpose/necessity assessment where applicable. No legal certification is implied.
