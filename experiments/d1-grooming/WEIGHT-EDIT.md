# Direct d1 weight edit for IG Guard

18 November 2026 (Hong Kong). This experiment edits Liquid AI's
d1-omni-600M at revision `02b55d7076f15129e59ab3f94783f32c4b088674`.
The published edit is a 13 KB safetensors file. The exported F32
checkpoint is 2,348,774,628 bytes. A tensor-by-tensor comparison
verified edits to exactly three existing tensors. The other 1,081
tensors match the original checkpoint. [Pinned model](https://huggingface.co/LiquidAI/d1-omni-600M/tree/02b55d7076f15129e59ab3f94783f32c4b088674) · [Export comparison](results/weight-edit/checkpoint-comparison.json)

## Result

**The edited checkpoint missed the quality target.** On the new blind set
it flagged fewer benign chats, but missed 21 of 36 risk cases.
The replay of the earlier benchmark lowered false alarms from 26 to 2,
while reducing detected risks from 27 to 2. On that benchmark the edit
made discrimination worse overall. It is a reproducible research result
and the APK still uses its existing detection. [All metrics](results/weight-edit/metrics.json) · [Per-case scores](results/weight-edit/predictions.jsonl)

| Test set | Method | Risk caught | Benign flagged | AUROC |
| --- | --- | ---: | ---: | ---: |
| Blind, 72 binary cases | Original d1 | 29/36 | 30/36 | 0.500 |
| Blind, 72 binary cases | Original, selection-tuned cutoff | 21/36 | 26/36 | 0.500 |
| Blind, 72 binary cases | Edited d1 | 15/36 | 17/36 | 0.541 |
| Earlier benchmark replay, 60 binary cases | Original d1 | 27/30 | 26/30 | 0.573 |
| Earlier benchmark replay, 60 binary cases | Original, selection-tuned cutoff | 23/30 | 21/30 | 0.573 |
| Earlier benchmark replay, 60 binary cases | Edited d1 | 2/30 | 2/30 | 0.358 |

The edited scores caught 4/12 English, 6/12 Cantonese and 5/12
mixed-language risk cases and flagged 4/12, 10/12 and 3/12 benign
cases respectively. Seven of 18 ambiguous holdout examples landed
in the original review band after the edit, compared with four
before it. This remains a small synthetic comparison with related
language variants of each scenario. The two sets gave different
results, and the blind AUROC remained close to 0.5. Continuing to
select weights based on these same cases would compromise the blind
comparison. Android inference and quantized-model parity remain
untested.

The F32 CPU evaluation ran 162 cases through the full System One API
twice, once per checkpoint, in 321 seconds with peak RSS of 1,883 MiB.
The exported checkpoint can recreate those scores without applying a
runtime patch. The compact patch leaves the full model's size virtually
the same. [Timing](results/weight-edit/timing.json)

## Method

The model scores a false option and a true option with a shared
scorer. We captured the difference between their activations and
solved a regularized least-squares problem with synthetic labeled
examples and no gradient updates. Pairwise terms encouraged the
weight edit to separate a concerning exchange from a benign
alternative in the same setting. The original question's final
decision cutoff remains 0.5. The linked reference describes a
different use of direct weight edits, suppressing reasoning loops
in Qwen. Its result does not establish generalization for this
classification task. [Article](https://www.mindstudio.ai/blog/gpt-5-astra-weight-space-programming)

The chosen edit uses the features before and after the scorer's
GELU layer. GELU(x) minus GELU(−x) equals x, so two existing neurons
can implement the additional projection. The patch rewrites those
two weight rows, zeros their biases, and replaces the final linear
readout. It changes 3,074 entries across those three tensors. No
new network layers are required. [Fitter](fit_inner.py) · [Applied weights](results/weight-edit/d1-grooming-head.safetensors)

The 108 fitting rows cover 18 matched scenario families. The
48 selection rows use eight other families. English, Cantonese
in Traditional Chinese, and mixed-language versions of a scenario
stay together. A separately authored holdout contains 12 matched
families, or 36 risk and 36 benign conversations, plus 18
insufficient-context cases. Fitting and selection used its hash
only. The holdout opened after the patch was frozen. The original
72 held-out-style cases were rerun as a historical replay.

The fixed quality target is overall risk recall of at least 80%
and benign false alarms of at most 20%, with language-group
targets of at least 70% recall and at most 30% false alarms.
Gold review cases are reported separately under the original
0.35–0.65 review band. A cutoff selected on the selection set
provides a threshold-only control using the original weights.
[Protocol](weight-protocol.json) · [Selection and provenance](results/weight-edit/selection.json)

| Development attempt | Risk caught | Benign flagged |
| --- | ---: | ---: |
| Original model and question | 24/24 | 22/24 |
| Final tensor edit, original question | 13/24 | 4/24 |
| Final tensor edit, focused question | 15/24 | 10/24 |
| Two-neuron edit, original question | 17/24 | 7/24 |

The two-neuron edit had the best minimum recall-minus-false-positive
rate across the overall and language groups among these three
edits. It was selected on that criterion and verified through the
model's live inference API before the holdout was scored. None of
the development attempts met the quality target.

## Reproduce

Use the pinned dependencies in [requirements.lock](requirements.lock) and
the reviewed base snapshot from `fetch_model.py`. Store checkpoint
files outside the Git checkout. The exported checkpoint includes
the model weights, reviewed loader files and upstream license. The
published weight patch is licensed under the same terms.
[Download script](fetch_model.py) · [LFM Open License](results/weight-edit/LICENSE)

```bash
python -m unittest -v test_weight_edit.py test_harness.py
python weight_edit.py evaluate --snapshot /path/to/pinned-d1 \
  --artifact results/weight-edit --holdout weight-test.jsonl \
  --output /tmp/igguard-d1-edit-evaluation
python weight_edit.py export --snapshot /path/to/pinned-d1 \
  --artifact results/weight-edit --output /tmp/igguard-d1-edited-snapshot
```

Use the holdout digest from `results/weight-edit/selection.json` to
reproduce fitting. The helper takes the digest without reading the
holdout cases. Use the new fit folder as `--artifact` for evaluation
or export.

```bash
python fit_inner.py --snapshot /path/to/pinned-d1 \
  --holdout-sha256 1652ae95d68b9e7e33be50cf4474259fd57141f75aae0536093bee70f3a2230b \
  --output /tmp/igguard-d1-edit-fit
```
