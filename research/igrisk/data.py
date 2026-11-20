"""Loading test sets and mapping their labels to truth classes."""
import hashlib
import json
from pathlib import Path

RESEARCH = Path(__file__).resolve().parent.parent
HANDOFF = RESEARCH / "data" / "handoff"
BUILTIN = {
    "blind90": {"file": HANDOFF / "blind.jsonl", "mode": "binary",
                "sha256": "4fcf4a33536006a92512426533cb2eadcce1d4717caa6a58cbd06d5fdc934be1"},
    "severity60": {"file": HANDOFF / "severity-test.jsonl", "mode": "severity",
                   "sha256": "e4e7a141039d85f8952fe657d5d9d6b55ab4ad60d76c901d81429f5ae2358dd8"},
}
POTENTIAL_FAMILIES = ("b03", "b06")  # risk chats of these families sit at the potential level


def sha256_file(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def truth_from_level(level):
    if level is None:
        return "unresolved"
    return {0: "benign", 1: "potential", 2: "risk", 3: "risk"}[level]


def truth_from_label(label):
    return {"risk": "risk", "benign": "benign", "review": "unresolved"}[label]


def load_set(spec):
    """spec: 'blind90', 'severity60' or a path to a JSONL file with a `level` field.
    Returns (label, rows, info). Each row gains `truth` and `level`."""
    if spec in BUILTIN:
        b = BUILTIN[spec]
        path, mode, pinned, label = b["file"], b["mode"], b["sha256"], spec
    else:
        path, mode, pinned, label = Path(spec), "severity", None, Path(spec).stem
    digest = sha256_file(path)
    if pinned and digest != pinned:
        raise SystemExit(f"{path} has sha256 {digest}, expected {pinned}. Refusing to run.")
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            row = json.loads(line)
            if spec == "blind90":
                row["truth"], row["level"] = truth_from_label(row["label"]), None
            else:
                row["truth"] = truth_from_level(row["level"])
            rows.append(row)
    ids = [r["id"] for r in rows]
    if len(set(ids)) != len(ids):
        raise SystemExit("Duplicate chat ids in input")
    shown = path.relative_to(RESEARCH.parent).as_posix() if path.is_relative_to(RESEARCH.parent) else str(path)
    return label, rows, {"path": shown, "sha256": digest, "mode": mode, "n": len(rows)}


def excluding_potential(rows):
    """blind90 variant: drop the risk chats of families b03 and b06."""
    return [r for r in rows if not (r["truth"] == "risk" and r["family"] in POTENTIAL_FAMILIES)]
