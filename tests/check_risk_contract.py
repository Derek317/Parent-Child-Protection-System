"""Keeps the app's risk-scoring constants and prompt assets in step with the research harness."""
import re
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


class RiskContract(unittest.TestCase):
    def test_instruction_copy_is_byte_identical(self):
        self.assertEqual((ROOT / "research/prompts/instruction-v1.txt").read_bytes(),
                         (ROOT / "ui-res/raw/risk_instruction_v1.txt").read_bytes())

    def test_model_constants_match_the_stage4_report(self):
        report = (ROOT / "research/results/stage4-report.md").read_text(encoding="utf-8")
        kotlin = (ROOT / "RiskModel.kt").read_text(encoding="utf-8")
        # The report lists each model file it evaluated; the app pins the latest one (v4 from the "Retrain on the restyled data" section).
        name, size, sha = re.findall(r"`([\w.\-]+\.gguf)`, ([\d,]+) bytes.*?SHA-256 `([0-9a-f]{64})`", report)[-1]
        self.assertIn(f'FILE_NAME = "{name}"', kotlin)
        self.assertIn(f"FILE_BYTES = {int(size.replace(',', '')):_}L", kotlin)
        self.assertIn(f'FILE_SHA256 = "{sha}"', kotlin)

    def test_fixture_is_reproducible(self):
        result = subprocess.run([sys.executable, str(ROOT / "research/make_app_fixture.py"), "--check"],
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
