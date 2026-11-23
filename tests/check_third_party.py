"""The vendored ZXing jar matches its pin, and the build uses it without any download or repository coordinate."""
import hashlib
from pathlib import Path
import re
import unittest

REPO = Path(__file__).resolve().parents[1]
WORKFLOW = (REPO / '.github/workflows/build-apk.yml').read_text()
JAR = 'zxing-core-3.5.3.jar'


def assembly_step():
    return WORKFLOW.split('      - name: Assemble Android project structure\n', 1)[1].split('\n      - name:', 1)[0]


class VendoredZxing(unittest.TestCase):
    def test_pin_file_names_exactly_this_jar(self):
        match = re.fullmatch(r'([0-9a-f]{64})  (\S+)\n', (REPO / 'third_party/zxing-core.sha256').read_text())
        self.assertIsNotNone(match)
        self.assertEqual(JAR, match.group(2))

    def test_jar_in_the_repository_matches_the_pin(self):
        pin = (REPO / 'third_party/zxing-core.sha256').read_text().split()[0]
        self.assertEqual(pin, hashlib.sha256((REPO / 'third_party' / JAR).read_bytes()).hexdigest())

    def test_assembly_step_verifies_the_jar_and_never_downloads(self):
        step = assembly_step()
        self.assertIn('sha256sum -c', step)
        self.assertIn('third_party/zxing-core.sha256', step)
        self.assertIn('exit 1', step)
        self.assertNotIn('curl', step)

    def test_gradle_uses_only_the_local_file_never_a_coordinate(self):
        self.assertNotIn('com.google.zxing', WORKFLOW)
        self.assertIn("implementation files('libs/zxing-core-3.5.3.jar')", WORKFLOW)


if __name__ == '__main__':
    unittest.main()
