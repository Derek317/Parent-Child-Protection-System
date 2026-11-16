# First-launch consent

Implement the user's requested one-time Terms acceptance before pairing, permissions, capture or relay delivery. A native offline page displays the exact UTF-8 Terms published in this repository, with clear transfer/security/family-purpose disclosures and three unticked acknowledgments. Decline or Back returns to Android Home without acceptance. Each installation records an encrypted receipt (revision, document hash, time); normal updates do not repeat the first-launch prompt. Existing expanded-capture consent remains separate.

1. Add failing first-launch, decline and existing-role tests.
2. Bundle the canonical bilingual Terms; store/validate the encrypted receipt; add the page and read-only Settings review.
3. Gate services, capture, pairing and relay entry points; preserve existing data/queues while unaccepted.
4. Align Terms, privacy/deletion/data-flow docs, README and beta release notes. Explain the limited wording of Cap.486 s52 without promising an exemption for the service.
5. Run Android regression/security/UI tests, lint and APK build; check packaged Terms bytes. Review the diff and publish the tested changes and verified beta artifact through the existing CI.
