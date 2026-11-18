"""Check and apply wording-only patches for the zh-Hant-yue and mixed chats of a family."""
import argparse
import shutil
import sys
from pathlib import Path

from dt_common import (add_data_dir_arg, fail, load_catalogue, load_splits,
                       read_json, read_jsonl, write_jsonl)
from validate_family import validate_rows

NON_EN = ("zh-Hant-yue", "mixed")


def load_derek(data_dir):
    f = Path(data_dir) / "derek-reviewed-ids.json"
    return set(read_json(f)) if f.exists() else set()


def merge_rows(raw_rows, patch_by_id):
    out = []
    for r in raw_rows:
        if r["id"] in patch_by_id:
            r = dict(r, messages=patch_by_id[r["id"]])
        out.append(r)
    return out


def check_patch(patch_path, data_dir, catalogue, derek):
    """Return (problems, warnings, merged_rows_or_None)."""
    patch_path = Path(patch_path)
    family, split = patch_path.stem, patch_path.parent.name
    raw_path = Path(data_dir) / "raw" / split / f"{family}.jsonl"
    problems, warnings = [], []
    if not raw_path.exists():
        return [f"no family file {raw_path} for this patch (split taken from folder name {split!r})"], [], None
    try:
        raw = read_jsonl(raw_path)
        patch = read_jsonl(patch_path)
    except (OSError, ValueError, UnicodeDecodeError) as exc:
        return [f"cannot read: {exc}"], [], None
    orig = {r["id"]: r for r in raw}
    want = {r["id"] for r in raw if r["language"] in NON_EN}
    seen = {}
    for n, p in enumerate(patch, 1):
        pid = p.get("id") if isinstance(p, dict) else None
        if not isinstance(pid, str):
            problems.append(f"patch row {n} has no string id")
            continue
        seen.setdefault(pid, []).append(p)
    if len(patch) != 10:
        problems.append(f"patch has {len(patch)} rows; must be exactly the family's 10 zh-Hant-yue and mixed chats")
    for pid, lst in seen.items():
        if len(lst) > 1:
            problems.append(f"{pid}: appears {len(lst)} times in the patch; list it once")
        if pid in orig and orig[pid]["language"] == "en":
            problems.append(f"{pid}: is an English chat; remove it from the patch")
        elif pid not in orig:
            problems.append(f"{pid}: not a chat of family {family}; remove it")
    for pid in sorted(want - set(seen)):
        problems.append(f"{pid}: missing from the patch; add it")
    patch_by_id = {}
    for pid, lst in seen.items():
        if pid not in want:
            continue
        p = lst[0]
        msgs = p.get("messages")
        o = orig[pid]["messages"]
        if not isinstance(msgs, list) or not all(isinstance(m, dict) for m in msgs):
            problems.append(f"{pid}: messages must be a list of objects")
            continue
        if len(msgs) != len(o):
            problems.append(f"{pid}: has {len(msgs)} messages but the original has {len(o)}; keep the count")
            continue
        ok = True
        for i, (m, om) in enumerate(zip(msgs, o), 1):
            if set(m) != {"speaker", "text"}:
                problems.append(f"{pid}: message {i} must have exactly the keys speaker and text")
                ok = False
            elif m["speaker"] != om["speaker"]:
                problems.append(f"{pid}: message {i} speaker is {m['speaker']!r} but the original is {om['speaker']!r}; keep the speaker order")
                ok = False
            elif not isinstance(m["text"], str) or not m["text"].strip():
                problems.append(f"{pid}: message {i} text must be a non-empty string")
                ok = False
        if not ok:
            continue
        diff = sum(1 for m, om in zip(msgs, o) if m["text"] != om["text"])
        if pid in derek:
            if diff:
                problems.append(f"{pid}: was hand-edited by the user; it must stay identical to the current file ({diff} message(s) differ)")
        elif diff / len(o) < 0.30:
            warnings.append(f"{pid}: only {diff} of {len(o)} messages changed - unchanged, confirm it already reads naturally")
        patch_by_id[pid] = [{"speaker": m["speaker"], "text": m["text"]} for m in msgs]
    merged = None
    if not problems:
        merged = merge_rows(raw, patch_by_id)
        vp, vw = validate_rows(merged, family, catalogue)
        problems += [f"after applying the patch: {p}" for p in vp]
        warnings += [f"after applying the patch: {w}" for w in vw]
    return problems, warnings, merged


def cmd_check(args):
    cat = load_catalogue(args.data_dir)
    derek = load_derek(args.data_dir)
    bad = 0
    for f in args.patches:
        problems, warnings, _ = check_patch(f, args.data_dir, cat, derek)
        if problems:
            bad += 1
            print(f"{f}: {len(problems)} problem(s)")
            for p in problems:
                print(f"  - {p}")
        else:
            print(f"{f}: OK")
        for w in warnings:
            print(f"  warning: {w}")
    return 1 if bad else 0


def apply_split(data_dir, split):
    """Return (errors, stats). Writes only if every patch passes."""
    data_dir = Path(data_dir)
    cat = load_catalogue(data_dir)
    derek = load_derek(data_dir)
    families = load_splits(data_dir)[split]
    errors, merged_by = [], {}
    for fam in families:
        pf = data_dir / "restyle" / split / f"{fam}.jsonl"
        if not pf.exists():
            errors.append(f"missing patch file {pf}")
            continue
        problems, _, merged = check_patch(pf, data_dir, cat, derek)
        errors += [f"{pf}: {p}" for p in problems]
        merged_by[fam] = merged
    if errors:
        return errors, None
    raw_dir = data_dir / "raw" / split
    backup = data_dir / "raw-round1" / split
    if not backup.exists():
        backup.mkdir(parents=True)
        for fam in families:
            shutil.copy2(raw_dir / f"{fam}.jsonl", backup / f"{fam}.jsonl")
    chats = msgs = 0
    for fam in families:
        old = read_jsonl(raw_dir / f"{fam}.jsonl")
        for o, n in zip(old, merged_by[fam]):
            d = sum(1 for a, b in zip(o["messages"], n["messages"]) if a["text"] != b["text"])
            msgs += d
            chats += 1 if d else 0
        write_jsonl(raw_dir / f"{fam}.jsonl", merged_by[fam])
    return [], {"families": len(families), "chats_changed": chats, "messages_changed": msgs}


def cmd_apply(args):
    errors, stats = apply_split(args.data_dir, args.split)
    if errors:
        for e in errors:
            print(f"error: {e}", file=sys.stderr)
        return 1
    print(f"applied {stats['families']} families: {stats['chats_changed']} chats and "
          f"{stats['messages_changed']} messages changed")
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("check", help="check patch files")
    c.add_argument("patches", nargs="+")
    add_data_dir_arg(c)
    a = sub.add_parser("apply", help="apply all patches of a split")
    a.add_argument("split", choices=["train", "tune", "check", "sealed"])
    add_data_dir_arg(a)
    args = ap.parse_args(argv)
    try:
        return cmd_check(args) if args.cmd == "check" else cmd_apply(args)
    except (OSError, ValueError, KeyError) as exc:
        return fail(f"error: {exc}")


if __name__ == "__main__":
    sys.exit(main())
