# Models in the ladder, as confirmed

Checked on 18 November 2026 against each publisher's Hugging Face page (repository metadata, file list with LFS SHA-256, and licence field or licence file). URLs are pinned to a revision so the bytes cannot change. The same facts are in machine-readable form in [`models.json`](models.json); `python research/fetch.py <key>` downloads a file and verifies size and checksum.

## Where the design's facts were wrong

| Design said | What the publisher's page shows | Effect |
| --- | --- | --- |
| Gemma 4 E2B is about 1.5 GB | The 4-bit GGUF is **3.35 GB** (Google's QAT file) or 2.84 GB (ggml-org Q4_0). E2B means 2.3B *effective* parameters; with its per-layer embedding tables the model has 5.1B. | **E2B does not fit the 2 GB limit in llama.cpp format.** It still runs as a phone-sized rung, but if it wins, the size limit or the format has to change. Google's "mobile" build is 2.67 GB and is not a llama.cpp file. |
| Gemma 4 E4B is about 3 GB | 5.15 GB (Google QAT 4-bit) | Further over the limit than assumed. |
| Qwen3Guard 0.6B | Exists as `Qwen3Guard-Gen-0.6B`. Qwen publishes weights only, no GGUF. | A community conversion (mradermacher) is used. It is a moderation model trained to answer "Safety: Safe/Unsafe…", so the five-token instruction is outside what it was trained for. |
| Qwen3 1.7B | Exists. Qwen's own GGUF repository has 8-bit only (1.83 GB). | 8-bit is used for stage 1 and is under 2 GB. 4-bit files (about 1.1 GB) exist from ggml-org and unsloth. |
| d1-3B | Exists, with official GGUF. The 4-bit file is 1.67 GB. | d1-3B at 4-bit is inside the 2 GB limit, so it is a phone candidate as well as a stretch rung. |
| Gemma 4, Qwen3Guard are Apache-2.0 | Confirmed on the model cards. | None. |

## Files

| Rung | Key in `models.json` | Repository @ revision | File | Bytes | Licence |
| --- | --- | --- | --- | ---: | --- |
| Control | `d1-600m-q8` | `LiquidAI/d1-omni-600M-GGUF` @ `8eb2d2a1` | `d1-omni-600M-Q8_0.gguf` | 407,207,584 | LFM Open License v1.0 |
| Phone-sized | `qwen3guard-0.6b-q8` | `mradermacher/Qwen3Guard-Gen-0.6B-GGUF` @ `2a59799e` (from `Qwen/Qwen3Guard-Gen-0.6B`) | `Qwen3Guard-Gen-0.6B.Q8_0.gguf` | 804,753,920 | Apache-2.0 |
| Phone-sized | `qwen3-1.7b-q8` | `Qwen/Qwen3-1.7B-GGUF` @ `90862c4b` | `Qwen3-1.7B-Q8_0.gguf` | 1,834,426,016 | Apache-2.0 |
| Phone-sized | `gemma4-e2b-q4` | `google/gemma-4-E2B-it-qat-q4_0-gguf` @ `675cff42` | `gemma-4-E2B_q4_0-it.gguf` | 3,349,516,256 | Apache-2.0 |
| Stretch | `gemma4-e4b-q4` | `google/gemma-4-E4B-it-qat-q4_0-gguf` @ `4b4a2c1d` | `gemma-4-E4B_q4_0-it.gguf` | 5,154,941,280 | Apache-2.0 |
| Stretch | `d1-3b-q8` | `LiquidAI/d1-3B-GGUF` @ `bb1e436e` | `d1-3B-Q8_0.gguf` | 2,874,781,280 | LFM Open License v1.0 |
| Stretch | `d1-3b-q4` | same | `d1-3B-Q4_K_M.gguf` | 1,674,456,672 | LFM Open License v1.0 |
| Ceiling | `gemma4-12b-q4` | `google/gemma-4-12B-it-qat-q4_0-gguf` @ `29d09777` | `gemma-4-12b-it-qat-q4_0.gguf` | 6,975,879,296 | Apache-2.0 |

SHA-256 of each file:

```
cd94463f4cac9c700ec6750df8df005bb6846de410e159d1f5e985e1171693d3  d1-omni-600M-Q8_0.gguf
127e8833aa16c6839613cd08df5409440302e8c61e90617914b3100f2141411a  Qwen3Guard-Gen-0.6B.Q8_0.gguf
061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a  Qwen3-1.7B-Q8_0.gguf
fa401b55b07ee70a54c6dae3903c783a6e65064312529ea57175cb5f8dec6634  gemma-4-E2B_q4_0-it.gguf
676c35070db6dbe52f93e9c864ee0fba4eddea94b9c875d9cb10daff453fbaee  gemma-4-E4B_q4_0-it.gguf
2f0942d5a5f64cf69c3356d2be439b71644b1f0eb3a580912a5a0179eeaba77d  d1-3B-Q8_0.gguf
16aff27ea2eefdc32b9897f43854a5d3170c1dc8dccb9c756905af30a4e22402  d1-3B-Q4_K_M.gguf
93567e57a8fe10b23569b9d9ec38cd005deedf71e29477c421a4b83f418a538b  gemma-4-12b-it-qat-q4_0.gguf
```

The d1 file, revision and checksum are the ones the handoff pinned (`artifacts.json`).

## Notes for later stages

- **Ceiling choice.** Gemma 4 12B (11.95B parameters) is the publisher's own 4-bit file, fits the 16 GB card with room for context, and lists Chinese among its languages. Qwen3 14B (9.0 GB at Q4_K_M, Apache-2.0) is the alternative if a second opinion is wanted.
- **Full-precision weights for fine-tuning** (GPU session downloads these, not GGUF): `Qwen/Qwen3Guard-Gen-0.6B` @ `fada3b2f` (1.50 GB), `Qwen/Qwen3-1.7B`, `google/gemma-4-E2B-it` @ `4adf1574` (10.2 GB), `LiquidAI/d1-omni-600M` @ `02b55d70` (2.35 GB; ships custom loading code: `modeling_d1.py`, `encoder.py`, `prompt.py`, `audio.py`, `vision.py`). Use the d1 revision the handoff reviewed (see the handoff's `REPRODUCE.md`) and read those files before running them.
- **d1 licence.** The LFM Open License defines and restricts commercial use. Read the terms before any release that includes d1 weights.
- **Engine.** llama.cpp build `b12503`, the build the handoff used. It serves d1's decision call at `/v1/systemone`.
- **App capture format.** `ConversationCapture.kt` stores each message as `CapturedText` with `text`, `direction` (`incoming`, `outgoing` or `unknown`), `conversationKind` (`direct`, `group`, `unknown`) and `messageTime`. `outgoing` maps to `child` and `incoming` to `other`. The app can also record `unknown` direction, which the design's input format has no tag for; the next project has to decide how to handle those messages.
