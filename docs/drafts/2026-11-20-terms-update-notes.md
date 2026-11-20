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
