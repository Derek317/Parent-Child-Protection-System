# Local evidence and reliability validation

Validated on 2026-11-05 UTC (2026-11-06 Hong Kong), against the draft based on `50c7638`, before publication. The user subsequently authorized pushing these changes and building with the existing private GitHub Actions signing setup. Version/signing settings are unchanged.

## Final automated checks

```bash
env -u APK_KEYSTORE_PATH -u APK_KEYSTORE_PASSWORD -u APK_KEY_ALIAS -u APK_KEY_PASSWORD \
  bash tests/run_android.sh assembleDebug assembleRelease lintDebug testDebugUnitTest
```

- Exit 0: `BUILD SUCCESSFUL in 1m 40s`.
- 97 tests, 0 failures, 0 errors, 0 skipped across 13 test classes.
- Debug APK and unsigned release APK built successfully. Private release credentials were not used.
- Android lint: 0 errors, 54 warnings. These include unused UI resources, deprecated/discouraged API use, text-resource suggestions and synchronous preference-write suggestions. Required synchronous writes preserve crash-recovery/deletion ordering; warnings are not a claim of zero remaining maintenance work.
- `actionlint .github/workflows/build-apk.yml` and `git diff --check` passed.
- Both APKs were checked for preview-fixture identifiers/text; none were found. Synthetic records and the capture fixture were kept outside production sources. No new runtime dependency or Android permission was added.

Local log: `/workspace/.android-dev/evidence-final-validation.log`. Generated reports/APKs are under `/workspace/.android-dev/project/app/build/`.

## Regression coverage

- Retain more than the previous 60 records and full Unicode text; contact/body search, literal wildcard escaping, type/date/saved filters, stable pagination, deletion and replay tombstones.
- Preserve valid and malformed legacy data before family replacement; isolate families; retain chart totals on archive deletion; recover rotations interrupted on either side of the SQLite/preferences boundary.
- Bound encrypted frames below the relay body limit; preserve text through out-of-order durable reassembly; reject conflicting/malformed parts and retain encrypted work after upload failure.
- Require validated signed receipts for secure pairings; reject unsigned/wrong-family/malformed receipts; match event IDs exactly; handle out-of-order and explicit discarded receipts. Receipt waiting does not defer new alerts.
- Complete send callbacks after the actual attempt/defer/cancellation, including concurrent delivery. Network-failure tests use a local refusing proxy, not a real family's relay.
- Keep outgoing-DM exclusion, trusted/unapproved-contact behavior, existing security/rotation/permission/statistics tests, help acknowledgment, scoped private notification routing and UI state restoration.

A fresh independent whole-change review found five material issues and a callback issue. Regressions reproduced them before fixes; all are covered by the final passing suite.

## Native visual checks

Rendered the actual Android Views with Robolectric SDK 34 native graphics at 390 × 844 dp. Reviewed contact groups, thread previews, search, full selectable details and the help sheet; contact/thread views were checked in light and dark themes. Save/copy controls precede the long body and previews are limited to two lines. Captures use test-only synthetic data, not real children's messages.

Local previews: `/workspace/outputs/evidence-reliability-preview/`. The temporary capture test was removed from the generated test project before the final full suite.

## Remaining device checks and limits

No physical Samsung S26, Instagram, HSBC or Play Protect test was performed. No real-family/live-relay end-to-end delivery, battery/performance endurance or emergency-support service test was performed. Automated checks cannot establish security-app acceptance or complete message capture.

Before release, update both parent and child phones and test real supported Instagram screens/notifications, notification denial, network loss/reconnect, app restart, Android battery restrictions, permission revocation, long-text delivery, advice notifications and family/key rotation. Android can stop the app and controls all special access. The app cannot silently grant permissions or force a bank/Play Protect to trust it.

Retention is until explicit deletion in app-private local storage; uninstalling/clearing app data also removes it. Existing truncation or previously discarded records cannot be recovered. The archive stores captured text, not certified complete conversations. Signed receipts confirm parent-device storage, not adult review. Old pairings without a pinned parent key remain relay-confirmed only. Image/voice detection is assessed in `media-detection-feasibility.md` and is not implemented in this update.
