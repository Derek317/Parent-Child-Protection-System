"""Direct, gradient-free edit to one d1 scorer tensor, evaluated offline."""
import argparse
from collections import Counter
import itertools
import json
import math
import os
from pathlib import Path
import resource
import shutil
import time

import torch
from safetensors import safe_open
from safetensors.torch import save_file

from fetch_model import HERE, sha256, verify
from run import confusion, dataset, read_json, write_json

TENSOR = "head.scorer.3.weight"
INNER_WEIGHT = "head.scorer.1.weight"
INNER_BIAS = "head.scorer.1.bias"
ROW_INDEX = "head.scorer.1.row_index"
REPLACEMENT_ROWS = "head.scorer.1.replacement_rows"
LANGUAGES = ("en", "zh-Hant-yue", "mixed")
PATCH_NAME = "d1-grooming-head.safetensors"


def fit_delta(features, targets, original, ridge_fraction):
    """Minimize squared log-odds error with an L2 penalty on the weight edit."""
    x = features.double()
    residual = targets.double() - x @ original.double()
    gram = x @ x.T
    penalty = ridge_fraction * gram.diagonal().mean().clamp_min(1e-12)
    gram.diagonal().add_(penalty)
    return (x.T @ torch.linalg.solve(gram, residual)).to(original)


def write_patch(path, weight, base_hash):
    save_file({TENSOR: weight.detach().cpu().contiguous()}, path, metadata={
        "base_checkpoint_sha256": base_hash,
        "modification": "IG Guard gradient-free grooming readout edit; LFM Open License v1.0",
    })


def replacement_tensors(path, base_hash, original):
    with safe_open(str(path), framework="pt") as patch:
        if (patch.metadata() or {}).get("base_checkpoint_sha256") != base_hash:
            raise ValueError("Patch requires the pinned base checkpoint")
        keys = set(patch.keys())
        if keys not in ({TENSOR}, {TENSOR, ROW_INDEX, REPLACEMENT_ROWS}):
            raise ValueError("Unexpected tensors in the edit")
        weight = patch.get_tensor(TENSOR)
        if (weight.shape != original[TENSOR].shape or weight.dtype != original[TENSOR].dtype
                or not torch.isfinite(weight).all()):
            raise ValueError("Patch does not match the scorer")
        replacements = {TENSOR: weight}
        if ROW_INDEX in keys:
            if INNER_WEIGHT not in original or INNER_BIAS not in original:
                raise ValueError("Patch requires the two-layer scorer")
            indices = patch.get_tensor(ROW_INDEX)
            rows = patch.get_tensor(REPLACEMENT_ROWS)
            inner = original[INNER_WEIGHT]
            if (indices.dtype != torch.int64 or indices.shape != (2,)
                    or indices.unique().numel() != 2
                    or not ((indices >= 0) & (indices < len(inner))).all()
                    or rows.shape != inner[indices].shape or rows.dtype != inner.dtype
                    or not torch.isfinite(rows).all()):
                raise ValueError("Invalid replacement scorer rows")
            updated = inner.detach().clone()
            updated[indices] = rows
            bias = original[INNER_BIAS].detach().clone()
            bias[indices] = 0
            replacements.update({INNER_WEIGHT: updated, INNER_BIAS: bias})
    return replacements


def apply_patch(scorer, path, base_hash):
    if isinstance(scorer, torch.nn.Sequential):
        original = {TENSOR: scorer[3].weight, INNER_WEIGHT: scorer[1].weight, INNER_BIAS: scorer[1].bias}
    else:
        original = {TENSOR: scorer.weight}
    with torch.no_grad():
        for name, replacement in replacement_tensors(path, base_hash, original).items():
            original[name].copy_(replacement)


def export_weights(original_file, output_file, patch, base_hash):
    with safe_open(str(original_file), framework="pt") as base:
        tensors = {key: base.get_tensor(key) for key in base.keys()}
        edit = replacement_tensors(patch, base_hash, tensors)
        tensors.update(edit)
        metadata = base.metadata() or {}
        metadata.update(modification=f"IG Guard edited {', '.join(edit)}; original LiquidAI/d1-omni-600M")
        save_file(tensors, output_file, metadata=metadata)


def fixture(path, prefix, allow_review=False):
    rows = [json.loads(line) for line in path.read_text().splitlines()]
    ids, texts, families = set(), set(), {}
    for row in rows:
        family, language, label = (row[k] for k in ("family", "language", "label"))
        review = label == "review" and allow_review
        allowed_prefix = "u" if review else prefix
        messages = row["messages"]
        if (row["synthetic"] is not True or language not in LANGUAGES
                or not family.startswith(allowed_prefix)
                or (label not in {"risk", "benign"} and not review)
                or row["id"] in ids or not messages
                or any(set(m) != {"speaker", "text"} or any(
                    not isinstance(v, str) or not v.strip() for v in m.values()
                ) for m in messages)):
            raise ValueError(f"Invalid synthetic fixture row: {row['id']}")
        text = json.dumps(messages, sort_keys=True, ensure_ascii=False)
        if text in texts:
            raise ValueError(f"Duplicate conversation: {row['id']}")
        ids.add(row["id"])
        texts.add(text)
        families.setdefault(family, Counter())[language, label] += 1
    if not families:
        raise ValueError("Empty fixture")
    for family, seen in families.items():
        labels = ("review",) if family.startswith("u") else ("risk", "benign")
        if seen != Counter(itertools.product(LANGUAGES, labels)):
            raise ValueError(f"Family {family} lacks complete multilingual pairs")
    return rows


def load_model(snapshot, protocol, output):
    manifest = verify(snapshot)
    os.environ.update(HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1",
                      HF_HOME=str(output / "hf-cache"),
                      HF_MODULES_CACHE=str(output / "hf-modules"))
    from transformers import AutoModel

    torch.set_num_threads(protocol["threads"])
    torch.set_num_interop_threads(1)
    torch.manual_seed(0)
    model = AutoModel.from_pretrained(str(snapshot.resolve()), trust_remote_code=True,
                                      local_files_only=True, dtype=torch.float32).eval()
    weights = model.head.scorer[3].weight
    if weights.shape != (1, 1024):
        raise ValueError("Unexpected d1 scorer shape")
    return model, manifest["files"]["model.safetensors"]["sha256"]


@torch.inference_mode()
def infer(model, rows, question, batch_size, capture=False, capture_inner=False):
    """Run the upstream System One API. Capture true-minus-false scorer inputs for fitting."""
    scorer = model.head.scorer
    layers = (scorer[3], scorer[1]) if capture_inner else (scorer[3],) if capture else ()
    pending = [[] for _ in layers]
    features = [[] for _ in layers]
    scores = []
    temperature = model.config.temperatures["noul:2"]

    def take_features(buffers):
        def hook(_layer, inputs):
            activations, = inputs
            if activations.shape[1] != 2:
                raise ValueError("Expected false and true option activations")
            buffers.append((activations[:, 1] - activations[:, 0]).cpu() / temperature)
        return hook

    hooks = [layer.register_forward_pre_hook(take_features(buffer))
             for layer, buffer in zip(layers, pending)]
    try:
        for start in range(0, len(rows), batch_size):
            batch = rows[start:start + batch_size]
            responses = model.system_one_batch([({"chat": row["messages"]}, question) for row in batch])
            if len(responses) != len(batch):
                raise ValueError("System One response count differs from the batch")
            if layers:
                # Upstream sorts text by decreasing token count and restores response order.
                indices = sorted(range(len(responses)),
                                 key=lambda i: responses[i]["usage"]["input_tokens"], reverse=True)
                for chunks, collected in zip(pending, features):
                    captured = torch.cat(chunks)
                    ordered = torch.empty_like(captured)
                    ordered[indices] = captured
                    collected.append(ordered)
                    chunks.clear()
            for response in responses:
                score = float(response["answers"]["risk"]["noul"])
                if (not math.isfinite(score) or not 0 <= score <= 1
                        or response["usage"]["output_tokens"] != 0):
                    raise ValueError("Unexpected System One response")
                scores.append(score)
            if start % (batch_size * 5) == 0 or start + batch_size >= len(rows):
                print(f"Scored {len(scores)}/{len(rows)}", flush=True)
    finally:
        for hook in hooks:
            hook.remove()
    scores = torch.tensor(scores)
    if not layers:
        return scores
    matrices = [torch.cat(collected) for collected in features]
    reconstructed = (matrices[0] @ scorer[3].weight.detach()[0]).sigmoid()
    torch.testing.assert_close(reconstructed, scores, atol=3e-6, rtol=0)
    return (scores, *matrices)


def groups(rows, scores, threshold=0.5):
    summaries = {}
    for language in ("all", *LANGUAGES):
        pairs = [(row["label"], float(score)) for row, score in zip(rows, scores, strict=True)
                 if language == "all" or row["language"] == language]
        summary = confusion((label, score >= threshold) for label, score in pairs)
        risk = [score for label, score in pairs if label == "risk"]
        benign = [score for label, score in pairs if label == "benign"]
        summary["auroc"] = (sum((a > b) + 0.5 * (a == b) for a in risk for b in benign)
                            / (len(risk) * len(benign))) if risk and benign else None
        summary["gold_review_total"] = sum(label == "review" for label, _ in pairs)
        # Same fixed review band as the original feasibility benchmark.
        summary["gold_review_in_band"] = sum(label == "review" and 0.35 <= score <= 0.65
                                             for label, score in pairs)
        summaries[language] = summary
    return summaries


def gate(summaries, protocol):
    result = {}
    for language, values in summaries.items():
        scope = "overall" if language == "all" else "language"
        result[language] = (values["recall"] is not None and values["false_positive_rate"] is not None
                            and values["recall"] >= protocol[f"{scope}_min_recall"]
                            and values["false_positive_rate"] <= protocol[f"{scope}_max_false_positive_rate"])
    return {"pass": all(result.values()), "groups": result}


def rank(summaries, protocol):
    all_rows = summaries["all"]
    return (
        gate(summaries, protocol)["pass"],
        min(v["recall"] - v["false_positive_rate"] for v in summaries.values()),
        all_rows["recall"] - all_rows["false_positive_rate"],
        -all_rows["false_positive_rate"],
    )


def paired_differences(features, targets, rows):
    pairs = {}
    for i, row in enumerate(rows):
        pairs.setdefault((row["family"], row["language"]), {})[row["label"]] = i
    risk, benign = zip(*((pair["risk"], pair["benign"]) for pair in pairs.values()))
    return features[list(risk)] - features[list(benign)], targets[list(risk)] - targets[list(benign)]


def conversation_keys(rows):
    return {json.dumps(row["messages"], sort_keys=True, ensure_ascii=False) for row in rows}


def input_hashes(protocol):
    names = {protocol["fit_file"], protocol["selection_file"], "weight-protocol.json",
             "questions.json", protocol.get("question_file", "questions.json")}
    names = sorted(names)
    return {name: sha256(HERE / name) for name in names}


def fit(args, protocol, question):
    if args.holdout is not None:
        raise ValueError("Fitting takes a holdout hash only")
    if (not args.holdout_sha256 or len(args.holdout_sha256) != 64
            or any(c not in "0123456789abcdef" for c in args.holdout_sha256)):
        raise ValueError("Provide the independently frozen holdout SHA-256")
    frozen_inputs = input_hashes(protocol)
    fit_file = HERE / protocol["fit_file"]
    selection_file = HERE / protocol["selection_file"]
    train = fixture(fit_file, "f")
    selection = fixture(selection_file, "v")
    if conversation_keys(train) & conversation_keys(selection):
        raise ValueError("Fitting and selection fixtures share conversation text")

    model, base_hash = load_model(args.snapshot, protocol, args.output)
    original = model.head.scorer[3].weight.detach()[0].clone()
    _, features = infer(model, train, question, protocol["batch_size"], capture=True)
    original_scores, selection_features = infer(
        model, selection, question, protocol["batch_size"], capture=True
    )
    # Keep the frozen activations locally to diagnose selection without rerunning the encoder.
    save_file({"fit": features.contiguous(), "selection": selection_features.contiguous(),
               "original_selection_score": original_scores.contiguous(), "original_weight": original.contiguous()},
              args.output / "activations.safetensors")
    targets = torch.tensor([1. if row["label"] == "risk" else -1. for row in train])
    targets *= protocol["margin_target"]
    contrast_x, contrast_y = paired_differences(features, targets, train)
    threshold = protocol["binary_threshold"]

    cuts = sorted({threshold, *[float(x) for x in original_scores]})
    cuts.append(math.nextafter(cuts[-1], math.inf))
    cutoff, tuned = max(((c, groups(selection, original_scores, c)) for c in cuts),
                        key=lambda item: (*rank(item[1], protocol), -abs(item[0] - threshold)))

    candidates = []
    best_key = None
    for contrast in protocol["contrast_weights"]:
        x = torch.cat((features, contrast_x * math.sqrt(contrast))) if contrast else features
        y = torch.cat((targets, contrast_y * math.sqrt(contrast))) if contrast else targets
        for ridge in protocol["ridge_fractions"]:
            delta = fit_delta(x, y, original, ridge)
            for scale in protocol["edit_scales"]:
                weight = original + scale * delta
                edited = (selection_features @ weight).sigmoid()
                summary = groups(selection, edited, threshold)
                edit_norm = float((weight - original).norm())
                key = (*rank(summary, protocol), -edit_norm)
                candidate = {"contrast": contrast, "ridge_fraction": ridge, "scale": scale,
                             "l2_edit": edit_norm, "selection": summary}
                candidates.append(candidate)
                if best_key is None or key > best_key:
                    best_key, best, best_weight = key, candidate, weight
    changed = int(torch.count_nonzero(best_weight != original))
    if changed == 0:
        raise ValueError("Selected candidate did not change the scorer")
    patch_path = args.output / PATCH_NAME
    write_patch(patch_path, best_weight[None], base_hash)
    apply_patch(model.head.scorer[3], patch_path, base_hash)
    live = infer(model, selection, question, protocol["batch_size"])
    reconstructed = (selection_features @ best_weight).sigmoid()
    # The upstream scorer rounds two F32 dot products before subtracting. The
    # reconstruction subtracts features first, so a small score difference is expected.
    torch.testing.assert_close(live, reconstructed, atol=2e-5, rtol=0)
    if not torch.equal(live >= threshold, reconstructed >= threshold):
        raise ValueError("Reconstructed and live selection decisions differ at the cutoff")
    if frozen_inputs != input_hashes(protocol):
        raise ValueError("Experiment inputs changed during fitting")
    write_json(args.output / "selection.json", {
        "base_checkpoint_sha256": base_hash,
        "patch_sha256": sha256(patch_path),
        "holdout_sha256": args.holdout_sha256,
        "inputs_sha256": frozen_inputs,
        "fit_rows": len(train), "selection_rows": len(selection),
        "candidates": candidates, "chosen": best,
        "changed_weight_entries": changed,
        "original": groups(selection, original_scores, threshold),
        "threshold_only": {"threshold": cutoff, "groups": tuned},
    })
    print("Frozen patch", sha256(patch_path), "selection gate", gate(best["selection"], protocol), flush=True)


def evaluate(args, protocol, question):
    if args.artifact is None or args.holdout is None:
        raise ValueError("Evaluation requires a frozen artifact directory and holdout file")
    selection = json.loads((args.artifact / "selection.json").read_text())
    patch = args.artifact / PATCH_NAME
    if sha256(patch) != selection["patch_sha256"] or sha256(args.holdout) != selection["holdout_sha256"]:
        raise ValueError("Frozen weight or holdout hash differs")
    for name, digest in selection["inputs_sha256"].items():
        if sha256(HERE / name) != digest:
            raise ValueError(f"Experiment input changed after selection: {name}")
    holdout = fixture(args.holdout, "t", allow_review=True)
    fit_texts = conversation_keys(fixture(HERE / protocol["fit_file"], "f"))
    selection_texts = conversation_keys(fixture(HERE / protocol["selection_file"], "v"))
    if conversation_keys(holdout) & (fit_texts | selection_texts):
        raise ValueError("Blind holdout repeats development conversations")
    replay = [row for row in dataset() if row["split"] == "heldout_style"]
    rows = holdout + replay
    model, base_hash = load_model(args.snapshot, protocol, args.output)
    if base_hash != selection["base_checkpoint_sha256"]:
        raise ValueError("Evaluation checkpoint differs from fit")
    original = infer(model, rows, question, protocol["batch_size"])
    reference_question = read_json("questions.json")
    reference = (original if question == reference_question else
                 infer(model, rows, reference_question, protocol["batch_size"]))
    apply_patch(model.head.scorer, patch, base_hash)
    if sha256(patch) != selection["patch_sha256"]:
        raise ValueError("Patch changed before application")
    edited = infer(model, rows, question, protocol["batch_size"])
    threshold = protocol["binary_threshold"]
    tuned_cutoff = selection["threshold_only"]["threshold"]
    result = {"weight_sha256": selection["patch_sha256"],
              "holdout_sha256": selection["holdout_sha256"], "binary_threshold": threshold,
              "original_tuned_threshold": tuned_cutoff, "splits": {}}
    with (args.output / "predictions.jsonl").open("w") as fp:
        for i, row in enumerate(rows):
            key = "blind_holdout" if i < len(holdout) else "historical_replay"
            data = {k: row[k] for k in ("id", "family", "language", "label")}
            data.update(split=key, original_score=float(reference[i]),
                        unedited_same_question_score=float(original[i]), edited_score=float(edited[i]))
            fp.write(json.dumps(data, ensure_ascii=False, allow_nan=False) + "\n")
    for key, sl in (("blind_holdout", slice(0, len(holdout))),
                    ("historical_replay", slice(len(holdout), None))):
        before = groups(rows[sl], reference[sl], threshold)
        after = groups(rows[sl], edited[sl], threshold)
        result["splits"][key] = {"original": before, "edited": after,
                                 "unedited_same_question": groups(rows[sl], original[sl], threshold),
                                 "same_question_tuned_threshold": groups(rows[sl], original[sl], tuned_cutoff),
                                 "edited_gate": gate(after, protocol)}
    write_json(args.output / "metrics.json", result)
    print("Blind test gate", result["splits"]["blind_holdout"]["edited_gate"], flush=True)


def export(args):
    if args.artifact is None:
        raise ValueError("Export requires the frozen artifact directory")
    manifest = verify(args.snapshot)
    base_hash = manifest["files"]["model.safetensors"]["sha256"]
    selection = json.loads((args.artifact / "selection.json").read_text())
    patch = args.artifact / PATCH_NAME
    if sha256(patch) != selection["patch_sha256"] or base_hash != selection["base_checkpoint_sha256"]:
        raise ValueError("Frozen weight or base checkpoint hash differs")
    for name in manifest["files"]:
        if name != "model.safetensors":
            shutil.copyfile(args.snapshot / name, args.output / name)
    export_weights(args.snapshot / "model.safetensors", args.output / "model.safetensors",
                   patch, base_hash)
    (args.output / "IG-GUARD-MODIFICATIONS.md").write_text(
        "IG Guard edited the decision scorer in the Liquid AI checkpoint at revision "
        f"{manifest['revision']}. Patch SHA-256 {selection['patch_sha256']}. "
        "The upstream LFM Open License v1.0 is supplied in LICENSE.\n"
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", choices=("fit", "evaluate", "export"))
    parser.add_argument("--snapshot", type=Path, default=Path("/workspace/private/d1-grooming/snapshot"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--artifact", type=Path)
    parser.add_argument("--holdout", type=Path)
    parser.add_argument("--holdout-sha256")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    start = time.perf_counter()
    protocol = read_json("weight-protocol.json")
    if protocol["tensor"] != TENSOR:
        raise ValueError("Protocol names an unsupported tensor")
    if args.stage == "export":
        export(args)
    else:
        (fit if args.stage == "fit" else evaluate)(
            args, protocol, read_json(protocol.get("question_file", "questions.json"))
        )
    write_json(args.output / "timing.json", {
        "elapsed_seconds": time.perf_counter() - start,
        "peak_rss_mib_linux": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024,
        "torch": torch.__version__,
    })


if __name__ == "__main__":
    main()
