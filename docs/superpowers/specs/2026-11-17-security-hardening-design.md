# Security hardening and isolated d1 feasibility experiment

Authorized by the user's instruction to apply the preceding security proposal and run multiple debug sessions. Keep existing capture, archive, pairing and background delivery operational. Do not push, merge into main, deploy a Worker, or publish an APK.

## Decisions
- Incremental hardening first; no custom ratchet/crypto protocol. Retain AES-GCM and signed commands. Compromise recovery is fresh, locally verified pairing, never transmission of replacement secrets through a suspect old channel. Routine rotation remains explicitly separate.
- Protect parent UI and sensitive exports with Android biometric/device credential authentication, relock on background, FLAG_SECURE and private generic notifications. Background delivery must work while the UI is locked. Never silently reset an inaccessible key.
- Use non-exportable Keystore parent signing identities. Preserve existing public-key trust through secure import or an authenticated explicit migration; no silently broken pairings. Password plaintext should be removed wherever not required.
- Consent withdrawal is a real persistent gate over capture and pending transfer, with optional discard of queued content. Separate expanded-scope incoming/outgoing/trusted-context choices. No drafts. Re-enabling expanded scope requires child acknowledgement.
- New records default to 30-day retention, pending messages to 72 hours. Explicit bounded preservation is 90 days. Existing archives are not immediately deleted: offer a migration preview/confirmation. Expired/deleted content must not resurrect through replay/reconnect. Relay has bounded packet and idle-registration lifetimes and authenticated retirement/purge.
- Experiment only: pin LiquidAI/d1-omni-600M at a verified revision, review custom loader code/licence, synthetic text scenarios in English, Traditional Chinese/Cantonese and mixed language, rules baseline, abstention, per-language false positives/negatives, latency and resource measurements. No child data or automatic punitive decisions. Actual Android feasibility is a separate measured result, not inferred from parameter count or vendor wording.

## Approaches
Chosen: incremental native security controls and separate experimental branch, preserving the current production pipeline. Full transport-protocol replacement is a larger follow-up requiring an audited library and compatibility design; no home-grown forward-secrecy claim.

## Checks
Test revocation/in-flight callbacks, background delivery while parent screen is locked, legacy pairing/key migration, retention cutoff/preservation/replay, relay purge authentication, existing regression suite, APK build/lint, multiple focused debug passes, independent final review. No claim of zero bugs or proven grooming efficacy from synthetic cases.
