"""Layer probe on d1-omni-600M: can a linear rule on each encoder layer's activations separate risk from benign chats?

Fits on research/data/train.jsonl, evaluates on research/data/tune.jsonl only. Risk = level 2/3, benign = level 0.
Activations are taken at the two option-marker positions (<|mask|> before "false: ..." and "true: ...") the decision
head scores; feature = true-marker minus false-marker (as the handoff did), with a true-marker-only variant as well.
Run: .venv python research/d1_probe.py   (HF_HUB_OFFLINE is set below)
"""
import json
import os
import sys
from pathlib import Path

os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["TRANSFORMERS_OFFLINE"] = "1"

import numpy as np
import torch
from transformers import AutoModel

ROOT = Path(__file__).resolve().parent
MODEL_DIR = ROOT / "models" / "hf" / "d1-omni-600M"
OUT = ROOT / "results" / "d1-probe"
CACHE = OUT / "features.pt"
LANGS = ["en", "zh-Hant-yue", "mixed"]
SEED = 0


def load(name):
    rows = []
    with open(ROOT / "data" / name, encoding="utf-8") as f:
        for line in f:
            r = json.loads(line)
            if r["level"] in (0, 2, 3):
                rows.append({"id": r["id"], "family": r["family"], "language": r["language"],
                             "y": int(r["level"] >= 2), "messages": r["messages"]})
    return rows


def extract(model, rows, question):
    """One chat per call through model.system_one, hooks capture every layer at the option markers."""
    enc, head = model.encoder, model.head
    names = ["embed"] + [f"L{i + 1}" for i in range(len(enc.layers))]
    stash = {}
    hooks = []
    # the Trunk loops over layer parts itself, so layer modules never run; the residual stream after layer i is the
    # input of the next layer's operator_norm (and of embedding_norm after the last layer)
    for i in range(len(enc.layers)):
        tgt = enc.layers[i].operator_norm
        hooks.append(tgt.register_forward_pre_hook(lambda m, a, n=names[i]: stash.__setitem__(n, a[0])))
    hooks.append(enc.embedding_norm.register_forward_pre_hook(lambda m, a: stash.__setitem__(names[-1], a[0])))
    hooks.append(enc.embedding_norm.register_forward_hook(lambda m, a, o: stash.__setitem__("trunk_out", o)))
    pos = {}
    hooks.append(head.register_forward_pre_hook(lambda m, a: pos.__setitem__("p", a[2])))
    head_in = {}
    hooks.append(head.scorer[1].register_forward_pre_hook(lambda m, a: head_in.__setitem__("scorer1_in", a[0])))
    hooks.append(head.scorer[3].register_forward_pre_hook(lambda m, a: head_in.__setitem__("scorer3_in", a[0])))
    layer_names = names + ["trunk_out"]
    diff = {n: [] for n in layer_names + ["scorer1_in", "scorer3_in"]}
    true_only = {n: [] for n in layer_names + ["scorer1_in", "scorer3_in"]}
    probs = []
    try:
        for r in rows:
            resp = model.system_one({"chat": r["messages"]}, question)
            probs.append(float(resp["answers"]["risk"]["noul"]))
            p = pos["p"][0]  # (2,) marker positions: [false, true]
            assert p.numel() == 2
            for n in layer_names:
                g = stash[n][0, p].float()
                diff[n].append((g[1] - g[0]).cpu())
                true_only[n].append(g[1].cpu())
            for n in ("scorer1_in", "scorer3_in"):
                g = head_in[n][0].float()  # (2, d): already gathered at the markers
                diff[n].append((g[1] - g[0]).cpu())
                true_only[n].append(g[1].cpu())
    finally:
        for h in hooks:
            h.remove()
    return ({k: torch.stack(v) for k, v in diff.items()}, {k: torch.stack(v) for k, v in true_only.items()},
            np.array(probs))


def auroc(score, y):
    score, y = np.asarray(score, float), np.asarray(y)
    pos, neg = score[y == 1], score[y == 0]
    if len(pos) == 0 or len(neg) == 0:
        return float("nan")
    allv = np.concatenate([pos, neg])
    _, inv, cnt = np.unique(allv, return_inverse=True, return_counts=True)
    csum = np.cumsum(cnt)
    avg_rank = (csum - (cnt - 1) / 2.0)[inv]  # average 1-based ranks
    return float((avg_rank[: len(pos)].sum() - len(pos) * (len(pos) + 1) / 2) / (len(pos) * len(neg)))


def ridge_fit(X, y, lam):
    """Closed-form ridge on standardised X (float64, GPU) toward targets +-1. Returns weights, bias."""
    yy = torch.tensor(np.where(y == 1, 1.0, -1.0), dtype=torch.float64, device=X.device)
    ym = yy.mean()
    n, d = X.shape
    if n < d:
        a = torch.linalg.solve(X @ X.T + lam * torch.eye(n, dtype=X.dtype, device=X.device), yy - ym)
        w = X.T @ a
    else:
        w = torch.linalg.solve(X.T @ X + lam * torch.eye(d, dtype=X.dtype, device=X.device), X.T @ (yy - ym))
    return w, ym


def standardise(Xtr, *others):
    mu, sd = Xtr.mean(0), Xtr.std(0) + 1e-6
    return [(Xtr - mu) / sd] + [(o - mu) / sd for o in others]


def group_folds(groups, k=5, seed=SEED):
    fams = sorted(set(groups))
    rng = np.random.RandomState(seed)
    rng.shuffle(fams)
    fold_of = {f: i % k for i, f in enumerate(fams)}
    return np.array([fold_of[g] for g in groups])


CHECK_LAYERS = ("L4", "L10", "trunk_out", "scorer3_in")
LAMS = [10.0 ** e for e in range(-1, 8)]


def probe(Ftr, ytr, gtr, Fte, dev):
    """Choose ridge by family-grouped CV inside train; cutoff = Youden-optimal on the out-of-fold scores."""
    Xtr = torch.tensor(Ftr, dtype=torch.float64, device=dev)
    Xte = torch.tensor(Fte, dtype=torch.float64, device=dev)
    folds = group_folds(gtr)
    best = (-1, None, None)
    for lam in LAMS:
        oof = np.zeros(len(ytr))
        for k in range(5):
            tr, va = folds != k, folds == k
            a, b = standardise(Xtr[torch.tensor(tr)], Xtr[torch.tensor(va)])
            w, bias = ridge_fit(a, ytr[tr], lam)
            oof[va] = (b @ w + bias).cpu().numpy()
        s = auroc(oof, ytr)
        if s > best[0]:
            best = (s, lam, oof)
    cv_auc, lam, oof = best
    # cutoff: maximise balanced accuracy on out-of-fold train scores
    cands = np.unique(oof)
    bal = [((oof[ytr == 1] >= c).mean() + (oof[ytr == 0] < c).mean()) / 2 for c in cands]
    cut = float(cands[int(np.argmax(bal))])
    a, b = standardise(Xtr, Xte)
    w, bias = ridge_fit(a, ytr, lam)
    return (b @ w + bias).cpu().numpy(), cv_auc, lam, cut


def summarise(score, y, lang, cut):
    out = {"auroc_all": auroc(score, y)}
    for L in LANGS:
        m = lang == L
        out[f"auroc_{L}"] = auroc(score[m], y[m]) if m.any() else None
    out["risk_flagged"] = float((score[y == 1] >= cut).mean())
    out["benign_flagged"] = float((score[y == 0] >= cut).mean())
    return out


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    train, tune = load("train.jsonl"), load("tune.jsonl")
    dev = "cuda"
    if CACHE.exists():
        cache = torch.load(CACHE, weights_only=False)  # own cache written by this script
    else:
        question = {"risk": json.load(open(ROOT / "data" / "handoff" / "d1-binary-question.json",
                                           encoding="utf-8"))["risk"]}
        model = AutoModel.from_pretrained(str(MODEL_DIR), trust_remote_code=True, local_files_only=True,
                                          dtype=torch.float32).to(dev).eval()
        cache = {}
        for name, rows in (("train", train), ("tune", tune)):
            d, t, p = extract(model, rows, question)
            cache[name] = {"diff": d, "true": t, "p": p}
            print(name, len(rows), "extracted", flush=True)
        torch.save(cache, CACHE)
    ytr = np.array([r["y"] for r in train]); yte = np.array([r["y"] for r in tune])
    gtr = [r["family"] for r in train]
    lang = np.array([r["language"] for r in tune])
    print("train n", len(ytr), "risk", int(ytr.sum()), "| tune n", len(yte), "risk", int(yte.sum()),
          {L: [int(((lang == L) & (yte == 1)).sum()), int(((lang == L) & (yte == 0)).sum())] for L in LANGS})

    # baseline: untouched model yes-probability
    ptr, pte = cache["train"]["p"], cache["tune"]["p"]
    cands = np.unique(ptr)
    bal = [((ptr[ytr == 1] >= c).mean() + (ptr[ytr == 0] < c).mean()) / 2 for c in cands]
    cut_b = float(cands[int(np.argmax(bal))])
    baseline = {"at_0.5": summarise(pte, yte, lang, 0.5), "at_train_cutoff": summarise(pte, yte, lang, cut_b),
                "train_cutoff": cut_b, "train_auroc": auroc(ptr, ytr)}

    rng = np.random.RandomState(SEED)
    results = {"layers": {}, "true_marker_only": {}, "shuffled_labels_check": {}, "baseline_model_p_yes": baseline,
               "n": {"train": len(ytr), "tune": len(yte)}}
    for variant, key in (("diff", "layers"), ("true", "true_marker_only")):
        for n in cache["train"][variant]:
            Ftr = cache["train"][variant][n].numpy().astype(np.float64)
            Fte = cache["tune"][variant][n].numpy().astype(np.float64)
            s, cv, lam, cut = probe(Ftr, ytr, gtr, Fte, dev)
            r = summarise(s, yte, lang, cut)
            r.update(cv_auroc_train=cv, ridge=lam, cutoff=cut)
            results[key][n] = r
            if variant == "diff" and n in CHECK_LAYERS:
                # 20 independent label permutations: a single draw can land on the dominant risk axis by chance
                a = [auroc(probe(Ftr, rng.permutation(ytr), gtr, Fte, dev)[0], yte) for _ in range(20)]
                results["shuffled_labels_check"][n] = {"mean": float(np.mean(a)), "sd": float(np.std(a)),
                                                       "min": float(min(a)), "max": float(max(a))}
            print(variant, n, f"auc {r['auroc_all']:.3f} cv {cv:.3f} lam {lam:g}", flush=True)
    with open(OUT / "results.json", "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)

    def fmt(x):
        return "n/a" if x is None or x != x else f"{x:.3f}"
    lines = ["# d1-omni-600M layer probe", "",
             "Ridge linear probe per layer, fit on train (risk = level 2/3 vs benign = level 0), scored on tune.",
             "Feature = true-option marker minus false-option marker activation (the positions the decision head scores).",
             f"Train n={len(ytr)} (risk {int(ytr.sum())}); tune n={len(yte)} (risk {int(yte.sum())}). "
             "Ridge strength by family-grouped 5-fold CV in train; cutoff = balanced-accuracy optimum on out-of-fold train scores.",
             "", "| layer | AUROC all | en | Cantonese | mixed | risk flagged | benign flagged |", "|---|---|---|---|---|---|---|"]
    for n, r in results["layers"].items():
        lines.append(f"| {n} | {fmt(r['auroc_all'])} | {fmt(r['auroc_en'])} | {fmt(r['auroc_zh-Hant-yue'])} | "
                     f"{fmt(r['auroc_mixed'])} | {fmt(r['risk_flagged'])} | {fmt(r['benign_flagged'])} |")
    for lab, b in (("baseline model P(yes), cutoff 0.5", baseline["at_0.5"]),
                   (f"baseline model P(yes), train cutoff {cut_b:.3f}", baseline["at_train_cutoff"])):
        lines.append(f"| {lab} | {fmt(b['auroc_all'])} | {fmt(b['auroc_en'])} | {fmt(b['auroc_zh-Hant-yue'])} | "
                     f"{fmt(b['auroc_mixed'])} | {fmt(b['risk_flagged'])} | {fmt(b['benign_flagged'])} |")
    chk = "; ".join(f"{n} mean {v['mean']:.3f} sd {v['sd']:.3f} (range {v['min']:.2f}-{v['max']:.2f})"
                    for n, v in results["shuffled_labels_check"].items())
    lines += ["", f"Self-check: probes on 20 shuffled-label draws, tune AUROC: {chk}.", "",
              "Layer names: embed = token embeddings; L1..L16 = residual stream after trunk layer i; trunk_out = after "
              "the final norm (input to the head's 2 transformer layers); scorer1_in / scorer3_in = inputs to the "
              "head scorer's Linear layers (true minus false). Details: results.json (also a true-marker-only variant)."]
    with open(OUT / "README.md", "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
