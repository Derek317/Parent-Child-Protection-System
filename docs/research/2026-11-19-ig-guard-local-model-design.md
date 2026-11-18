# IG Guard: a local risk model for the child's phone

Design, 19 November 2026. Status: approved by Derek.

Lines marked **(my call)** are choices Claude made. Everything else in the decision sections is what Derek said or picked.

## Intent

Parents want to know when a child's Instagram conversation shows signs of grooming or coercion. They should get that signal without the chats going to a cloud service and without reading the chats themselves. So the model has to run on the child's phone and hand the parent a score only.

The earlier work (the handoff in `handoff/IG-Guard-AI-Audit-Handoff/`) tried three small models and none passed. It never tested anything larger, so we do not know whether the benchmark is passable by any local model. This project answers that first, then adapts the smallest model that can pass.

## Goals

1. Find out whether any local model passes the existing benchmark, and how small a model can be before it stops passing.
2. Build a larger three-language dataset (English, Hong Kong Cantonese in Traditional Chinese, mixed) with a sealed test set.
3. Produce one model file of about 2 GB or less that passes the pass mark on the sealed test, running in the same engine family that runs on Android.
4. Keep d1 weight edits in the comparison, on the same data, so we learn whether direct weight edits can compete with ordinary fine-tuning.

## What the model detects

- **Who.** Pressure, manipulation or coercion of the child by any person, adult or peer. The phone cannot verify anyone's age, and the handoff's test chats were labelled this way. (Derek's decision.)
- **Severity.** Every chat has one of the handoff's four levels, unchanged: low, potential, moderate, high. A chat cut off before its outcome is "unresolved".
- **Risk for the pass mark (my call).** A risk chat is one at moderate or high. A benign chat is one at low. This matches the app, which alerts at a score of 50. Potential and unresolved chats are counted on their own lines: they are neither a required catch nor a false alarm.

## Pass mark

Unchanged from the handoff, and binary only **(my call)**:

- Overall: at least 80% of risk chats flagged, at most 20% of benign chats flagged.
- Within each language: at least 70% and at most 30%.
- Judged on the measured figure, as the handoff did. Every figure is reported with its 95% range, worked out by scenario family because the three language versions of one family are not independent.
- Exact band match and how unresolved chats are handled are reported for information. They do not decide pass or fail.

## What the model reads and returns

- **Input.** One window of a one-to-one thread: up to the 30 most recent messages, each tagged `child` or `other`, text only. Photos, voice notes and reactions appear as placeholders such as `[photo]`. The app captures both sides and knows the sender. (Derek confirmed.)
- **Thread score (my call).** A long thread is scored in windows; the thread takes its highest window score.
- **Answer (my call).** The model answers with one of five single tokens: `0`, `1`, `2`, `3` for the four levels, or `U` for unresolved. Nothing else is generated.
- **Alert score.** The probability of `2` plus the probability of `3`. A chat is flagged at 0.5 or above. The pass mark uses this.
- **Report score.** The probability-weighted level, scaled to 0–100, so the app's bands at 25, 50 and 75 still apply. When `U` is the most likely answer the chat is reported as "needs review" with no number, which the app's reader already supports.

## Non-goals

- Changing the Android app's scoring path, or measuring speed and memory on a real phone. That is the next project. This one ends with a phone-sized model file that passes on a PC.
- Scoring each message as it arrives. Chats are scored in batches while the phone is idle or charging.
- Showing parents any chat text, summaries or quotes. Score bands only.
- Group chats, images, voice messages and video.
- The PAN12 research dataset. It is English only and needs an access request. It can be added later if English turns out to be the weak language.

## Anti-goals

These make the project a failure even if the numbers look good.

- Any real child's chat used for training, testing or debugging.
- Graphic or sexual content in generated data. Examples show the pattern (secrecy, isolation, gifts with conditions, moving to private channels, pressure after a refusal, threats to share something private) and stay non-graphic, like the existing fixtures.
- Choosing a model, prompt or cutoff by looking at the sealed test set.
- Reporting a result as validated for real families. Everything here is measured on synthetic chats.
- A runtime path that sends chat text off the phone.

## Constraints

- **Target phone (my call):** a mid-range Android with 8 GB of memory. That sets the size limit of about 2 GB for the model file.
- **GPU machine:** Windows 11, RTX 5080 with 16 GB, 47 GB of memory, Python 3.12.
- **The old scripts do not run on Windows.** They import a Linux-only module, read Chinese text without naming the encoding, and point at `/workspace` paths. The new work reuses the handoff's data files and pass mark, and writes a new small harness instead of porting those scripts.
- **The app's report reader accepts d1 only.** `RiskScoreReport.kt` rejects any report whose model name is not `LiquidAI/d1-omni-600M`. If another model wins, the reader needs a small change in the next project.
- **Licences.** d1 is under the LFM Open License, which has commercial limits. Gemma 4 and Qwen3Guard are reported as Apache-2.0. The builder confirms each licence from the model's own page before download.
- **Coding is done by Sonnet 5.5 subagents.** Claude reviews their work.

## Approaches considered

| Approach | Trade-off |
| --- | --- |
| **1. Measure first, then adapt the winner (chosen).** Run a ladder of models on the existing benchmark, build better data, fine-tune the smallest model that gets close, and run d1 weight edits on the same data. | Tells us whether the benchmark is passable before any training. The winner may be 1.5 GB instead of 400 MB. |
| 2. d1 only, with deeper weight edits. | Smallest and fastest on a phone. Highest risk: d1 scored near chance, and every earlier edit traded missed risks for false alarms. |
| 3. Fine-tune Gemma 4 E2B straight away. | Quickest route to a trained model, but a blind bet on one model and on a benchmark nobody has shown to be passable. |

Derek picked approach 1.

## The design

Four stages. Each ends with a short written result and a stop-or-go decision. All the numeric rules below are **my calls**.

### Stage 1: the ladder

Run each model below, with no training, on the handoff's two existing test sets: the 90-chat binary set (`grounded/data/blind.jsonl`) and the 60-chat severity set (`ordinal/context-data/test.jsonl`).

| Rung | Model | Why it is here |
| --- | --- | --- |
| Control | Keyword rules from the app | The floor |
| Control | d1-omni-600M, 8-bit, 407 MB | Checks the harness against the handoff's known result |
| Phone-sized | Qwen3Guard 0.6B | Small safety model with wide language coverage |
| Phone-sized | Qwen3 1.7B | Strong Chinese for its size |
| Phone-sized | Gemma 4 E2B, about 1.5 GB | Built for phones |
| Stretch | Gemma 4 E4B, about 3 GB | Over the size limit; shows what 1 GB more buys |
| Stretch | d1-3B | Larger sibling of d1 |
| Ceiling | One general model of about 12–14B at 4-bit | The best the GPU machine can run |
| Label check | Sonnet 5.5 reading each chat without the answer | An independent second opinion on the labels. Safe because the chats are synthetic. |

Scoring:

- Decision models (d1, d1-3B) use their own decision call with the handoff's original questions: the yes/no question on the binary set, and the rubric's two questions on the severity set.
- Chat models use the five-token answer described above, with one fixed instruction. It is written and frozen using only the handoff's development files and is the same for every chat model.
- The 90-chat set is scored as the handoff labelled it, so numbers stay comparable. Six of its 36 risk chats sit at the potential level; results are also shown with those six taken out.

Harness check: the d1 control must land within two chats of the handoff's 8-bit result on each count, so 30–34 of 36 risk chats flagged and 28–32 of 36 benign chats flagged. Outside that, the harness is fixed before anything else is run.

Stop-or-go after stage 1:

| Ceiling model | Label check | Decision |
| --- | --- | --- |
| Fails | Fails | Stop. The benchmark or its labels are the problem; fix those first. |
| Fails | Passes | Go on. The labels are sound and untrained local models are weak, which is what fine-tuning is for. |
| Passes | Fails | Read the disagreements before going on. |
| Passes | Passes | Go on. |

A phone-sized model that already passes still goes on to the sealed test. The existing sets are too small and too reused to trust alone.

Which models get fine-tuned: the two phone-sized rungs with the best ranking quality (AUROC) on the 90-chat set.

### Stage 2: the data

The handoff's sets are small, written by one author, reused across many selection rounds, and show a teen describing events instead of the messages themselves. The new data fixes those four things.

- **Shape.** Scenario families. Each family is one setting written with five endings (low, potential, moderate, high, unresolved) in three languages, so 15 chats per family. Shared settings stop a model from learning keywords.
- **Content.** Two-sided chats between `child` and `other`, 6 to 30 messages, in the input format above. The other person is an adult in some families and a peer in others.
- **Size.**

  | Set | Families | Chats | Used for |
  | --- | ---: | ---: | --- |
  | Training | 100 | 1,500 | Fitting |
  | Tuning | 15 | 225 | Choosing settings and cutoffs |
  | Check | 15 | 225 | The stage 3 stop-or-go, opened after settings are frozen |
  | Sealed test | 50 | 750 | The final result, opened once in stage 4 |

- **Sealed test.** Written by a separate subagent from different source scenarios, with no access to the other families. A second subagent labels the chats without seeing the intended labels. Chats where the two disagree are set aside and counted. If more than 15% are set aside, the set is rewritten before it is used. The file is then hashed and not opened until stage 4. It holds 100 risk chats and 50 benign chats per language.
- **Cantonese check (needs Derek).** Derek reads about 30 Cantonese and mixed chats and marks each as natural or off. More than one in five marked off means the writing instructions change and the batch is redone. After two failed redos the work stops for a discussion. Stage 1 and the English data go ahead while this is pending; training does not start until it passes.

### Stage 3: adapt

Two arms, trained on the same data. Settings and cutoffs are chosen on the tuning set. Pass or fail is then read from the check set.

**Fine-tune arm.** Train a small add-on to the weights (LoRA) of the two models picked in stage 1, on the RTX 5080. The trained model keeps the five-token answer, so scoring does not change.

**d1 weight-edit arm.** Three steps, cheapest first:

1. *Layer probe.* For each layer of d1, test whether a straight-line rule on that layer's activations separates risk from benign on the new data. This says whether d1 holds the information at all, and where.
2. *Head edit, refit.* Repeat the handoff's direct edit of the final scoring weights, now with about fourteen times the data. The earlier fit had 108 examples for 1,024 weights, which is too few to trust.
3. *Fine-tune d1,* only if the layer probe shows the information sits in earlier layers that the final weights cannot reach.

Stop-or-go after stage 3: if no arm passes on the check set, stop and report. The sealed test stays sealed for a later attempt.

### Stage 4: shrink and confirm

- Convert the best model to a 4-bit phone format and confirm the file is about 2 GB or less.
- Open the sealed test once. Score the full-size model and the 4-bit model.
- The 4-bit model is the product, so it is the one that has to pass. If it fails and an 8-bit version fits in 2 GB, the 8-bit version is scored once and judged instead. No other retries against the sealed test.
- Report results by language with their ranges, and list the missed risk chats and the false alarms for reading.
- Write the score-only report in the app's existing format, with the model name field updated.

## Tools

All **my calls**.

- **Engine: llama.cpp.** One engine runs every rung, gives answer probabilities, supports d1's decision call, and is the engine the handoff planned for Android.
- **Training: PyTorch with the standard LoRA library** in a Python 3.12 environment.
- **Data writing and label checks: Sonnet 5.5 subagents.**
- **Model files stay out of version control.**

## Left to the builder

- Exact model files and versions, confirmed against each publisher's page. The model facts here come from a web search and have not been checked first-hand. If a named model does not exist as described, the builder picks the closest equivalent and says so.
- Which 12–14B model serves as the ceiling.
- The wording of the fixed instruction, within the freezing rule above.
- LoRA settings, chosen on the tuning set.
- How d1's custom loading code is reviewed before it is run. The handoff pinned and reviewed one exact version; use that version.
- File layout and names inside the project folder.

## Known risks

- **Synthetic data may not look like real chats.** A model that passes here may still fail on real conversations. Nothing in this project can rule that out.
- **Cantonese is the weakest point.** No public Cantonese grooming data exists, and small models are weaker in Cantonese than in English.
- **The winner may need more than 2 GB.** If only the 3 GB model passes, the phone target has to change or a cheaper phone is ruled out.
- **Sonnet writes the data and also checks the labels.** That is one model family agreeing with itself. Derek's Cantonese read is the only outside check in this project.
- **The window format is assumed to match the app.** Derek confirmed the app captures both sides with the sender. The exact fields should be checked against `ConversationCapture.kt`.
