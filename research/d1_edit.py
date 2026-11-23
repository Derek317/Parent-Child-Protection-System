"""Gradient-free edit of d1-omni-600M's decision head, from the layer-probe finding that the true-minus-false input of
head.scorer[1] is linearly readable.

  python research/d1_edit.py fit     fit on train.jsonl only, choose on train-CV and tune.jsonl only, write the patch and
                                     research/results/d1-edit/frozen.json, check live == reconstructed on tune. STOP.
  python research/d1_edit.py score   after freezing: verify the patch hash, score unedited and edited model once on each
                                     test set (live system_one, cutoff 0.5), write results.json and README.md.

Trick: GELU(x) - GELU(-x) = x. Two rows of scorer[1].weight set to +w and -w (biases 0) and scorer[3].weight entries
+1 and -1 at those slots add the linear term w.x to the head's logit; features x are scorer[1]'s input at each marker.
The head softmaxes the false/true logits divided by temperature T, so P(yes) = sigmoid((z_true - z_false) / T) and no
intercept can be written (it cancels), so the fit has none and the model's own 0.5 cutoff is sigmoid(0).
"""
import hashlib
import json
import os
import sys
from pathlib import Path

os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["TRANSFORMERS_OFFLINE"] = "1"

import numpy as np
import torch
from safetensors import safe_open
from safetensors.torch import save_file
from transformers import AutoModel

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
from igrisk import metrics  # noqa: E402
from igrisk.data import load_set  # noqa: E402

MODEL_DIR = ROOT / "models" / "hf" / "d1-omni-600M"
OUT = ROOT / "results" / "d1-edit"
PATCH = OUT / "d1-head-edit.safetensors"
FROZEN = OUT / "frozen.json"
PROVENANCE = Path(r"E:\Claude code\AI  model\handoff\IG-Guard-AI-Audit-Handoff\experiments\d1-grooming\evidence\model-provenance.json")
FEATURES = Path(os.environ.get("D1_EDIT_FEATURES", str(Path(os.environ.get("TEMP", ".")) / "d1-edit-features.pt")))  # local cache, not tracked
W3, W1, B1 = "head.scorer.3.weight", "head.scorer.1.weight", "head.scorer.1.bias"
ROW_INDEX, ROWS = "head.scorer.1.row_index", "head.scorer.1.replacement_rows"
C_B = 2.0            # inner-only variant: target logit magnitude
FRACS = [1e-4, 1e-3, 1e-2, 1e-1, 1.0, 10.0]  # ridge strength as a fraction of the mean Gram diagonal
SEED = 0


def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def base_sha():
    return json.load(open(PROVENANCE, encoding="utf-8"))["files"]["model.safetensors"]["sha256"]


def load_model():
    m = AutoModel.from_pretrained(str(MODEL_DIR), trust_remote_code=True, local_files_only=True, dtype=torch.float32)
    return m.to("cuda").eval()


def read_rows(name):
    rows = []
    for line in open(ROOT / "data" / name, encoding="utf-8"):
        r = json.loads(line)
        if r["level"] in (0, 2, 3):
            rows.append({"id": r["id"], "family": r["family"], "language": r["language"],
                         "y": int(r["level"] >= 2), "messages": r["messages"]})
    return rows


def question():
    return {"risk": json.load(open(ROOT / "data" / "handoff" / "d1-binary-question.json", encoding="utf-8"))["risk"]}


def live(model, messages):
    return float(model.system_one({"chat": messages}, question())["answers"]["risk"]["noul"])


def extract(model, rows):
    """Per chat: scorer[1] input diff (true - false), scorer[3] input diff, live P(yes)."""
    got = {}
    hs = [model.head.scorer[1].register_forward_pre_hook(lambda m, a: got.__setitem__("d", a[0][0].double())),
          model.head.scorer[3].register_forward_pre_hook(lambda m, a: got.__setitem__("g", a[0][0].double()))]
    D, G, P = [], [], []
    try:
        for r in rows:
            P.append(live(model, r["messages"]))
            assert got["d"].shape[0] == 2
            D.append((got["d"][1] - got["d"][0]).cpu())
            G.append((got["g"][1] - got["g"][0]).cpu())
    finally:
        for h in hs:
            h.remove()
    return torch.stack(D), torch.stack(G), torch.tensor(P, dtype=torch.float64)


def auroc(s, y):
    s, y = np.asarray(s, float), np.asarray(y)
    pos, neg = s[y == 1], s[y == 0]
    allv = np.concatenate([pos, neg])
    _, inv, cnt = np.unique(allv, return_inverse=True, return_counts=True)
    rk = (np.cumsum(cnt) - (cnt - 1) / 2.0)[inv]
    return float((rk[: len(pos)].sum() - len(pos) * (len(pos) + 1) / 2) / (len(pos) * len(neg)))


def bal_acc(logit, y):
    return float(((logit[y == 1] > 0).mean() + (logit[y == 0] <= 0).mean()) / 2)


def ridge(X, target, y, frac, scale):
    """Class-balanced ridge without intercept on scale-standardised X (no centring). Returns v in raw feature units."""
    n = len(y)
    om = np.where(y == 1, n / (2 * (y == 1).sum()), n / (2 * (y == 0).sum()))
    Xs = X / scale
    sw = torch.tensor(np.sqrt(om))[:, None]
    Xw, tw = Xs * sw, target * sw[:, 0]
    K = Xw @ Xw.T
    lam = frac * K.diagonal().mean()
    a = torch.linalg.solve(K + lam * torch.eye(n, dtype=K.dtype), tw)
    return (Xw.T @ a) / scale


def fit_scale(z, y):
    """Scalar s>0 minimising class-balanced logloss of sigmoid(s*z), no intercept."""
    om = np.where(y == 1, 0.5 / (y == 1).mean(), 0.5 / (y == 0).mean()) / len(y)
    sgn = np.where(y == 1, 1.0, -1.0)
    s = 1.0
    for _ in range(100):
        m = sgn * z * s
        p = 1 / (1 + np.exp(m))  # d/dm of logloss = -(1 - sigmoid(m))
        g = -(om * sgn * z * p).sum()
        h = (om * z * z * p * (1 - p)).sum() + 1e-12
        s = max(s - g / h, 1e-3)
    return float(s)


def folds_of(groups, k=5):
    fams = sorted(set(groups))
    np.random.RandomState(SEED).shuffle(fams)
    f = {x: i % k for i, x in enumerate(fams)}
    return np.array([f[g] for g in groups])


def variant_logit(variant, X, v, r, T):
    """Live logit (before sigmoid): variant A replaces the readout, B adds to it."""
    return (X @ v) + (r / T if variant == "B" else 0.0)


def build_patch(model_w3, model_w1, rows, v, T, variant):
    w = (v * T).float()  # live logit = (w.x)/T
    w3 = model_w3.clone()
    if variant == "A":
        w3.zero_()
    w3[rows[0]], w3[rows[1]] = 1.0, -1.0
    return {W3: w3[None], ROW_INDEX: torch.tensor(rows, dtype=torch.int64),
            ROWS: torch.stack([w, -w]).to(model_w1.dtype)}


def apply_patch(model, path, expected_base):
    with safe_open(str(path), framework="pt") as p:
        assert (p.metadata() or {}).get("base_checkpoint_sha256") == expected_base, "patch base hash mismatch"
        assert set(p.keys()) == {W3, ROW_INDEX, ROWS}
        w3, idx, rows = p.get_tensor(W3), p.get_tensor(ROW_INDEX), p.get_tensor(ROWS)
    sc = model.head.scorer
    assert w3.shape == sc[3].weight.shape and rows.shape == (2, sc[1].weight.shape[1]) and idx.shape == (2,)
    assert torch.isfinite(w3).all() and torch.isfinite(rows).all() and idx.unique().numel() == 2
    with torch.no_grad():
        sc[3].weight.copy_(w3)
        sc[1].weight[idx] = rows.to(sc[1].weight.device)
        sc[1].bias[idx] = 0


def cmd_fit():
    OUT.mkdir(parents=True, exist_ok=True)
    assert not FROZEN.exists(), "already frozen; refusing to refit"
    train, tune = read_rows("train.jsonl"), read_rows("tune.jsonl")
    ytr, yte = np.array([r["y"] for r in train]), np.array([r["y"] for r in tune])
    model = load_model()
    T = float(model.config.temperatures["noul:2"])
    w3 = model.head.scorer[3].weight[0].detach().cpu().double()
    rows = [int(i) for i in torch.argsort(w3.abs())[:2]]  # the two rows the original readout uses least
    if FEATURES.exists():
        c = torch.load(FEATURES, weights_only=False)  # own cache
    else:
        c = {}
        for n, rs in (("train", train), ("tune", tune)):
            c[n] = extract(model, rs)
        torch.save(c, FEATURES)
    resid = lambda G: G @ w3 - G[:, rows] @ w3[rows]  # original readout without the two rewritten rows  # noqa: E731
    (Dtr, Gtr, _), (Dte, Gte, Pte) = c["train"], c["tune"]
    rtr, rte = resid(Gtr), resid(Gte)
    folds = folds_of([r["family"] for r in train])
    scale_all = Dtr.std(0) + 1e-6
    yt = torch.tensor(np.where(ytr == 1, 1.0, -1.0))
    report, best = {}, None
    for variant in ("A", "B"):
        for frac in FRACS:
            oof = np.zeros(len(ytr))
            for k in range(5):
                tr, va = folds != k, folds == k
                ttr = torch.tensor(tr)
                tgt = (yt * (C_B if variant == "B" else 1.0) - (rtr / T if variant == "B" else 0))[ttr]
                sc = Dtr[ttr].std(0) + 1e-6
                v = ridge(Dtr[ttr], tgt, ytr[tr], frac, sc)
                oof[va] = variant_logit(variant, Dtr[torch.tensor(va)], v, rtr[torch.tensor(va)], T).numpy()
            cv = bal_acc(oof, ytr)
            # final fit on all train
            tgt = yt * (C_B if variant == "B" else 1.0) - (rtr / T if variant == "B" else 0)
            v = ridge(Dtr, tgt, ytr, frac, scale_all)
            s = 1.0
            if variant == "A":  # scale so live probabilities are calibrated on out-of-fold train scores
                s = fit_scale(oof, ytr)
                v = v * s
            lt = variant_logit(variant, Dte, v, rte, T).numpy()
            rec = dict(variant=variant, frac=frac, scale=s, cv_balanced_acc=cv, tune_balanced_acc=bal_acc(lt, yte),
                       tune_auroc=auroc(lt, yte), tune_risk_flagged=float((lt[yte == 1] > 0).mean()),
                       tune_benign_flagged=float((lt[yte == 0] > 0).mean()))
            report[f"{variant}/{frac:g}"] = rec
            print(rec, flush=True)
            # per variant the ridge strength is chosen by train CV only; the variant by tune
            if best is None or best.get(variant) is None or cv > best[variant][0]:
                best = best or {}
                best[variant] = (cv, rec, v)
    pick = max(best, key=lambda k: (best[k][1]["tune_balanced_acc"], best[k][1]["tune_auroc"]))
    _, chosen, v = best[pick]
    patch = build_patch(model.head.scorer[3].weight[0].detach().cpu(), model.head.scorer[1].weight.detach().cpu(),
                        rows, v.float(), T, pick)
    save_file({k: t.contiguous() for k, t in patch.items()}, str(PATCH), metadata={
        "base_checkpoint_sha256": base_sha(), "variant": pick,
        "modification": "gradient-free two-row linear readout edit of head.scorer; LFM Open License v1.0"})

    # apply in memory; audit changed tensors; live vs reconstructed on tune
    before = {k: t.detach().cpu().clone() for k, t in model.state_dict().items() if k.startswith("head.scorer")}
    apply_patch(model, PATCH, base_sha())
    changed = {}
    for k, t in model.state_dict().items():
        if k.startswith("head.scorer"):
            d = int((t.detach().cpu() != before[k]).sum())
            if d:
                changed[k] = d
    assert set(changed) <= {W3, W1, B1}, changed
    w_stored = patch[ROWS][0].double()
    recon = torch.sigmoid(variant_logit(pick, Dte, w_stored / T, rte, T))
    livep = torch.tensor([live(model, r["messages"]) for r in tune], dtype=torch.float64)
    maxdiff = float((recon - livep).abs().max())
    print("live vs reconstructed on tune: max abs diff", maxdiff, "changed", changed)
    assert maxdiff < 1e-4, "live and reconstructed disagree"
    frozen = {"patch_file": PATCH.name, "patch_sha256": sha256_file(PATCH), "base_checkpoint_sha256": base_sha(),
              "variant": pick, "ridge_fraction": chosen["frac"], "scale": chosen["scale"], "temperature": T,
              "rows_rewritten": rows, "target_c_for_B": C_B, "cutoff": 0.5,
              "changed_entries": changed, "changed_total": sum(changed.values()),
              "live_vs_reconstructed_max_abs_diff_tune": maxdiff, "selection": chosen, "grid": report,
              "script_sha256": sha256_file(Path(__file__)),
              "selection_rule": "ridge strength per variant by family-grouped 5-fold CV balanced accuracy at cutoff 0.5 "
                                "inside train; variant by tune balanced accuracy then AUROC; no other data used"}
    json.dump(frozen, open(FROZEN, "w", encoding="utf-8"), indent=2)
    print("FROZEN", frozen["patch_sha256"], pick, chosen)


SETS = [("tune", str(ROOT / "data" / "tune.jsonl")), ("check", str(ROOT / "data" / "check.jsonl")),
        ("style3-test", str(ROOT / "data" / "style3-test.jsonl")), ("blind90", "blind90"), ("severity60", "severity60")]


def score_set(model, rows):
    out = []
    for r in rows:
        p = live(model, r["messages"])
        out.append({"id": r["id"], "family": r.get("family", ""), "language": r.get("language", ""),
                    "truth": r["truth"], "level": r.get("level"), "alert_score": p, "flagged": p >= 0.5, "answer": None})
    return out


def cmd_score():
    frozen = json.load(open(FROZEN, encoding="utf-8"))
    assert sha256_file(PATCH) == frozen["patch_sha256"], "patch changed after freezing"
    assert sha256_file(Path(__file__)) == frozen["script_sha256"], "d1_edit.py changed after freezing"
    sets = {n: load_set(spec) for n, spec in SETS}
    model = load_model()
    results = {"frozen_patch_sha256": frozen["patch_sha256"], "sets": {}}
    preds = {}
    for n, (_, rows, _) in sets.items():
        preds[n] = {"unedited": score_set(model, rows)}
    apply_patch(model, PATCH, base_sha())
    for n, (_, rows, _) in sets.items():
        preds[n]["edited"] = score_set(model, rows)
    for n, (_, rows, info) in sets.items():
        results["sets"][n] = {"file": info["path"], "sha256": info["sha256"], "n": info["n"]}
        for which in ("unedited", "edited"):
            pr = preds[n][which]
            stats = {g: metrics.group_stats(metrics._subset(pr, g)) for g in metrics.GROUPS}
            results["sets"][n][which] = {"groups": stats, "pass_mark": metrics.pass_mark(stats)}
    json.dump(results, open(OUT / "results.json", "w", encoding="utf-8"), indent=2, ensure_ascii=False)

    def cell(s, t):
        x = s[t]
        return f"{x['k']}/{x['n']}" if x["n"] else "-"
    L = ["# d1-omni-600M head edit (frozen patch " + frozen["patch_sha256"][:16] + "...)", "",
         f"Variant {frozen['variant']}, ridge fraction {frozen['ridge_fraction']:g}, temperature {frozen['temperature']:.4f}, "
         f"cutoff 0.5. Changed entries: {frozen['changed_entries']}. Risk = level 2-3 (or label risk), benign = level 0 "
         "(or label benign); potential = level 1; unresolved = no level / review. Flagged = P(yes) >= 0.5.", "",
         "| set | model | risk flagged | benign flagged | potential (L1) flagged | unresolved flagged | AUROC all | en | Cantonese | mixed | pass mark |",
         "|---|---|---|---|---|---|---|---|---|---|---|"]
    f = lambda a: "-" if a is None else f"{a:.3f}"  # noqa: E731
    for n in sets:
        for which in ("unedited", "edited"):
            r = results["sets"][n][which]
            g = r["groups"]
            L.append(f"| {n} | {which} | {cell(g['all'], 'risk')} | {cell(g['all'], 'benign')} | "
                     f"{cell(g['all'], 'potential')} | {cell(g['all'], 'unresolved')} | {f(g['all']['auroc'])} | "
                     f"{f(g['en']['auroc'])} | {f(g['zh-Hant-yue']['auroc'])} | {f(g['mixed']['auroc'])} | "
                     f"{'PASS' if r['pass_mark']['pass'] else 'FAIL'} |")
    open(OUT / "README.md", "w", encoding="utf-8").write("\n".join(L) + "\n")
    print("\n".join(L))


if __name__ == "__main__":
    {"fit": cmd_fit, "score": cmd_score}[sys.argv[1]]()
