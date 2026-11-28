import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "native" / "risk"))
import host_check

LANGUAGES = ("zh-Hant-yue", "en", "mixed")


class PickRowsTest(unittest.TestCase):
    def test_requested_n_is_honoured(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "research" / "data").mkdir(parents=True)
            rows = [{"id": f"{lang}-{i}", "language": lang, "messages": []} for lang in LANGUAGES for i in range(40)]
            (Path(d) / "research" / "data" / "tune.jsonl").write_text(
                "".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8")
            with mock.patch.object(host_check, "REPO", Path(d)):
                for n in (1, 2, 5, 8, 9, 10, 11, 24, 40):
                    self.assertEqual(len(host_check.pick_rows(n)), n - 1, n)


if __name__ == "__main__":
    unittest.main()
