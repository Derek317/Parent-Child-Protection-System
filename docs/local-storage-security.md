# Local storage security — 4.9-beta.2

Updated 16 November 2026 (Hong Kong).

Preference strings and captured archive content are encrypted at rest using AES-256-GCM and keys generated in Android Keystore. These keys are separate from the shared family transport secret and from the developer's APK signing identity. No database decryption key is shipped in the APK or stored in ordinary preferences. Hardware backing depends on the device; this release does not claim that every device has hardware-backed storage.

## Protected data and remaining metadata

- Preference strings include the parent master password, encoded parent control-signing private key, family token, verifier/configuration state, pending key transitions and legacy message caches. Numeric flags and preference key names remain ordinary metadata. The existing parent signing identity is wrapped rather than replaced, so the paired child does not need to trust a new control-signing public key.
- Archive message bodies, original payloads, sender/conversation/account labels, fragment data and capture-window state are separately authenticated and encrypted. Purpose/record/family binding prevents ciphertext from being moved between different fields or family scopes without detection.
- Sender and conversation lookup columns use Keystore HMAC indexes. Timestamps, alert categories, directions, opaque record/family IDs and workflow flags remain clear metadata. This is field encryption in SQLite, not encryption of the complete database file.
- Keyword search decrypts candidate rows inside the app. It does not create a persistent plaintext search index. Search cost grows with the candidate archive; this is a deliberate privacy/performance tradeoff.

## Existing installations and recovery

Existing preference strings migrate in one committed preferences transaction. Schema versions 1–4 migrate transactionally into the encrypted archive schema. Message IDs, review/saved status, pending delivery, replay records and existing family identity are retained. SQLite secure deletion and post-commit compaction/checkpointing remove logical plaintext remnants from database pages and journals; a committed marker retries interrupted cleanup. This cannot guarantee forensic erasure from flash storage, provider backups or copies made before the update.

There is no plaintext fallback for an unreadable encrypted value. Missing/invalid keys or corrupt encrypted data must not be interpreted as an empty archive or new family. The UI shows a recovery screen and retains existing files. Unlock the phone and retry; if storage remains unavailable, preserve the app data for diagnosis. Clearing app data, uninstalling or losing Keystore keys can make existing encrypted data unrecoverable. App cloud backup and device transfer remain disabled.

Password inputs are excluded from saved activity drafts. Intentional copy/share/export actions still produce plaintext for the selected recipient, outside the archive's encryption controls.

## Limits

Encryption protects an extracted storage snapshot when its Keystore keys remain unavailable. A compromised running app or sufficiently privileged attacker operating through the app's identity can still access data during authorised decryption. It does not provide complete device-compromise protection or make imperfect Instagram capture reliable evidence. No absolute security guarantee is made.

Robolectric tests use a test-only Keystore provider running real JCA AES-GCM/HMAC operations. That provider is not compiled into the production APK and does not prove hardware isolation. Physical-device Keystore/update testing remains necessary. The normal release is signed through CI with the current trusted developer identity; historically exposed signing identities must remain retired.
