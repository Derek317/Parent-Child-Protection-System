"""Exercise real keystore checks; only Gradle packaging is replaced locally."""
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
    @classmethod
    def setUpClass(cls):
        cls.fixture = tempfile.TemporaryDirectory()
        cls.store_password = 'test-only store $! password'
        cls.key_password = 'test-only key $! password'
        cls.keys = {}
        for same in (False, True):
            key = Path(cls.fixture.name) / f'test-{same}.jks'
            env = os.environ.copy()
            env.update(IG_GUARD_TEST_STORE_PASS=cls.store_password,
                       IG_GUARD_TEST_KEY_PASS=cls.store_password if same else cls.key_password)
            subprocess.run(['keytool', '-genkeypair', '-storetype', 'JKS', '-keystore', str(key),
                            '-alias', 'test-only-alias', '-keyalg', 'RSA', '-keysize', '2048',
                            '-validity', '2', '-dname', 'CN=Disposable test key',
                            '-storepass:env', 'IG_GUARD_TEST_STORE_PASS', '-keypass:env', 'IG_GUARD_TEST_KEY_PASS'],
                           env=env, check=True, capture_output=True)
            cls.keys[same] = key.read_bytes()

    @classmethod
    def tearDownClass(cls):
        cls.fixture.cleanup()

    def credentials(self, same=False):
        return dict(zip(SECRETS, (base64.b64encode(self.keys[same]).decode(), self.store_password,
                                  'test-only-alias', self.store_password if same else self.key_password)))

    def package(self, values):
        workflow = (REPO / '.github/workflows/build-apk.yml').read_text()
        step = re.search(r'      - name: Compile (?:Signed )?APK\n(.*?)(?=\n      - name:|\Z)', workflow, re.S).group(1)
        script = '\n'.join(line[10:] for line in step.split('        run: |\n', 1)[1].splitlines())
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            runner = root / 'gradle'
            runner.write_text('''#!/usr/bin/env python3
from pathlib import Path
import json, os
key = os.environ.get('APK_KEYSTORE_PATH')
if key:
    assert Path(key).stat().st_size > 0
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
            self.last_log = result.stdout + result.stderr
            for key in ('APK_KEYSTORE_BASE64', 'APK_KEYSTORE_PASSWORD', 'APK_KEY_PASSWORD'):
                if values.get(key): self.assertNotIn(values[key], self.last_log)
            observed = json.loads((root/'observed.json').read_text()) if (root/'observed.json').exists() else None
            output = (root/'output').read_text() if (root/'output').exists() else ''
            return result.returncode, output, observed, not list(root.glob('ig-guard-key.*'))

    def rejected(self, field, value, message):
        values = self.credentials(); values[field] = value
        code, _, observed, removed = self.package(values)
        self.assertNotEqual(0, code)
        self.assertIsNone(observed, 'Invalid credentials must fail before Gradle starts')
        self.assertIn(message, self.last_log)
        self.assertTrue(removed)

    def test_missing_secrets_produce_explicit_unsigned_artifact(self):
        code, output, observed, _ = self.package({})
        self.assertEqual(0, code)
        self.assertIn('artifact_name=ig-guard-unsigned-apk', output)
        self.assertIsNone(observed['key'])

    def test_partial_secrets_fail_before_packaging(self):
        code, _, observed, _ = self.package({'APK_KEY_ALIAS': 'test-only-alias'})
        self.assertNotEqual(0, code)
        self.assertIsNone(observed)

    def test_distinct_passwords_use_temporary_key_and_remove_it(self):
        code, output, observed, removed = self.package(self.credentials())
        self.assertEqual(0, code)
        self.assertIn('artifact_name=ig-guard-signed-apk', output)
        self.assertIsNotNone(observed['key'])
        self.assertTrue(removed)

    def test_one_password_is_supported_for_both_fields(self):
        code, output, _, removed = self.package(self.credentials(same=True))
        self.assertEqual(0, code)
        self.assertIn('artifact_name=ig-guard-signed-apk', output)
        self.assertTrue(removed)

    def test_wrong_store_password_is_identified(self):
        self.rejected('APK_KEYSTORE_PASSWORD', 'wrong-test-password', '[SIGNING_STORE]')

    def test_wrong_key_password_is_identified(self):
        self.rejected('APK_KEY_PASSWORD', 'wrong-test-password', '[SIGNING_KEY_PASSWORD]')

    def test_wrong_alias_is_identified(self):
        self.rejected('APK_KEY_ALIAS', 'missing-test-alias', '[SIGNING_ALIAS]')

    def test_invalid_base64_is_identified(self):
        self.rejected('APK_KEYSTORE_BASE64', 'not%base64', '[SIGNING_BASE64]')

    def test_unreadable_file_is_rejected_before_gradle(self):
        self.rejected('APK_KEYSTORE_BASE64', base64.b64encode(b'not a keystore').decode(), '[SIGNING_STORE]')

if __name__ == '__main__': unittest.main()
