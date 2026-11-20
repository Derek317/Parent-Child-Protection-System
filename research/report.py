"""Build research/results/stage1-summary.md from every results/<set>/<name>/metrics.json."""
import json
from pathlib import Path

RESULTS = Path(__file__).resolve().parent / "results"
LANGS = ("en", "zh-Hant-yue", "mixed")


def pct(x):
    return "n/a" if x is None else f"{100 * x:.0f}%"


def cell(stat, with_range=False):
    if stat["n"] == 0:
        return "-"
    text = f"{stat['k']}/{stat['n']}"
    if with_range and stat.get("range"):
        text += f" ({pct(stat['rate'])}, {pct(stat['range'][0])}-{pct(stat['range'][1])})"
    return text


def auroc_cell(g):
    if g["auroc"] is None:
        return "n/a"
    r = g.get("auroc_range")
    return f"{g['auroc']:.2f}" + (f" ({r[0]:.2f}-{r[1]:.2f})" if r else "")


def table(entries):
    head = ["Model", "Risk flagged", "Benign flagged", *[f"{l} risk / benign" for l in LANGS],
            "AUROC (95% range)", "Pass mark", "Potential flagged", "Unresolved flagged"]
    lines = ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    for name, m in entries:
        g = m["groups"]
        verdict = "PASS" if m["pass_mark"]["pass"] else f"FAIL ({len(m['pass_mark']['failed'])})"
        lines.append("| " + " | ".join([
            name, cell(g["all"]["risk"], True), cell(g["all"]["benign"], True),
            *[f"{cell(g[l]['risk'])} / {cell(g[l]['benign'])}" for l in LANGS],
            auroc_cell(g["all"]), verdict, cell(g["all"]["potential"]), cell(g["all"]["unresolved"])]) + " |")
    return "\n".join(lines)


def main():
    by_set = {}
    for path in sorted(RESULTS.glob("*/*/metrics.json")):
        m = json.loads(path.read_text(encoding="utf-8"))
        by_set.setdefault(path.parent.parent.name, []).append((path.parent.name, m))
    out = ["# Stage 1 summary", "",
           "Counts are chats flagged / chats in the class. Ranges are 95% cluster-bootstrap ranges over scenario families.",
           "Pass mark: overall risk >= 80% and benign <= 20%; each language risk >= 70% and benign <= 30%.", ""]
    for set_name, entries in by_set.items():
        out += [f"## {set_name}", "", table(entries), ""]
        if set_name == "blind90":
            variants = [(n, m["variants"]["excluding_potential6"]) for n, m in entries if "variants" in m]
            out += ["### blind90 excluding the six potential-level risk chats (families b03, b06)", "", table(variants), ""]
        failed = [(n, m["pass_mark"]["failed"]) for n, m in entries if not m["pass_mark"]["pass"]]
        if failed:
            out += ["Failed conditions:", ""] + [f"- {n}: " + "; ".join(f) for n, f in failed] + [""]
        notes = []
        for n, m in entries:
            if "answer_mass" in m:
                a = m["answer_mass"]
                notes.append(f"- {n}: answer_mass mean {a['mean']:.3f}, below 0.5 on {a['below_0.5']}/{a['n']} chats")
            if "harness_check" in m:
                h = m["harness_check"]
                notes.append(f"- {n}: harness check {'OK' if h['in_range'] else 'OUT OF RANGE'} "
                             f"(risk {h['risk_flagged']}/36, benign {h['benign_flagged']}/36; "
                             f"difference vs handoff max {h['abs_diff_vs_handoff_max']:.3f}, mean {h['abs_diff_vs_handoff_mean']:.3f})")
        out += notes + ([""] if notes else [])
    (RESULTS / "stage1-summary.md").write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"Wrote {RESULTS / 'stage1-summary.md'}")


if __name__ == "__main__":
    main()
