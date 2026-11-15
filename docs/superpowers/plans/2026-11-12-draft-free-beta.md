# Draft-free beta implementation plan

**Goal:** Apply the user-approved removal of unsent Instagram draft capture and align public disclosures for local beta 4.9-beta.1.

**Architecture:** Delete the comment/story draft producer and its debounce state. Keep composer layout/hint metadata for DM gating, but do not read composer contents or descendants. Preserve incoming alerts, acknowledged two-way capture of displayed messages, and readable historical records. No remote publication or email sending.

- [x] Add service regression checks for comment/story drafts and composer text access in both capture modes; run them against the old code to confirm failure.
- [x] Remove draft producer and typing-text cache usage; preserve metadata-only composer detection and historical draft labels/schema compatibility.
- [x] Update in-app disclosures, version and local release workflow labels without publishing anything.
- [x] Align README, policies, deletion/data-flow docs, dated changelog and release notes with verified behavior and limitations.
- [x] Run Android regression tests/build, existing Python and relay protocol checks, inspect the diff and prepare a local reviewable beta.
