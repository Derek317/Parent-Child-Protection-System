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

## Third-style test (added later on 19 November 2026)

The cloud session wrote `research/data/style3-test.jsonl` from a new guide (`research/data/STYLE3-GUIDE.md`): 20 new settings, long messy threads, with writers who saw none of the earlier data. 270 of 300 chats were kept after a blind relabel. The frozen 4-bit model was scored on it once; nothing was changed afterwards.

| Group | Risk flagged | Benign flagged | AUROC |
| --- | --- | --- | --- |
| **All** | **112/120 (93%)** | **6/55 (11%)** | **0.96** |
| English | 37/40 | 1/19 | 0.97 |
| Cantonese | 38/40 | 4/18 | 0.96 |
| Mixed | 37/40 | 1/18 | 0.96 |

**Pass**, overall and in each language. Also flagged: 2 of 49 potential chats and 3 of 46 unresolved chats. Exact level or `U` on 161 of 224 chats; `U` on 37 of 46 unresolved chats.

- This is the first test in a style the model was neither trained on nor steered by, and it holds up: clearly below the perfect sealed score, clearly above the pass mark.
- Cantonese benign is the weakest cell (4 of 18 flagged, 22%, limit 30%). With 18 chats that is a wide range.
- The writers were still the same model family as the training data's writers. Real chats remain untested.

## d1 weight-edit arm (added later on 19 November 2026)

Details: [`d1-probe/README.md`](d1-probe/README.md) and [`d1-edit/README.md`](d1-edit/README.md).

- **Layer probe.** A straight-line rule on d1's activations separates risk from benign tuning chats at AUROC 0.98–1.00 from layer 3 to layer 14, 0.97 at the input to the head's first scoring layer and 0.93 at the input to its last. d1's own score gives 0.74. So d1 holds the signal for this data.
- **Head edit.** That rule was fitted in closed form on the 900 risk and benign training chats and written into three existing tensors (3,074 entries, the handoff's two-neuron method), frozen, then scored once per set through the live decision call at the 0.5 cutoff.

| Set | d1 untouched | d1 edited | Qwen3 1.7B v3, 4-bit |
| --- | --- | --- | --- |
| Check | 38/90 · 3/45, fail | 85/90 · 3/45, **pass** | 89/90 · 0/45, pass |
| Third-style test | 30/120 · 14/55, AUROC 0.56 | 77/120 · 18/55, AUROC 0.76, fail | 112/120 · 6/55, AUROC 0.96, pass |
| Handoff 90-chat | 32/36 · 29/36 | 35/36 · 36/36, AUROC 0.52, fail | 30/36 · 2/36, pass |
| Handoff 60-chat | 16/24 · 7/12 | 21/24 · 7/12, fail | 22/24 · 3/12, fail by one |

Cells are risk flagged · benign flagged.

- **The edit works in the style it was fitted on and nowhere else**, the same pattern as the handoff's edits and as Qwen3 v1.
- **The comparison is not like for like on the handoff sets.** The d1 edit was fitted on the new-style chats only; Qwen3 v3 also trained on 387 handoff-style development chats. The third-style test is the fair row: neither model saw that style.
- **Decision: the fine-tuned Qwen3 1.7B is the model to carry forward.** A head edit can only re-read what d1's last layers already expose; fine-tuning changes what the model computes.

## Note added 19 November 2026: the sealed set changed after this report

The Cantonese and mixed chats in the sealed set were reworded a second time and relabelled blind (661 kept, 89 set aside; new hashes in `research/data/SEALED.sha256`). The figures above were measured on the earlier wording (662 kept). A new sealed score on the reworded set is a second use, not a clean first look. See `research/HANDOVER-GPU.md`.

## Retrain on the restyled data: v4 (19 November 2026)

After Derek passed the round 3 Cantonese sample, Qwen3 1.7B was retrained with the v3 recipe unchanged, on `research/data/train-mixed-v3-restyled.jsonl`:

`python research/train_lora.py --base research/models/hf/Qwen3-1.7B --family qwen3 --out research/models/lora/qwen3-1.7b-lora-v4 --train research/data/train-mixed-v3-restyled.jsonl --epochs 3 --batch 2 --lr 1e-4 --merge`

Then `convert_hf_to_gguf.py` (llama.cpp b12503 source, `--outtype f16`) on the merged folder and `llama-quantize.exe <f16> <out> Q4_K_M`. The same two commands produced v3.

- File: `qwen3-1.7b-lora-v4-Q4_K_M.gguf`, 1,107,408,736 bytes, SHA-256 `833efa22ff726b0df4c6461fd61c12409b9dd7eddfee35417c7c342a6a435314`.
- Frozen at pass 3 and the 0.5 cutoff after the tuning set only. Sealed checksums verified before opening.

4-bit file through the harness, risk flagged · benign flagged:

| Set | v4 | v3 | Note |
| --- | --- | --- | --- |
| Tuning | 87/90 · 2/45, pass | 89/90 · 1/45, pass | v3 scored on the earlier wording |
| Check | 87/90 · 0/45, pass | 89/90 · 0/45, pass | v3 scored on the earlier wording |
| Sealed test | 287/298 · 0/144, pass | 300/300 · 0/150, pass | Second use of the sealed set, after a restyle and relabel. Not a clean first look. |
| Third-style test | 103/120 · 2/55, pass | 112/120 · 6/55, pass | Repeat use; same file for both |
| Handoff 90-chat | 26/36 · 4/36, AUROC 0.92, fail | 30/36 · 2/36, AUROC 0.97, pass | Context only; same file for both |
| Handoff 60-chat | 24/24 · 3/12, fail by one | 22/24 · 3/12, fail by one | Context only; same file for both |

v4 by language, risk · benign: sealed English 98/100 · 0/49, Cantonese 96/100 · 0/48, mixed 93/98 · 0/47. Third-style English 35/40 · 0/19, Cantonese 35/40 · 1/18, mixed 33/40 · 1/18. Handoff 90-chat English 10/12 · 1/12, Cantonese 7/12 · 1/12, mixed 9/12 · 2/12.

- **v4 passes every set it was required to pass.**
- **On the three files both models saw unchanged, v4 is not better than v3.** It flags fewer risk chats on the third-style test and the handoff 90-chat set, where it falls below the pass mark, mostly in Cantonese (7/12). It raises fewer false alarms on the third-style test.
- **The differences are small against the sample sizes** (9 chats of 120; 4 of 36), and one training run each cannot separate a real effect of the restyle from run-to-run variation.
- **Which model the app ships is Derek's decision.** v4 was trained on the Cantonese wording Derek approved; v3 has the slightly better numbers on wording neither was trained on. Both files are kept.

## Retrain with real chats added: v7 (19 November 2026)

Derek supplied two kinds of real chats, which are **not in this repository and must never be added to it**:

- **Real grooming chats:** 30 text transcripts of chat screenshots, 10 each for training, verification and testing, all labelled level 3. Simplified Chinese. Graphic messages had been replaced by a marker; the marker lines were dropped before use. The three folders overlap: the same conversations appear in more than one.
- **Real ordinary chats:** Derek's own Instagram conversations, 11 one-to-one threads cut into 151 windows of up to 30 messages, all labelled level 0. Split by thread: 64 windows for training, 50 for verification, 37 for testing. Shared posts were replaced by `[link]`.

v7 is the v4 recipe on `train-mixed-v3-restyled.jsonl` plus the 10 real grooming training chats (each repeated ten times) and the 64 real ordinary training windows. Same base, settings, conversion and quantization as v4. Because the real chats are kept out of the repository, **v7 cannot be rebuilt from the repository alone.**

- File: `qwen3-1.7b-lora-v7-Q4_K_M.gguf`, 1,107,408,736 bytes, SHA-256 `32c05d65e380072e01ab194021419ec5bb8026a07fdcea5290e773a0995dff60`.

4-bit files through the harness. Real chats, flagged at the 0.5 cutoff:

| Model | Grooming, verification (of 10) | Grooming, testing (of 10) | Ordinary, verification (of 50) | Ordinary, testing (of 37) |
| --- | --- | --- | --- | --- |
| v3 | 3 | 3 | 17 | 13 |
| v4 | 0 | 3 | 10 | 11 |
| v5 (grooming chats added, markers kept) | 8 | 6 | 39 | 32 |
| v6 (grooming chats added, markers dropped) | 7 | 7 | 34 | 30 |
| **v7 (grooming and ordinary chats added)** | **7** | **6** | **2** | **0** |
| Gemma 4 E2B, retrained like v6 (3.42 GB) | 7 | 6 | 16 | 19 |
| d1-omni-600M, unedited | 0 | 0 | 0 | 0 |

Invented sets, risk flagged · benign flagged:

| Set | v7 | v4 |
| --- | --- | --- |
| Check | 87/90 · 0/45, pass | 87/90 · 0/45, pass |
| Third-style test | 102/120 · 2/55, pass | 103/120 · 2/55, pass |
| Handoff 90-chat | 27/36 · 5/36, fail | 26/36 · 4/36, fail |
| Handoff 60-chat | 17/24 · 2/12, fail | 24/24 · 3/12, fail by one |

- **Models trained only on invented chats do poorly on real ones.** v3 and v4 catch 3 of 10 real grooming testing chats and flag about a third of real ordinary windows.
- **Adding real grooming chats alone is harmful.** v5 and v6 flag over 80% of real ordinary windows.
- **v7 is the only model that catches most real grooming chats and stays quiet on real ordinary ones.** It is worse than v4 on the handoff 60-chat set (17/24 against 24/24).
- **Derek chose v7 as the shipped model on 19 November 2026.** v3 and v4 files are kept.

Limits: 10 real grooming chats per split, from overlapping conversations and one language; the ordinary chats are adult conversations; the real testing splits were used to compare v5, v6 and v7, so they are not a fresh test; the third-style and handoff sets were reused again; one training run each; v7 was not run on the sealed set. The d1 head edit refitted with the real grooming chats flags 9 of 10 real grooming testing chats but also 36 of 36 benign chats in the handoff 90-chat set, so it was dropped.

## Beacon: research data added, repeat runs and a blind test (20 November 2026)

Model names replace version numbers from here on. **Beacon** is the v4 recipe trained on four sources. Only the first is in this repository; **the other three must never be added to it**, so Beacon cannot be rebuilt from the repository alone.

| Source | Rows | In the repository |
| --- | --- | --- |
| `train-mixed-v3-restyled.jsonl` (invented chats) | 2,097 | Yes |
| Real grooming transcripts supplied by Derek, fuller text version, training split (10 chats, each repeated ten times) | 100 | No |
| PAN-12 research corpus, windows built from its official test half, training split (a third English, a third Cantonese, a third mixed; translated by a local Gemma 4 12B model with keyword-flagged lines dropped) | 261 | No |
| Derek's own ordinary Instagram chats, training split | 64 | No |

- File: `qwen3-1.7b-beacon-Q4_K_M.gguf`, 1,107,408,736 bytes, SHA-256 `5b873dbfa4c36fff10372f8c1cf28ae84182d6d41604bf8f8b83f21966debb5c`.
- PAN-12 windows: a risk window is 30 messages of a predator's chat holding at least three lines the organisers marked suspicious; an ordinary window is the first 30 messages of a two-person chat with no predator and no keyword hit. Splits keep each predator in one split. Risk windows are trained as "2 or 3".

Three runs of Beacon, same data and settings, different seeds (risk flagged · ordinary flagged):

| Set | Run 1 (shipped file) | Run 2 | Run 3 | v7 |
| --- | --- | --- | --- | --- |
| Real grooming transcripts, testing | 7/10 | 7/10 | 7/10 | 6/10 (earlier text version) |
| PAN-12 testing | 132/141 · 7/117 | 132/141 · 3/117 | 137/141 · 2/117 | 40/141 · 7/117 |
| Derek's ordinary chats, testing | 0/37 | 0/37 | 0/37 | 0/37 |
| Check | 89/90 · 0/45 | 85/90 · 0/45 | 89/90 · 0/45 | 87/90 · 0/45 |
| Third-style test | 112/120 · 9/55 | 107/120 · 3/55 | 114/120 · 7/55 | 102/120 · 2/55 |
| Handoff 90-chat | 30/36 · 1/36 | 26/36 · 0/36 | 30/36 · 4/36 | 27/36 · 5/36 |
| Handoff 60-chat | 18/24 · 3/12 | 21/24 · 1/12 | 19/24 · 2/12 | 17/24 · 2/12 |

Run-to-run spread is three to seven chats on the small sets, so differences of that size between models are not evidence.

Blind test: 500 conversations from PAN-12's official training half, which no model here was trained, tuned or tested on (200 from 102 predators none of which appear in the used half, 150 with sexual wording and no predator, 150 general). This half has no line-level marks, so a conversation counts as flagged if any of its overlapping 30-message windows is flagged.

| Model | Predator | Sexual look-alike | General |
| --- | --- | --- | --- |
| v4 | 111/200 | 60/150 | 26/150 |
| v7 | 106/200 | 59/150 | 18/150 |
| Beacon run 1 | 176/200 | 78/150 | 14/150 |
| Beacon run 2 | 169/200 | 78/150 | 11/150 |
| Beacon run 3 | 175/200 | 73/150 | 14/150 |

333 of those conversations were also translated to Cantonese or mixed by Claude Opus, which left about a sixth of the longer lines in English (mostly sexual lines in predator chats). On those 333, Beacon run 1 flags 115/133 predator and 11/100 general conversations in English, and 116/133 and 11/100 translated.

- **Derek chose Beacon (run 1) as the shipped model on 20 November 2026.** Earlier files are kept.
- **No model here separates adults' sexual chat from grooming**: about half of the look-alike conversations are flagged by every model.
- **The written instruction does not describe what PAN-12 taught.** `instruction-v1.txt` defines risk as pressure, conditional favours and threats and says a topic alone is not evidence; PAN-12's risk windows are mostly sexual approaches with no refusal or threat. Beacon flags them because of its training. The instruction and the level descriptions shown to parents should be brought into line before the next retrain.

Limits: the blind set is from the same source and era as Beacon's PAN-12 training data, so it shows generalisation to new predators of the same kind, not to another platform or to Hong Kong teenagers today; PAN-12 window labels are rough; the Cantonese is machine-translated and unreviewed; the third-style and handoff sets have been reused many times; the Zenodo record for PAN-12 lists no licence and its maintainers' derived-data repository warns against using models trained on it in practice; consent for the real transcripts and for Derek's contacts' chats is an open item for the Hong Kong privacy lawyer.

Memory on a desktop processor (not a phone): with the engine's resident memory hard-capped at 300 MB, below the 1.1 GB file, Beacon gave identical answers about a third slower, so the memory-mapped model file can be streamed from storage. Non-reclaimable working memory was about 1.7 GB at a 4,096-token context and about 1.2 GB at 2,048 with the prompt cache off; these figures include desktop-build overhead.
