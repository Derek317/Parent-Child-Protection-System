# Conversation capture and parent archive — completion report

**Reviewed:** 8 November 2026, Hong Kong time.
**Status:** Implementation, code review and automated debugging complete; prepared for release 4.7.
**Repository base:** `f2a7a45`. This report records the pre-publication verification checkpoint. The user subsequently authorized pushing the completed work and publishing a release; see the [4.7 change log](releases/4.7.md).

This report covers both approved requests: capture the child's outgoing DMs as well as incoming messages, including ordinary trusted-contact messages; and give parents a DM-style list of conversation objects with chronological messages and organized review information. It builds on the existing implementation rather than reapplying it.

## Completed behavior

| Requirement | Implemented behavior | Verification |
| --- | --- | --- |
| Two-way capture | Process exposed notification entries and visible DM bubbles in both directions. Retain ordinary trusted-contact context. DM typing-box text is excluded. Existing comment/story reply observations remain explicitly labelled as unconfirmed drafts. | Capture, ingestion, protocol and screen-capture tests. |
| Conversation list by object | Group by available account, conversation label and known direct/group type. Explicitly identified groups remain separate from same-named direct contacts and show a group label. The actual author remains separate from the conversation title. | Parent archive/group collision tests and native light/dark previews. |
| Chronological threads | The list shows latest recorded activity first. Each thread displays 30-record windows from oldest to newest. Available source time is used; capture/legacy time is identified when source time is unknown. Stable boundaries prevent new arrivals moving older pages. | Timestamp, equal-time ordering, paging/new-arrival and activity-recreation tests. |
| Organized parent decisions | Separate **對話記錄**, **需要跟進**, and **裝置與傳送** destinations. Review reasons include existing keyword matches, unapproved incoming contacts and flagged-contact follow-ups. Show available matching phrases, author, direction, source and time. | Archive, classification, UI, statistics and notification tests. |
| Review and retrieval | Per-event New / Follow-up / Reviewed state; new events remain independently reviewable. Search, date/type/direction/saved filters, full-text details, copy/save, deletion and older-message navigation remain available. | Archive and native UI tests, including existing evidence tests. |
| Controlled expanded scope | A supported signed parent request and explicit acknowledgment on the child are required before expanded capture. Existing incoming protection continues beforehand. | Signed-policy, local-acknowledgment, replay and family-scope tests. |
| Durable delivery and retention | Accepted captures use the existing encrypted outbox, bounded framing, retries, signed parent receipts and local parent archive. Ordinary context is excluded from risk totals and per-message warning notifications. | Delivery, framing/reassembly, receipt, rotation, migration and statistics tests. |

Capture remains dependent on what Instagram exposes through Android. This is an archive of accepted captures, not a complete Instagram history or a verified transcript of every sent message.

## Remaining defects found and repaired in this pass

| Finding | Observed failure | Repair and regression protection |
| --- | --- | --- |
| Delayed capture could cross families | A story/comment draft staged under family A could be flushed after switching to family B, using B's current full or legacy capture mode. | Bind the observation to its original role/token/session generation. Check that origin when flushing and at actual capture/enqueue boundaries. Tests cover both an acknowledged new family and legacy fallback. Normal same-family reply checks remain covered. |
| Group identity and approval could be confused with an individual | A direct conversation and an explicitly marked group with the same account/label shared a key. A group could inherit approval from its title or flag the individual whose name matched that title. A group named after the child could also be discarded as self content. | Preserve direct/group/unknown type in compatible record metadata and identity. Classify explicit groups using the captured author rather than the title. A group title cannot flag or approve an individual. Retain incoming group messages even when the title matches the child's name. Parent group/direct archive separation is tested. |
| Older-page position was lost after recreation | The selected page number was saved, but its chronological boundaries were not. The app could display newest messages under an older-page number after recreation. | Save bounded timestamp/order/ID cursor metadata with the existing family-bound UI state. Restore earlier/later navigation correctly even with new arrivals. Unusable/oversized navigation state returns to the newest page; archive data is unchanged. |
| Trust action guessed a username | A display name such as `Alex Chan` was truncated to `alex` for the quick trust action. | Validate the complete candidate; never truncate it into another username. Explicit group titles are excluded from this individual-contact shortcut. The valid-handle trust action remains tested and functional. Existing parent-selected trust entries are preserved. |

The first three regression classes initially produced **7 failing assertions across 27 tests** before these repairs. The subsequent focused run passed **41 tests**. A further explicit-group/self-title case was observed failing before its correction. The final complete run below verifies all repairs together.

The independent review found two material capture/scope issues, alongside the two UI issues identified during the completion check. All were addressed in one repair pass. No Critical/Important finding from this completion review remains open, and no minor finding was deferred by the reviewer. This is not a guarantee that every possible bug or security issue has been eliminated.

## Previous repairs retained

The original **144 regression tests remain present and pass**, alongside nine additional cases. These continue to cover:

- Peer titles cannot establish the child's identity; old unverified identity caches are invalidated.
- Display-name spaces and a named composer above the keyboard do not disable the tested DM capture layouts.
- Real message bodies resembling reply/profile controls do not cause unsent DM drafts to be relayed.
- Rolling notification bundles containing old and new entries retain the new entries transactionally.
- Unprefixed observations do not inherit another account's identity; uncertainty remains explicit.
- Flagged incoming follow-up notifications retain their existing suppression behavior, including reposts.
- Explicit connection confirmations, upload/poll status separation, signed receipts and failed-delivery retention remain intact.
- Pairing protection, signed-command validation, family isolation, key rotation, schema migration, deletion and previous UI behaviors retain their regression coverage.

The old faulty implementations were not restored to perform this verification.

## Final debugging and build results

```bash
env -u APK_KEYSTORE_PATH -u APK_KEYSTORE_PASSWORD -u APK_KEY_ALIAS -u APK_KEY_PASSWORD \
  bash tests/run_android.sh assembleDebug assembleRelease lintDebug testDebugUnitTest
```

| Check | Result |
| --- | --- |
| Full command | Exit 0; `BUILD SUCCESSFUL in 2m 12s` |
| Android tests | **153 tests, 20 classes; 0 failures, 0 errors, 0 skipped** |
| APK builds | Debug APK and unsigned release APK packaged successfully |
| Android lint | **0 errors, 57 warnings**; existing API/resource/preference maintenance findings remain |
| Workflow checks | **13 Python checks passed**; `actionlint` passed |
| Diff hygiene | `git diff --check` passed |
| Build-source consistency | All four generated Kotlin source files match the current checkout |
| APK fixture/key inspection | Both APKs: **0 tested preview/synthetic fixture matches, 0 keystore-file entries** |
| Native UI | Actual Android Views rendered successfully at SDK 34, 390 × 844 dp; light/dark conversation lists, separate same-name group/direct rows, threads, details and child scope notice inspected |

Preview records are synthetic test-only data. The external preview test was removed before the final suite and is absent from its reports and generated test sources. No preview records were added to app startup or production storage. Private release signing credentials were not used for these local builds.

Publication recheck for the exact 4.7 tree: `BUILD SUCCESSFUL in 1m 39s`, all 153 Android tests pass, both APK builds pass, lint remains 0 errors / 57 warnings, and 21 workflow/publication checks pass. The release script's tests cover signed-only attachment/checksums, updating an existing release, unsigned/unverified/missing artifacts, failed builds, incomplete uploads and mismatched tags. No app feature changed during this publication preparation.

Local evidence:

- Final build/lint/test log: `/workspace/.android-dev/conversation-review/completion-final-validation.log`
- Earlier failing/focused checks: `completion-red.log`, `completion-green.log`, `group-self-title-red.log` in the same directory.
- Native preview log: `completion-native-preview.log`; images under `/workspace/outputs/conversation-preview/`.
- Generated APKs/reports: `/workspace/.android-dev/project/app/build/`.

## Scope and implementation decisions

- New completion edits are confined to `MainActivity.kt`, `ConversationCapture.kt`, `FamilyEvidence.kt`, relevant tests and documentation. Earlier capture-policy/source-copy changes remain part of the overall feature. `FamilySecurity.kt` was unchanged during this completion pass.
- Preserve the existing native minimalist UI, role security, cryptography, signing/version configuration and unrelated functionality. No new Android permission, runtime dependency or central message database was added.
- Preserve compatibility for existing records whose conversation type is unknown. Do not retroactively invent group identity, authorship or timestamps for old evidence.
- Bound saved navigation metadata to 64,000 characters. Exceptionally deep histories may reopen at the newest page after recreation; retained messages remain searchable and available.
- Account, author and conversation labels are source observations, not verified Instagram identities. When stable identity is unavailable, aliases or identically named groups can remain ambiguous. Explicit group/direct information is used when available; uncertainty does not justify flagging a group title as an individual.

## Reviewing the feature in the app

1. Both phones need the updated build to support the expanded record format.
2. On the parent, open **提醒 → 對話記錄** and request the expanded capture scope.
3. On the child, read and explicitly acknowledge the scope in protection/permission settings. It includes both directions and ordinary trusted-contact context. Updating the APK alone does not enable it.
4. Open a conversation on the parent to review chronological records and use filters/older-message navigation. Use **需要跟進** for the actionable queue and **裝置與傳送** for system/connection records.

## Remaining verification limits

No physical Samsung S26/Instagram session or live parent–child relay test was available. Actual exposed layouts, notification contents, background endurance and OEM behavior still need handset verification. The earlier normal ntfy reachability probe was denied by this cloud environment's network policy; that does not establish whether it is blocked on the phones. Automated checks cannot certify Play Protect/banking acceptance or guarantee live delivery, complete capture, server delivery/read status or verified sender identity.

Existing v4.6 installations need the new 4.7 APK signed with the existing release key on both phones. The local debug signing identity and unsigned release artifact do not replace that process. The tag workflow publishes release notes and attaches only a successfully built, verified signed APK; otherwise it records a notes-only or incomplete-upload status. Publication does not establish live handset verification.
