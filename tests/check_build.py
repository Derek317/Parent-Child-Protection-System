"""Execute packaging control flow with a local Gradle stand-in; no real signing material."""
from pathlib import Path
import base64
import json
import os
import re
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[1]
SECRETS = ('APK_KEYSTORE_BASE64', 'APK_KEYSTORE_PASSWORD', 'APK_KEY_ALIAS', 'APK_KEY_PASSWORD')

class Packaging(unittest.TestCase):
    def package(self, values):
        workflow = (REPO / '.github/workflows/build-apk.yml').read_text()
        step = re.search(r'      - name: Compile (?:Signed )?APK\n(.*?)(?=\n      - name:|\Z)', workflow, re.S).group(1)
        if '        run: |\n' in step:
            script = '\n'.join(line[10:] for line in step.split('        run: |\n', 1)[1].splitlines())
        else:
            script = step.split('        run: ', 1)[1].strip()
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            runner = root / 'gradle'
            runner.write_text('''#!/usr/bin/env python3
from pathlib import Path
import json, os
key = os.environ.get('APK_KEYSTORE_PATH')
if key:
    assert Path(key).read_bytes() == b'test-only-keystore'
    assert all(os.environ.get(k) for k in ('APK_KEYSTORE_PASSWORD','APK_KEY_ALIAS','APK_KEY_PASSWORD'))
Path('observed.json').write_text(json.dumps({'key': key}))
''')
            runner.chmod(0o700)
            env = os.environ.copy()
            for key in (*SECRETS, 'APK_KEYSTORE_PATH'):
                env.pop(key, None)
            env.update({key: '' for key in SECRETS})
            env.update(values)
            env.update(PATH=temp+os.pathsep+env['PATH'], RUNNER_TEMP=temp,
                       GITHUB_OUTPUT=str(root/'output'), GITHUB_STEP_SUMMARY=str(root/'summary'))
            result = subprocess.run(['bash', '-c', script], cwd=root, env=env, capture_output=True, text=True)
            observed = json.loads((root/'observed.json').read_text()) if (root/'observed.json').exists() else None
            output = (root/'output').read_text() if (root/'output').exists() else ''
            key_removed = observed is None or not observed['key'] or not Path(observed['key']).exists()
            return result.returncode, output, observed, key_removed
    def test_missing_secrets_produce_explicit_unsigned_artifact(self):
        code, output, observed, _ = self.package({})
        self.assertEqual(0, code)
        self.assertIn('artifact_name=ig-guard-unsigned-apk', output)
        self.assertIsNone(observed['key'])
    def test_partial_secrets_fail_before_packaging(self):
        code, _, observed, _ = self.package({'APK_KEY_ALIAS': 'test-only-alias'})
        self.assertNotEqual(0, code)
        self.assertIsNone(observed)
    def test_complete_secrets_use_temporary_key_and_remove_it(self):
        values = dict(zip(SECRETS, (base64.b64encode(b'test-only-keystore').decode(),
                                  'test-only-password', 'test-only-alias', 'test-only-password')))
        code, output, observed, removed = self.package(values)
        self.assertEqual(0, code)
        self.assertIn('artifact_name=ig-guard-signed-apk', output)
        self.assertIsNotNone(observed['key'])
        self.assertTrue(removed)

if __name__ == '__main__': unittest.main()
