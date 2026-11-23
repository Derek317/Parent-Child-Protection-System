# Security review and migration

This maintenance pass preserves the single APK, native UI, monitoring, charts, trusted accounts, master-password unlock, temporary unlock, key rotation and remote rescue. It replaces unsafe authentication behavior and removes manual key/value caches. It is a source review and automated regression exercise, not a guarantee against every vulnerability or a real-device penetration test.

## Required migration

The old workflow's APK signing identity and old invitation contents must be treated as compromised. Deleting them from current source cannot revoke copies or erase Git history.

1. Configure a new private APK signing key as described in [signing setup](signing.md). Until configured, CI intentionally produces `ig-guard-unsigned-apk`. It is not directly installable. Do not re-use the exposed signing key. A fresh signing key normally requires reinstalling existing apps; plan preservation of any needed family records first, or arrange an Android signing-key rotation migration separately.
2. Update both phones. On the parent phone, establish a family with a **new master password**. The existing role-selection flow can reopen setup: choose the child role, continue to setup, return to role selection, then choose parent and create the family. This generates a new family token. Do not complete child pairing on the parent phone.
3. Send a new V43 invitation to the child phone. On the child, open the invitation and enter the current parent password when asked to confirm; a temporary unlock alone does not allow replacing the pairing, and the old family receives a tamper alert first. A parent phone refuses pairing links: choose "leave family" (authenticated) first. Compare the complete security code shown on both phones, as well as the four matching safety symbols. The new code binds the family token, password verifier and parent public key; the original emoji fingerprint alone cannot authenticate a replacement signing key.
4. Confirm a new child report and test an unlock code. New temporary codes require the child to receive the signed encrypted command first. Old deterministic codes and unsigned control messages are rejected.

Existing child monitoring remains active before re-pairing. Legacy invitations can be imported for monitoring/master-password recovery, but cannot establish a trusted parent signing key. The child permissions page explains when secure parent commands need a new invitation. Old master passwords were included reversibly in earlier invitations; simply updating the APK does not make those old passwords private again.

## Protocol boundary

- Alerts remain AES-256-GCM encrypted with fresh random nonces over HTTPS. Unencrypted relay messages are rejected.
- New invitations carry a salted PBKDF2-SHA256 password verifier and an EC parent public key. They do not carry the parent password or signing private key.
- Parent controls are ECDSA signed, bound to the family channel, timestamped and assigned authenticated replay identifiers. The child pins the public key from its explicitly accepted invitation. The parent must compare the complete security code on their own phone with the child phone to detect replacement of the invitation’s key or verifier.
- Temporary codes come from secure randomness, expire after delivery's stated issuance period and retain the existing five-attempt lockout. They cannot be calculated from the invitation token.
- Child verifier/public-key state and parent private credentials live in Android app-private preferences; backups and cleartext network traffic are disabled. This does not defend a device whose operating system or app process has been compromised.
- Explicitly accepted family replacement clears old authorization, identity and transient unlock state. Key rotation preserves the intended history and can retry a pending transition.

The invitation still contains the shared encryption token, so it must be sent privately. Anyone holding it can decrypt family traffic. A child device is a participant in this shared channel; it is not a tamper-proof hardware boundary. Real-device testing is still needed for Instagram metadata/geometry, Samsung permission behavior, background scheduling and banking/Play Protect decisions. No permission or monitoring scope is expanded by these fixes.

## Child-phone impersonation: what is now done, and what remains

**History.** The risk was open through 5.2 (see the [5.2 release notes](releases/5.2.md)). Child-to-parent messages were encrypted with the family token but not signed. Whoever held the invitation text (the child, who sees it in the chat it was sent through, or anyone who finds it in a chat backup or screenshot) could derive the relay room and the child credential and send messages that the parent phone accepted as coming from the child phone: fake "running, notification access on" heartbeats, so the missing-heartbeat alert never fired after IG Guard was uninstalled or its access revoked; fake scores reports and capture acknowledgements; and a new child login on the relay, which disconnected the real child phone. A routine key rotation did not end this, because the new token was sent encrypted under the old one.

**Status of the change below.** It is in the source on a development branch and is not part of any released version at the time of writing. It has not been tested on real phones yet; the device round comes later. Nothing here says the app is free of vulnerabilities.

### What is now done

- **Pairing is by a one-time QR code, in person.** The parent phone shows it and the child phone scans it. It is new each time, valid for 3 minutes and single use. It holds a one-time secret and the parent phone's public key, and no long-term secret. The two phones then agree a new family secret between themselves; it is never shown, typed or sent through a chat app. So the invitation text that used to hold the family token no longer exists for new pairings.
- **A typed check.** The child phone shows 6 digits; the parent types them on the parent phone (3 tries), accepts, confirming with the parent phone's own screen lock or biometric, and the child confirms on its own phone. Four pictures are shown on both phones as a visual aid. If two phones answer the same QR code, pairing stops.
- **Chat invitations are no longer offered** for new pairings, and a child phone refuses them.
- **The child phone has its own signing key** in the Android Keystore; it cannot be exported. Every record the child phone sends to the parent (heartbeats, alerts, captured messages, scores reports, acknowledgements) is signed with it.
- **The parent refuses unsigned or wrongly signed records** once it has verified a child phone this way. Nothing from such a record is stored, and the parent gets a notice without content (at most one per hour). A correctly signed record that is too old, dated too far ahead, or of an unexpected kind is dropped quietly. A forged heartbeat no longer counts as "running" for a verified child.
- **The relay locks the room to the child key.** For a family paired this way the relay stores the child phone's public key (no content, no private key) and admits a child connection only with proof of that key. An attempt without it cannot disconnect the real child phone. Families that were not paired this way are handled exactly as before.
- **Camera.** The child phone asks for camera permission only on the scan screen, only to read the QR code. Frames are processed in memory; no image is stored or sent.
- **One third-party library is added:** ZXing core 3.5.3 (Apache-2.0), stored in the repository with a pinned SHA-256 checksum (see `third_party/README.md`).

### What is not done or not protected

- **Pairings made before this version** keep working and show a permanent "not verified" notice on the parent phone until the two phones are paired again with the QR code. Until then everything above about forged records applies to them as it did in 5.2: treat a "running" heartbeat from such a pairing as a sign that something holding the invitation is online, not as proof that the child phone is protected.
- **A rooted or compromised child phone** can still use its key and sign whatever it likes.
- **A parent who pairs a device that is not really the child's phone.** The parent must read the 6 digits from the child's own phone; the app cannot tell which phone is in front of the parent.
- **A child who scans a stranger's QR code** pairs the child phone with that stranger.
- **A child who uninstalls the app.** The parent sees missing heartbeats, as before.
- **Relay metadata** (room ids, sizes, timing, and now one child public key per paired room) is still visible to the relay operator.
- **A future-dated heartbeat held back by a hostile relay.** A signed record may be dated up to 24 hours ahead of the parent's clock, so a child phone with a fast clock is not refused. If the child's clock is set ahead and a hostile relay holds a signed heartbeat back, that heartbeat can count as fresh later by at most 24 hours.
- **No key attestation.** The app cannot prove that the signing key sits in tamper-resistant hardware.
- **The relay has no per-address rate limit** for requests to unknown rooms. This can cost the operator money; it exposes no data.
- **The release build runs tests and signing in one job**, without full dependency verification (only the ZXing jar is checked against a pinned checksum).
- **Not tested on real phones yet** at the time of writing.
- **A stalled pairing behind queued records.** The relay keeps at most 8 records in flight per connection. If 8 or more records that are not part of the pairing wait in the new room ahead of the parent's confirmation, the child phone's pairing stalls until its 10-minute timeout.
- **Old commands are not carried over** on "pair again to verify": commands a parent with an older pairing still has queued for the old family are not moved to the new room.
- **A parent cancel after the child confirmed is not signalled directly** to the child phone. The parent retires the new room, which ends the child's wait (the child phone is refused and deletes its key); there is no signed cancel message.

## Verification

Behavior tests cover malformed invitations, password verifiers, signatures and family scope, expired/future/replayed commands, plaintext injection, family replacement, sender spoofing, stale heartbeats, pairing consent/recreation, failure recovery, and the existing statistics/UI/permission flows. Transport-failure tests use a local refusing proxy; no test posts to a real family relay.

## Review decisions and limits

The final independent source review found two important issues, covered by additional regression tests: old-channel reports must retain pending rotation retries, and manual/clipboard pairing must use the same full-code confirmation as deep links. The fix pass addresses both without another protocol or UI redesign.

Real-device Instagram identity metadata, Samsung permission behavior, background scheduling and physical clock skew remain device-validation requirements. Use automatic date/time: commands more than two minutes ahead of the child clock are rejected. Live relay delivery was not exercised against a real family; local transport tests deliberately refuse production requests. Replacement signing-secret configuration and installation migration require the owner's private key and device decisions; no replacement key is configured in this session. Errors in these external conditions can affect monitoring, timely alerts or installation/update behavior. The reviewed code does not establish otherwise.

Validation completed: **54 Android tests** and **seven build/contrast checks** pass. Android lint reports **zero errors** and 51 non-fatal resource/API/style warnings; actionlint passes. Debug and unsigned release builds succeed. The secret-configured signing branch was separately exercised with a disposable local key; its APK signer and temporary-key cleanup were verified. The fixture-signed APK was deleted and is not a user release.
