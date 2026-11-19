# Host check: native scoring core vs llama-server harness path

- Date: 2026-11-19
- Model: `Qwen3-1.7B-Q8_0.gguf` SHA-256 `061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a`
- Engine: llama.cpp b12503 (4577932fab595eaa8b27ad71734d5de1f3c07d8c); host build of `risk_core.cpp` (x86-64, GGML_NATIVE) vs prebuilt `llama-server` b12503 (x86-64, `-c 8192 -t 4`)
- Prompts compared: 24 (fixture prompt, 8 Cantonese, rest en/mixed rows of research/data/tune.jsonl)
- Token id sequences differing: 0 prompts (0 positions)
- Max absolute difference of the five renormalised probabilities: 1.69e-08
- Prompts where the server's top-100 list missed some of the five tokens: 0

| prompt | language | tokens | token mismatches | answers in server top-100 | max abs prob diff | top (native/server) |
| --- | --- | --- | --- | --- | --- | --- |
| fixture | fixture | 601 | 0 | 5/5 | 8.15e-10 | 0/0 |
| s005-low-yue | zh-Hant-yue | 457 | 0 | 5/5 | 1.60e-10 | 0/0 |
| s016-high-yue | zh-Hant-yue | 556 | 0 | 5/5 | 1.06e-09 | 2/2 |
| s025-unresolved-yue | zh-Hant-yue | 439 | 0 | 5/5 | 9.79e-12 | 1/1 |
| s060-potential-yue | zh-Hant-yue | 560 | 0 | 5/5 | 2.39e-11 | 1/1 |
| s089-moderate-yue | zh-Hant-yue | 445 | 0 | 5/5 | 7.33e-15 | 1/1 |
| s102-low-yue | zh-Hant-yue | 539 | 0 | 5/5 | 2.56e-10 | 1/1 |
| s121-high-yue | zh-Hant-yue | 486 | 0 | 5/5 | 1.69e-08 | 1/1 |
| s133-unresolved-yue | zh-Hant-yue | 514 | 0 | 5/5 | 1.28e-10 | 1/1 |
| s005-low-en | en | 503 | 0 | 5/5 | 1.49e-10 | 1/1 |
| s016-low-en | en | 530 | 0 | 5/5 | 6.55e-10 | 1/1 |
| s027-low-en | en | 517 | 0 | 5/5 | 2.73e-09 | 0/0 |
| s077-low-en | en | 525 | 0 | 5/5 | 3.94e-10 | 0/0 |
| s093-low-en | en | 464 | 0 | 5/5 | 1.75e-09 | 1/1 |
| s114-low-en | en | 529 | 0 | 5/5 | 1.66e-10 | 1/1 |
| s133-low-en | en | 491 | 0 | 5/5 | 1.26e-08 | 1/1 |
| s005-low-mixed | mixed | 512 | 0 | 5/5 | 1.90e-09 | 1/1 |
| s016-high-mixed | mixed | 663 | 0 | 5/5 | 1.39e-11 | 3/3 |
| s025-unresolved-mixed | mixed | 470 | 0 | 5/5 | 6.59e-09 | 0/0 |
| s060-potential-mixed | mixed | 478 | 0 | 5/5 | 6.66e-11 | 1/1 |
| s089-moderate-mixed | mixed | 547 | 0 | 5/5 | 4.67e-10 | 2/2 |
| s102-low-mixed | mixed | 449 | 0 | 5/5 | 4.78e-11 | 1/1 |
| s121-high-mixed | mixed | 422 | 0 | 5/5 | 1.26e-09 | 1/1 |
| s133-unresolved-mixed | mixed | 501 | 0 | 5/5 | 3.39e-09 | 1/1 |

## What this proves

The same llama.cpp engine version, driven through the same code that the Android library uses (`risk_core.cpp`: tokenize with add_special=false and parse_special=true, one decode over an empty KV cache, raw logits of ids 15 16 17 18 52), gives the same token ids and the same five-way probabilities as the research harness's llama-server path, on this machine.

## What this does not prove

- It ran on x86-64, not arm64. The Android build uses different CPU kernels, so small numeric differences on a phone are not excluded and were not measured.
- It used the base `Qwen3-1.7B-Q8_0.gguf`, not the fine-tuned `qwen3-1.7b-lora-v3-Q4_K_M.gguf` (same tokenizer and architecture, different weights and quantisation). The base model is not calibrated for the answer format, so these probabilities say nothing about accuracy.
- It was never run on a device: speed, memory and battery are unmeasured.
- The JNI layer and Kotlin wrapper are not exercised by this check.
- The server's probabilities come from its top-100 candidates, so tokens outside that list are missing there (counted above).
