"""Write the score-only report the app imports, from one harness run.

    python research/make_report.py research/results/style3-test/qwen3-1.7b-lora-v3-q4 --gguf <model.gguf>

Same shape as the app's igguard-d1-risk-v1 report (RiskScoreReport.kt): conversation ids C001.., a 0-100 score or
null with a review reason, nothing else. No chat text, ids, labels or languages leave the run folder.
`model` and `revision` name the fine-tuned Qwen3 file, so the app's reader must allow them (it accepts d1 only today).
"""
import argparse
import hashlib
import json
from pathlib import Path

MODEL = "igguard/qwen3-1.7b-lora-v3-Q4_K_M"
MAX_CONVERSATIONS, MAX_BYTES = 500, 128 * 1024  # limits enforced by the app's reader


def build(predictions, revision):
    rows = []
    for i, p in enumerate(predictions, 1):
        unresolved = p["answer"] == "U" or p["report_score"] is None
        rows.append({"conversation": f"C{i:03d}", "score": None if unresolved else p["report_score"],
                     "review_reason": "insufficient_context" if unresolved else None})
    report = {"schema": "igguard-d1-risk-v1", "model": MODEL, "revision": revision,
              "source": "local_research", "conversations": rows}
    text = json.dumps(report, indent=2, allow_nan=False) + "\n"
    assert 0 < len(rows) <= MAX_CONVERSATIONS and len(text.encode()) <= MAX_BYTES, "report exceeds the app reader's limits"
    assert all(r["score"] is None or 0 <= r["score"] <= 100 for r in rows)
    return text


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("run", type=Path)
    ap.add_argument("--gguf", type=Path, required=True)
    a = ap.parse_args()
    h = hashlib.sha256()
    with open(a.gguf, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    preds = [json.loads(l) for l in open(a.run / "predictions.jsonl", encoding="utf-8") if l.strip()]
    (a.run / "score-report.json").write_text(build(preds, h.hexdigest()), encoding="utf-8", newline="\n")
    print(f"Wrote {a.run / 'score-report.json'} ({len(preds)} conversations)")
