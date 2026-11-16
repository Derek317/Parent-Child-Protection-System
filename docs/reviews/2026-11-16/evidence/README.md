# Review evidence — 16 November 2026 (Hong Kong)

These references describe **4.9-beta.3**, version code **165**, source commit [9a836af50d1cb2aa18421fd709c9f4d049930045](https://github.com/Derek317/Parent-Child-Protection-System/commit/9a836af50d1cb2aa18421fd709c9f4d049930045).

| Reference | Public file | Scope |
|---|---|---|
| E01 | [Release verification JSON](release-verification.json) | Existing release test counts, CI result, APK/certificate/Terms fingerprints and test exclusions. It records 262 Android tests, 33 Python checks and zero lint errors. |
| E02 | [Prior signing-cleanup status](signing-cleanup-status.md) | A prior-session status summary, not a new secret scan, proof of complete cache erasure or a submitted GitHub Support ticket. |

**How to read E01:** its statement that review findings were resolved concerns the preceding release-remediation review. It is not a statement that the app has no security/privacy flaws, a legal compliance certificate, or evidence that the later technical/legal report's open issues have been fixed. The record explicitly says physical-device testing was not performed. Its public CI source is [run 43837959021](https://github.com/Derek317/Parent-Child-Protection-System/actions/runs/43837959021).

**How to read E02:** the historical observations are dated; cached copies and externally submitted support-ticket status have not been rechecked for this publication. No ticket was submitted through this session. Cleanup cannot recall signing keys or APKs already copied by others.

The hashes and certificate fingerprints here are public identifiers, not private keys. This directory deliberately excludes keystores, passwords, live family invitations, private messages and sensitive historical source URLs.
