# Visual review and release implementation plan

> **For agentic workers:** Use superpowers:executing-plans inline, followed by one fresh read-only whole-change review.

**Goal:** Make existing actions visibly interactive, repair visual defects, verify behaviour and publish the reviewed app with documentation.

**Architecture:** Preserve the existing native Android design and functions. Fix shared control helpers and the crowded conversation toolbar; include the already-tested, unpublished chat-reader repairs in the same release.

**Tech Stack:** Android Views, Kotlin, platform drawables, Robolectric, Gradle and GitHub Actions; no new dependencies.

**Spec:** The user's request: review visuals first, make buttons clearly interactive, then debug, push and release with documents. Preserve the approved minimalist family design and unrelated functionality.

## Global constraints

- Keep signed parent policy, child consent, family separation, encrypted relay and existing signing secrets.
- Keep all synthetic screenshot records in external test fixtures; never seed production data.
- Inspect actual native layouts in both themes, including 320dp width and 150% text.
- Use the next unused release version, 4.8; verify remote main before publishing, without force-pushing.

## Review focus

- Native actions must expose a button role and remain keyboard/accessibility operable.
- Secondary controls need visible boundaries and focus feedback in both themes.
- Narrow, enlarged-text filters must remain readable and independently tappable.
- A visible message-details cue must open the unchanged full record.
- Privacy and chronological-order limitations must remain available when shortening the thread introduction.

### Task: Repair and release the reviewed app

**Files:** Modify `MainActivity.kt` shared controls/conversation layout, add behaviour tests, update `.github/workflows/build-apk.yml` version, `README.md`, `docs/releases/4.8.md` and the visual/debug report. Preserve the pending chat-reader changes and tests.

- [x] Render and inspect existing native screens before production edits: role/setup, dashboard, family, settings, archive/thread/details and child permission/unlock; both themes, normal/narrow enlarged text.
- [x] Add failing tests for accessible button roles, discoverable record details, filter operation and retained ordering explanations.
- [x] Repair shared control boundaries/focus, message affordance and filter layout; run focused regressions and inspect new native renders.
- [x] Conduct one fresh read-only review, correct material findings with regressions and run the full Android build/lint/test and workflow checks.
- [ ] Write release notes, validation results and setup limits; commit verified files, push main/tag 4.8 and verify workflow/release assets and signing certificate.

Local verification complete: 181 Android tests, 22 workflow checks, 56 after renders, both builds and lint (0 errors / 57 warnings). The publication step is recorded after the remote workflow and assets are confirmed.
