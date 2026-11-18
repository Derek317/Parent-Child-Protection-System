"""Assign catalogue settings to splits and write writer batch briefs."""
import argparse
import random
import sys
from pathlib import Path

from dt_common import (SEED, SPLITS, SPLIT_SIZES, add_data_dir_arg, fail,
                       load_catalogue, write_json, write_jsonl)

BATCH = 5


def largest_remainder(sizes, total_a, total):
    """Split total_a adults over splits proportionally to their sizes."""
    exact = {k: sizes[k] * total_a / total for k in sizes}
    base = {k: int(v) for k, v in exact.items()}
    left = total_a - sum(base.values())
    order = sorted(sizes, key=lambda k: -(exact[k] - base[k]))  # stable: ties keep split order
    for k in order[:left]:
        base[k] += 1
    return base


def make_splits(catalogue, seed=SEED, sizes=SPLIT_SIZES):
    if len(catalogue) != sum(sizes.values()):
        raise ValueError(f"catalogue has {len(catalogue)} settings, expected {sum(sizes.values())}")
    rng = random.Random(seed)
    adults = sorted(i for i, r in catalogue.items() if r["other_role"] == "adult")
    peers = sorted(i for i, r in catalogue.items() if r["other_role"] == "peer")
    rng.shuffle(adults)
    rng.shuffle(peers)
    a_counts = largest_remainder(sizes, len(adults), len(catalogue))
    out = {"seed": seed}
    ai = pi = 0
    for s in SPLITS:
        na = a_counts[s]
        npeer = sizes[s] - na
        ids = adults[ai:ai + na] + peers[pi:pi + npeer]
        ai += na
        pi += npeer
        out[s] = sorted(ids)
    return out


def write_briefs(data_dir, catalogue, splits):
    n = 0
    for s in SPLITS:
        ids = splits[s]
        for b in range(0, len(ids), BATCH):
            rows = [catalogue[i] for i in ids[b:b + BATCH]]
            n += 1
            write_jsonl(Path(data_dir) / "briefs" / s / f"batch-{b // BATCH + 1:02d}.jsonl", rows)
    return n


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    add_data_dir_arg(p)
    p.add_argument("--force", action="store_true", help="overwrite an existing splits.json")
    args = p.parse_args(argv)
    data_dir = args.data_dir
    out = data_dir / "splits.json"
    if out.exists() and not args.force:
        return fail(f"{out} already exists; use --force to overwrite")
    try:
        cat = load_catalogue(data_dir)
        splits = make_splits(cat)
    except (OSError, ValueError, KeyError) as exc:
        return fail(f"error: {exc}")
    write_json(out, splits)
    nb = write_briefs(data_dir, cat, splits)
    print(f"wrote {out} and {nb} batch briefs")
    regions = {}
    for s in SPLITS:
        roles = {"adult": 0, "peer": 0}
        for i in splits[s]:
            roles[cat[i]["other_role"]] += 1
        print(f"{s:7s} {len(splits[s]):3d} settings  adult {roles['adult']}  peer {roles['peer']}")
        regions[s] = {}
        for i in splits[s]:
            r = cat[i].get("region")
            regions[s][r] = regions[s].get(r, 0) + 1
    for s in SPLITS:
        print(f"regions {s}: " + ", ".join(f"{k}={v}" for k, v in sorted(regions[s].items(), key=lambda kv: str(kv[0]))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
