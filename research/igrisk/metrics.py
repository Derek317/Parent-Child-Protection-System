"""Pure metric functions. A prediction row is a dict with:
id, family, language, truth (risk|benign|potential|unresolved), level (0-3 or None),
alert_score, flagged, answer ('0'..'3', 'U' or None), report_score (or None)."""
import random

LANGUAGES = ("en", "zh-Hant-yue", "mixed")
GROUPS = ("all",) + LANGUAGES
TRUTHS = ("risk", "benign", "potential", "unresolved")
BOOTSTRAP_REPLICATES = 2000
BOOTSTRAP_SEED = 20261119
# Pass mark (design doc): (min risk rate, max benign rate)
OVERALL_BOUNDS = (0.80, 0.20)
LANGUAGE_BOUNDS = (0.70, 0.30)


def auroc(rows):
    """P(score of a risk chat > score of a benign chat), ties count 0.5. None if a class is empty."""
    pos = [r["alert_score"] for r in rows if r["truth"] == "risk"]
    neg = [r["alert_score"] for r in rows if r["truth"] == "benign"]
    if not pos or not neg:
        return None
    wins = sum((p > n) + 0.5 * (p == n) for p in pos for n in neg)
    return wins / (len(pos) * len(neg))


def _rate(k, n):
    return k / n if n else None


def group_stats(rows):
    """Counts per truth class plus AUROC for one list of rows."""
    out = {}
    for truth in TRUTHS:
        sel = [r for r in rows if r["truth"] == truth]
        k = sum(1 for r in sel if r["flagged"])
        out[truth] = {"k": k, "n": len(sel), "rate": _rate(k, len(sel))}
    out["auroc"] = auroc(rows)
    return out


def _subset(rows, group):
    return rows if group == "all" else [r for r in rows if r["language"] == group]


def pass_mark(stats):
    """stats: {group: group_stats}. Judged on the measured figures. Returns {"pass": bool, "failed": [...]}"""
    failed = []
    for group in GROUPS:
        lo, hi = OVERALL_BOUNDS if group == "all" else LANGUAGE_BOUNDS
        s = stats.get(group)
        for truth, ok in (("risk", lambda x: x >= lo), ("benign", lambda x: x <= hi)):
            rate = s[truth]["rate"] if s else None
            if rate is None:
                failed.append(f"{group} {truth}: no chats")
            elif not ok(rate):
                bound = f">= {lo}" if truth == "risk" else f"<= {hi}"
                failed.append(f"{group} {truth} {rate:.3f} (needs {bound})")
    return {"pass": not failed, "failed": failed}


def _percentile(sorted_vals, q):
    """Linear-interpolated percentile (q in 0..100) of a sorted list."""
    pos = (len(sorted_vals) - 1) * q / 100
    lo = int(pos)
    hi = min(lo + 1, len(sorted_vals) - 1)
    return sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * (pos - lo)


def _range(values):
    values = sorted(v for v in values if v is not None)
    if not values:
        return None
    return [_percentile(values, 2.5), _percentile(values, 97.5)]


def bootstrap(rows, replicates=BOOTSTRAP_REPLICATES, seed=BOOTSTRAP_SEED):
    """Cluster bootstrap over families. Returns {group: {"risk": [lo, hi], "benign": ..,
    "potential": .., "unresolved": .., "auroc": ..}}; a range is None if never defined."""
    by_family = {}
    for r in rows:
        by_family.setdefault(r["family"], []).append(r)
    families = sorted(by_family)
    rng = random.Random(seed)
    draws = {g: {m: [] for m in TRUTHS + ("auroc",)} for g in GROUPS}
    for _ in range(replicates):
        sample = [r for f in (rng.choice(families) for _ in families) for r in by_family[f]]
        for g in GROUPS:
            s = group_stats(_subset(sample, g))
            for t in TRUTHS:
                draws[g][t].append(s[t]["rate"])
            draws[g]["auroc"].append(s["auroc"])
    return {g: {m: _range(v) for m, v in d.items()} for g, d in draws.items()}


def info(rows):
    """For information only: four-way (levels 0-3) exact match over chats with a level, and the share of unresolved chats answered U."""
    known = [r for r in rows if r["level"] is not None and r["answer"] is not None]
    unres = [r for r in rows if r["truth"] == "unresolved" and r["answer"] is not None]
    match = sum(r["answer"] == str(r["level"]) for r in known)
    u = sum(r["answer"] == "U" for r in unres)
    return {"exact_match": {"k": match, "n": len(known), "rate": _rate(match, len(known))},
            "unresolved_answered_U": {"k": u, "n": len(unres), "rate": _rate(u, len(unres))}}


def evaluate(rows, replicates=BOOTSTRAP_REPLICATES, seed=BOOTSTRAP_SEED):
    stats = {g: group_stats(_subset(rows, g)) for g in GROUPS}
    ranges = bootstrap(rows, replicates, seed)
    for g in GROUPS:
        for m in TRUTHS + ("auroc",):
            if m == "auroc":
                stats[g]["auroc_range"] = ranges[g][m]
            else:
                stats[g][m]["range"] = ranges[g][m]
    return {"groups": stats, "pass_mark": pass_mark(stats), "info": info(rows),
            "bootstrap": {"replicates": replicates, "seed": seed, "unit": "family"}}
