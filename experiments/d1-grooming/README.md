# d1 grooming feasibility experiment

Independent text-only research harness on `experiment/d1-grooming-2026-11-17`.
No app code, live monitoring, account actions or real child data are used. Results are in
[REPORT.md](REPORT.md); Android evidence and unexecuted device steps are in
[ANDROID.md](ANDROID.md). All 90 input rows are authored synthetic, non-graphic chats.

Run from this directory. The baseline and harness tests need Python 3.12 and its standard
library only. Each output directory must be new, so recorded results cannot be overwritten.

```bash
python -m unittest -v test_harness.py
python run.py --backend baseline --output /tmp/d1-baseline-rerun
```

Before loading, read the pinned model README, LICENSE and every custom `.py` using the
immutable URLs in [model-provenance.json](evidence/model-provenance.json). The original
review is in [code-review.md](evidence/code-review.md). `fetch_model.py` only downloads
the enumerated, hash-pinned files and does not execute them. It includes the monolithic
checkpoint's unused vision/audio weights but fetches no demo media.

```bash
mkdir -p /workspace/private/d1-grooming
python fetch_model.py /workspace/private/d1-grooming/snapshot
uv venv /workspace/private/d1-grooming/venv
UV_CACHE_DIR=/workspace/private/d1-grooming/uv-cache uv pip install \
  --python /workspace/private/d1-grooming/venv/bin/python \
  --extra-index-url https://download.pytorch.org/whl/cpu -r requirements.lock
/workspace/private/d1-grooming/venv/bin/python run.py \
  --backend model --snapshot /workspace/private/d1-grooming/snapshot \
  --output /tmp/d1-model-rerun
```

`requirements.lock` captures the exact Linux x86-64 environment used, including CPU
PyTorch. The original setup installed `torch==2.9.1+cpu` from the CPU wheel index, then
`transformers==5.23.0 pillow==12.2.0`. Runtime model/tokenizer loading is local and offline;
model files are rehashed before `trust_remote_code=True`. Caches are redirected to
`/workspace/private/d1-grooming/`. No image/audio inference dependency or media path is used.

`synthetic.jsonl`, `questions.json`, `protocol.json` and `baseline.json` were frozen before
inference, with hashes and time in `evidence/frozen-inputs.json`. The development split has
18 rows; held-out-style has 72 rows (per language: 10 risk, 10 benign, 4 ambiguous review).
Each translated family stays in one split. These are hand-selected challenge examples
from one author, with a shared rubric and recognizable linguistic cues; they are not an
independent or representative validation set. The model receives only speaker/text messages.
No prompt or threshold was optimized on these results.

The baseline ports the exact captured `SEXUAL_PATTERN` literal from revision
`71606689c58806818a98c234b8ac7d82ef5ecb36`, applied to each message and speaker. The source
block and full-source hash are saved. This compares the app's content keyword gate, not its
trust lists, history, stranger alerts, UI filtering or sender-flagging side effects. Python
`re` and Kotlin/Java Unicode matching can differ; the literal and relevant matches are tested,
but a full Android regex parity run was not performed.

Each run retains machine information, input hashes, raw scores, zero-output token usage,
wall/CPU timings, process peak RSS and grouped metrics. Forced binary metrics use 0.5.
Selective metrics use the fixed inclusive review band [0.35, 0.65], with errors abstaining.
Review-labelled rows are excluded from binary denominators and counted separately.
`risk_not_cleared` includes risk rows deferred for human review as well as risk decisions.
The keyword baseline has no uncertainty estimate and never abstains.

Scores describe the supplied question's yes option. They are not offender probabilities,
identity determinations, evidence of an offence or task-calibrated abuse probabilities.

## Interrupted run recovery

The completed evidence is in `results/model-complete/` (90 real model predictions) and
`results/baseline/` (90 rule predictions). The initial 22-row smoke is preserved separately.
The original stopped process saved row 23 on resume, then lost its progress pipe. Its
`results/model/` files retain that failure and the unadjusted pause-inflated timings.
The repaired harness verified and reused those 23 rows, then inferred only the missing 67:

```bash
env OMP_NUM_THREADS=4 MKL_NUM_THREADS=4 OPENBLAS_NUM_THREADS=4 \
  TOKENIZERS_PARALLELISM=false taskset -c 0-3 \
  /workspace/private/d1-grooming/venv/bin/python -u run.py \
  --backend model --snapshot /workspace/private/d1-grooming/snapshot \
  --resume-from results/model --output results/model-complete \
  > /workspace/private/d1-grooming/full-run.log 2>&1
```

That output directory now exists; choose a new directory for any intentional rerun.
Resume rejects a changed fixture, question, protocol, baseline, backend or invalid prefix.
It preserves inherited prediction objects and writes the complete metrics into a new directory.
`run.json` timings describe that invocation only; inherited row timings remain from their
original process. The report excludes the interrupted row from latency percentiles and
labels approximate pause subtraction. Progress-reader disconnects no longer abort inference.
Original harness source is retained in `evidence/run-initial.py.txt` with its recorded hash.
