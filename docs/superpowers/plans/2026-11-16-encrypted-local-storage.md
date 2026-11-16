# Signing exposure cleanup and encrypted local storage

User-authorized remediation of the 16 November security assessment. Implementation choices are the assistant's: use Android Keystore AES-GCM for stored strings and archive content, keep existing pairing/signing identity and database APIs, and preserve search through in-app decryption. No encryption key is stored alongside ciphertext. This is application-layer protection, not a guarantee against a compromised running phone.

- [x] Write failing tests for cleartext secret storage and archive migration.
- [x] Add `LocalVault` with Keystore AES-GCM, purpose-bound authenticated ciphertext and Keystore HMAC indexes. Migrate existing preference strings atomically without changing values or identities; reject corrupt encrypted data without plaintext fallback.
- [x] Encrypt archive content/identifiers, preserve queries/rotation/recovery, migrate legacy database rows transactionally and clean old SQLite pages/journals after commit. Non-content timestamps/categories/opaque routing metadata remain queryable.
- [x] Test real cryptographic operations with a test-only Keystore provider in Robolectric, plus migration, corruption, concurrency and existing functional suites. Build/lint release and document physical-device limits.
- [x] Prepare private rollback backups, exact-secret Git history rewriting and targeted Actions log/artifact cleanup in an isolated clone. Preserve unrelated commits, tags and current trusted signing identity. Scan rewritten history before publication.
- [ ] Commit verified app changes, publish sanitized main/tags with expected-old-ref checks, delete exposed logs/artifacts, and verify public refs/downloads. Report old-SHA/cache/support limitations without claiming copied keys were revoked.
