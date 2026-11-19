# Handover to the GPU session

For the local session on the Windows 11 PC (RTX 5080, 16 GB). Read the design first: [`docs/research/2026-11-19-ig-guard-local-model-design.md`](../docs/research/2026-11-19-ig-guard-local-model-design.md). The cloud session's results are in [`results/stage1-report.md`](results/stage1-report.md).

Run every command from the repository root in PowerShell, on branch `research/local-risk-model`, with Python 3.12. The harness needs no packages.

## Retrain after the second Cantonese restyle (19 November 2026)

Read this section first. It is newer than everything below it. Steps 0 to 3 below were done in earlier sessions; this section says what to do now. It stands on its own, because nobody can answer questions during the run.

### Wait for Derek before spending GPU time

A third review sample is with Derek now: `research/data/cantonese-review-round3.md` (30 chats). If more than 6 of the 30 are marked "off", the Cantonese and mixed chats are reworded again and all the sets change again. **Do not start training until Derek says the round 3 sample passes.** A retrain on data that is about to change is wasted work and would use up a sealed look for nothing.

### What changed in the data, and why

- Derek, a native Hong Kong reader, reviewed samples of the invented Cantonese chats. In round 1 he rewrote 26 of 30. In round 2 he changed 21 of 30. Each round added style rules to `research/data/WRITING-GUIDE.md` ("Hong Kong texting style", rounds 1 and 2).
- After round 2, all Cantonese (`zh-Hant-yue`) and mixed chats in train, tune, check and sealed were reworded a second time to the new rules. Share of Cantonese and mixed messages that changed: about 36% of train, 34% of tune, 26% of check and 35% of sealed. The train, tune and check shares were measured from git history. The sealed share is the reworders' report and was not rechecked, because nobody reads sealed text.
- **English chats are byte for byte the same.** Labels (`level`), ids, message counts and speaker order did not change for any chat.
- The validator thresholds were relaxed so the new wording passes: Cantonese chats need at least 50% Chinese characters (was 60%), and mixed chats need at least 5% Latin letters (was 10%; the Chinese-character minimum stays 15%).
- Because the sealed wording changed, five blind labellers (who saw no labels) relabelled all 750 sealed chats again. The sealed test was rebuilt from that.

### The data now

| Set | Chats | File |
| --- | ---: | --- |
| Training | 1,500 | `research/data/train.jsonl` |
| Tuning | 225 | `research/data/tune.jsonl` |
| Check | 225 | `research/data/check.jsonl` |
| Sealed test | 661 kept, 89 set aside (of 750; 11.9%, under the 15% limit) | `research/data/sealed-test.jsonl`, `research/data/sealed-set-aside.jsonl` |

- Previous sealed round: 662 kept, 88 set aside. The kept sealed test now holds 298 risk, 144 benign, 120 potential and 99 unresolved chats. Two risk chats and six benign chats were set aside this time (the last round set aside none), so the stage 4 figure of 300 risk and 150 benign no longer applies. Per language, risk / benign kept: English 100 / 49, Cantonese 100 / 48, mixed 98 / 47.
- Set aside by language: English 29 of 250, Cantonese 25 of 250, mixed 35 of 250. By intended ending: low 6, potential 30, moderate 1, high 1, unresolved 51 (of 150 each).
- Blind labellers matched the intended ending exactly on 649 of 750 chats (86.5%, last round 652). Unresolved is still the fuzzy ending. Twelve kept chats were labelled high where the writer said moderate; they are kept because the pass mark does not separate the two.
- New `research/data/SEALED.sha256` (check it with `python research/datatools/seal.py verify`, which prints no content):

```
8990b6f1f797e74fe5c594faeea004d45d00ab9237a379aeb8a2be4e06ff178b  sealed-test.jsonl
5b0de37022687cf12d3d5436e38bf08f269c3d178ba627662e636fbf648d582d  sealed-set-aside.jsonl
```

- **The old mixed training file is stale.** `research/data/train-mixed-v3.jsonl` (the file the winning model trained on) holds the 1,500 training chats as they were before the restyle (942 of them have different wording now) plus 597 handoff development chats. Do not use it. `research/data/train-mixed-v3-restyled.jsonl` is the same file with the current `train.jsonl` in front and the 597 handoff rows unchanged. `python research/datatools/rebuild_mixed.py` rebuilds it. The handoff rows were not reworded (another author wrote them).

### How to retrain: same recipe as the current winner

The current winner is Qwen3 1.7B, LoRA, "v3" (see `research/results/stage3-report.md` and `stage4-report.md`). Change nothing in the recipe except the data file and the output name. The settings are the ones in the stage 3 report and the defaults in `research/train_lora.py`:

```powershell
python research/train_lora.py --base research/models/hf/Qwen3-1.7B --family qwen3 --out research/models/lora/qwen3-1.7b-lora-v4 --train research/data/train-mixed-v3-restyled.jsonl --epochs 3 --batch 2 --lr 1e-4 --merge
```

- Rank 16, seed 1234 (script defaults), 3 epochs, batch 2, learning rate 1e-4. `--base` is the folder the earlier run used; the stage 3 report shows it as `research/models/hf/<model>`. I could not see the exact folder name or the exact `--train` argument of the original v3 run in the repository, so check your own earlier command history and keep it the same. The v3 data recipe is described in the stage 3 report ("Closing the style gap").
- Scoring after each pass is on the tuning set (`research/data/tune.jsonl`), as in stage 3. Pick the pass the same way (pass 3 was chosen last time), keep the 0.5 alert cutoff unless tune clearly says otherwise, convert with llama.cpp b12503 and quantize to Q4_K_M as before. I found no written conversion commands in the repository; use the same ones as for v3.
- **Choose only on tune and check.** Open `check.jsonl` once settings and cutoff are frozen, as before. Do not choose anything on the sealed set, on `research/data/style3-test.jsonl`, or on the handoff 90-chat and 60-chat sets (those steered v3 already; report them as context only).
- Name the new file differently from the old one, for example `qwen3-1.7b-lora-v4-Q4_K_M.gguf`. Do not overwrite the v3 file.

### Honest caveats

1. **A second sealed result is not a clean first look.** The sealed set was already used once, to report the current model (stage 4: 300/300 risk, 0/150 benign). Its wording has since changed, and the labels and ids are the same. Score it once, after everything is frozen (training, pass, cutoff, file), report it as "second use, after a restyle", and change nothing afterwards.
2. **`research/data/style3-test.jsonl` was scored once already** (stage 4, third-style test) and must not be used to choose anything. Scoring it again as a report-only figure is fine if labelled as a repeat.
3. **Derek's round 3 verdict comes first** (see the top of this section). A further restyle would change the data again and this whole section would need redoing.
4. **The app pins the current model.** On branch `app/local-risk-scoring`, `RiskModel.kt`, `RiskModelSpec` has the file name `qwen3-1.7b-lora-v3-Q4_K_M.gguf`, size 1,107,408,736 bytes and SHA-256 `14f2176d569772bcc8e0e0dcd97e59f7ac60d8c4f6e1cb5f6a45159b1892c293`. A retrained model gets a new name, size and hash, so those three values must change there, and the test fixture (`tests/fixtures/risk-prompt-fixture.json`, made by `research/make_app_fixture.py` on that branch) must be regenerated. The model file (GGUF) must never be committed to git. Also change the model name string in `research/make_report.py`.
5. **All results are on invented chats only.** No real conversation has been scored, and by the project rules none may be. The same model family wrote the training and test chats, so near-perfect scores say little about real chats.

## Rules

- Do not open, print, score or search `research/data/sealed-test.jsonl`, `research/data/sealed-set-aside.jsonl`, `research/data/raw/sealed/` or `research/data/sealed-work/`, `research/data/sealed-work-round1/`, `research/data/restyle/sealed/` or `research/data/raw-round1/sealed/` before stage 4. `python research/datatools/seal.py verify` checks the hashes without showing content.
- Do not open `research/data/check.jsonl` until training settings and cutoffs are frozen. (The sealed rule above is for the retrain too: stage 4 is done, but the sealed files are still not for choosing anything.)
- Do not edit `research/prompts/instruction-v1.txt`. It is frozen.
- Synthetic chats only. Model files stay out of git (`research/models/` is ignored).
- **Do not start any fine-tuning until Derek has passed the Cantonese check** (now round 3: `research/data/cantonese-review-round3.md`; rounds 1 and 2 each led to a restyle of all Cantonese and mixed chats) and the stage 1 stop-or-go below says go.

## Step 0: set up and prove the harness on this machine

```powershell
python -m unittest discover -s research/tests
python research/fetch.py llama-win-cuda
python research/fetch.py llama-win-cuda-runtime
Expand-Archive research\models\llama-b12503-bin-win-cuda-12.4-x64.zip research\models\llama-b12503-win
Expand-Archive research\models\cudart-llama-bin-win-cuda-12.4-x64.zip research\models\llama-b12503-win -Force
python research/fetch.py d1-600m-q8
```

Start d1 and repeat the harness check on the GPU:

```powershell
research\models\llama-b12503-win\llama-server.exe -m research\models\d1-omni-600M-Q8_0.gguf --host 127.0.0.1 --port 8081 -ngl 99 --parallel 4 -c 8192 -b 4096 -ub 4096
```

```powershell
python research/run_eval.py --kind d1 --set blind90 --name d1-q8-gpu --port 8081 --workers 4
```

It must print 30–34 of 36 risk and 28–32 of 36 benign flagged (the CPU run here gave 32 and 29). If it does not, stop and report; do not run anything else.

**Known risk: the CUDA build may not support the RTX 5080.** llama.cpp b12503 publishes only a CUDA 12.4 Windows build, and its GPU library lists architectures up to sm_90, not the 50-series. If `llama-server.exe` fails to start or runs on CPU only, use the Vulkan build of the same release instead (`https://github.com/ggml-org/llama.cpp/releases/download/b12503/llama-b12503-bin-win-vulkan-x64.zip`; it is not in `models.json`, so record its SHA-256 in your notes), and rerun the d1 check. Do not move to a different llama.cpp release without telling Derek: d1's `/v1/systemone` call and the comparison with the handoff depend on this build.

## Step 1: finish the stage 1 ladder

One model at a time. Start the server in one terminal, run both sets in another, stop the server with Ctrl+C.

| Rung | Fetch key | File | Kind | Port |
| --- | --- | --- | --- | --- |
| Ceiling | `gemma4-12b-q4` | `gemma-4-12b-it-qat-q4_0.gguf` | `chat` | 8090 |
| Stretch | `gemma4-e4b-q4` | `gemma-4-E4B_q4_0-it.gguf` | `chat` | 8091 |
| Stretch | `d1-3b-q8` | `d1-3B-Q8_0.gguf` | `d1` | 8092 |
| Stretch (fits 2 GB) | `d1-3b-q4` | `d1-3B-Q4_K_M.gguf` | `d1` | 8093 |

Ceiling model:

```powershell
python research/fetch.py gemma4-12b-q4
research\models\llama-b12503-win\llama-server.exe -m research\models\gemma-4-12b-it-qat-q4_0.gguf --host 127.0.0.1 --port 8090 -ngl 99 -c 8192
```

```powershell
python research/run_eval.py --kind chat --set blind90 --name gemma4-12b-q4 --port 8090
python research/run_eval.py --kind chat --set severity60 --name gemma4-12b-q4 --port 8090
```

Gemma 4 E4B: the same three commands with key `gemma4-e4b-q4`, file `gemma-4-E4B_q4_0-it.gguf`, name `gemma4-e4b-q4`, port 8091.

d1-3B (decision model, so it needs the d1 server flags and `--kind d1`):

```powershell
python research/fetch.py d1-3b-q8
research\models\llama-b12503-win\llama-server.exe -m research\models\d1-3B-Q8_0.gguf --host 127.0.0.1 --port 8092 -ngl 99 --parallel 4 -c 8192 -b 4096 -ub 4096
```

```powershell
python research/run_eval.py --kind d1 --set blind90 --name d1-3b-q8 --port 8092 --workers 4
python research/run_eval.py --kind d1 --set severity60 --name d1-3b-q8 --port 8092 --workers 4
```

Repeat with key `d1-3b-q4`, file `d1-3B-Q4_K_M.gguf`, name `d1-3b-q4`, port 8093. The harness check printed for d1-3B on `blind90` applies to d1-600M only; ignore it for d1-3B.

For every chat model, open `research/results/blind90/<name>/run.json` once and read `first_prompt`: it must end exactly where the answer goes (`<|turn>model` and a newline for Gemma 4, with no `<|think|>` in the system turn). Check `answer_mass` in `metrics.json`; it should be close to 1.

Then rebuild the summary and commit the results:

```powershell
python research/report.py
git add research/results
git commit -m "research: stage 1 GPU rungs"
```

## Step 2: fill in the stop-or-go table

The label check (Sonnet 5.5) **passes** on both sets. So, from the design:

| Ceiling model (Gemma 4 12B) | Decision |
| --- | --- |
| Fails the pass mark | Go on. Labels are sound; untrained local models are weak, which is what fine-tuning is for. |
| Passes | Go on. |

Add the ceiling and stretch rows to `research/results/stage1-report.md` and state the decision.

## Step 3 (only after the Cantonese check passes): stage 3

Not started by the cloud session. Listed so the inputs are clear.

- **Data.** `research/data/train.jsonl` (1,500 chats) for fitting, `research/data/tune.jsonl` (225) for settings and cutoffs, `research/data/check.jsonl` (225) for the stage 3 stop-or-go. Row format: `id`, `family`, `language`, `ending`, `level` (0–3 or null for unresolved), `other_role`, `messages` (`speaker` is `child` or `other`). The training target is the single answer token: `0`, `1`, `2`, `3`, or `U` when `level` is null. Build the prompt exactly as the harness does (`research/igrisk/scorers.py`: `user_prompt` and `render_prompt`) so training and scoring match.
- **Fine-tune arm.** The two phone-sized rungs with the best AUROC on the 90-chat set are **Gemma 4 E2B (0.77)** and **Qwen3 1.7B (0.76)**. Qwen3Guard 0.6B (0.51) is out. Full-precision weights: `google/gemma-4-E2B-it` and `Qwen/Qwen3-1.7B` (see `research/models.md`). Note that Gemma 4 E2B is 3.35 GB at 4-bit in llama.cpp format, over the 2 GB limit; Qwen3 1.7B is about 1.1 GB at 4-bit.
- **d1 weight-edit arm.** Layer probe, then head-edit refit, then fine-tune only if the probe says so. Use `LiquidAI/d1-omni-600M` at revision `02b55d7076f15129e59ab3f94783f32c4b088674`, the revision the handoff pinned and reviewed, and read its Python files before running them.
- **Scoring a trained model on tuning or check:**

  ```powershell
  python research/run_eval.py --kind chat --set research/data/tune.jsonl --name <model-name> --port <port>
  ```

  A path given to `--set` is scored with `level` 0 as benign, 1 as potential, 2–3 as risk and null as unresolved.

## What the cloud session found that affects you

- Gemma 4 E2B and E4B are much larger than the design assumed (3.35 GB and 5.15 GB at 4-bit). See `research/models.md`.
- Qwen3Guard does not answer with the five tokens at all (its probability on them is about 3%), so its untrained result says little. It needs `--template chatml`.
- The d1 CPU result differs from the handoff's per-chat probabilities by up to 0.06 (mean 0.014). The harness subagent found that request batching is not the cause; hardware or runtime differences are the likely reason. Expect small differences on GPU too.
