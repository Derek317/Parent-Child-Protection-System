# Notes for Derek: Terms 2026-11-20-v5 update

Written 20 November 2026 by Claude. Not legal advice. No lawyer has read the new text. Nothing is committed; the changes are in the working tree for you to read.

## What changed, and where

- `docs/ig-guard-terms-of-service.md` (bundled in the app, hashed into each receipt)
  - Header: Terms revision `2026-11-20-v4` → `2026-11-20-v5`. Date and release lines unchanged.
  - Section 2 (both languages): one sentence saying the forwarding does not apply in the scores-only mode; one paragraph on the 15-minute background check-in.
  - New section 2A "AI risk analysis (scores-only mode)": who decides, what happens, what the parent receives, the two-way capture options, the child's pause and withdraw, switching off, deletion triggers, score retention on the parent phone, the other person in each chat.
  - New section 2B "Collection method": automatic capture with your approved warning quoted word for word (Chinese in the Chinese half, English in the English half), manual upload with its limits, the capture hold.
  - New section 2C "The model": Beacon, how it arrives, the memory limit (about 5 GB reported), training material, the open legal question, limits, 999.
  - Section 4: one pointer sentence to 2A.
  - Section 6: "No new model runs in the stable app" replaced (it is no longer true).
  - Sections 1, 3, 5 and 7 are not changed.
- `AppConsent.kt`: only `VERSION` → `2026-11-20-v5`.
- `docs/ig-guard-privacy-policy.md`: header revision; one sentence in "Initial choice"; "What IG Guard does" (forwarding exception, background check-in, model sentence); new section "AI risk analysis (scores-only mode, optional)"; three new table rows; one paragraph on pause and withdraw; two new retention bullets.
- `docs/ig-guard-data-deletion.md`: header revision; new section "AI risk analysis (scores-only mode): pause, withdraw and scores"; "Delete all evidence" now says it also removes AI risk scores; clearing app data now lists the store, agreement record and model copy.
- `docs/privacy-data-flow.md`: kept as the dated 4.9-beta.3 description; added one boxed note at the top that it does not cover the new mode and pointing to the three live documents.
- `README.md`: three sentences that became false or incomplete (text reaching the parent, capture method, "no new model running").
- `CHANGELOG.md`: one new entry at the top; the memory limit in the existing Beacon line corrected from "about 8 GB" to "about 5 GB reported (phones sold with less than 6 GB)".

## Decisions you should check

- **Notice, not re-consent.** A new revision shows existing users a one-time "條款已更新" notice and keeps their old receipt; nobody is asked again. I did not change that. This revision adds a whole new mode, the Instagram-terms warning and the use of real conversations in training. You may decide this is material enough to ask both phones to accept again. That would be a code change (not made). Note that the scores-only mode itself already needs the child's own separate agreement before anything happens.
- **Confirmation 2 in section 7** still says "encrypted forwarding". In the scores-only mode no text is forwarded. I left the three confirmations alone because they are tied to the receipt; check whether they still fit.
- **Responsibility wording.** I quoted your approved warning exactly, then added one sentence: the warning tells the user about a risk, does not by itself remove anyone else's legal responsibility, and does not change sections 5 and 6. I did not write any new limit on the developer's liability.
- **Open legal question stated inside the Terms.** The Terms and privacy policy say plainly that the lawful basis and consent for training on real conversations is an open question for a Hong Kong privacy lawyer. You may prefer to keep that only in the privacy policy.
- **Memory limit.** Written as "less than about 5 GB reported, in practice phones sold with 6 GB or more", per your decision passed on by the coordinator. The code now has `MIN_MEMORY_BYTES = 5 GiB`. If that code change is not kept, the documents are wrong.
- **Group chats.** I kept the in-app wording: a group chat read from the screen may be analysed; a chat known to be a group is not.
- **Default method.** The parent's sheet preselects Manual upload. The documents do not say which is the default; add it if you want.
- **Fixed on the reviewer's request:** Terms section 3, second paragraph (both languages), and the first-run summary in `AppConsent.kt` no longer say the relay cannot delete a room or that registrations never expire; they now match the fourth paragraph and the privacy policy.
- **Old CHANGELOG lines left as history:** the 19 November entry still says "nothing below has run on a physical phone" and "the live documents are unchanged". Both are no longer true. I did not rewrite them; the new entry supersedes the second.

## Checked in code (and therefore stated)

- Switch-on blocked until the child reports a valid model; child agreement on own phone; nothing changes before (`RiskLocal`, `ScoresUi.enableBlockedWhy`).
- Fresh parent device authentication for switching on, off and changing method (`MainActivity.addMethodChooser`, `confirmScoresOff`).
- Analysis only while charging (idle + charging job; charging-only fallback after 48 h) (`RiskScoringSchedule`).
- 60 messages, 14 days, scored on the latest 30 (`RiskLimits`, `RiskModelSpec.WINDOW`).
- Capture method needs the confirmed two-way scope to run (`RiskLocal.active` uses `fullCaptureEnabled`); the scope choices filter what is stored (`captureObservationAllowed`).
- Upload method stores nothing from notifications or screen (`captureMessages` returns early on `RiskCapture.UPLOAD`).
- Import: streamed, not copied, one-to-one only, groups skipped, 60/14-day limits (`InstagramExport.kt`).
- Wipe triggers: switch-off (`receivePolicy`), withdraw, model removal (`RiskModelStore.remove`), method change on agreement (`accept`), child stop (`withdrawNow`), role change (`setRole`), family token change (`setFamilyToken`), room deletion (`purgeChildFamily`). Pause does not wipe. Queued reports kept except when the family ends (`resetFamily`).
- Capture hold and the parent being told (`CAPTURE_HELD`, `PARENT_CAPTURE_HELD`).
- Parent-phone scores expire with the archive retention from scoring time, and "Delete all evidence" deletes them (`FamilyEvidence.pruneRisk`, `deleteArchive`).
- 15-minute background check-in (`ArchiveMaintenance.backgroundTick`, `HEARTBEAT_ID`).
- Model file name, size, SHA-256 check (`RiskModel.kt`, `RiskModelStore`).

## Not confirmed in code, so left out

- Exactly how the parent's "highest score in 14 days" is shown; I said only "a score".
- Speed, battery and heat on real phones, and how often charging windows occur.
- The phone test result itself (one phone, 5.8 GB, about 1.7 GB peak) is not quoted in the user documents; it is in the code comment only.
- Training-data facts (Beacon's sources) come from you and `docs/local-risk-scoring.md`, not from code; they cannot be checked in code.
- Whether Cloudflare logs or backs up score reports (same as the existing "provider records unverified" wording).

## Open items for the Hong Kong lawyer

- **Real conversations in training.** Beacon was trained partly on real conversations you supplied and on your own ordinary chats, including the other people in them. What is the lawful basis, and what consent is needed? Kept marked open in every document.
- **PAN-12.** The corpus has no stated licence on its record. Can it be used to train a model handed to families?
- **The automatic-capture warning and "responsibility".** Does the warning, plus the parent's tick box, do what you intend? It informs the user; it does not by itself move the developer's own legal position. Does it need to change, and should the developer offer automatic capture at all given Instagram's terms?
- **The teen's consent and age.** Is a one-time agreement on the child's phone by a 13 to 17 year old adequate, and is the page wording right?
- **The other person in each chat.** Their messages are stored and analysed and their account label reaches the parent; they are never asked.
- **Re-consent.** Is a one-time "Terms updated" notice enough for a change this size, or must both phones accept again?
- **Section 52 and the developer.** Who is the data user for the child-side store, the score reports and relay metadata?
- **Manual-upload files.** The parent cannot verify completeness; does that need more than the plain statement now in the Terms?

## Not changed because outside what I may edit (please update)

- `docs/local-storage-security.md` (last paragraph) and `docs/releases/4.9-beta.4.md` (line 42) still say "No new model ... in the stable app". Both are now untrue.
- `docs/local-risk-scoring.md` line 60 says the native library "has never run on a device"; it has now been phone-tested.
- In-app texts (Kotlin, not mine to edit): the child consent point "分數可能出錯 / Scores can be wrong" and `ScoresUi.LIMITS_NOTICE` say the model was "tested mostly on invented chats and on a small number of real ones". That is narrower than the Terms (PAN-12, own chats, cannot tell adult sexual chat from grooming). Consider aligning.

---

# Pass 2 (20 November 2026, later): the model ships inside the released app, Terms 2026-11-20-v6

Written by Claude. Not legal advice; no lawyer has read this text. Nothing is committed.

## Owner decisions recorded in this pass

- **Morning decision:** the model ships inside the app package; first start on a child phone copies it into private storage; manual import is a recovery path; distribution to families undecided.
- **Later the same day, replacing the "undecided / never publish" part:** the APK with the model inside is published as a public GitHub release, signed with the release key in the repository's GitHub secrets, and the model file is also published on GitHub Releases as an asset of a separate release (`model-beacon`) so the release workflow can fetch, check and package it. This is against the earlier plan, in which the packaged APK was never to be uploaded or shared. The git repository still does not contain the model file; ordinary `main` builds still have no model.
- **Notice only, no re-consent** for this revision (owner's decision today).

## What changed in this pass

- `docs/ig-guard-terms-of-service.md`: revision `2026-11-20-v5` → `2026-11-20-v6`. Section 2C (both languages): the model is not in the source repository; the app released on GitHub Releases includes it and the model file is published as a release asset, so anyone can download it; other builds have no model and AI risk analysis cannot be used on them; first start copies it into private storage (about 1.1 GB free on top of the installed app); manual import is a recovery path. Training paragraph: kept, plus "including text written by other people", and a new sentence that public distribution makes the untested reproduction risk greater and a downloaded copy cannot be recalled. Section 6 (both languages): "The GitHub build contains no model" replaced. Nothing else in the Terms changed.
- `AppConsent.kt`: only `VERSION` → `2026-11-20-v6`.
- `docs/ig-guard-privacy-policy.md`, `docs/ig-guard-data-deletion.md`, `docs/privacy-data-flow.md`: revision references to v6. Privacy policy: the same model facts and risk sentence as Terms 2C; the "GitHub build contains no model" sentence replaced. Deletion guide: a downloaded copy of the release APK or model file is not removed by clearing app data.
- `README.md`: model paragraph rewritten (release carries the model, publicly downloadable, `main` builds have none, recovery import, Google Play note, link to `THIRD-PARTY-NOTICES.md`).
- `docs/local-model-package.md`: no longer says "never upload / never share"; describes the planned release workflow and the local script as the same package; keeps the instruction never to add the real chats, PAN-12 data or training files anywhere; Google Play note; phone-test results and what was not tested.
- `docs/local-risk-scoring.md`: "How the model file arrives" row; "has never run on a device" replaced by the phone-test results and the precise not-verified list; stale "Open before any release" items replaced.
- `docs/signing.md`, `docs/local-storage-security.md`, `docs/releases/4.9-beta.4.md`: "never uploaded" and "No new model ... in the stable app" replaced. The release note also carries the Google Play note.
- `CHANGELOG.md`: one new entry at the top; old entries untouched.
- New `THIRD-PARTY-NOTICES.md`: llama.cpp (MIT, text copied from the b12503 checkout) and Qwen3 1.7B (Apache-2.0, verified at https://huggingface.co/Qwen/Qwen3-1.7B and its `LICENSE` file; Beacon described as a fine-tuned, 4-bit quantised modified work).

## Decisions for you

- **The release workflow is not changed by this pass** (`.github/` was out of scope). Until a release actually contains the model, the documents describe a planned state: Terms 2C says the released app includes the model. Do not tag a release with the v6 Terms before the workflow change lands, or the Terms will be wrong for that release.
- **Licence notices inside the APK and the release.** The MIT and Apache licences ask that the notice / licence text travel with the copies. `THIRD-PARTY-NOTICES.md` is only in the repository. Consider attaching it to each release and adding it to the in-app licence text (a Kotlin/`ui-res` change, not made). Apache-2.0 4(b) also asks for a prominent statement that the files were changed; the notices file says so, but the GGUF itself carries no such statement that I could confirm.
- **Re-consent.** Publishing the model is a material change to who can obtain material trained on real conversations. You chose notice only; that choice is recorded, not endorsed.
- **Google Play.** Play Console Help (support.google.com/googleplay/android-developer/answer/9859372, checked 20 November 2026): apps published as APKs are limited to 100 MB; app bundles allow a 500 MB base module, 1.5 GB per asset pack and 4 GB in total for install-time content. A Play listing would need an app bundle with an asset pack (not built).

## Not confirmed, so left out or marked planned

- That the release workflow fetches, checks and packages the model: planned, not in this tree.
- Which phone models were tested, how long the first-start copy takes, battery and heat.
- Whether the natural 15-minute check-in and idle-and-charging job run as expected (started by hand in the test).
- A 1.1 GB import through the system file picker, and screen-reader behaviour on real phones.
- Whether the Beacon GGUF metadata names its licence or base model.

## Added to the lawyer list

- **Publishing the model.** On 20 November 2026 the owner decided to publish Beacon inside a public GitHub release, with the model file also attached as a release asset, against the earlier plan to keep it private. Risks, stated plainly: (1) the weights were trained partly on real conversations, including messages written by other people who never agreed, and on PAN-12, whose record states no licence; (2) a trained model can sometimes reproduce fragments of its training text, and this has not been tested for Beacon; (3) once downloaded, a copy cannot be recalled or deleted, so a later decision to withdraw it cannot undo the release. Is publication lawful in Hong Kong (PDPO) and wherever it is downloaded, and what would have to be done first (for example an extraction test, removal of the real conversations from training, or consent)?
- **Distribution to families generally.** Even apart from public release, handing a model trained on these sources to families means it leaves the developer's PC. Is that a disclosure of personal data or a new use under the PDPO?
- **PAN-12 terms for redistribution of a derived model.**
- **Licence compliance for the published APK and model** (MIT notice, Apache-2.0 sections 4(a) and 4(b)).
## Addendum: version 5.0 and the release workflow (same pass)

- **Version 5.0** (owner, 20 November 2026; tag `5.0`, normal GitHub release). The Terms header "版本 / Release" line, the privacy policy and deletion guide headers, `README.md`, `docs/local-storage-security.md` and `docs/cloudflare-relay-setup.md` now name 5.0. New `docs/releases/5.0.md`. The new CHANGELOG entry is headed "5.0". `docs/releases/4.9-beta.4.md` is left as that version's history; only its model sentence was reworded to "No model runs in 4.9-beta.4" with a pointer to the 5.0 notes. `tests/check_ui.py` now checks `docs/releases/5.0.md` in place of the 4.9-beta.4 notes and that the CHANGELOG has one `## 5.0 ` heading.
- **Where the model asset lives.** Another agent's uncommitted workflow change (seen read-only in `.github/workflows/build-apk.yml`) fetches the model from a separate GitHub release named `model-beacon`, checks it against `RiskModel.kt`, packages and signs it, and fails the release rather than publish an APK without the model. The documents say the model is published as an asset of that separate release. They still say the workflow step is planned and not shown to have run, because I did not see a run.
- **Licence notices in the app.** The same uncommitted work adds the llama.cpp MIT text and a Qwen3 / Beacon notice to `ui-res/raw/ui_asset_licenses.txt`, with a link to the Apache text rather than the full text. Open: whether a link meets Apache-2.0 section 4(a), and the `model-beacon` model file carries no notice at all. Consider attaching `THIRD-PARTY-NOTICES.md` (or the Apache text) to the `model-beacon` release.
- **Speed fixes** in the 5.0 notes are taken from commit messages 2a656e3 and 1b603ce, not re-tested.
- **Not confirmed:** that the release APK keeps the same signing certificate as earlier installed builds; the notes say only that an upgrade keeps data when the key matches.
- **Update, 20 November 2026 (memorisation test; replaces "has not been tested" in the Terms 2C, privacy policy, 5.0 notes, CHANGELOG, local-model-package.md and local-risk-scoring.md; the Terms revision id is unchanged):** the developer prompted the shipped model file with the first half of chats and compared its free output with the true continuation. Real training conversations: 0 of 334 checked spans reproduced; conversations it never saw: 0 of 1,020; the developer's own everyday chats: 1.4% of spans overlapped against 1.1% for a model that never saw them (guessable repeated lines, not recall); prompts with no chat gave no match of 20 characters or more. Limits: one decoding setting and one prefix length; PAN-12 not tested; no test of whether someone could infer that a conversation was used in training; it cannot prove the model never reproduces training text. The lawful-basis and consent question stays open for a Hong Kong privacy lawyer.
