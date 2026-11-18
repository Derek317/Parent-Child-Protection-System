# Stage 1 result: the ladder, CPU rungs

18 November 2026. Cloud session. Everything here is measured on synthetic chats; none of it says how a model behaves on real conversations.

**No untrained phone-sized model passes. The label check passes on both sets, so the stop-or-go decision is "go on" whatever the ceiling model does.** The ceiling model, Gemma 4 E4B and d1-3B still have to be run by the GPU session.

Full tables with every range and every failed condition are in [`stage1-summary.md`](stage1-summary.md), built by `python research/report.py`.

## Harness check

The d1-omni-600M 8-bit control flagged **32 of 36** risk chats and **29 of 36** benign chats on the 90-chat set. The allowed ranges were 30–34 and 28–32, and the handoff's own result was 32 and 30. Per chat, the yes-probability differs from the handoff's saved value by 0.014 on average and 0.063 at most. Sending the question alone or batched with the handoff's other three questions made no difference, so the gap is most likely the different CPU.

## 90-chat binary set

Pass mark: at least 80% of risk chats and at most 20% of benign chats flagged overall; 70% and 30% within each language. Ranges are 95%, worked out by scenario family.

| Rung | Model | Risk flagged | Benign flagged | AUROC | Pass mark |
| --- | --- | --- | --- | --- | --- |
| Control | Keyword rules | 1/36 (3%, 0–9%) | 1/36 (3%, 0–9%) | 0.50 | Fail |
| Control | d1-omni-600M 8-bit | 32/36 (89%, 73–100%) | 29/36 (81%, 65–93%) | 0.53 (0.44–0.64) | Fail |
| Phone-sized | Qwen3Guard 0.6B 8-bit | 0/36 | 0/36 | 0.51 (0.43–0.60) | Fail |
| Phone-sized | Qwen3 1.7B 8-bit | 1/36 (3%, 0–8%) | 0/36 | 0.76 (0.68–0.85) | Fail |
| Phone-sized | Gemma 4 E2B 4-bit | 14/36 (39%, 14–67%) | 4/36 (11%, 0–28%) | 0.77 (0.70–0.86) | Fail |
| Label check | Sonnet 5.5 | 36/36 | 0/36 | 1.00 | **Pass** |
| Stretch | Gemma 4 E4B | not run (GPU session) | | | |
| Stretch | d1-3B | not run (GPU session) | | | |
| Ceiling | Gemma 4 12B 4-bit | not run (GPU session) | | | |

By language, risk flagged / benign flagged:

| Model | English | Cantonese | Mixed |
| --- | --- | --- | --- |
| Keyword rules | 1/12 · 1/12 | 0/12 · 0/12 | 0/12 · 0/12 |
| d1-omni-600M | 11/12 · 9/12 | 10/12 · 10/12 | 11/12 · 10/12 |
| Qwen3Guard 0.6B | 0/12 · 0/12 | 0/12 · 0/12 | 0/12 · 0/12 |
| Qwen3 1.7B | 1/12 · 0/12 | 0/12 · 0/12 | 0/12 · 0/12 |
| Gemma 4 E2B | 4/12 · 2/12 | 4/12 · 0/12 | 6/12 · 2/12 |
| Sonnet 5.5 | 12/12 · 0/12 | 12/12 · 0/12 | 12/12 · 0/12 |

With the six potential-level risk chats taken out (families b03 and b06), nothing changes in substance: d1 26/30, Qwen3 1.7B 1/30, Gemma 4 E2B 11/30, Sonnet 30/30. Benign counts are the same.

## 60-chat severity set

Here risk means level 2 or 3 (24 chats) and benign means level 0 (12 chats). Potential and unresolved chats are counted on their own.

| Model | Risk flagged | Benign flagged | Potential flagged | Unresolved flagged | AUROC | Exact five-way match | Pass mark |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Keyword rules | 0/24 | 0/12 | 0/12 | 0/12 | 0.50 | n/a | Fail |
| d1-omni-600M 8-bit | 1/24 | 0/12 | 0/12 | 0/12 | 0.72 (0.55–0.84) | 11/60 | Fail |
| Qwen3Guard 0.6B | 0/24 | 0/12 | 0/12 | 0/12 | 0.53 (0.42–0.64) | n/a | Fail |
| Qwen3 1.7B | 5/24 (21%, 8–33%) | 0/12 | 0/12 | 0/12 | 0.80 (0.59–0.95) | 13/60 | Fail |
| Gemma 4 E2B | 7/24 (29%, 17–42%) | 0/12 | 0/12 | 0/12 | 0.92 (0.83–0.99) | 26/60 | Fail |
| Sonnet 5.5 | 24/24 | 0/12 | 0/12 | 0/12 | 1.00 | 60/60 | **Pass** |

d1's 11 exact matches are all unresolved chats that it declined to score; it withheld a score on almost every chat. Neither Qwen3 1.7B nor Gemma 4 E2B answered `U` for any of the 12 unresolved chats.

## Reading the results

- **The benchmark is passable and the labels are consistent.** An independent reader with labels hidden reproduced every label on both sets, including all five levels on the severity set.
- **The small models rank better than they flag.** Qwen3 1.7B and Gemma 4 E2B put risk chats above benign ones about three times in four (AUROC 0.76–0.77, and 0.80–0.92 on the severity set), but they answer `0` or `1` for most risk chats, so few cross the 0.5 line. That is a calibration problem as much as a knowledge problem, which is the kind of thing fine-tuning fixes. No cutoff was tuned here, because these sets may not be used to choose one.
- **Qwen3Guard 0.6B is not usable untrained.** It puts about 3% of its probability on the five answer tokens; its first word is usually "Chat" or "Safety". Its numbers reflect that it ignores the instruction, not that it judged the chats.
- **d1 flags nearly everything on the binary set and nearly nothing on the severity set**, as the handoff found.
- **Keyword rules catch almost nothing**, on these sets and on the new tuning set (1 of 90 risk chats).

## Stop-or-go

| Ceiling model | Label check | Decision |
| --- | --- | --- |
| Not yet run (GPU session) | **Passes** | **Go on in either case.** If the ceiling fails, the labels are sound and untrained local models are weak, which is what fine-tuning is for. If it passes, go on. |

**Models to fine-tune** (the two phone-sized rungs with the best AUROC on the 90-chat set): **Gemma 4 E2B (0.77)** and **Qwen3 1.7B (0.76)**. The two are within each other's range.

## Things that change the plan

1. **Gemma 4 E2B does not fit the 2 GB limit in llama.cpp format.** Its 4-bit file is 3.35 GB (Google's own) or 2.84 GB (ggml-org), not about 1.5 GB as the design assumed. Qwen3 1.7B is about 1.1 GB at 4-bit and 1.83 GB at 8-bit. d1-3B at 4-bit is 1.67 GB. If Gemma 4 E2B wins stage 3, the size limit or the phone runtime has to be revisited. Details in [`../models.md`](../models.md).
2. **The label check was easier than the models' task.** The labeller read all chats of a set in one pass, and these sets hold sibling chats that differ only in their ending, so it could compare siblings. Models score one chat at a time. The check shows the labels are coherent; it does not show a single-chat reader would reach 100%.
3. **The handoff sets are disclosures, not direct chats.** Speakers are named `person_a`, `teen`, `aunt` and so on, and a teen is usually telling someone what happened. They were scored with those names as written. The new stage 2 data uses `child` and `other` in direct chats, which is what the app will see.
4. **The RTX 5080 may not work with the pinned llama.cpp CUDA build.** See [`../HANDOVER-GPU.md`](../HANDOVER-GPU.md).

## Stage 2 data, as built

| Set | Families | Chats | File |
| --- | ---: | ---: | --- |
| Training | 100 | 1,500 | `research/data/train.jsonl` |
| Tuning | 15 | 225 | `research/data/tune.jsonl` |
| Check | 15 | 225 | `research/data/check.jsonl` |
| Sealed test | 50 | 662 kept of 750 | `research/data/sealed-test.jsonl` |

- Every family is one setting with five endings in three languages. Every file passes the validator (shape, tags, 6–30 messages, language checks, no near-copies, and a spread of lengths within each ending so length does not reveal the level). Average length is 11–12 messages for every ending in the training set.
- **Sealed test.** Written by ten subagents that saw only their own five settings. Five other subagents labelled the 750 chats with ids and labels hidden. **88 chats (11.7%) were set aside**, under the 15% limit: 57 unresolved, 31 potential, and no low, moderate or high chat. All 100 risk chats and all 50 benign chats per language remain. Ten chats where the labeller said high and the writer said moderate were kept, because the pass mark does not separate them. SHA-256 of the files is in `research/data/SEALED.sha256`. The comparison script prints counts only. These figures are from the second relabel, done after the Cantonese restyle below; the first relabel set aside 94 (12.5%). A few restyle subagents quoted single sealed lines in their reports against instructions, so the main session has seen a handful of sealed messages; none were used for any choice.
- **"Unresolved" is the fuzzy class.** Blind labellers agreed with the writers on 62% of unresolved chats and 79% of potential chats, against 100% for low, moderate and high. In the first relabel, agreement on unresolved varied a lot between labellers (from 10 of 24 to 29 of 33). The training, tuning and check sets were not relabelled and will carry the same fuzziness. It does not touch the pass mark, which uses only low, moderate and high.
- **Cantonese check, round 1: not passed as written.** Derek rewrote 26 of the 30 sample chats (81 of 385 messages) to his standard (`research/data/cantonese-review-round1-derek.md`). His edits are applied to those 30 training chats, and his style rules are in the writing guide ("Hong Kong texting style"). All 1,770 other Cantonese and mixed chats in all four sets were then reworded by subagents to those rules, keeping each chat's plot, level, speakers and message count: about 30% of messages changed (1,691 chats, 6,461 messages). The pre-restyle files are kept in `research/data/raw-round1/`. English chats were not touched.
- **Cantonese check, round 2 is waiting for Derek:** `research/data/cantonese-review-round2.md` holds 30 different chats. More than 6 marked off means another redo. Training must not start before this passes. The restyle agents varied a lot in how much they changed (from about 20% to 85% of messages per batch), so quality is probably uneven.
- One model family wrote the chats and checked the labels. Derek's read is the only outside check.
