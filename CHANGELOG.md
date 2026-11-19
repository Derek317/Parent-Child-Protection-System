# Changelog

## 4.9-beta.4 — 19 November 2026 (Hong Kong)

- Add native Android biometric/device-credential gating for parent UI and sensitive actions, relock on background, secure-window flags and generic private notifications. Parent background receipt, archiving and signed acknowledgments continue while the UI is locked. Explicit app copies are marked sensitive and get best-effort cleanup after 60 seconds if still current.
- Move production parent signing to non-exportable Android Keystore identities. Verified legacy import preserves the public key already trusted by the child and removes the encoded private-key preference afterward; unavailable keys do not silently replace established trust. Add separate fresh, locally verified compromise re-pairing with authenticated old-room purge or an explicitly confirmed offline path without purge assurance; routine old-channel rotation remains separate.
- Add persistent child capture/transfer withdrawal with hold/discard choices and separate outgoing/trusted-context choices. Explicit resume discards old pending content and restarts incoming alerts; expansion needs renewed child acknowledgment. Saving scope choices discards old pending content, and widening scope clears acknowledgment. Composer drafts remain excluded.
- Assign new archives a default 30-day expiry and pending outboxes/incomplete assemblies 72-hour deadlines. Add an explicit one-time 90-day preservation action in record details and preview/confirmation before legacy archives adopt retention. Ordinary saving does not extend retention; cleanup runs during app activity rather than promising instant deletion while stopped.
- Relay retention is unchanged: buffered packets expire seven days after first queuing, room registrations have no automatic expiry, and the relay cannot delete a room. The fresh re-pairing screen says so plainly, keeps the existing pairing and offers creating a new pairing only. In-app consent and help texts state the relay's 7-day packet lifetime and the phone's 72-hour outbox and 30-day archive deadlines separately.
- Update bundled bilingual Terms, privacy, deletion and storage guidance. Preserve the existing feature set subject to deliberate security gates. No new model runs in the stable app; synthetic-data model experiments remain separate. Packaging/revision synchronization and final implementation verification remain outstanding.
- **Fresh authentication.** Showing the parent master password, routine key rotation, remote child reset, compromise re-pairing and leaving the parent role each ask for a new biometric/device-credential check; an open session is not enough. Cancelling cancels only that action. Leaving the parent role is now an explicit, authenticated step that removes the master password and parent signing key from the phone (stored records stay).
- **Pairing links.** A parent phone, or an unconfigured phone that still holds parent secrets, refuses `igguard://pair` with a plain message and never deletes the master password or signing key because of a link. A child phone can be re-paired only by entering the current parent password (checked with the existing verifier and lockout) or after a signed remote reset; a temporary unlock alone is no longer enough. A tamper alert is queued to the old family before the switch, and delivery is awaited for up to 6 seconds; if the relay is unreachable that notice cannot be sent after the switch. A pairing link at a locked child phone raises at most one alert per hour.
- **Temporary unlock and time.** Unlock expiry, unlock-code life and the 10-minute wrong-password lockout are counted on `elapsedRealtime` with a boot marker; the wall-clock expiry stays as an additional upper bound. An unlock ends at reboot, a reboot restarts (never ends) a running lockout, and moving the clock back cannot extend an unlock or revive a code. Unlock codes are single-use. A child whose clock moves back by more than 5 minutes sends one tamper alert per hour. A rolled-back clock still makes the child reject signed commands; that protocol limit is unchanged.
- **Diagnostics.** Checkpoint logs no longer record sender names or Instagram handles; older entries are rewritten on read, and deleting all evidence clears the logs.
- **Overlay protection.** Consent, pairing confirmation, two-way capture confirmation, remote reset and role-switch controls (and all other in-app buttons) ignore touches delivered while another window covers them.
- Pairing, relay messages and signed commands keep their wire formats; updated and not-yet-updated paired phones keep working together. Not addressed here: single-use or short-lived invitations, per-device child keys, end-to-end sequence numbers and changing how trusted senders are recognised.

See the [release notes and known limits](docs/releases/4.9-beta.4.md). Earlier entries describe their historical versions.

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
