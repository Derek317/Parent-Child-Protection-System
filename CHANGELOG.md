# Changelog

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
