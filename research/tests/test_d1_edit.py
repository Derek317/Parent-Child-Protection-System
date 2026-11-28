import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import d1_edit


class ScriptHashTest(unittest.TestCase):
    def test_crlf_and_lf_copies_hash_the_same(self):
        with tempfile.TemporaryDirectory() as d:
            lf, crlf = Path(d, "lf.py"), Path(d, "crlf.py")
            lf.write_bytes(b"a = 1\nb = 2\n")
            crlf.write_bytes(b"a = 1\r\nb = 2\r\n")
            self.assertEqual(d1_edit.script_sha256(lf), d1_edit.script_sha256(crlf))

    def test_frozen_hash_matches_this_checkout(self):
        frozen = json.loads(d1_edit.FROZEN.read_text(encoding="utf-8"))
        self.assertEqual(frozen["script_sha256"], d1_edit.script_sha256())


if __name__ == "__main__":
    unittest.main()
