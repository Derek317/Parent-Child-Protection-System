# Handover to the GPU session

For the local session on the Windows 11 PC (RTX 5080, 16 GB). Read the design first: [`docs/research/2026-11-19-ig-guard-local-model-design.md`](../docs/research/2026-11-19-ig-guard-local-model-design.md). The cloud session's results are in [`results/stage1-report.md`](results/stage1-report.md).

Run every command from the repository root in PowerShell, on branch `research/local-risk-model`, with Python 3.12. The harness needs no packages.

## Rules

- Do not open, print, score or search `research/data/sealed-test.jsonl`, `research/data/sealed-set-aside.jsonl`, `research/data/raw/sealed/` or `research/data/sealed-work/`, `research/data/sealed-work-round1/`, `research/data/restyle/sealed/` or `research/data/raw-round1/sealed/` before stage 4. `python research/datatools/seal.py verify` checks the hashes without showing content.
- Do not open `research/data/check.jsonl` until training settings and cutoffs are frozen.
- Do not edit `research/prompts/instruction-v1.txt`. It is frozen.
- Synthetic chats only. Model files stay out of git (`research/models/` is ignored).
- **Do not start any fine-tuning until Derek has passed the Cantonese check** (round 2: `research/data/cantonese-review-round2.md`; round 1 led to a restyle of all Cantonese and mixed chats) and the stage 1 stop-or-go below says go.

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
