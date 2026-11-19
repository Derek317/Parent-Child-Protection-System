# Local risk scoring in the app

Build spec, 19 November 2026. Status: in progress on branch `app/local-risk-scoring`. Not released.

Everything about the model's accuracy is measured on invented chats only (see `research/results/stage4-report.md`). Nothing here is validated on real conversations.

## Decisions

| Decision | Who | Choice |
| --- | --- | --- |
| Where scoring runs | Derek | On the child's phone. |
| How the model file arrives | Derek | A person imports the file by hand with a file picker. No download code, no hosting. |
| Model | Research | `qwen3-1.7b-lora-v3-Q4_K_M.gguf`, 1,107,408,736 bytes, SHA-256 `14f2176d569772bcc8e0e0dcd97e59f7ac60d8c4f6e1cb5f6a45159b1892c293`. Any other file is refused. |
| Engine | Research | llama.cpp tag `b12503` (commit `4577932fab595eaa8b27ad71734d5de1f3c07d8c`), built for `arm64-v8a` with the NDK. |
| Keyword detection | Derek | Unchanged. The model is an addition. |

## What changes on the child's phone

Today the child's phone keeps no readable chats: it queues encrypted records for the parent and deletes them on receipt. Scoring needs text, so this feature adds a **local scoring buffer**.

- **Buffer.** When risk scoring is on, each captured message (full two-way capture mode only) is also written to an encrypted table on the child's phone: conversation key, time, order, direction and text. Encryption is the same `LocalVault` field sealing the parent archive uses.
- **Limits.** At most the newest 90 messages per conversation (three windows) and at most 14 days. Older rows are deleted. The buffer is wiped when scoring is turned off, the model file is removed, collection is withdrawn, the role changes, the family key rotates or the phone is reset remotely.
- **On when.** Risk scoring is on only if all are true: consent accepted, role is child, collection allowed, full capture enabled, the verified model file is installed, and the scoring switch is on. The switch and the model import sit behind the parent password on the child's phone.
- **Which threads.** One-to-one only. A conversation marked `group`, or one with more than one distinct incoming author, is not scored. Screen captures often have kind `unknown`; those are treated as one-to-one unless the author rule says otherwise. Messages with direction `unknown` are left out of the prompt.
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
