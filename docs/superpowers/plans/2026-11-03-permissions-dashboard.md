# Permission and dashboard implementation plan

Execution: Superpowers executing-plans, inline, followed by a fresh review.
Spec: ../specs/2026-11-03-permissions-dashboard-design.md (approved).

- [x] Permission safety, role lifecycle, consent/settings guidance; regression checks.
- [x] Pure Kotlin weekly accounting, JSON persistence, replay and offline-alert integration; runnable JVM tests.
- [x] Fixed status/navigation shell, charts, input preservation and accessible colours.
- [x] Debug/release compilation, regression checks, independent review and device-test notes.

Constraints: one APK, full child capabilities, same single-file distribution, no changes to encryption/classification or release keys, no security evasion.
Review focus: role changes/delayed callbacks; permission denial/Settings return; replay/migration/reset; day boundaries/timezone; input preservation and large-text navigation.

Ledger:
- Approved in chat; proceed without further design approval.
- Session restarted on clean branch work at the same baseline; restored local plan and tools from recorded workflow.
- Use existing isolated cloud checkout; no extra worktree.

Review ledger:
- Fresh review identified UI network-lock contention, stale-role commands and non-atomic family changes; fixed by network/state separation, captured role/family generation checks and atomic token/history replacement.
- Navigation restore race reproduced (expected 0, observed 300) and fixed.
- Stale child reset reproduced (expected parent, observed unconfigured) and fixed.
- Regraded the stale allowlist draft and midnight reporting window as correctness issues; added focused checks and fixes.
- Device-specific OEM permission UI, HSBC/Play Protect acceptance, service binding and real TalkBack remain unverified; test matrix is in docs/permission-dashboard-validation.md.
- Preserved encryption/classification and unrelated existing background-service behavior.

Final validation: 18 JVM/Android tests passed (8 accounting, 9 Android permission/lifecycle/navigation, 1 native rendering), with zero failures/errors/skips. Four Python safety/contrast checks passed. Debug and release variants built; both APK signatures verified. Android 14 restricted-settings help was exercised in Robolectric. Light/dark native renders inspected. Local builds use development signing; physical Samsung/Play Protect/HSBC validation remains outstanding. No deferred code-review findings remain within this change's scope.
