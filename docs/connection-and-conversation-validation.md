# Connection repair and two-way conversation validation

Local draft based on `f2a7a45`, reviewed 8 November 2026. No publication, version bump, signing-key change or new Android permission is part of this work.

A subsequent [conversation-feature completion pass](conversation-capture-completion-report.md) verified the full approved capture/archive scope and repaired additional group identity, delayed-capture scope, page restoration and contact-action defects. Its latest full run passes 153 tests; the 144-test results below record the earlier connection-repair checkpoint.

## What was independently confirmed

The reported phones both run v4.6 and show the same complete safety code and four safety symbols. That makes a mismatched family invite unlikely. It does not prove that either phone can reach the relay. The immediate test toast and child notification/accessibility “connected” indicators in v4.6 do not establish parent delivery.

| Reproduced defect | Correction |
| --- | --- |
| The legacy notification reader could save a conversation partner's title as the child's own account and suppress that person's incoming messages. | Conversation titles and MessagingStyle display names no longer establish the child's username. A validated recipient prefix or own-profile context can update the display cache. Old unverified identity caches are invalidated once. |
| A DM display name containing spaces, or a named composer moved upward by the keyboard, could disable the screen reader. | Accept normal display names and use actual window bounds; allow a named DM composer above the keyboard while preserving feed exclusion. |
| The manual test emitted only a routine heartbeat and did not force pending retries. Routine heartbeats were excluded from the main alert view. | The action now forces pending retries, retains the heartbeat, and adds an explicit encrypted test confirmation. A received test updates the parent connection status and device archive; it does not increase risk totals or enter the review queue. |
| Background GET results overwrote the last upload result. Failed queued uploads left no CP3 failure checkpoint. | Publish and poll statuses are separate. Failed/successful queued publishing adds content-free CP3 checkpoints; failures retain the encrypted queue. Test messaging distinguishes queued work, relay acceptance and parent-device acknowledgment. |
| In expanded capture, duplicate entries in a rolling notification bundle could poison a nested SQLite transaction and roll back new entries. | A duplicate is a successful no-op transaction. Initial `[A]`, then `[A, B]`, must leave both records durably queued after reopening the database. |
| DM body text such as “回覆我” or “Edit profile” could be mistaken for screen controls, causing missed capture, false identity detection or collection of an unsent DM draft. | Reply drafts require an explicitly labelled reply/comment composer. Confirmed DM context blocks profile inference. Expanded screen capture requires a named DM composer; uncertain screens are not treated as DMs. |
| A previous account cache could assign an unprefixed notification or later screen capture to the wrong account. | Only a validated recipient prefix in that notification establishes its account. Visible DMs without active-account evidence remain labelled unknown; they are separate from known-account threads. |
| Expanded capture stopped removing ordinary incoming notifications from an already flagged contact, including deduplicated reposts. | Independently attributed incoming flagged authors retain the existing notification-removal behavior, including reposts. Outgoing messages do not flag the partner. |
| New arrivals shifted older conversation pages and repeated boundary messages. | Conversation paging uses the last displayed timestamp/order/ID as its boundary. Existing archive query defaults remain compatible. |

The first four findings concern the installed legacy flow or its diagnostic visibility. The expanded-capture findings were caught in this local draft before publication. None alone establishes the actual network failure on the two reported handsets.

## Claims in the supplied review that were not adopted

- The current role-selection UI waits for an invite before activating child mode. Locked child pairing replacement still requires parent authorization; weakening this guard would create a family-hijack path.
- The signed-command wrapper has `payload` and `signature`; ordinary child alerts and completed fragments do not use that wrapper. Rejecting parent-side privileged wrappers does not discard normal child alerts. Unsigned parent controls remain rejected.
- Android MessagingStyle represents a local user's message with a null sender. Treating every such entry as incoming would misattribute the child's outgoing messages. Expanded capture preserves this documented distinction.
- ntfy's public server routes topic POSTs directly to publishing; root POSTs use its JSON publishing transformer. The existing content type alone does not establish the alleged failure. The inspected upstream reference is [server/server.go](https://github.com/binwiederhier/ntfy/blob/main/server/server.go), routing and `handlePublishBody`.
- Parent storage and heartbeat state do not depend on permission to show Android notifications. With notifications denied, the app can still show a received test and its archived device record.

## Applying and checking the repair

1. Build this draft with the existing private release signing key and install the same new build on both phones. The local debug APK has a different signing identity; the local release artifact is unsigned. Do not clear app data or uninstall simply to install a debug build, since that deletes the local archive and pairing state.
2. Keep the existing matching family pairing. Open the child's Instagram profile once to refresh the account display cache, then test a genuine incoming DM. Older misidentification may already have removed an entry from the trust/flag lists; the original values cannot be reconstructed automatically.
3. Keep both apps open for the first test. On the child phone, open permission setup and tap **立即發送測試及心跳回報**. A queue toast means only that work was queued. Relay acceptance and parent-device acknowledgment have separate labels.
4. On the parent phone, check the persistent connection status and **對話記錄 → 裝置與傳送**. The explicit test belongs there, while risk messages belong in conversations/review. Existing **重新同步可用記錄** can fetch available cached relay records.
5. For expanded two-way capture, request the new scope on the parent and explicitly acknowledge it on the child. Legacy incoming protection continues before acknowledgment. Notification access captures only data Instagram puts in notifications; named visible DM screens require accessibility access. Ordinary incoming and outgoing messages remain outside risk totals.

## Diagnostic interpretation

- **通知已連接 / 畫面已連接:** Android connected the local capture service. This is independent of internet delivery.
- **CP2-MATCH:** A legacy alert was evaluated and queued; message text is omitted from diagnostics.
- **CP3-RELAY / 中繼站已接收:** The publishing endpoint returned success. This is not proof of parent storage.
- **CP3-ERR:** Publishing failed, for example an HTTPS status or exception class. Encrypted records remain for retry. `429` indicates relay rate limiting; changing the signing key or reinstalling does not resolve a relay quota.
- **CP4-STREAM / CP4-RECV:** Parent subscription connected / a valid child event was decrypted and processed. A connected stream without a child test does not prove child publishing succeeded.
- **家長手機已確認接收:** The child verified a signed parent receipt for stored evidence. It does not mean a parent read the message.
- **CP4-POLL / 最新同步:** Separate background control-poll status; it cannot overwrite the latest publish result.

Android can stop background work, revoke access, or restrict battery/network activity. A public relay also has quotas and finite cache retention. Delivery requires working internet access on both phones; uncaptured or expired content cannot be recovered. No central developer message database was added. Existing local retention and explicit deletion controls remain.

## Validation evidence and limits

Regression failures were observed before their targeted fixes. Coverage includes matching safety codes, signed-command rejection/replay/scope checks, encrypted framing/reassembly/receipts, failed delivery retention, source repeats, rolling notification bundles, Android 8 string-sender compatibility, DM draft exclusion, account uncertainty, flagged follow-ups, schema-v1 migration, per-event review and live chronological paging.

Final command:

```bash
env -u APK_KEYSTORE_PATH -u APK_KEYSTORE_PASSWORD -u APK_KEY_ALIAS -u APK_KEY_PASSWORD \
  bash tests/run_android.sh assembleDebug assembleRelease lintDebug testDebugUnitTest
```

- Exit 0, `BUILD SUCCESSFUL in 2m 6s`: **144 tests in 20 classes, 0 failures, 0 errors, 0 skipped**. The temporary preview fixture was absent from this suite.
- Debug APK and unsigned release APK packaged successfully. Private release credentials were not used; these checks do not produce a distributable APK signed with the user's release key.
- Android lint: **0 errors, 57 warnings**, including 34 unused-resource findings, 7 discouraged-API findings, 6 synchronous-preference suggestions and other resource/API maintenance findings. Existing required synchronous writes preserve persistence ordering. This is not a claim that no maintenance work remains.
- Workflow checks: **13 Python checks passed**, `actionlint .github/workflows/build-apk.yml` passed, and `git diff --check` passed.
- Both APKs' DEX files were scanned for preview contact/child IDs, preview-test names and synthetic preview/message strings: **0 matches**. APK entries contained **0 keystore files**. Generated Android sources match all four current Kotlin source files. No new runtime dependency or permission was added.
- Actual native Views rendered successfully at SDK 34, 390 × 844 dp. Inspected the light conversation list, dark chronological thread, full-message details and child scope notice. Details place selectable message text before actions; save/copy and review controls use compact rows, with vertical layout at larger font scales. Captures use synthetic test-only data. The preview fixture was removed before the full suite.

Local execution log: `/workspace/.android-dev/conversation-review/final-validation.log`. Native previews: `/workspace/outputs/conversation-preview/`. Generated APKs/reports: `/workspace/.android-dev/project/app/build/`. No commit, push or release was created.

No physical Samsung S26 or real Instagram/ntfy family session was available. A normal reachability probe to `https://ntfy.sh/` was denied by this cloud environment's enforced network policy (proxy CONNECT 403); this is a cloud limitation, not evidence that ntfy is blocked on the user's phones. Regression network failures use a local refusing proxy and never upload message contents. Play Protect/HSBC acceptance, live delivery and Android background endurance remain handset checks, not established by these tests.
