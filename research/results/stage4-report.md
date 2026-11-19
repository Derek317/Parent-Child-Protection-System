# Stage 4 result: the sealed test

19 November 2026. Local GPU session (RTX 5080). Everything here is measured on synthetic chats; none of it says how the model behaves on real conversations.

**The frozen 4-bit model passes the sealed test.** The result is too clean to be read as proof the model works: it shows the model has learned the two writing styles in this project.

## The model

- Qwen3 1.7B with a LoRA add-on merged in, version v3 (see [`stage3-report.md`](stage3-report.md)), pass 3, alert cutoff 0.5.
- Converted with llama.cpp b12503 and quantized to Q4_K_M: `qwen3-1.7b-lora-v3-Q4_K_M.gguf`, 1,107,408,736 bytes (1.03 GiB), SHA-256 `14f2176d569772bcc8e0e0dcd97e59f7ac60d8c4f6e1cb5f6a45159b1892c293`. Under the 2 GB limit.
- Frozen before the check set was rescored and before the sealed test was opened. `python research/datatools/seal.py verify` reported both sealed files unchanged. The sealed test was scored once per file size and nothing was changed afterwards.

## Results

| Set | Model file | Risk flagged | Benign flagged | AUROC | Pass mark |
| --- | --- | --- | --- | --- | --- |
| Check (second use) | 4-bit | 89/90 | 0/45 | 1.00 | Pass |
| **Sealed test** | **4-bit** | **300/300** | **0/150** | **1.00** | **Pass** |
| Sealed test | Full size (f16) | 299/300 | 0/150 | 1.00 | Pass |

Sealed test, 4-bit, by language: English 100/100 risk and 0/50 benign; Cantonese 100/100 and 0/50; mixed 100/100 and 0/50. No potential chat (0/119) and no unresolved chat (0/93) was flagged. The model gave the exact level or `U` on 541 of 569 chats and answered `U` on 90 of 93 unresolved chats. Shrinking to 4-bit changed one chat in 450.

## Reading it

- **The pass mark in the design is met**, on 50 scenario families the model never saw, written by separate writers and relabelled blind.
- **A perfect score mostly measures how alike the test and the training data are.** One model family wrote both from one writing guide. Stage 3 showed the same model, before style mixing, scoring near perfect here-style and failing a set written by someone else.
- **Evidence beyond that one style is thin.** After adding the handoff author's development chats, the model passes the handoff 90-chat set (30/36, 2/36) and misses the 60-chat set by one benign chat (22/24, 3/12). Those two sets were used to steer training, so they are not independent tests either.
- **No real chat has been scored**, and by the project's rules none may be. Real conversations are a third style nobody has tested.

## Not done

- A test set in a third writing style, written by a different process. This is the next thing worth doing before trusting the model.
- The d1 weight-edit arm (layer probe, head-edit refit). Not run.
- The score-only report in the app's format, and the change to `RiskScoreReport.kt` so it accepts a model other than d1.
- Speed and memory on a real phone.
