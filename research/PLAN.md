# Plan: non-GPU part of the local risk model project

Design (source of truth): [`docs/research/2026-11-19-ig-guard-local-model-design.md`](../docs/research/2026-11-19-ig-guard-local-model-design.md).
This plan covers what the cloud session does. GPU work is listed in [`HANDOVER-GPU.md`](HANDOVER-GPU.md).

Rules that apply to every step: synthetic chats only; generated chats stay non-graphic; the sealed test is never used to choose anything; code and data are written by Sonnet 5.5 subagents and reviewed; no model files and nothing private in the repository.

## Steps

1. **Confirm the models.** Check each ladder model on its publisher's page: exact file, size, licence, checksum. Record in `models.md`. Note every place where the design's web-search facts were wrong.
2. **Freeze the instruction.** Write the one fixed instruction for chat models from the handoff's development files and rubric only (`prompts/instruction-v1.txt`). It is not edited after any test-set result has been seen.
3. **Build the harness.** `run_eval.py` (one model on one set), `report.py` (summary tables), `fetch.py` (checksummed downloads), `igrisk/metrics.py` with unit tests. Standard library only, UTF-8 everywhere, talks to a local `llama-server`, same commands on Linux CPU and Windows GPU.
4. **Run the controls.** Keyword rules, then d1-omni-600M 8-bit. Gate: d1 flags 30–34 of 36 risk chats and 28–32 of 36 benign chats on the 90-chat set. Nothing else runs until this holds.
5. **Run the phone-sized rungs on CPU.** Qwen3Guard 0.6B, Qwen3 1.7B, Gemma 4 E2B, on both sets.
6. **Label check.** A Sonnet 5.5 subagent reads all 150 chats with ids scrambled and labels removed, and answers with the same five choices. Scored by the harness like a model.
7. **Stage 1 report.** `results/stage1-report.md`: tables by language with AUROC and ranges, the stop-or-go table filled in for what ran, and which rows wait for the GPU session.
8. **Stage 2 data.**
   1. One catalogue of 180 distinct settings; a seeded script assigns 100 training, 15 tuning, 15 check, 50 sealed, balanced by adult/peer.
   2. Writers for training/tuning/check get only their own settings. Each family: 5 endings × 3 languages = 15 chats, 6–30 messages, tagged `child`/`other`.
   3. Sealed test: separate writer subagents that see only the 50 sealed settings. Separate labeller subagents then label the sealed chats with labels and ids hidden. Disagreements are set aside and counted; over 15% means a rewrite.
   4. A validator checks shape, counts, tags, message limits and duplicates across splits. The sealed file is hashed (`data/SEALED.sha256`) and not opened again before stage 4.
   5. `data/cantonese-review.md`: about 30 Cantonese and mixed chats from the training set for Derek to mark natural or off.
9. **Handover.** `HANDOVER-GPU.md` with the exact commands for the GPU session. Push the branch, open a pull request, do not merge. No fine-tuning.

## Builder decisions made here

- **Ceiling model:** Gemma 4 12B, Google's own QAT 4-bit GGUF (7.0 GB, fits the 16 GB card).
- **Speaker tags on the handoff sets.** Those chats use names like `person_a` or `teen` and often show a teen telling a friend what happened, so they cannot be mapped to `child`/`other`. Stage 1 passes the names through as written. The new data uses `child`/`other`.
- **d1 on the severity set** follows the handoff's rule: a chat is flagged when the score is kept (enough context, confident level) and is 50 or more. Its ranking score for AUROC is P(2)+P(3).
- **What counts as a sealed-test disagreement:** the blind labeller's answer falls in a different pass-mark class from the intended one (benign = 0, potential = 1, risk = 2 or 3, unresolved = U). A 2-versus-3 difference is recorded but not set aside, because the pass mark does not separate them.
