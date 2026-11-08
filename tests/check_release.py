"""Exercise release publication with local Git and a refusing/fake GitHub CLI."""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/publish-release.sh'


class ReleasePublication(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.log = self.root / 'gh.jsonl'
        (self.bin / 'gh').write_text('''#!/usr/bin/env python3
from pathlib import Path
import json, os, sys
args=sys.argv[1:]
row={'args':args}
if '--notes-file' in args:
    row['notes']=Path(args[args.index('--notes-file')+1]).read_text()
if args[:2]==['release','upload']:
    row['assets']={Path(p).name:Path(p).read_bytes().hex() for p in args if Path(p).is_file()}
with open(os.environ['FAKE_GH_LOG'],'a') as f:f.write(json.dumps(row)+'\\n')
if args[:2]==['release','view']:sys.exit(0 if os.environ.get('FAKE_RELEASE_EXISTS')=='1' else 1)
if args[:2]==['release','upload'] and os.environ.get('FAKE_UPLOAD_FAIL')=='1':sys.exit(1)
''')
        (self.bin / 'gh').chmod(0o700)
        self.git('init', '-q', '-b', 'main')
        self.git('-c', 'user.name=Release test', '-c', 'user.email=test@example.invalid',
                 'commit', '--allow-empty', '-qm', 'fixture')
        self.sha = self.git('rev-parse', 'HEAD').strip()
        self.git('tag', '4.7')
        (self.root / 'docs/releases').mkdir(parents=True)
        (self.root / 'docs/releases/4.7.md').write_text('# 4.7\nTwo-way conversation archive.\n')
        (self.root / 'release-artifacts').mkdir()
        (self.root / 'release-artifacts/app-release.apk').write_bytes(b'test-only upstream-verified artifact')

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, text=True)

    def publish(self, **overrides):
        env = os.environ.copy()
        env.update(PATH=str(self.bin)+os.pathsep+env['PATH'], RUNNER_TEMP=str(self.root),
                   GITHUB_REPOSITORY='example/family', GITHUB_SHA=self.sha,
                   GITHUB_REF_NAME='4.7', GITHUB_SERVER_URL='https://github.com', GITHUB_RUN_ID='123',
                   BUILD_RESULT='success', ARTIFACT_NAME='ig-guard-signed-apk',
                   APK_VERIFIED='true', DOWNLOAD_RESULT='success', FAKE_GH_LOG=str(self.log))
        env.update(overrides)
        result = subprocess.run(['bash', str(SCRIPT)], cwd=self.root, env=env, text=True, capture_output=True)
        rows = [json.loads(x) for x in self.log.read_text().splitlines()] if self.log.exists() else []
        return result, rows

    def test_signed_artifact_creates_release_and_uploads_matching_checksum(self):
        result, rows = self.publish()
        self.assertEqual(0, result.returncode, result.stderr)
        create = next(r for r in rows if r['args'][:2] == ['release', 'create'])
        self.assertIn('--verify-tag', create['args'])
        self.assertIn('Two-way conversation archive.', create['notes'])
        upload = next(r for r in rows if r['args'][:2] == ['release', 'upload'])
        self.assertEqual({'IG-Guard-4.7.apk', 'SHA256SUMS'}, set(upload['assets']))
        payload = bytes.fromhex(upload['assets']['IG-Guard-4.7.apk'])
        checksum = bytes.fromhex(upload['assets']['SHA256SUMS']).decode()
        self.assertEqual(hashlib.sha256(payload).hexdigest()+'  IG-Guard-4.7.apk\n', checksum)

    def test_existing_release_is_updated_without_creating_another(self):
        result, rows = self.publish(FAKE_RELEASE_EXISTS='1')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(any(r['args'][:2] == ['release', 'edit'] for r in rows))
        self.assertFalse(any(r['args'][:2] == ['release', 'create'] for r in rows))

    def test_unsigned_or_unverified_artifact_never_becomes_a_release_asset(self):
        for env in [{'ARTIFACT_NAME': 'ig-guard-unsigned-apk'}, {'APK_VERIFIED': 'false'}]:
            with self.subTest(env=env):
                if self.log.exists(): self.log.unlink()
                result, rows = self.publish(**env)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertFalse(any(r['args'][:2] == ['release', 'upload'] for r in rows))
                self.assertIn('unavailable', rows[-1]['notes'].lower())

    def test_failed_build_still_publishes_notes_without_an_apk(self):
        result, rows = self.publish(BUILD_RESULT='failure')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(any(r['args'][:2] == ['release', 'create'] for r in rows))
        self.assertFalse(any(r['args'][:2] == ['release', 'upload'] for r in rows))
        self.assertIn('/actions/runs/123', rows[-1]['notes'])

    def test_missing_download_still_publishes_notes(self):
        result, rows = self.publish(DOWNLOAD_RESULT='failure')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(any(r['args'][:2] == ['release', 'upload'] for r in rows))

    def test_upload_failure_leaves_a_published_release_with_honest_notes(self):
        result, rows = self.publish(FAKE_UPLOAD_FAIL='1')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['release', 'edit'], rows[-1]['args'][:2])
        self.assertIn('incomplete', rows[-1]['notes'].lower())

    def test_mismatched_tag_stops_before_any_github_mutation(self):
        result, rows = self.publish(GITHUB_SHA='0'*40)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], rows)

    def test_missing_release_notes_stop_before_any_github_mutation(self):
        (self.root / 'docs/releases/4.7.md').unlink()
        result, rows = self.publish()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], rows)

    def prepare_hotfix(self):
        self.git('-c','user.name=Release test','-c','user.email=test@example.invalid','commit','--allow-empty','-qm','hotfix')
        self.sha=self.git('rev-parse','HEAD').strip()
        self.git('tag','4.7-hotfix.1')

    def test_hotfix_updates_existing_release_without_moving_original_tag(self):
        original=self.git('rev-parse','refs/tags/4.7').strip();self.prepare_hotfix()
        result,rows=self.publish(GITHUB_REF_NAME='4.7-hotfix.1',FAKE_RELEASE_EXISTS='1')
        self.assertEqual(0,result.returncode,result.stderr)
        edit=next(row for row in rows if row['args'][:2]==['release','edit'])
        self.assertEqual('4.7',edit['args'][2]);self.assertIn(self.sha,edit['notes'])
        upload=next(row for row in rows if row['args'][:2]==['release','upload'])
        self.assertIn('IG-Guard-4.7.apk',upload['assets'])
        self.assertEqual(original,self.git('rev-parse','refs/tags/4.7').strip())

    def test_failed_hotfix_never_changes_existing_notes_or_apk(self):
        self.prepare_hotfix()
        result,rows=self.publish(GITHUB_REF_NAME='4.7-hotfix.1',FAKE_RELEASE_EXISTS='1',BUILD_RESULT='failure')
        self.assertNotEqual(0,result.returncode)
        self.assertFalse(any(row['args'][:2] in (['release','edit'],['release','upload'],['release','create']) for row in rows))

    def test_hotfix_never_creates_an_unrelated_missing_release(self):
        self.prepare_hotfix()
        result,rows=self.publish(GITHUB_REF_NAME='4.7-hotfix.1')
        self.assertNotEqual(0,result.returncode)
        self.assertFalse(any(row['args'][:2] in (['release','create'],['release','upload']) for row in rows))


if __name__ == '__main__': unittest.main()
