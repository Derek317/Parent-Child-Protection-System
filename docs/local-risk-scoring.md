# Local risk scoring in the app

> **Superseded in part.** The approved design is `docs/superpowers/specs/2026-11-19-automatic-on-phone-scoring-design.md` and the plan is `docs/superpowers/plans/2026-11-19-scores-only-family-mode.md`. Where this note differs (90 messages, multi-window scoring, unknown-kind threads, the `risk_report` record), the design and plan win. The "Scoring contract" section here is still correct for a single window.

Build spec, 19 November 2026. Status: in progress on branch `app/local-risk-scoring`. Not released.

The shipped model is Beacon (see `research/results/stage4-report.md` on the `research/local-risk-model` branch, section "Beacon"). It was trained on invented chats, a small number of real transcripts that Derek supplied, windows from the PAN-12 research corpus and Derek's own ordinary chats. The real chats and the PAN-12 data are private, are not in the repository and must never be added, so Beacon cannot be rebuilt from the repository alone. Memorisation test by the developer on 20 November 2026: the shipped model file was prompted with the first half of chats and its free output compared with the true continuation. None of the checked text spans of the real conversations it was trained on was reproduced (0 of 334), the same as for conversations it never saw (0 of 1,020); for the developer's own everyday chats the small overlap (1.4% of spans) was about the same as for a model that had never seen those chats (1.1%), which points to guessable repeated lines rather than recall; prompts with no chat produced no match of 20 characters or more. Limits: one decoding setting and one prefix length; the PAN-12 conversations were not part of the test; it does not test whether someone could infer that a particular conversation was used in training; it cannot prove that the model never reproduces training text. A downloaded copy of the model cannot be recalled. On the real testing transcripts it flagged 7 of 10 grooming chats and 0 of 37 ordinary chat windows; on the handoff 90-chat set, 30/36 risk chats and 1/36 harmless chats. On a blind set of 500 PAN-12 conversations it flagged 176/200 predator conversations, 14/150 general ones and 78/150 adult sexual conversations with no predator: it does not separate adults' sexual chat from grooming. The blind set is from the same source and era as part of the training data, the small sets have been reused many times, and run-to-run spread is three to seven chats. Nothing here shows that it reliably detects real grooming.

## Decisions

| Decision | Who | Choice |
| --- | --- | --- |
| Where scoring runs | Derek | On the child's phone. |
| How the model file arrives | Derek | Inside the app package (Derek's decision, 20 November 2026): the APK published on GitHub Releases contains the model, which is also published on GitHub Releases as an asset of a separate release (`model-beacon`), so it is publicly downloadable. The release workflow is planned to package it with `tools/package_with_model.py` and the release key; the same script runs on Derek's PC ([local-model-package.md](local-model-package.md)). On first start on a child phone the app copies the model into its own storage (about 1.1 GB free needed on top of the installed app). Import by hand with a file picker is a recovery path only. No download code in the app; the model file is never in git, and ordinary `main` builds have none. |
| Model | Research | `qwen3-1.7b-beacon-Q4_K_M.gguf` (Beacon; chosen by Derek on 20 November 2026; the v3, v4 and v7 files are no longer accepted), 1,107,408,736 bytes, SHA-256 `5b873dbfa4c36fff10372f8c1cf28ae84182d6d41604bf8f8b83f21966debb5c`. Any other file is refused. |
| Engine | Research | llama.cpp tag `b12503` (commit `4577932fab595eaa8b27ad71734d5de1f3c07d8c`), built for `arm64-v8a` with the NDK. |
| Keyword detection | Derek | Unchanged. The model is an addition. |

## What changes on the child's phone

Today the child's phone keeps no readable chats: it queues encrypted records for the parent and deletes them on receipt. Scoring needs text, so this feature adds a **local scoring buffer**.

- **Buffer.** When risk scoring is on, each captured message (full two-way capture mode only) is also written to an encrypted table on the child's phone: conversation key, time, order, direction and text. Encryption is the same `LocalVault` field sealing the parent archive uses.
- **Limits.** At most the newest 90 messages per conversation (three windows) and at most 14 days. Older rows are deleted. The buffer is wiped when scoring is turned off, the model file is removed, collection is withdrawn, the role changes, the family key rotates or the phone is reset remotely.
- **On when.** Risk scoring is on only if all are true: consent accepted, role is child, collection allowed, full capture enabled, the verified model file is installed, and the scoring switch is on. The switch, the model import and removing the model sit behind the parent password on the child's phone. Installing the copy included in the app needs no password the first time (it is pinned by size and SHA-256); after a removal it is not reinstalled until a parent enters the password.
- **Which threads.** One-to-one only. A conversation marked `group`, or one with more than one distinct incoming author, is not scored. Screen captures often have kind `unknown`; those are treated as one-to-one unless a second distinct incoming author appears (Derek's decision, 19 November 2026; see the plan). Messages with direction `unknown` are left out of the prompt.
- **Speakers.** `outgoing` is `child`, `incoming` is `other`.

## Scoring contract

Must match the research harness exactly, or the published numbers do not apply.

1. **Prompt.** ChatML: system = `research/prompts/instruction-v1.txt` byte for byte (shipped as `ui-res/raw/risk_instruction_v1.txt`); user = `Chat window:\n` + one line per message `speaker: text` + `\n\nAnswer (0, 1, 2, 3 or U):`; then `<|im_start|>assistant\n<think>\n\n</think>\n\n`. Message text has runs of whitespace collapsed to one space and is trimmed, using Python's `str.split()` whitespace set. At most the newest 30 messages.
2. **Tokens.** No BOS added; special tokens parsed.
3. **One forward pass, no sampling.** Read the next-token logits of token ids 15, 16, 17, 18, 52 (`0 1 2 3 U`) and take a softmax over those five. At load the native code checks those ids decode to exactly those strings and refuses the model otherwise.
4. **Scores.** alert = p2 + p3, flagged at 0.5 or more. Report score = 100·(p1 + 2·p2 + 3·p3) / (3·(p0 + p1 + p2 + p3)). If `U` is the most likely answer there is no score and the review reason is `insufficient_context`. Ties go to the earlier answer in the order `0 1 2 3 U`.
5. **Long threads.** Only the newest window is scored: the newest 30 buffered messages. Older buffered messages are kept up to the buffer limit but are not scored. The thread's report score comes from that one window; if `U` is the most likely answer there is no score and the thread is "needs review" (`insufficient_context`). A prompt that does not fit the context is cut down before it is scored (`RiskFit` in `RiskModel.kt`): the oldest messages are left out one at a time down to 10, then every message but the newest is shortened (a character cap halved each try, finally leaving them out). The instruction, template and answer tokens do not change. Only a newest message that does not fit by itself gives `context_too_long`.

## When scoring runs

A `JobScheduler` job on the child's phone, only while the device is idle and charging, at most a few times a day. It re-checks every "on when" condition at the start and between conversations, verifies the model file's size and SHA-256 before loading it, scores conversations that have new messages since their last score, and stops if the session changes.

## What leaves the child's phone

A small `risk_report` control record over the existing end-to-end encrypted relay:

- status: `scoring`, `idle`, `model_missing`, `model_mismatch` or `off`
- model name and revision
- per conversation: the conversation key (the same hash the parent's archive already uses), score or null, review reason or null, flagged, scored-at time

No message text, no names, no quotes. The model file never leaves the phone it was installed or imported on.

## Parent side

- A `risk_scores` table in the parent's database, deleted with the same rules as the archive (retention, delete conversation, delete archive, key rotation, other-archive cleanup).
- A score page: one plain answer first (how many conversations need attention), then rows showing band, score and date only. States: locked, empty, child still scoring, model not installed on the child's phone, model file wrong, error.

## Phone test (20 November 2026)

On two real phones: a package with the model installed, the first-start copy of the included model out of the APK worked, and both collection methods worked end to end. On a 2018 phone reporting 5.8 GB of memory, scoring completed with about 1.7 GB peak app memory (one phone, a few short chats; not a guarantee).

Still not verified: the natural schedules (the 15-minute check-in and the idle-and-charging scoring job were started by hand, so whether and how often they run on their own was not observed), importing the owner's real Instagram export, any device beyond those two, speed, battery cost and heat, a 1.1 GB import through the system file picker, and screen readers.

## Open before any release

- The release workflow step that fetches the model asset, checks it and packages it is planned, not shown to have run.
- Licences: llama.cpp is MIT and the Qwen3 1.7B base model is Apache-2.0 (checked at the model's Hugging Face page on 20 November 2026); see [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md). The published model file carries no notice of its own.
- The legal questions about publishing a model trained partly on real conversations and on PAN-12 are in `docs/drafts/2026-11-20-terms-update-notes.md`.
