# d1 grooming feasibility — completed synthetic experiment

18 November 2026 (Hong Kong) · branch `experiment/d1-grooming-2026-11-17`

This experiment branch now includes security hardening commit `18e9693`. The captured rules baseline remains pinned to `71606689c58806818a98c234b8ac7d82ef5ecb36` for reproducibility; rebasing did not change or rerun the frozen model evaluation. No classifier is integrated into the Android app.

**Actual inference completed for all 90 frozen synthetic chats using
`LiquidAI/d1-omni-600M`, original F32 weights.** The 90-row rules baseline is also
complete. This prompt/model combination is unsuitable for deployment on this evidence:
on the held-out-style binary cases, d1 detected 27/30 risk cases but flagged 26/30 benign
cases. Rules detected 12/30 risk cases and flagged 11/30 benign cases. Both achieved
31/60 correct forced binary decisions on this deliberately challenging sample.
Neither result validates a child-safety product.

## What was evaluated

The immutable model revision is `02b55d7076f15129e59ab3f94783f32c4b088674`.
Its 2,348,774,500-byte safetensors file has SHA-256
`0713bb05270c2685ad106522f4092bceeeb3a93cf79b401f399a712296c911e1`.
The pinned README, license and all five custom Python files were reviewed before the
original load; see [code review](evidence/code-review.md) and
[file provenance](evidence/model-provenance.json). Neither weights nor custom model code
were changed. The resumed load reverified every file and used the existing external
snapshot and venv, with no new downloads.

The dataset has 30 authored families, each rendered in English, Cantonese using
Traditional Chinese (`zh-Hant-yue`), and mixed English/Cantonese. Its 18 development
rows contain six risk, six benign and six ambiguous cases; the 72 held-out-style rows
contain 30 risk, 30 benign and 12 ambiguous cases. Translations stay in the same split.
Examples cover dependency, secrecy, conditional gifts, channel migration, requests,
coercion, concealed meetings, age deception, refusals, safety education and injections.
Every row is synthetic and non-graphic; no real child data or app chats were used.

[Inputs and thresholds](evidence/frozen-inputs.json) were frozen at
15:15:33 UTC before inference. The model received only `{"chat": messages}` and the
fixed English [question](questions.json), including speaker strings and text. Gold
labels, rationales, family, split and stage were excluded. No prompt changes, fitting,
threshold search or selection of successful examples followed the smoke results.

The original decision API performs one forward pass per chat/question and returns a
yes-option score. This is not an offender probability or a grooming-calibrated abuse
probability. Forced binary uses score >= 0.5; selective decisions defer the inclusive
[0.35, 0.65] band to review. Ambiguous gold cases are excluded from binary denominators.
All 90 model responses were valid with zero generated tokens and no inference errors.
[Tokenizer checks](evidence/tokenization.json) establish that none of these inputs,
instructions or criteria were truncated: total lengths were 114–190 tokens against a
16,384-token context; instructions occupied 53 of their 95 available tokens.

The rules baseline is the captured `SEXUAL_PATTERN` content gate from app revision
`71606689c58806818a98c234b8ac7d82ef5ecb36`, applied to each speaker and message.
Its [literal and scope](baseline.json) are preserved. It excludes trust lists, sender
history, stranger alerts, UI filtering and side effects. Python `re` is used; full
Kotlin/Java Unicode regex parity was not measured.

## Frozen quality results

Held-out-style forced binary results, excluding the 12 ambiguous cases:

| Language | Backend | TP / FP / TN / FN | Precision | Recall | False-positive rate | False-negative rate |
| --- | --- | --- | --- | --- | --- | --- |
| All | d1 | 27 / 26 / 4 / 3 | 50.9% | 90.0% | 86.7% | 10.0% |
| All | Rules | 12 / 11 / 19 / 18 | 52.2% | 40.0% | 36.7% | 60.0% |
| English | d1 | 9 / 8 / 2 / 1 | 52.9% | 90.0% | 80.0% | 10.0% |
| English | Rules | 2 / 4 / 6 / 8 | 33.3% | 20.0% | 40.0% | 80.0% |
| Cantonese / Traditional Chinese | d1 | 9 / 9 / 1 / 1 | 50.0% | 90.0% | 90.0% | 10.0% |
| Cantonese / Traditional Chinese | Rules | 6 / 3 / 7 / 4 | 66.7% | 60.0% | 30.0% | 40.0% |
| Mixed | d1 | 9 / 9 / 1 / 1 | 50.0% | 90.0% | 90.0% | 10.0% |
| Mixed | Rules | 4 / 4 / 6 / 6 | 50.0% | 40.0% | 40.0% | 60.0% |

There are ten risk and ten benign cases per language. Development totals (12 binary
cases) were d1 **5 TP / 5 FP / 1 TN / 1 FN**, precision 50.0%, recall 83.3%, FPR 83.3%;
rules **3 TP / 2 FP / 4 TN / 3 FN**, precision 60.0%, recall 50.0%, FPR 33.3%.
Complete development/per-language counts and error IDs are retained in the metrics files.

Held-out-style d1 results with the fixed review band:

| Language | Binary coverage | Decided TP / FP / TN / FN | Risk → review | Benign → review | Ambiguous → review |
| --- | --- | --- | --- | --- | --- |
| All | 46/60 (76.7%) | 23 / 21 / 1 / 1 | 6/30 | 8/30 | 5/12 |
| English | 13/20 (65.0%) | 8 / 5 / 0 / 0 | 2/10 | 5/10 | 3/4 |
| Cantonese / Traditional Chinese | 16/20 (80.0%) | 8 / 7 / 0 / 1 | 1/10 | 3/10 | 0/4 |
| Mixed | 17/20 (85.0%) | 7 / 9 / 1 / 0 | 3/10 | 0/10 | 2/4 |

Among the 46 decided binary rows, precision was 52.3%, recall 95.8% and FPR 95.5%.
These conditional metrics omit the 14 deferred binary cases. Across all 30 risk cases,
23 were flagged, six deferred and one cleared; **29/30 “not cleared” includes review,
not 29 detections**. Across benign cases, 21 were flagged, eight deferred and only one
cleared. There were 19/72 total abstentions including ambiguous cases. Of the seven
ambiguous cases not deferred, six were flagged and one cleared. The rules never
abstained: binary coverage 60/60 and ambiguous deferrals 0/12. Development d1 coverage
was 9/12, with four of six ambiguous cases deferred.

Examples illustrate the tradeoff rather than a general ability claim:

| Frozen case | Gold | d1 score → decision | Rules | Observation |
| --- | --- | --- | --- | --- |
| `h01-en` | benign | 0.86465 → risk | benign | Ordinary supervised activity is overflagged. |
| `h14-en` | risk | 0.92013 → risk | benign | Concealed channel migration is detected without a matching keyword. |
| `h11-en` | risk | 0.53052 → review | benign | Dependency manipulation is deferred, not detected by a risk decision. |
| `h20-zh-Hant-yue` | risk | 0.34801 → benign | benign | Risk with an embedded instruction is missed; delimiter escaping does not prove semantic injection resistance. |
| `h23-zh-Hant-yue` | review | 0.34035 → benign | benign | Missing context is not reliably represented by the review band. |

Exact scores, messages, labels and all errors can be inspected in the linked artifacts;
example scores above are rounded to five decimal places.

## Execution, time and memory

Linux x86-64 shared container; Intel Xeon Platinum 8573C; five visible logical CPUs,
cgroup quota four CPUs, memory limit 32 GiB. Python 3.12.14, CPU PyTorch 2.9.1+cpu,
Transformers 5.23.0, F32, batch size one, no CUDA. Both model processes used four
PyTorch compute threads and one interop thread. The resumed process additionally used
CPU affinity 0–3, OMP/MKL/OpenBLAS caps of four and disabled tokenizer parallelism.
Other work shared the host; this was not an isolated speed test.

The previously stopped process retained the 22-row smoke. SIGCONT saved the interrupted
23rd row (`h02-zh-Hant-yue`), then its detached progress pipe raised `BrokenPipeError`.
The original files and failure remain in `results/model/`. A tested harness repair made
progress-reader loss nonfatal and added `--resume-from`, validating input hashes,
protocol, backend, model revision, prefix order and saved decisions. The new output
retains those 23 prediction objects and infers only the remaining 67. Both loads produced
the same refund warmup score, 0.99558794. No completed fixture inference was repeated.

| Measurement | Original process / 23 rows | Resumed process / 67 new rows | Rules / 90 rows |
| --- | --- | --- | --- |
| Total process wall, excluding metadata setup | 12,388.993 s **including suspension** | 102.111 s | 0.022441 s |
| Total process CPU | 243.402 s | 225.970 s | 0.006912 s |
| Model-load wall / CPU | 13.976 / 5.576 s | 2.449 / 2.303 s | — |
| Warmup wall | 6.482 s | 0.465 s | — |
| Process peak RSS | 1,883.223 MiB | 1,758.379 MiB | 15.750 MiB |

The resumed load is a reload with filesystem caches potentially warm, not a cold-start
benchmark. Totals include hash verification/imports, loading, warmup and loop work;
per-row timings bracket scoring/decision, excluding result serialization and logging.
Peak RSS is Linux process `ru_maxrss`, not an Android memory measurement or the sum of
parameter storage. The model reports 587,143,681 parameters / 2,348,574,724 parameter
bytes, including instantiated unused modality towers. Artifact bytes and RSS are distinct.

The interrupted row's raw wall time is **12,171.299 s**. Raw combined process wall is
12,491.103 s; combined CPU is 469.372 s. [Pause evidence](evidence/resume.json) records
12,098.013 s from confirmed stop to resume; the earlier UTC pause-request marker gives
12,168.403 s. Those markers differ by 70.390 s, so exact pause-free total and that row's
latency cannot be recovered. Subtracting the respective markers gives approximate
combined wall totals of 393.090 s or 322.700 s, not a measured uninterrupted run.

[Timing evidence](evidence/timing.json) excludes that one row for pooled latency:
**89 rows: p50 0.693 s, nearest-rank p95 9.340 s**. The initial 22 had p50/p95
7.565/16.038 s; the new 67 had **0.583/4.793 s**, summing to 89.815 s of row wall and
213.354 s of row CPU. Different cases and shared contention confound comparison.
Rules took p50 12.391 µs and p95 85.341 µs per row across all 90. These are single-pass
observations, without repeated trials, uncertainty estimates, energy or thermal data.
Saved raw metrics intentionally retain original times; use the explicit exclusions here.

## Android and deployment limits

[ANDROID.md](ANDROID.md) separates source support from device evidence and supplies
an unexecuted isolated device recipe. The official pinned GGUF repository lists Q8_0
text weights (407,207,584 bytes, approximately 388.34 MiB) and a separate multimodal
projector. Pinned llama.cpp source explicitly dispatches `COMMON_DECISION_TYPE_LFM2_D1_OMNI`
and constructs its decision input; the official model guide specifies llama.cpp 0.7.0+
and `/v1/systemone`. This is concrete artifact/source evidence for decisions, not a
successful build, GGUF numerical-parity check or phone result. Upstream endpoint tests
shown in the retained source use other model presets.

No Android device test, runtime build or GGUF inference was performed. `adb` was absent
in the resumed environment. The Android probe passed a local HTTP stub test only.
There are no measured phone load time, latency, RSS, energy, sustained thermal behavior
or verified Kotlin/JNI decision bindings. The Q8 artifact is not tied by its card to the
exact F32 revision evaluated here, and generic LFM2/ONNX/SDK support establishes no
d1 decision-head export path. All 14 previously retrieved source/document snapshots
were hash-checked without fetching them again.

The sample is small, balanced by construction, written and labelled by one author,
family-correlated across translations and rich in explicit ages/relationship cues.
“Held-out-style” means disjoint families, not independent annotation or a validated test
set. The English question, synthetic code-switching and short windows do not establish
Cantonese population coverage or longitudinal grooming detection. The hand-labelled
rubric is contestable. The fixed review band is a heuristic, not calibrated uncertainty;
low scores cannot establish safety, and high scores establish neither identity nor an
offence. These observations justify neither automated accusations nor production triage.

LFM Open License v1.0 applies; see the pinned license review for commercial threshold
and redistribution conditions. Research use here establishes no future product entity's
commercial eligibility. App security and integration remain outside this experiment.

## Retained evidence and verification

- [Complete model predictions](results/model-complete/predictions.jsonl),
  [metrics](results/model-complete/metrics.json) and [run record](results/model-complete/run.json).
- [Rules predictions](results/baseline/predictions.jsonl),
  [metrics](results/baseline/metrics.json) and [run record](results/baseline/run.json).
- [Original smoke](evidence/initial-smoke.json), [original harness](evidence/run-initial.py.txt),
  [original interrupted run](results/model/run.json), [recovery command](README.md#interrupted-run-recovery).
- [Validation record](evidence/benchmark-validation.json): 90 unique ordered rows per
  backend; unchanged frozen inputs and saved prefix; valid zero-output-token responses;
  exact untruncated token counts; baseline replay equivalence; metric recalculation and
  independent forced confusion counts; hashes of result files.
- [Harness tests after recovery](evidence/harness-tests-resumed.txt): eight passing tests,
  including closed-pipe survival and a real CLI resume that retains 23 rows and computes
  only 67, plus invalid-resume rejection, metric arithmetic, review boundaries, family
  isolation, the captured regex and the local Android transport stub.

All changes are confined to `experiments/d1-grooming/` in this worktree. Model files,
venv, downloads, runtime caches and source snapshots remain external under
`/workspace/private/d1-grooming/`. No app checkout changes, agents, push, deployment or
merge were used.
