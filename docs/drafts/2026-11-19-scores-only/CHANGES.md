> **DRAFT, not in force.** Written on 19 November 2026 for the scores-only family mode. It must be reviewed by Derek and by a Hong Kong privacy lawyer before release. It is not bundled in the app, and nothing in it claims approval by Meta or legal clearance.

# Change summary for the scores-only draft documents

Sources: the approved design (`docs/superpowers/specs/2026-11-19-automatic-on-phone-scoring-design.md`), the plan (`docs/superpowers/plans/2026-11-19-scores-only-family-mode.md`, builder decisions 1-6), `research/results/stage4-report.md`, and the live documents. The live documents were not edited. The mode is not built, so every passage about it is a description of the design, to be checked against the build.

Conventions in the drafts: a second blockquote under the banner says this; text in `[square brackets]` is an open point to settle before publishing, not wording to publish.

## 1. terms-of-service.md

Bilingual (Traditional Chinese, then English). Every change is written in both.

| Where | Change | Why |
| --- | --- | --- |
| Header | "Updated" set to 19 Nov 2026 draft; "Terms revision" set to "to be assigned at release" with the live value shown. Release line (4.9-beta.4) left alone. | The revision constant `AppConsent.VERSION` and the hash of the bundled file change when the Terms change. |
| 2 Conditional forwarding | One sentence added: in the scores-only mode message text is not forwarded. | Section 2 says text is forwarded; the mode is an exception. |
| New 2A Scores-only family mode | New section (placed after 2 so no later section is renumbered) covering: optional, off by default, parent switches on, child agrees once on the child's phone, nothing stored or analysed before that, parent cannot override, earlier mode keeps running meanwhile; what is analysed (one-to-one only; group and unknown-type threads not analysed, counted); analysis on the child's phone with a hand-installed model (size and SHA-256 checked), idle and charging (charging only after 48 h), no chat text to any server or parent; about 8 GB memory; the new encrypted store (60 messages per conversation, 14 days, 30 messages per scoring, highest score in 14 days, deleted on withdrawal); what the parent receives; the child's controls (status, pause, withdraw); no fallback to text after withdrawal; existing families (new text stops, archive stays until the parent deletes it); the other person as a data subject; model limits in plain words; relationship with Meta in the project's existing wording. | Required content from the design and plan (decisions 1, 3, 4). |
| 4 Initial choice and stopping | One sentence pointing to 2A for pause and withdraw. | Section 4 describes the existing stop control only. |
| 6 Service and requests | The sentence "No new model runs in the stable app ..." replaced: the app includes no model; a hand-installed model runs only in the mode; limits in 2A. | The old sentence would be untrue once the mode exists. |
| 1, 3, 5, 7 | Not changed. | See questions 1, 2 and 7 below. |

## 2. privacy-policy.md

English only, as the live policy is.

| Where | Change | Why |
| --- | --- | --- |
| Header | "Updated" set to 19 Nov 2026 draft; Terms revision note. The 4.9-beta.4 and branch wording left alone. | |
| Initial choice on each phone | New paragraph: the mode has its own agreement by the child, separate from the three confirmations. | The Terms receipt is hashed; the mode's agreement is a separate receipt (design step 3, plan task 3). |
| What IG Guard does | Forwarding sentence qualified for the mode; "No new model runs in the stable app" replaced. | Would be untrue. |
| New section "Scores-only family mode (optional)" | Same facts as Terms 2A, in the policy's wording, plus how the parent's phone keeps scores. | Required content. |
| "Information handled" table | Three rows: the child-side store; scores, content-free alerts and status; the child's agreement record. | The table lists every kind of information and who holds it. |
| Scope choices and withdrawal | One sentence: Pause and Withdraw exist in this mode. | |
| Storage, retention and deletion | Two bullets: child-phone store; parent-phone scores. | Retention list must cover the new store. |
| Family use and Hong Kong law | One clause: other correspondents' messages are held on the child's phone for up to 14 days and their account label reaches the parent. | Factual statement about the other data subject; no conclusion drawn. |
| Everything else | Not changed. | |

## 3. privacy-data-flow.md

| Where | Change | Why |
| --- | --- | --- |
| Header | Added that the mode is added in this draft and that its passages describe the approved design, not inspected code. | The document is otherwise "code-backed". |
| Intended use; Meta paragraph | One sentence each: no text forwarded in the mode; same capture, no new Meta connection. | |
| "Where information goes" | The existing table is untouched. After the "Encrypted traffic" paragraph a sentence says the captured-text categories are not sent in the mode. A new section "Scores-only family mode (optional)" has its own stage table (model import, mode switch, child's agreement, child-side store, scoring, report, alerts, parent view, pause and withdraw) with who has access, and notes on `conversation_kind`/`conversation_label`. | Plan decisions 1-2. |
| Retention and deletion | A drafting note flags the stale older bullets (section 6 below). Four bullets added at the end: child store; parent scores and state; reports in transit; child's agreement record. | |
| Closing paragraph | One sentence: the HK assessment does not cover this mode. | |
| Everything else, including the 4.9-beta.3 statements | Not changed. | Instruction: do not silently fix unrelated sections. |

## 4. data-deletion.md

A change is needed, so a draft was written. The live document covers only the parent's archive, the child's outbox and the existing stop control; it says nothing about a store of readable messages on the child's phone or about scores on the parent's phone. Changes: "Updated" line; new section "Scores-only family mode: pause, withdraw and scores" (child's Pause and Withdraw, scores staying on the parent's phone, how to delete them, Safe Guard not holding the store); one clause in "Remove IG Guard data from both phones" (clearing the child's app data also removes the store and the agreement record). The parent's existing archive deletion controls are unchanged and are pointed to. Menu names and the parent's score-deletion control are placeholders because the screens do not exist yet.

## 5. Not drafted

- `docs/local-storage-security.md` was outside the request. It will need a paragraph on the new store (same vault keys, 60 messages, 14 days), and its last paragraph says "No new model is introduced into the stable app", which the mode makes untrue.
- `docs/hong-kong-privacy-assessment.md` does not cover the mode (it predates two-way capture and the model). A new section is needed; its item 7 already says to reassess if research/training use or broader capture is introduced.
- `README.md` and `CHANGELOG.md` were not touched. The plan lists `CHANGELOG.md` under task 7.
- `docs/local-risk-scoring.md` (an earlier build spec) conflicts with the approved design: it says 90 messages per conversation (design: 60), treats unknown-kind screen captures as one-to-one (design: not stored or scored), and lists more cases where the buffer is wiped (scoring switched off, model removed, collection withdrawn, role change, family key rotation, remote reset). The drafts follow the design. The extra wipe cases are not in the design or plan, so the drafts leave "other cases" as a bracketed point.

## 6. Open legal questions for the lawyer

1. **Cap. 486 and the developer.** Section 52 covers personal data held by an individual for personal, family or household affairs. The design says it covers the parent, not the developer. Who is the data user for the child-phone store, the reports and the relay metadata, and does the new 14-day store on the child's phone change that answer?
2. **The other person in each conversation.** Their messages are held for up to 14 days and their account label is sent to the parent. They are not asked. What notice, if any, is needed, and does the analysis of their messages raise a purpose or collection issue (DPP1, DPP3)?
3. **The child's agreement.** Is a one-time agreement by a child (12 to 16 is the intended age in the assessment) adequate, how should the page be worded, and can a parent's switch-on be the condition that starts the process at all? The drafts say only what the design says.
4. **Existing Terms receipts.** The Terms file is hashed into each receipt and existing users are never asked again. Do parents on both phones have to re-accept changed Terms, or is the child's separate agreement sufficient? If the Terms change, `AppConsent.VERSION` must change; whether existing users should be prompted is open (plan, "Terms receipt").
5. **Terms confirmation 2** says users agree to "encrypted forwarding" of message text. In the mode no text is forwarded. Does the confirmation need to change, or does 2A suffice? (Section 7 was left unchanged.)
6. **Instagram's Terms of Use** restrict automated collection. The app has no Meta approval and the README says so; the mode relies on the same capture and stores more of the captured text on the child's phone. Does storage and analysis change the position?
7. **Google Play accessibility policy.** Not checked against current wording. Does analysing captured text, not only forwarding it, change eligibility?
8. **Retention.** Is 14 days (store and score) proportionate, and do scores on the parent's phone need a fixed limit? Does the s52 position make the archive's 30-day default irrelevant for the parent?
9. **Content-free alerts still name the contact** (account label) and the rule category. Is the category itself sensitive enough to need a notice? The design asks Derek to confirm the contact label.
10. **No text in an emergency.** The design records that parents cannot read messages even in an emergency. Is any additional notice or a route for disclosure to authorities needed?
11. **Wording on model limits.** Are "tested mostly on invented conversations and on a small number of real ones" and "no promise about detection accuracy" sufficient, given the third-style test (112/120 risk, 6/55 benign flagged) and the real testing chats (6 of 10 grooming chats and 0 of 37 ordinary windows flagged, not a fresh test because they also helped choose the model)? The drafts quote no accuracy figure on purpose.
12. **OPEN ITEM: real conversations used in training.** The shipped model (v7, confirmed by Derek on 19 November 2026) was trained mostly on invented conversations and partly on a small number of real conversations supplied by the developer. They are not in the app or the repository, and the model file does not contain readable chats. What is the lawful basis, and what consent is needed (including from the other people in those conversations), under Hong Kong privacy law? The privacy-policy and privacy-data-flow drafts state the fact and mark this open; nothing in them claims it is cleared.

## 7. Gaps in the drafts that Derek or the builder must fill

- Whether the store is deleted when the parent switches the mode off, when the model file is removed, or when app data is cleared (the plan states only withdrawal). Whether pause keeps stored messages.
- Whether the 30-day archive default, or any deletion rule, applies to scores and state on the parent's phone (plan task 4 says "the archive's deletion rules").
- What the child's agreement record contains and how long it is kept.
- Where the imported model file is kept and whether clearing app data removes it.
- Names of the screens and controls (child status page, parent score deletion).
- The terms revision string, and removal of the banner and drafting note before anything is bundled (the banner would otherwise change the hash).
- The 8 GB wording: plan decision 5 treats 7 GB or more reported memory as "about 8 GB". The drafts say "about 8 GB".
- Plan decision 6 (notification once per conversation per 14 days, no contact name) appears only in the data-flow draft.
- Derek decided on 19 November 2026 that unknown-kind (mostly screen) threads are analysed while only one other person appears; the drafts now say so, and say that a group seen only on the screen may be analysed because the screen capture records no incoming author.
- Version: the plan says 4.9-beta.4 is not changed; the drafts keep that.

## 8. Things in the live documents that were already inaccurate before this feature

Checked against `AppConsent.kt` (`VERSION = "2026-11-18-v2"`), `FamilyEvidence.kt` (default 30-day retention), `relay/src/worker.js` (`TTL` of 3 days, `REGISTRATION_TTL` of 90 days), the build workflow (`versionName "4.9-beta.4"`) and `MainActivity.kt` (the scope screen **擷取範圍與停止**). Not fixed in the drafts, except for a drafting note in the data-flow draft.

1. **privacy-data-flow.md is on an older release.** It describes 4.9-beta.3 and Terms revision 2026-11-16-v1; the source is 4.9-beta.4 and 2026-11-18-v2.
2. **Data-flow retention list is outdated** against the Privacy Policy, Terms and code:
   - "Parent evidence has no automatic expiry" (code: 30 days by default, 90-day preservation).
   - Child queues retained "without a fixed time expiry" and "can remain indefinitely" (now 72 hours).
   - Relay packets expire "seven days" after queuing (now 72 hours; the code TTL is 3 days).
   - Registration fields have "no automatic expiry or deletion route" (now 90 days idle retirement and authenticated purge).
   - "The scope screen currently has no separate switch to withdraw expanded capture" (the **擷取範圍與停止** stop control exists).
3. **Privacy-policy header** says the notice is "on `security/hardening-2026-11-17`"; this work is on `app/local-risk-scoring`. `local-storage-security.md` carries the same branch name.
4. **hong-kong-privacy-assessment.md** is dated 6 November 2026 and still discusses the ntfy relay and ntfy documentation; the relay is now Cloudflare. It also says outgoing DMs are excluded from alerts, which is true only for the baseline mode (two-way capture exists). It records that live legal sources could not be retrieved, so its statutory text is unverified.
5. **"No new model runs in the stable app"** appears in the Terms (section 6), Privacy Policy and `local-storage-security.md`. It is accurate today and becomes inaccurate with this feature; the Terms and Policy drafts fix it, `local-storage-security.md` is not drafted (section 5).
