# IG Guard local-model evaluation harness

Runs a model on the synthetic chat sets and reports the pass mark from the design
(`docs/research/2026-11-19-ig-guard-local-model-design.md`). Python 3.12, standard library only.
Run every command from the repository root. On Windows use `python` where this file says `python3.12`.
Everything below works in PowerShell unless noted.

## 1. Fetch llama.cpp and a model

Linux CPU:

    python3.12 research/fetch.py llama-linux-cpu
    mkdir -p research/models/llama-b12503-linux
    tar -xzf research/models/llama-b12503-bin-ubuntu-x64.tar.gz -C research/models/llama-b12503-linux
    python3.12 research/fetch.py d1-600m-q8

Windows (NVIDIA GPU):

    python research/fetch.py llama-win-cuda
    python research/fetch.py llama-win-cuda-runtime
    Expand-Archive research\models\llama-b12503-bin-win-cuda-12.4-x64.zip research\models\llama-b12503-win
    Expand-Archive research\models\cudart-llama-bin-win-cuda-12.4-x64.zip research\models\llama-b12503-win -Force
    python research/fetch.py d1-600m-q8

`python research/fetch.py --list` shows all keys. Downloads go to `research/models/` (git-ignored) and are
checked against the size and sha256 in `research/models.json`.

The only CUDA build published for b12503 is 12.4. It was built for GPUs up to sm_90; an RTX 5080 (sm_120)
may need the PTX fallback or may not start. If `llama-server` fails on the GPU, tell the researcher; do not
change the build on your own.

## 2. Start the server

Leave it running in its own terminal. Use a different port per model.

d1 (needs `-b 4096 -ub 4096`; 4 slots of 2048 tokens each):

    # Linux CPU
    research/models/llama-b12503-linux/llama-b12503/llama-server -m research/models/d1-omni-600M-Q8_0.gguf --host 127.0.0.1 --port 8081 -t 4 --parallel 4 -c 8192 -b 4096 -ub 4096

    # Windows GPU
    research\models\llama-b12503-win\llama-server.exe -m research\models\d1-omni-600M-Q8_0.gguf --host 127.0.0.1 --port 8081 -ngl 99 --parallel 4 -c 8192 -b 4096 -ub 4096

Chat models (example Qwen3-1.7B; fetch key `qwen3-1.7b-q8`):

    # Linux CPU
    research/models/llama-b12503-linux/llama-b12503/llama-server -m research/models/Qwen3-1.7B-Q8_0.gguf --host 127.0.0.1 --port 8083 -t 4 -c 8192

    # Windows GPU
    research\models\llama-b12503-win\llama-server.exe -m research\models\Qwen3-1.7B-Q8_0.gguf --host 127.0.0.1 --port 8083 -ngl 99 -c 8192

Stop the server with Ctrl+C when you are done.

## 3. Run one model on one set

    python3.12 research/run_eval.py --kind keywords --set blind90 --name keywords
    python3.12 research/run_eval.py --kind d1 --set blind90 --name d1-q8 --port 8081 --workers 4
    python3.12 research/run_eval.py --kind d1 --set severity60 --name d1-q8 --port 8081 --workers 4
    python3.12 research/run_eval.py --kind chat --set blind90 --name qwen3-1.7b-q8 --port 8083

Options:

- `--set` is `blind90`, `severity60`, or a JSONL path whose rows have a `level` (0-3 or null).
- `--kind labels --labels file.jsonl` scores an outside labeller's answers (`{"id": ..., "answer": "0|1|2|3|U"}`).
- `--template chatml` renders plain ChatML by hand instead of the server's template. **Qwen3Guard-Gen needs it**:
  its own template ignores the system message and asks its fixed moderation question.
- `--chat-kwargs '{"enable_thinking": false}'` is the default and switches thinking off for Qwen3 and Gemma.
  In PowerShell pass JSON with single quotes outside, as shown.
- Re-running the same command resumes: finished chats in `predictions.jsonl` are skipped. Delete the
  folder to start again.

Output goes to `research/results/<set>/<name>/`: `predictions.jsonl`, `metrics.json`, `run.json`.
For chat runs `run.json` holds the first rendered prompt. Read it once per model: the answer token must
start right after the last character. Check `answer_mass` in `metrics.json` too; if it is low on many chats
the model is not answering with a bare `0`-`3` or `U`, and the summary will say so.

The d1 run on `blind90` also prints a harness check. It must show 30-34 of 36 risk and 28-32 of 36 benign flagged.

## 4. Build the summary

    python3.12 research/report.py

Writes `research/results/stage1-summary.md` from every `metrics.json`.

## 5. Run the tests

    python3.12 -m unittest discover -s research/tests
