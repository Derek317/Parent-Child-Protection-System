"""Sealed test handling: strip labels for blind relabelling, compare, hash, verify.

Nobody needs to read sealed chat text: strip writes label-free parts, compare only prints counts.
"""
import argparse
import hashlib
import random
import sys
from pathlib import Path

from dt_common import (ENDINGS, LANGUAGES, LEVELS, SEED, add_data_dir_arg, fail,
                       load_catalogue, load_splits, read_jsonl, write_json, write_jsonl, read_json)
from validate_family import add_length_args, validate_file

PARTS = 5
ANSWERS = ["0", "1", "2", "3", "U"]
CLASS_OF = {"0": "benign", "1": "potential", "2": "risk", "3": "risk", "U": "unresolved"}


def label_of(row):
    lv = row["level"]
    return "U" if lv is None else str(lv)


def load_sealed_rows(data_dir, min_messages=6, spread_at=11):
    data_dir = Path(data_dir)
    splits = load_splits(data_dir)
    cat = load_catalogue(data_dir)
    rows, errors = [], []
    for fam in splits["sealed"]:
        f = data_dir / "raw" / "sealed" / f"{fam}.jsonl"
        if not f.exists():
            errors.append(f"missing sealed family file {f}")
            continue
        problems, _ = validate_file(f, cat, min_messages, spread_at)
        if problems:
            errors.append(f"{f} has {len(problems)} problem(s); run validate_family.py on it")
            continue
        rows.extend(read_jsonl(f))
    return rows, errors


def strip(data_dir, min_messages=6, spread_at=11):
    data_dir = Path(data_dir)
    rows, errors = load_sealed_rows(data_dir, min_messages, spread_at)
    if errors:
        return errors
    rows.sort(key=lambda r: r["id"])
    random.Random(SEED).shuffle(rows)
    key = {}
    work = []
    for n, r in enumerate(rows, 1):
        alias = f"x{n:04d}"
        key[alias] = r["id"]
        work.append({"id": alias, "messages": r["messages"]})
    size = -(-len(work) // PARTS)
    for p in range(PARTS):
        write_jsonl(data_dir / "sealed-work" / f"part-{p + 1}.jsonl", work[p * size:(p + 1) * size])
    write_json(data_dir / "sealed-work" / "key.json", key)
    print(f"stripped {len(work)} chats into {PARTS} parts of up to {size}; key in sealed-work/key.json")
    return []


def norm_answer(v):
    if v is None:
        return "U"
    s = str(v).strip().upper()
    if s in ("", "NULL", "NONE"):
        return "U"
    return s if s in ANSWERS else None


def compare(data_dir, min_messages=6, spread_at=11):
    """Return (summary, errors)."""
    data_dir = Path(data_dir)
    work = data_dir / "sealed-work"
    key = read_json(work / "key.json")
    files = sorted(work.glob("answers-*.jsonl"))
    if not files:
        return None, [f"no answers-N.jsonl files in {work}"]
    answers, errors = {}, []
    for f in files:
        for r in read_jsonl(f):
            a = r.get("id")
            n = norm_answer(r.get("answer"))
            if n is None:
                errors.append(f"{f.name}: bad answer {r.get('answer')!r} for {a}")
            elif a in answers:
                errors.append(f"alias {a} answered more than once")
            elif a not in key:
                errors.append(f"{f.name}: unknown alias {a}")
            else:
                answers[a] = n
    missing = sorted(set(key) - set(answers))
    if missing:
        errors.append(f"{len(missing)} aliases have no answer (first: {', '.join(missing[:5])})")
    if errors:
        return None, errors
    by_id = {}
    for r in load_sealed_rows(data_dir, min_messages, spread_at)[0]:
        by_id[r["id"]] = r
    kept, aside = [], []
    confusion = {e: {a: 0 for a in ANSWERS} for e in ENDINGS}
    per_lang = {l: {"total": 0, "set_aside": 0} for l in LANGUAGES}
    per_end = {e: {"total": 0, "set_aside": 0} for e in ENDINGS}
    mismatch_kept = 0
    remaining = {l: {"risk": 0, "benign": 0, "potential": 0, "unresolved": 0} for l in LANGUAGES}
    for alias, rid in key.items():
        row = by_id[rid]
        ans = answers[alias]
        lab = label_of(row)
        confusion[row["ending"]][ans] += 1
        per_lang[row["language"]]["total"] += 1
        per_end[row["ending"]]["total"] += 1
        if CLASS_OF[lab] == CLASS_OF[ans]:
            kept.append(row)
            remaining[row["language"]][CLASS_OF[lab]] += 1
            if lab != ans:
                mismatch_kept += 1
        else:
            aside.append(dict(row, blind_answer=None if ans == "U" else int(ans)))
            per_lang[row["language"]]["set_aside"] += 1
            per_end[row["ending"]]["set_aside"] += 1
    kept.sort(key=lambda r: r["id"])
    aside.sort(key=lambda r: r["id"])
    write_jsonl(data_dir / "sealed-test.jsonl", kept)
    write_jsonl(data_dir / "sealed-set-aside.jsonl", aside)
    total = len(key)

    def rate(d):
        for v in d.values():
            v["rate"] = round(v["set_aside"] / v["total"], 4) if v["total"] else 0.0
        return d
    summary = {
        "total": total,
        "kept": len(kept),
        "set_aside": len(aside),
        "set_aside_rate": round(len(aside) / total, 4) if total else 0.0,
        "per_language": rate(per_lang),
        "per_ending": rate(per_end),
        "confusion_intended_by_blind_answer": confusion,
        "kept_2_vs_3_mismatches": mismatch_kept,
        "remaining_per_language": remaining,
        "over_15_percent": bool(total and len(aside) / total > 0.15),
    }
    write_json(data_dir / "sealed-relabel-summary.json", summary)
    return summary, []


def print_summary(s):
    print(f"total {s['total']}  kept {s['kept']}  set aside {s['set_aside']} ({s['set_aside_rate']:.1%})")
    print(f"over 15 percent: {s['over_15_percent']}")
    for name in ("per_language", "per_ending"):
        for k, v in s[name].items():
            print(f"  {k:12s} set aside {v['set_aside']}/{v['total']} ({v['rate']:.1%})")
    print("confusion (rows intended ending, columns blind answer " + " ".join(ANSWERS) + ")")
    for e, row in s["confusion_intended_by_blind_answer"].items():
        print(f"  {e:11s} " + " ".join(f"{row[a]:4d}" for a in ANSWERS))
    print(f"2-vs-3 mismatches kept: {s['kept_2_vs_3_mismatches']}")
    for l, v in s["remaining_per_language"].items():
        print(f"  remaining {l:12s} risk {v['risk']}  benign {v['benign']}  potential {v['potential']}  unresolved {v['unresolved']}")


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


HASHED = ["sealed-test.jsonl", "sealed-set-aside.jsonl"]


def do_hash(data_dir):
    data_dir = Path(data_dir)
    lines = []
    for name in HASHED:
        f = data_dir / name
        if not f.exists():
            raise FileNotFoundError(f"{f} not found (run seal.py compare first)")
        lines.append(f"{sha256_of(f)}  {name}")
    with open(data_dir / "SEALED.sha256", "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")
    return lines


def do_verify(data_dir):
    data_dir = Path(data_dir)
    rec = data_dir / "SEALED.sha256"
    if not rec.exists():
        return ["SEALED.sha256 not found"], []
    bad, ok, seen = [], [], set()
    with open(rec, "r", encoding="utf-8") as fh:
        for line in fh:
            if not line.strip():
                continue
            digest, name = line.rstrip("\n").split("  ", 1)
            seen.add(name)
            f = data_dir / name
            if not f.exists():
                bad.append(f"{name}: file missing")
            elif sha256_of(f) != digest:
                bad.append(f"{name}: hash MISMATCH")
            else:
                ok.append(f"{name}: ok")
    bad.extend(f"{name}: not in SEALED.sha256" for name in HASHED if name not in seen)
    return bad, ok


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for c in ("strip", "compare", "hash", "verify"):
        sp = sub.add_parser(c)
        add_data_dir_arg(sp)
        if c in ("strip", "compare"):
            add_length_args(sp)
    args = ap.parse_args(argv)
    try:
        if args.cmd == "strip":
            errs = strip(args.data_dir, args.min_messages, args.spread_at)
        elif args.cmd == "compare":
            summary, errs = compare(args.data_dir, args.min_messages, args.spread_at)
            if not errs:
                print_summary(summary)
        elif args.cmd == "hash":
            for l in do_hash(args.data_dir):
                print(l)
            errs = []
        else:
            bad, ok = do_verify(args.data_dir)
            for l in ok + bad:
                print(l)
            errs = [] if not bad else ["verification failed"]
    except (OSError, ValueError, KeyError) as exc:
        return fail(f"error: {exc}")
    if errs:
        for e in errs:
            print(f"error: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
