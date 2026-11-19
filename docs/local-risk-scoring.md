# Local risk scoring in the app

> **Superseded in part.** The approved design is `docs/superpowers/specs/2026-11-19-automatic-on-phone-scoring-design.md` and the plan is `docs/superpowers/plans/2026-11-19-scores-only-family-mode.md`. Where this note differs (90 messages, multi-window scoring, unknown-kind threads, the `risk_report` record), the design and plan win. The "Scoring contract" section here is still correct for a single window.

Build spec, 19 November 2026. Status: in progress on branch `app/local-risk-scoring`. Not released.

The model was tested mostly on invented chats and on a small number of real ones (see `research/results/stage4-report.md`, section "Retrain with real chats added: v7"). On the real testing chats it flagged 6 of 10 grooming chats and 0 of 37 ordinary chat windows; those chats were also used to choose the model, so this is not a fresh test. It fails the handoff 90-chat set (27/36 risk chats flagged, 5/36 harmless chats flagged). Nothing here shows that it reliably detects real grooming. v7 was trained partly on a small number of real chats that Derek supplied; they are private, are not in the repository, and so v7 cannot be rebuilt from the repository alone. v7 was not run on the sealed set.

## Decisions

| Decision | Who | Choice |
| --- | --- | --- |
| Where scoring runs | Derek | On the child's phone. |
| How the model file arrives | Derek | A person imports the file by hand with a file picker. No download code, no hosting. |
| Model | Research | `qwen3-1.7b-lora-v7-Q4_K_M.gguf` (v7, retrained with a small number of real chats added; confirmed by Derek on 19 November 2026; the v3 and v4 files are no longer accepted), 1,107,408,736 bytes, SHA-256 `32c05d65e380072e01ab194021419ec5bb8026a07fdcea5290e773a0995dff60`. Any other file is refused. |
| Engine | Research | llama.cpp tag `b12503` (commit `4577932fab595eaa8b27ad71734d5de1f3c07d8c`), built for `arm64-v8a` with the NDK. |
| Keyword detection | Derek | Unchanged. The model is an addition. |

## What changes on the child's phone

Today the child's phone keeps no readable chats: it queues encrypted records for the parent and deletes them on receipt. Scoring needs text, so this feature adds a **local scoring buffer**.

- **Buffer.** When risk scoring is on, each captured message (full two-way capture mode only) is also written to an encrypted table on the child's phone: conversation key, time, order, direction and text. Encryption is the same `LocalVault` field sealing the parent archive uses.
- **Limits.** At most the newest 90 messages per conversation (three windows) and at most 14 days. Older rows are deleted. The buffer is wiped when scoring is turned off, the model file is removed, collection is withdrawn, the role changes, the family key rotates or the phone is reset remotely.
- **On when.** Risk scoring is on only if all are true: consent accepted, role is child, collection allowed, full capture enabled, the verified model file is installed, and the scoring switch is on. The switch and the model import sit behind the parent password on the child's phone.
- **Which threads.** One-to-one only. A conversation marked `group`, or one with more than one distinct incoming author, is not scored. Screen captures often have kind `unknown`; those are treated as one-to-one unless a second distinct incoming author appears (Derek's decision, 19 November 2026; see the plan). Messages with direction `unknown` are left out of the prompt.
- **Speakers.** `outgoing` is `child`, `incoming` is `other`.

## Scoring contract

Must match the research harness exactly, or the published numbers do not apply.

1. **Prompt.** ChatML: system = `research/prompts/instruction-v1.txt` byte for byte (shipped as `ui-res/raw/risk_instruction_v1.txt`); user = `Chat window:\n` + one line per message `speaker: text` + `\n\nAnswer (0, 1, 2, 3 or U):`; then `<|im_start|>assistant\n<think>\n\n</think>\n\n`. Message text has runs of whitespace collapsed to one space and is trimmed, using Python's `str.split()` whitespace set. At most the newest 30 messages.
2. **Tokens.** No BOS added; special tokens parsed.
3. **One forward pass, no sampling.** Read the next-token logits of token ids 15, 16, 17, 18, 52 (`0 1 2 3 U`) and take a softmax over those five. At load the native code checks those ids decode to exactly those strings and refuses the model otherwise.
4. **Scores.** alert = p2 + p3, flagged at 0.5 or more. Report score = 100·(p1 + 2·p2 + 3·p3) / (3·(p0 + p1 + p2 + p3)). If `U` is the most likely answer there is no score and the review reason is `insufficient_context`. Ties go to the earlier answer in the order `0 1 2 3 U`.
5. **Long threads.** The buffered messages are cut into windows of 30 counting back from the newest. Each window is scored. The thread is flagged if any window is flagged; its report score is the highest window score; it is "needs review" only if no window produced a score. A prompt that does not fit the context gives `context_too_long`.

## When scoring runs

A `JobScheduler` job on the child's phone, only while the device is idle and charging, at most a few times a day. It re-checks every "on when" condition at the start and between conversations, verifies the model file's size and SHA-256 before loading it, scores conversations that have new messages since their last score, and stops if the session changes.

## What leaves the child's phone

A small `risk_report` control record over the existing end-to-end encrypted relay:

- status: `scoring`, `idle`, `model_missing`, `model_mismatch` or `off`
- model name and revision
- per conversation: the conversation key (the same hash the parent's archive already uses), score or null, review reason or null, flagged, scored-at time

No message text, no names, no quotes. The model file never leaves the phone it was imported on.

## Parent side

- A `risk_scores` table in the parent's database, deleted with the same rules as the archive (retention, delete conversation, delete archive, key rotation, other-archive cleanup).
- A score page: one plain answer first (how many conversations need attention), then rows showing band, score and date only. States: locked, empty, child still scoring, model not installed on the child's phone, model file wrong, error.

## Not verified without a phone

The native library compiles for `arm64-v8a` here but has never run on a device. Speed, memory use, battery cost, whether `JobScheduler` idle-and-charging windows are long enough, and whether a 1.1 GB import through the system file picker is practical are all unmeasured.

## Open before any release

- The Terms of Service, privacy policy, data-flow and data-deletion documents do not describe the scoring buffer or the score record. The Terms file is byte-verified into the APK and hashed into consent receipts, so changing it is a decision for Derek, and existing users have no re-consent step.
- `CHANGELOG.md` for 4.9-beta.4 says no model runs in the app.
- The model's licence terms for redistribution (Qwen3 is Apache-2.0; the fine-tuned file is Derek's own).
