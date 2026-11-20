import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from make_report import build


class ReportTest(unittest.TestCase):
    def test_shape_matches_app_reader(self):
        preds = [{"id": "secret-id", "answer": "3", "report_score": 99.5, "language": "en"},
                 {"id": "x", "answer": "U", "report_score": None}]
        text = build(preds, "ab" * 32)
        r = json.loads(text)
        self.assertEqual(set(r), {"schema", "model", "revision", "source", "conversations"})
        self.assertEqual(r["conversations"], [
            {"conversation": "C001", "score": 99.5, "review_reason": None},
            {"conversation": "C002", "score": None, "review_reason": "insufficient_context"}])
        self.assertNotIn("secret-id", text)  # score-only: nothing identifying a chat

    def test_refuses_more_than_the_reader_accepts(self):
        with self.assertRaises(AssertionError):
            build([{"answer": "0", "report_score": 1.0}] * 501, "ab" * 32)


if __name__ == "__main__":
    unittest.main()
