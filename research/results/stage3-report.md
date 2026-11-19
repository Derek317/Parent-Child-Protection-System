# Stage 3 result: fine-tune arm

19 November 2026. Local GPU session (RTX 5080). Everything here is measured on synthetic chats.

**The 4-bit fine-tuned Qwen3 1.7B passes the check set. It does not pass the older handoff sets, and it ranks the 90-chat set worse than before training.** The sealed test has not been opened.

## What was trained

`python research/train_lora.py --base research/models/hf/<model> --family <qwen3|gemma4> --out research/models/lora/<name> --epochs 3 --batch 2 --lr 1e-4 --merge`

LoRA rank 16 on the attention and MLP projections, loss on the single answer token, 1,500 training chats, seed 1234. Batch 8 needs about 25 GB of GPU memory and stalls a 16 GB card; batch 2 peaks at 9.2 GB (Qwen3) and 12.8 GB (Gemma 4).

Tuning set after each pass, scored in PyTorch at full precision (90 risk, 45 benign):

| Model | Pass 1 | Pass 2 | Pass 3 |
| --- | --- | --- | --- |
| Qwen3 1.7B, risk / benign flagged | 90 / 1 | 88 / 1 | 90 / 0 |
| Gemma 4 E2B, risk / benign flagged | 86 / 0 | 85 / 0 | 85 / 0 |

Frozen before the check set was opened: Qwen3 1.7B, pass 3, cutoff 0.5, converted with llama.cpp b12503 and quantized to Q4_K_M (1,107,408,736 bytes). Gemma 4 E2B was not converted: its 4-bit file is over the 2 GB limit.

## 4-bit Qwen3 1.7B through the harness

| Set | Risk flagged | Benign flagged | AUROC | Pass mark |
| --- | --- | --- | --- | --- |
| Tuning (new data) | 87/90 | 1/45 | 1.00 | Pass |
| **Check (new data)** | **88/90** | **0/45** | **1.00** | **Pass** |
| Handoff 90-chat set | 19/36 | 7/36 | 0.64 | Fail |
| Handoff 60-chat severity set | 10/24 | 0/12 | 0.78 | Fail |

Untrained, the same model scored 1/36 and 0/36 with AUROC 0.76 on the 90-chat set, and 5/24 with AUROC 0.80 on the severity set.

## Reading it

- **The model learned the new data's style, not the task in general.** New-data sets are near perfect, including families it never saw. On the handoff sets, which another author wrote in a different form (a teen telling someone what happened, speakers named `person_a`, `teen`), it flags more risk chats than before but also more benign ones, and its ranking on the 90-chat set got worse.
- **Near-perfect scores on the check set are a warning as much as a result.** One model family wrote the training, tuning, check and sealed chats from one writing guide. The sealed test will very likely pass for the same reason and would say little about real chats.
- The handoff sets are not the format the app sends, so they are not the pass mark. They are the only data here written by someone else.

## Not done

- d1 weight-edit arm (layer probe, head-edit refit).
- Stage 4: the sealed test is unopened.

## Closing the style gap (added later on 19 November 2026)

Derek chose to fix the style gap before opening the sealed test. Two retrains of Qwen3 1.7B, same settings, 4-bit file scored through the harness:

| Version | Training data | Tuning | Handoff 90-chat | Handoff 60-chat |
| --- | --- | --- | --- | --- |
| v1 | 1,500 new chats | 87/90 · 1/45, pass | 19/36 · 7/36, AUROC 0.64, fail | 10/24 · 0/12, AUROC 0.78, fail |
| v2 | v1 + 105 levelled handoff development chats, three times each | 90/90 · 1/45, pass | 26/36 · 10/36, AUROC 0.80, fail | 22/24 · 3/12, AUROC 0.91, fail |
| v3 | v2 + 282 binary-labelled handoff development chats | 89/90 · 1/45, pass | **30/36 · 2/36, AUROC 0.97, pass** | 22/24 · 3/12, AUROC 0.93, fail by one benign chat |

Cells are risk flagged · benign flagged.

- **Sources.** v2 adds `ordinal/context-data/fit.jsonl` and `selection.jsonl` from the handoff (families `f*` and `v*`). v3 adds `grounded/data/fit.jsonl`, `grounded/data/selection.jsonl`, `weight-fit.jsonl` and `weight-selection.jsonl`. No added chat has the same text as any chat in the two handoff test sets, and none comes from their `b*` families. Files: `research/data/train-mixed-v2.jsonl`, `train-mixed-v3.jsonl`.
- **Binary labels.** Those 282 chats say only risk or benign. `train_lora.py` now accepts a row with `allowed`: a risk chat counts any of `1`, `2`, `3` as correct, a benign chat only `0`.
- **What this means for the handoff sets.** The model was trained on chats by the same author and steered by watching these two sets, so they are now tuning sets, not independent tests. The sealed test is the only untouched set, and it is in the new style only.
- **The check set was not rescored** for v2 or v3.
