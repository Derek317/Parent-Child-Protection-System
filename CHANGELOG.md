# Changelog

## Unreleased — scores-only family mode (19 November 2026, Hong Kong) — source work on branch `app/local-risk-scoring`, not released

No app version is changed by this entry. Nothing below has run on a physical phone, and the model was tested only on invented chats.

- Add an optional scores-only family mode. The parent switches it on only after the child's phone has reported a valid model file; the child sees a plain-language page and must agree once. Until the child agrees, the earlier behaviour continues unchanged. The child can pause (stops storing and scoring at once) or withdraw (deletes the local store and unsent scores). Turning the mode on again needs agreement again.
- While the mode is on, the child's phone keeps the latest 60 messages per one-to-one thread for up to 14 days in an encrypted local store, scores each thread on its latest 30 messages while charging and idle (charging only after 48 hours without a run), and sends the parent only an account label, a 0 to 100 score or "needs review", and times. Message text is no longer sent to the parent in this mode. Chats read from the screen (kind unknown) are analysed too. A chat known to be a group, or one that shows a second other person in a notification, is set aside: what was kept is deleted and it is only counted. The screen records no author for incoming messages, so a group chat opened on the phone may be analysed, and the consent wording says so; the consent wording changed, so a child who agreed earlier is asked again; until they agree again nothing is stored or analysed, no message text is sent, alerts stay without text, and the screens say so.
- Keyword and unapproved-contact alerts keep working but carry only the category, the account label and the time, marked as sent without text. After a withdrawal the phone does not go back to forwarding text; only the parent switching the mode off does, and the child is told when that happens.
- Importing or removing the analysis model on the child's phone needs the parent's password.
- Add the on-phone scoring engine (llama.cpp, CPU, arm64-v8a) and a hand-imported model file that is refused unless its size and SHA-256 match exactly. Phones with under about 8 GB of memory, or where the library cannot load, report the mode as unavailable.
- Add parent and child screens: a score page with states for waiting, paused, withdrawn, out of date, model missing or wrong, and scoring; a child status block that is always visible, and a permanent notification line while analysis is on, paused or waiting.
- The Terms of Service, privacy policy and data-flow drafts for this mode are drafts awaiting review by Derek and a Hong Kong privacy lawyer; the live documents are unchanged. The wording makes no claim of Meta approval or legal clearance.
- Not verified: speed, memory, heat and battery on a real phone, how often idle-and-charging windows occur, importing a 1.1 GB file through the system picker, and screen-reader behaviour on real hardware.

## 4.9-beta.4 — 18 November 2026 (Hong Kong) — source draft

- Add native Android biometric/device-credential gating for parent UI and sensitive actions, relock on background, secure-window flags and generic private notifications. Parent background receipt, archiving and signed acknowledgments continue while the UI is locked. Explicit app copies are marked sensitive and get best-effort cleanup after 60 seconds if still current.
- Move production parent signing to non-exportable Android Keystore identities. Verified legacy import preserves the public key already trusted by the child and removes the encoded private-key preference afterward; unavailable keys do not silently replace established trust. Add separate fresh, locally verified compromise re-pairing with authenticated old-room purge or an explicitly confirmed offline path without purge assurance; routine old-channel rotation remains separate.
- Add persistent child capture/transfer withdrawal with hold/discard choices and separate outgoing/trusted-context choices. Explicit resume discards old pending content and restarts incoming alerts; expansion needs renewed child acknowledgment. Saving scope choices discards old pending content, and widening scope clears acknowledgment. Composer drafts remain excluded.
- Assign new archives a default 30-day expiry and pending outboxes/incomplete assemblies 72-hour deadlines. Add an explicit one-time 90-day preservation action in record details and preview/confirmation before legacy archives adopt retention. Ordinary saving does not extend retention; cleanup runs during app activity rather than promising instant deletion while stopped.
- Bound relay-source packets to 72 hours, registrations to 90 days without qualifying activity, and retirement markers to a further 90 days after authenticated retirement/purge or idle retirement. Provider logs, regions and backups remain unverified; this update does not deploy those source changes.
- Update bundled bilingual Terms, privacy, deletion and storage guidance. Preserve the existing feature set subject to deliberate security gates. No new model runs in the stable app; synthetic-data model experiments remain separate. Packaging/revision synchronization and final implementation verification remain outstanding.

See [draft beta notes and integration limits](docs/releases/4.9-beta.4.md). Earlier entries describe their historical versions.

## 4.9-beta.3 — 16 November 2026 (Hong Kong)

- Require first-launch acceptance on each phone before pairing, capture or relay transfer. Display the exact bilingual Terms published in this repository, with three unticked acknowledgments of Terms, encrypted message transfer to the verified paired parent and the family-only purpose.
- Save a device-bound encrypted receipt with original revision, document SHA-256 and time. Do not repeat the initial prompt on ordinary launches or updates. Add read-only Terms/receipt review in Settings; expanded capture still needs its existing separate confirmation.
- Return to Android Home on decline or Back. Gate background services and direct capture/relay entry points, including previously configured phones, without deleting old records or sending pending queues before acceptance.
- Align privacy/deletion/data-flow documentation; distinguish the limited Cap.486 s52 exemption from the app's stricter family-only restrictions. Verify packaged Terms match the repository byte for byte.

See [beta notes](docs/releases/4.9-beta.3.md).

## 4.9-beta.2 — 16 November 2026 (Hong Kong)

- Encrypt preference strings, including parent credentials and family secrets, with Android Keystore-backed AES-GCM; preserve existing credentials during atomic migration.
- Encrypt archived content, labels, partial assemblies and capture-window state; use keyed indexes for sender/thread lookup and decrypt search candidates inside the app. Migrate existing SQLite records without changing pairing or archived message IDs.
- Reject unreadable encrypted data without a plaintext fallback or automatic reset; show a recovery screen. Password inputs are excluded from saved activity drafts.
- Prepare targeted cleanup of historically exposed APK signing material, Actions logs and affected historical build artifacts. Copies already downloaded cannot be recalled.

See [beta notes](docs/releases/4.9-beta.2.md) and [storage security](docs/local-storage-security.md).

## 4.9-beta.1 — 12 November 2026 (Hong Kong)

Local beta; publication and email sending are not part of this change.

- Remove unsent comment/story-reply capture and its debounce sender. Exclude composer contents and descendants from screen traversal and stop reading DM composer bodies for outgoing filtering.
- Retain incoming-alert mode and separately acknowledged two-way capture of exposed messages. Preserve older draft records as historical, with sending unconfirmed; no automatic deletion.
- Align README, privacy policy, terms, deletion instructions and data-flow documentation with capture scope, withdrawal and separate phone/relay retention. Stopping new capture requires disabling both Android capture permissions; there is no separate expanded-capture withdrawal switch. Existing pending or delivered copies are not erased.
- Clarify Instagram's applicable minimum age of at least 13, incomplete notification/view-dependent capture, and the absence of Meta approval, official API integration or implemented portability. Prepare a dated unsent correspondence draft.

See [beta notes and verification limits](docs/releases/4.9-beta.1.md). Earlier releases and reports remain historical records under [docs/releases](docs/releases/); they have not been rewritten as part of this update.
