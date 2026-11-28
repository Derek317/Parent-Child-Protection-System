import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from igrisk.scorers import score_labels


def label(answer):
    return score_labels({"id": "x"}, {"labels": {"x": answer}})


class ScoreLabelsTest(unittest.TestCase):
    def test_valid_answers_score(self):
        self.assertEqual(label("3")["answer"], "3")
        self.assertTrue(label("2")["flagged"])
        self.assertFalse(label("0")["flagged"])

    def test_missing_answers_are_unresolved_not_benign(self):
        for raw in ("", "NONE", "NULL", " none "):
            self.assertEqual(label(raw)["answer"], "U", raw)

    def test_unknown_answers_raise(self):
        for raw in ("5", "BENIGN", 1.5, "0.0"):
            with self.assertRaises(ValueError, msg=raw):
                label(raw)


if __name__ == "__main__":
    unittest.main()
