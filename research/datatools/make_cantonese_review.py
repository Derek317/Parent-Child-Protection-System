"""Sample 30 Cantonese/mixed training chats for Derek's natural-or-off review."""
import argparse
import random
import sys
from pathlib import Path

from dt_common import ENDINGS, SEED, add_data_dir_arg, fail, read_json, read_jsonl

LANGS = ["zh-Hant-yue", "mixed"]


def sample(rows, seed=SEED, attempts=2000, exclude_ids=()):
    exclude_ids = set(exclude_ids)
    banned = {r["family"] for r in rows if r["id"] in exclude_ids}
    rows = [r for r in rows if r["id"] not in exclude_ids and r["family"] not in banned]
    rng = random.Random(seed)
    pool = {(l, e): sorted((r for r in rows if r["language"] == l and r["ending"] == e), key=lambda r: r["id"])
            for l in LANGS for e in ENDINGS}
    for key, v in pool.items():
        if len(v) < 3:
            raise ValueError(f"need at least 3 chats for {key}, found {len(v)}")
    for _ in range(attempts):
        used, chosen = set(), []
        ok = True
        for l in LANGS:
            for e in ENDINGS:
                cands = [r for r in pool[(l, e)] if r["family"] not in used]
                if len(cands) < 3:
                    ok = False
                    break
                pick = rng.sample(cands, 3)
                chosen.extend(pick)
                used.update(r["family"] for r in pick)
            if not ok:
                break
        if not ok:
            continue
        if all({r["other_role"] for r in chosen if r["language"] == l} == {"adult", "peer"} for l in LANGS):
            rng.shuffle(chosen)
            return chosen
    raise ValueError("could not find a sample with distinct families covering both roles")


def render(chosen, round_no=1):
    out = [f"# Cantonese check{'' if round_no == 1 else ', round ' + str(round_no)}", "",
           "These are 30 invented chats between a young person (`child`) and another person (`other`). "
           "Please read each one as a Hong Kong reader would.", "",
           "For each chat:", "",
           "1. Read the whole chat.",
           "2. Tick **natural** if it sounds like real Hong Kong Cantonese (or Hong Kong English-Cantonese mixing) typed in a chat.",
           "3. Tick **off** if something sounds wrong, such as written-style Chinese, Mandarin wording, stiff or translated phrasing, or mixing that no one would do.",
           "4. If you tick off, add a short note on what sounds wrong. This helps rewrite the instructions.", "",
           "If more than 6 of the 30 are marked off, the whole batch is rewritten.", "",
           "---", ""]
    for n, r in enumerate(chosen, 1):
        out.append(f"## {n}")
        out.append("")
        for m in r["messages"]:
            out.append(f"> {m['speaker']}: {m['text']}  ")
        out.append("")
        out.append("- [ ] natural   - [ ] off   Note:")
        out.append("")
    out += ["---", "", "## Id to number table (for the project team, not needed for the review)", "",
            "| Number | Id |", "| ---: | --- |"]
    out += [f"| {n} | {r['id']} |" for n, r in enumerate(chosen, 1)]
    return "\n".join(out) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    add_data_dir_arg(ap)
    ap.add_argument("--seed", type=int, default=SEED)
    ap.add_argument("--exclude-ids-file", type=Path, default=None,
                    help="JSON list of chat ids to leave out, together with their whole families")
    ap.add_argument("--out", default="cantonese-review.md", help="output file name inside the data dir")
    ap.add_argument("--round", type=int, default=1, dest="round_no")
    args = ap.parse_args(argv)
    src = Path(args.data_dir) / "train.jsonl"
    try:
        exclude = read_json(args.exclude_ids_file) if args.exclude_ids_file else []
        chosen = sample(read_jsonl(src), seed=args.seed, exclude_ids=exclude)
    except (OSError, ValueError, KeyError) as exc:
        return fail(f"error: {exc}")
    dest = Path(args.data_dir) / args.out
    with open(dest, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(render(chosen, args.round_no))
    print(f"wrote {dest} with {len(chosen)} chats")
    return 0


if __name__ == "__main__":
    sys.exit(main())
