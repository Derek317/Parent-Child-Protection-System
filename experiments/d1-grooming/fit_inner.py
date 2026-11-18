"""Reproduce the two-neuron scorer edit without accessing the holdout."""
import argparse
import itertools
import math
from pathlib import Path
import re

import torch
from safetensors.torch import save_file

from fetch_model import HERE, sha256
from run import read_json, write_json
import weight_edit as w


def fit(args):
    protocol = read_json("weight-protocol.json")
    hashes = w.input_hashes(protocol)
    questions = read_json("questions.json")
    training = w.fixture(HERE / protocol["fit_file"], "f")
    selection = w.fixture(HERE / protocol["selection_file"], "v")
    if w.conversation_keys(training) & w.conversation_keys(selection):
        raise ValueError("Fitting and selection fixtures share conversation text")
    model, base_hash = w.load_model(args.snapshot, protocol, args.output)
    batch_size = protocol["batch_size"]
    _, last, inner = w.infer(model, training, questions, batch_size, capture_inner=True)
    original_scores, v_last, v_inner = w.infer(
        model, selection, questions, batch_size, capture_inner=True
    )
    original = model.head.scorer[3].weight.detach()[0].clone()
    # Reuse the two least influential slots in the original scalar readout.
    slots = torch.argsort(original.abs())[:2]
    last, v_last = last.clone(), v_last.clone()
    last[:, slots] = 0
    v_last[:, slots] = 0
    masked = original.clone()
    masked[slots] = 0
    ratio = float(last.norm() / inner.norm())
    zero = torch.zeros_like(original)
    targets = torch.tensor([protocol["margin_target"] * (1 if r["label"] == "risk" else -1)
                            for r in training])

    best = None
    views = (("inner-only", inner, v_inner, zero),
             ("joint", torch.cat((last, inner * ratio), 1),
              torch.cat((v_last, v_inner * ratio), 1), torch.cat((masked, zero))))
    for mode, features, validation, prior in views:
        contrast_x, contrast_y = w.paired_differences(features, targets, training)
        for contrast, ridge, scale in itertools.product(
            (0., 1.), (0.001, 0.003, 0.03, 0.3), (0.75, 1., 1.25)
        ):
            x = torch.cat((features, math.sqrt(contrast) * contrast_x))
            y = torch.cat((targets, math.sqrt(contrast) * contrast_y))
            weight = prior + scale * w.fit_delta(x, y, prior, ridge)
            scores = (validation @ weight).sigmoid()
            summary = w.groups(selection, scores)
            rank = w.rank(summary, protocol)
            if best is None or rank > best[0]:
                best = (rank, mode, contrast, ridge, scale, summary, weight, scores)

    _, mode, contrast, ridge, scale, summary, chosen, reconstructed = best
    if mode == "inner-only":
        direct, tail = chosen, zero.clone()
    else:
        tail, direct = chosen[:len(original)].clone(), chosen[len(original):] * ratio
    # GELU(x) - GELU(-x) = x. These two existing neurons implement the new projection.
    tail[slots] = torch.tensor([1., -1.])
    patch = args.output / w.PATCH_NAME
    save_file({w.ROW_INDEX: slots, w.REPLACEMENT_ROWS: torch.stack((direct, -direct)),
               w.TENSOR: tail[None]}, patch, metadata={
        "base_checkpoint_sha256": base_hash,
        "modification": "IG Guard two-neuron direct decision scorer edit; LFM Open License v1.0",
    })
    w.apply_patch(model.head.scorer, patch, base_hash)
    live = w.infer(model, selection, questions, batch_size)
    torch.testing.assert_close(live, reconstructed, atol=3e-5, rtol=0)
    threshold = protocol["binary_threshold"]
    if not torch.equal(live >= threshold, reconstructed >= threshold):
        raise ValueError("Live and reconstructed decisions differ at the cutoff")
    cuts = sorted({threshold, *[float(x) for x in original_scores]})
    cuts.append(math.nextafter(cuts[-1], math.inf))
    cutoff, tuned = max(((c, w.groups(selection, original_scores, c)) for c in cuts),
                        key=lambda item: (*w.rank(item[1], protocol), -abs(item[0] - threshold)))
    if hashes != w.input_hashes(protocol):
        raise ValueError("Experiment inputs changed during fitting")

    write_json(args.output / "selection.json", {
        "base_checkpoint_sha256": base_hash,
        "patch_sha256": sha256(patch),
        "holdout_sha256": args.holdout_sha256,
        "inputs_sha256": hashes,
        "fit_rows": len(training),
        "selection_rows": len(selection),
        "chosen": {
            "choice": {"variant": mode, "contrast": contrast, "ridge_fraction": ridge, "scale": scale},
            "selection": summary, "gate": w.gate(summary, protocol), "slots": slots.tolist(),
        },
        "original": w.groups(selection, original_scores, threshold),
        "threshold_only": {"threshold": cutoff, "groups": tuned},
    })


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", type=Path, required=True)
    parser.add_argument("--holdout-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch("[0-9a-f]{64}", args.holdout_sha256):
        parser.error("Provide the frozen holdout SHA-256")
    args.output.mkdir(parents=True, exist_ok=False)
    fit(args)


if __name__ == "__main__":
    main()
