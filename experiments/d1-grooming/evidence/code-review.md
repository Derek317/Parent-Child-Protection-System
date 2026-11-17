# Review before loading external model code

Reviewed 2026-11-17, before the first model import/load, at revision
`02b55d7076f15129e59ab3f94783f32c4b088674`. File hashes and immutable download URLs are in
`model-provenance.json`. The pinned README, LICENSE, config and all five custom Python files
were read in full. No model code was executed to perform this review.

- `modeling_d1.py` (168 lines): config/model definitions, local tensor inference, batching,
  question dispatch, softmax and temperature application. Its lazy tokenizer uses
  `AutoTokenizer.from_pretrained(self.name_or_path, trust_remote_code=True)`. Loading an
  absolute verified local snapshot with offline flags keeps that reference local.
- `encoder.py` (172 lines): bidirectional LFM-style trunk, centered short convolutions,
  rotary attention and decision head. No file/network/process operations observed.
- `prompt.py` (131 lines): typed question validation, JSON serialization, delimiter escaping,
  option rendering, right truncation, score conversion. Special strings matching
  `<|[A-Za-z0-9_]+|>` are escaped; this is structural protection, not semantic injection
  resistance. Binary instructions have a 96-token budget; the harness checks its question
  fits before inference. Option descriptions also have a per-option token limit.
- `vision.py` (113 lines): geometric image preprocessing and Siglip2 model construction.
  `torchvision` is imported only inside image preprocessing, which this experiment never
  invokes. The vision tower is still instantiated and its weights loaded.
- `audio.py` (250 lines): numpy/torch waveform, mel filterbank, Conformer and adapters.
  No media file loading or remote access observed. No audio methods are invoked.

No `eval`, `exec`, shell execution, subprocess calls, telemetry, network clients or arbitrary
file writes were observed in these five files. Imports transitively execute installed
PyTorch/Transformers dependencies; this review is not a complete dependency supply-chain
audit. Large weights are safetensors, not a pickle checkpoint. Original code is unmodified.
All external code, weights, venvs and runtime caches remain outside Git under
`/workspace/private/d1-grooming/`.

The README recommends CPU float32 and `transformers>=5.15`; inference uses float32 and
Transformers 5.23.0 with CPU PyTorch 2.9.1. The code implements a decision API, not a chat
generator. Binary answers are conditional yes-option scores. The included generic
temperatures do not establish calibration for grooming, Cantonese or this dataset.

The LICENSE is **LFM Open License v1.0**, not Apache. Sections 2–3 make grants subject to
section 5. Section 5 restricts commercial use for entities exceeding its threshold;
the threshold definition is annual revenue of US$10,000,000 or more. Sections 4(a–d)
require the license copy, notices on modified files, retained attribution and any NOTICE
on redistribution. Section 5(c) addresses qualifying nonprofits doing noncommercial or
research work. This isolated feasibility experiment exercises the research use; it does
not establish that a future product entity qualifies for commercial deployment. The
threshold boundary language should be resolved before a commercial release near it.
The original LICENSE is retained outside Git and hash-pinned in the provenance manifest.
