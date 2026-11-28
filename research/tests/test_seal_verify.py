import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "datatools"))
import seal


class VerifyRecordTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dd = Path(self.tmp.name)
        for name in seal.HASHED:
            (self.dd / name).write_bytes(b"fake")
        seal.do_hash(self.dd)

    def tearDown(self):
        self.tmp.cleanup()

    def test_complete_record_passes(self):
        bad, ok = seal.do_verify(self.dd)
        self.assertEqual((bad, len(ok)), ([], 2))

    def test_line_missing_from_record_is_reported(self):
        record = self.dd / "SEALED.sha256"
        first = record.read_text(encoding="utf-8").splitlines()[0]
        record.write_text(first + "\n", encoding="utf-8", newline="\n")
        bad, _ = seal.do_verify(self.dd)
        self.assertEqual(len(bad), 1)
        self.assertIn(seal.HASHED[1], bad[0])

    def test_empty_record_reports_every_file(self):
        (self.dd / "SEALED.sha256").write_text("", encoding="utf-8")
        bad, _ = seal.do_verify(self.dd)
        self.assertEqual(len(bad), len(seal.HASHED))


if __name__ == "__main__":
    unittest.main()
