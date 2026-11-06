# Family use and data flow

Code inspection date: 6 November 2026, Hong Kong time. This describes the implementation based on commit `a5b87c6`; it is not a full privacy notice, penetration test or legal opinion. No app behavior is changed by this document.

## Intended use

IG Guard is intended for authorized private parent–child protection. It contains no school administration console, developer-operated family-message database or developer message-viewing dashboard. Choosing the parent role does not verify a person's identity or parental responsibility. The existing child pairing flow requires explicit confirmation and supports comparing a full security code on both phones.

## Where information goes

| Stage | Implementation | Who normally has access |
| --- | --- | --- |
| Capture and matching | The child app processes Instagram notifications and selected accessible screen text locally. | The child device and its authorized app process. |
| Monitoring scope | Incoming DMs are checked under the trusted-contact and keyword rules. Outgoing DMs are excluded from alerts. The separate comment/story-reply path forwards distinct typed text of at least two trimmed characters after its debounce/flush, including ordinary text; it can forward a draft before Instagram submission. The app does not classify image/audio files. | Relevant captured alerts are forwarded to the paired parent. This is not a complete transcript of Instagram, nor an incoming-text-only monitoring policy. |
| Local pending delivery | Serialized alerts are encrypted before durable frames enter the child outbox. | The child app holds queued ciphertext and the family secret. |
| Delivery | HTTPS requests send application-encrypted bodies to `ntfy.sh`. Polling/streaming requests retrieve available relay messages. | The independent relay processes ciphertext, topic identifiers and connection information. The app does not send it the family token/decryption key. |
| Parent archive | The parent app decrypts alerts and stores captured text in app-private SQLite. | The parent app/user. SQLite contents are not separately encrypted by this app; Android's app isolation and device security remain important. |
| Other local state | Private preferences hold settings, pairing material and bounded checkpoint logs; logs can include handles and delivery status. | The relevant phone/app. No automatic diagnostic-upload service was found. |
| User-directed disclosure | A user can copy a full record, or copy/share a pairing invitation through Android facilities. | Clipboard/share recipients chosen by that user, outside the archive's access controls. Pairing invitations are secret. |

Encrypted traffic includes alert text, sender/account labels, reason, time, capture source, child account handle and trusted-contact settings where included in the payload. Other exchanges include heartbeat/permission state, configuration and authenticated parent controls. Encryption does not make these categories nonexistent, nor hide all network metadata.

## Developer access and keys

The reviewed sources contain no developer-controlled content-upload endpoint, message-retrieval dashboard, analytics/crash-reporting SDK or developer key escrow. The encryption key is derived locally from a family token generated on a device and passed through the pairing invitation. Parent control-signing keys are also generated/stored on the parent device. The APK signing key authenticates app packages; it does not decrypt family messages.

These are source-code findings about normal operation. They do not establish that a compromised device, leaked pairing invitation or deliberately modified app cannot disclose information. Do not send real messages, pairing invitations/tokens or keys to public GitHub issues. Voluntarily submitting material to support would be a separate disclosure, not an existing automatic collection feature.

## Retention and deletion

- Parent evidence remains until explicit deletion. App-data clearing/uninstallation also removes local app data. Old-family archives remain isolated and have an explicit removal control.
- Deleting archived text retains content-free replay tombstones and separate chart statistics. It does not request remote relay-cache deletion or erase clipboard/export copies.
- Securely paired updated child apps retain queued ciphertext until a valid parent-device receipt, or until the applicable pending work is discarded on family replacement/app-data removal. Legacy pairings use relay acceptance instead of signed parent receipts.
- The parent can retain incomplete received-message assemblies until completion or archive deletion, subject to the documented assembly bounds. Their fragment data has already passed transport decryption; app-private storage is not a claim of separate database encryption.
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

The monitoring notification is attempted only where Android notification permission permits it. It is not proof that a child saw or understood a complete privacy notice. See the [legal assessment](hong-kong-privacy-assessment.md) for outstanding disclosure/authority questions.
