# Security review and migration

This maintenance pass preserves the single APK, native UI, monitoring, charts, trusted accounts, master-password unlock, temporary unlock, key rotation and remote rescue. It replaces unsafe authentication behavior and removes manual key/value caches. It is a source review and automated regression exercise, not a guarantee against every vulnerability or a real-device penetration test.

## Required migration

The old workflow's APK signing identity and old invitation contents must be treated as compromised. Deleting them from current source cannot revoke copies or erase Git history.

1. Configure a new private APK signing key as described in [signing setup](signing.md). Until configured, CI intentionally produces `ig-guard-unsigned-apk`. It is not directly installable. Do not re-use the exposed signing key. A fresh signing key normally requires reinstalling existing apps; plan preservation of any needed family records first, or arrange an Android signing-key rotation migration separately.
2. Update both phones. On the parent phone, establish a family with a **new master password**. The existing role-selection flow can reopen setup: choose the child role, continue to setup, return to role selection, then choose parent and create the family. This generates a new family token. Do not complete child pairing on the parent phone.
3. Send a new V43 invitation to the child phone. Use the existing master-password unlock on the child before replacing its pairing, then explicitly accept the new invitation. Compare the complete security code shown on both phones, as well as the four matching safety symbols. The new code binds the family token, password verifier and parent public key; the original emoji fingerprint alone cannot authenticate a replacement signing key.
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

## Verification

Behavior tests cover malformed invitations, password verifiers, signatures and family scope, expired/future/replayed commands, plaintext injection, family replacement, sender spoofing, stale heartbeats, pairing consent/recreation, failure recovery, and the existing statistics/UI/permission flows. Transport-failure tests use a local refusing proxy; no test posts to a real family relay.

## Review decisions and limits

The final independent source review found two important issues, covered by additional regression tests: old-channel reports must retain pending rotation retries, and manual/clipboard pairing must use the same full-code confirmation as deep links. The fix pass addresses both without another protocol or UI redesign.

Real-device Instagram identity metadata, Samsung permission behavior, background scheduling and physical clock skew remain device-validation requirements. Use automatic date/time: commands more than two minutes ahead of the child clock are rejected. Live relay delivery was not exercised against a real family; local transport tests deliberately refuse production requests. Replacement signing-secret configuration and installation migration require the owner's private key and device decisions; no replacement key is configured in this session. Errors in these external conditions can affect monitoring, timely alerts or installation/update behavior. The reviewed code does not establish otherwise.

Validation completed: **54 Android tests** and **seven build/contrast checks** pass. Android lint reports **zero errors** and 51 non-fatal resource/API/style warnings; actionlint passes. Debug and unsigned release builds succeed. The secret-configured signing branch was separately exercised with a disposable local key; its APK signer and temporary-key cleanup were verified. The fixture-signed APK was deleted and is not a user release.
