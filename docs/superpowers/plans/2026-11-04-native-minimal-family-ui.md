# Native minimalist family UI implementation plan

Execution: Superpowers executing-plans, inline, with a fresh final review.
Spec: ../specs/2026-11-04-minimal-family-ui-design.md (approved in chat; publication authorised).

Goal: implement the approved role-first, sage/charcoal native Android UI with real app state and all existing functions.
Architecture: retain Activity/native Views and the single Kotlin source; package licensed fonts/icons as native resources. No WebView, Compose migration or runtime UI dependency. Existing GuardEngine/services remain unchanged except removal of the seeded example trust-list fallback requested by the user.

- [x] Add failing Android checks for role-first entry, protected role switching, bottom navigation, real empty state and no seeded trust accounts.
- [x] Package fonts/icons and theme resources; connect role-first setup, fixed status, bottom tabs and native transitions to existing handlers.
- [x] Rework overview/charts, alert details, family, child permissions/unlock and settings with real data; preserve all controls and drafts.
- [x] Run Android regression/rendering, contrast and workflow checks. Review diff against engine boundary; resolve significant review findings.
- [ ] Commit/push to main, inspect GitHub Actions and confirm release artifact.

Review focus: paired-child role gate; initial entry must not stop services; permission return/rotation/input focus; no sample accounts, unlock codes, counts, dates or connection states in runtime; dynamic chart data; all legacy recovery/unlock/allowlist/diagnostic controls retained; reduced motion and rapid navigation; native resource packaging.

Ledger:
- User approved the prototype and authorised code changes and GitHub push; no further approval round.
- Existing isolated cloud checkout is used; do not create an extra worktree.
- Keep prototype sample files out of the shipped Android source/resources. Test fixtures remain test-only.

- Review caught a retained parent sheet across pairing changes. Regression tests reproduced sheet/confirmation persistence and stale credential access; session-bound callbacks and tracked dialog dismissal fix it. The generated-password convenience is restored with a fresh random suggestion.
- Local verification: 26 Android tests pass, including real-state native rendering, role protection, permission routing, statistics and stale-dialog regression tests. Debug/release assembly succeeds. Four source/contrast checks and actionlint pass. Device/OEM permission behavior and banking-app acceptance still require real-device validation; this UI change does not change permission capabilities.
