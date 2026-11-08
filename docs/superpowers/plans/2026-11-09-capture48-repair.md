# Instagram capture 4.8 repair plan

> Use superpowers:executing-plans for native execution. The user authorizes implementation, push and replacement of release4.8.

**Goal:** Repair foreground DM capture, peer attribution and two-way capture without changing the working Cloudflare transport or unrelated features.
**Architecture:** Retain the existing accessibility reader, signed policy/local consent and chronological SQLite archive. Fix selectors and event scheduling at their existing boundaries.
**Tech stack:** Kotlin, Android accessibility/notifications, Robolectric, existing signed GitHub workflow.
**Spec:** User's report: enabled settings but missing foreground messages/two-way capture, own Instagram name used as room name, outdated ntfy disclosure. Prior consent and privacy boundaries remain requirements.

## Constraints and review focus

- No relay protocol, signing-key, archive schema or unrelated UI changes.
- Do not enable two-way capture without signed parent policy and child confirmation.
- Prefer peer header anchored to the chat toolbar over the account/inbox title; ambiguous names remain unknown.
- Support localized/raised composers without collecting unsent DM drafts, feeds or other applications.
- Debounced updates must eventually scan, and delayed callbacks must not cross family/role boundaries.
- Existing chronology, outgoing/incoming classification, anti-tamper and evidence must remain intact.

## Task 1: Capture and setup repair

- [x] Add failing reader regressions for account/peer headers, clickable headers, raised/localized/noneditable composers, coalesced content updates and quoted mute-setting text.
- [x] Verify actual queued signed policy exchange and local consent. User confirmed two-way mode already enabled, so preserve the existing setup algorithm; reinstall-policy recovery is outside this repair. Add notification self-person/peer-title regressions.
- [x] Fix only reproduced defects and update Cloudflare disclosure.
- [x] Run focused tests, full Android tests/build/lint and release checks. Request independent review; fix important findings with regressions.
- [x] Push source, run signed4.8-hotfix.N workflow, verify published checksum/certificate/version/endpoint and update repair report.

Completed: source dfb95fc/tag4.8-hotfix.3, successful signed workflow42506645535, APK4.8/code161 independently verified. See [capture follow-up report](../../capture-4.8-followup-report.md). No physical Samsung/Instagram test is asserted.
