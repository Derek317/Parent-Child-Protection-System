# Chat reader repair — 8 November 2026

These repairs were developed against published 4.7 (`39ebeec`) and are included in version 4.8. The [visual review and debugging report](visual-review-report.md) records final integration checks. No signing-key change is introduced.

## Confirmed causes

1. The reader chose the first acceptable text near the top of the screen. A readable Back control (`返回`) could therefore become the conversation name and key for unrelated rooms.
2. The child phone reports that no parent capture policy has arrived. That means two-way capture is not enabled: the existing incoming-alert mode intentionally excludes child replies and ordinary trusted conversations until the signed parent request and the child's local acknowledgment are present.
3. Full capture discarded description-only message nodes and relied on individual text bounds for direction. Short text inside a wide outgoing bubble could look incoming. The same issue could incorrectly forward outgoing text through the legacy incoming-only filter.
4. A 180-item traversal limit could stop the reader before reaching messages or the composer. Window dimensions also assumed an origin at the top-left of the display.
5. Messages first captured while scrolling backward received later capture timestamps. Sorting solely by those timestamps put older content after newer content.

## Changes

- Select the actual toolbar title using node IDs, or an unambiguous title aligned with a recognized Back control. Skip frames with unreadable or ambiguous titles. A message near the top is never used as a fallback conversation name.
- Request accessibility node IDs in the generated Android service configuration. This uses the existing accessibility authorization; no additional permission or dependency is introduced.
- Read text across the exposed area between the title and composer, including partially exposed edge messages. Traverse up to 2,048 nodes / 64 levels; each snapshot remains bounded to 128 message observations. Retain message text exposed through known message-node descriptions and containers with decorative children.
- Use message-node direction and ancestor bubble bounds before horizontal alignment. Keep uncertain direction marked unknown. Exclude controls, editable descendants and roots belonging to other apps. Incoming-only mode uses the same classification before its conservative incoming filter.
- Preserve top-to-bottom order within snapshots and align overlapping snapshots using a content-free screen anchor and relative position. Stable occurrence IDs suppress refreshes and revisits to the same aligned position. Original message, capture and receipt timestamps are retained.
- Upgrade evidence database version 2 to 3 with separate ordering columns. Preserve existing evidence, bookmarks, review state, deletion tombstones, encrypted delivery queues and family scopes. Thread paging uses the same ordering as the displayed thread.
- Show incoming-only versus two-way mode prominently. Put exact setup steps and a receive-again action at the top of the child notice. Preserve the signed-policy and explicit child-confirmation requirements.
- Label old navigation-named records as unidentified historical captures without rewriting their text or guessing their original rooms. Explicit notification contacts named Back/返回 remain ordinary contacts; a new explicit toolbar title with that literal name is also retained normally.

## Enable two-way capture

1. On the parent phone, open **提醒 → 對話記錄 → 雙向擷取尚未確認 · 前往設定**, then tap **傳送雙向擷取設定**. Keep both phones online.
2. On the child phone, open **權限 → 雙向對話記錄**. If necessary, use **重新接收家長設定** in the repaired build, or let the existing app synchronize after the parent resends.
3. Read the scope and tap **我已了解，啟用雙向擷取**. The child status must then show **雙向擷取已啟用**. Accessibility must also be enabled and connected for on-screen outgoing messages; notification access alone cannot read them.

Enabling capture does not restore content missed before it was enabled. Both phones need version 4.8 for the new screen-order metadata to affect the parent archive.

## Verification

The original defects were reproduced before implementation: six failures in the initial seven realistic service-tree tests, plus separate failures for backward-scrolling order, missing setup guidance and missing node-ID configuration. A fresh read-only review found three further issues; targeted tests reproduced each before correction.

- Focused capture, consent, chronology, migration and UI suite: **59 passed** after the review fixes.
- Python workflow, signing, release and UI checks: **22 passed**.
- Native Android setup screens rendered and inspected in light and dark themes. Fixtures remain outside production sources; no sample messages are added to the app.
- Full Android regression suite: **174 passed across 21 classes**, with zero failures, errors or skipped tests (21 more tests than the published base).
- `assembleDebug` and `assembleRelease`: **passed**. The local release APK is unsigned; no private signing credentials were used.
- Android `lintDebug`: **zero errors; 57 warnings**, matching the prior warning count. This is not a claim that every warning is resolved.
- `actionlint` and `git diff --check`: **passed**. New, untracked report/plan/test files were also checked for trailing whitespace.
- Built release resource inspected: accessibility flags are `0x11` (`flagDefault | flagReportViewIds`). Generated app sources match the three edited Kotlin files. Release archive inspection found no keystore files or test/preview fixture strings.

Commands and logs are retained in `/workspace/.android-dev/chat-reader-repair/`. Native UI previews are under `/workspace/outputs/chat-reader-repair/`.

The final integration review also found and repaired activity recreation rejecting negative relative screen-order cursors; the selected older page now survives recreation. See the visual report for that regression and release verification.

## Limits that remain

- The app can retain messages Instagram exposes through notifications or the currently loaded accessibility tree. It cannot recover unloaded history, force Instagram to expose hidden content, or guarantee capture when permissions/services are blocked. The app does not automatically scroll or bypass Android restrictions.
- Relative order within overlapping screen captures is observed, rather than an invented send time. Disconnected captures remain in observation order and can contain possible duplicates. Independently timestamped notification records cannot always be aligned precisely within a screen block; the parent view now states this limitation. A globally exact chronology across both sources is not established.
- A readable conversation title identifies the observed room label, not a verified Instagram account ID. Identical display titles and an unavailable active-account identity can remain ambiguous. Uncertain direction or authorship is not promoted to verified identity.
- Previously merged `返回` records cannot be split reliably because the original room identities were never stored. They are preserved with a warning.
- Tests use constructed Android accessibility trees and native Android rendering. No physical Samsung S26 / current Instagram session or two-phone relay was available here, so these changes are not a claim of live-device verification or universal compatibility.
