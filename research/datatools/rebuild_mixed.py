"""Rebuild the v3 mixed training file after a restyle of train.jsonl.

train-mixed-v3.jsonl is the 1,500 chats of train.jsonl (as they were then) followed by 597 handoff
development rows (315 levelled, 282 with `allowed`). This keeps those 597 rows byte for byte and puts the
current train.jsonl in front. Writes train-mixed-v3-restyled.jsonl. Reads no sealed or check data.
"""
import sys
from pathlib import Path

from dt_common import read_jsonl, write_jsonl

DATA = Path(__file__).resolve().parent.parent / "data"


def main():
    train = read_jsonl(DATA / "train.jsonl")
    old = read_jsonl(DATA / "train-mixed-v3.jsonl")
    ids = {r["id"] for r in train}
    handoff = old[len(old) - 597:]
    if any(r["id"] in ids for r in handoff) or len(old) != 2097:
        print("error: train-mixed-v3.jsonl is not laid out as expected", file=sys.stderr)
        return 1
    write_jsonl(DATA / "train-mixed-v3-restyled.jsonl", train + handoff)
    print(f"wrote {len(train) + len(handoff)} rows ({len(train)} train + {len(handoff)} handoff)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
