# Style 3 test set: working files

`research/data/style3-test.jsonl` (kept chats) and `research/data/style3-set-aside.jsonl` (blind labeller disagreed on the pass-mark class) are built from this folder. The guide is `research/data/STYLE3-GUIDE.md`.

This set exists to test a trained model on chats written in a style it has never seen. **Do not train on it, tune on it, or use it to choose a model, prompt or cutoff.**

- `catalogue/settings.jsonl`: the 20 settings (`x01`–`x20`), none taken from `research/data/catalogue/`.
- `raw/sealed/`: one file per setting, 15 chats each, as written.
- `briefs/`, `writer-prompt.txt`: what each writer subagent was given. Writers were told to open nothing else in the repository, and their file access was audited.
- `sealed-work/`: label-free parts, the blind labellers' answers and the alias key. `labeller-prompt.txt` is what the labellers were given.
- `relabel-summary.json`: agreement counts.

Validate (from the repository root):

    python3.12 research/datatools/validate_family.py --data-dir research/data/style3 --min-messages 15 --spread-at 21 research/data/style3/raw/sealed

Rebuild the two output files:

    python3.12 research/datatools/seal.py compare --data-dir research/data/style3 --min-messages 15 --spread-at 21

`compare` writes `sealed-test.jsonl` and `sealed-set-aside.jsonl` inside this folder; they are copied to `research/data/style3-test.jsonl` and `research/data/style3-set-aside.jsonl`.
