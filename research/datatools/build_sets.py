"""Build train/tune/check jsonl files from validated family files. Does not touch sealed."""
import argparse
import sys
from pathlib import Path

from dt_common import (ENDINGS, LANGUAGES, add_data_dir_arg, chat_key, fail,
                       load_catalogue, load_splits, read_jsonl, write_jsonl)
from validate_family import validate_file

BUILD = ["train", "tune", "check"]


def build(data_dir, splits=None, catalogue=None):
    """Return (rows_by_split, errors)."""
    data_dir = Path(data_dir)
    splits = splits or load_splits(data_dir)
    catalogue = catalogue or load_catalogue(data_dir)
    errors = []
    owner = {}
    for s in ["train", "tune", "check", "sealed"]:
        for fam in splits.get(s, []):
            if fam in owner and owner[fam] != s:
                errors.append(f"family {fam} is in both {owner[fam]} and {s}")
            owner.setdefault(fam, s)
    out = {}
    for s in BUILD:
        rows = []
        for fam in splits[s]:
            f = data_dir / "raw" / s / f"{fam}.jsonl"
            if not f.exists():
                errors.append(f"missing family file {f}")
                continue
            problems, _ = validate_file(f, catalogue)
            if problems:
                errors.append(f"{f} has {len(problems)} problem(s); run validate_family.py on it")
                continue
            rows.extend(read_jsonl(f))
        out[s] = sorted(rows, key=lambda r: r["id"])
    seen = {}
    for s, rows in out.items():
        for r in rows:
            k = chat_key(r)
            if k in seen:
                errors.append(f"identical chat in {seen[k]} and {r['id']}")
            else:
                seen[k] = r["id"]
    return out, errors


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    add_data_dir_arg(ap)
    args = ap.parse_args(argv)
    try:
        out, errors = build(args.data_dir)
    except (OSError, ValueError, KeyError) as exc:
        return fail(f"error: {exc}")
    if errors:
        for e in errors:
            print(f"error: {e}", file=sys.stderr)
        return 1
    for s in BUILD:
        write_jsonl(Path(args.data_dir) / f"{s}.jsonl", out[s])
        counts = {}
        for r in out[s]:
            key = (r["language"], r["ending"])
            counts[key] = counts.get(key, 0) + 1
        print(f"{s}: {len(out[s])} rows")
        for lang in LANGUAGES:
            print(f"  {lang:12s} " + "  ".join(f"{e}={counts.get((lang, e), 0)}" for e in ENDINGS))
    return 0


if __name__ == "__main__":
    sys.exit(main())
