import json
import sys
import unittest
from pathlib import Path

RESEARCH = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RESEARCH))
import train_lora  # noqa: E402

BLIND = RESEARCH / "data" / "handoff" / "blind.jsonl"
RUNS = {"qwen3": "qwen3-1.7b-q8", "gemma4": "gemma4-e2b-q4"}

try:
    import torch  # noqa: F401
    import transformers  # noqa: F401
    HAVE_TORCH = True
except ImportError:
    HAVE_TORCH = False


class PromptParity(unittest.TestCase):
    def test_first_blind_row_matches_stage1_first_prompt(self):
        with open(BLIND, encoding="utf-8") as f:
            row = json.loads(f.readline())
        for family, name in RUNS.items():
            with self.subTest(family=family):
                fp = json.loads((RESEARCH / "results" / "blind90" / name / "run.json")
                                .read_text(encoding="utf-8"))["first_prompt"]
                self.assertEqual(fp["id"], row["id"])
                # run.json stores the full rendered text, so this is a whole-string comparison.
                self.assertEqual(train_lora.render(family, row["messages"]), fp["text"])


class Targets(unittest.TestCase):
    def test_target_mapping(self):
        self.assertEqual([train_lora.target_of(x) for x in (0, 1, 2, 3, None)], ["0", "1", "2", "3", "U"])
        self.assertEqual(train_lora.ANSWERS, ("0", "1", "2", "3", "U"))


class PathRefusal(unittest.TestCase):
    def test_protected_paths_raise(self):
        for p in ("research/data/check.jsonl", "research/data/sealed-test.jsonl",
                  "research/data/sealed-set-aside.jsonl", "research/data/raw/sealed/x.jsonl",
                  "research/data/sealed-work/a.jsonl"):
            with self.subTest(p=p), self.assertRaises(ValueError):
                train_lora.check_path(p)
            with self.assertRaises(ValueError):
                train_lora.load_rows(p)

    def test_train_and_tune_allowed(self):
        train_lora.check_path(train_lora.TRAIN)
        train_lora.check_path(train_lora.TUNE)


@unittest.skipUnless(HAVE_TORCH, "torch/transformers not installed")
class LastPosition(unittest.TestCase):
    def test_left_padding_puts_real_token_last(self):
        import torch
        ids, mask, pos = train_lora.collate([[5, 6, 7], [8], [9, 10]], pad_id=0)
        self.assertEqual(ids[:, -1].tolist(), [7, 8, 10])
        self.assertTrue(bool(mask[:, -1].all()))
        self.assertEqual(pos.tolist(), [[0, 1, 2], [0, 0, 0], [0, 0, 1]])
        self.assertEqual(int(mask.sum()), 6)

    def test_guard_rejects_right_padding(self):
        import torch
        with self.assertRaises(AssertionError):
            train_lora.check_last_position(torch.tensor([[1, 1, 0], [1, 1, 1]]))


if __name__ == "__main__":
    unittest.main()
