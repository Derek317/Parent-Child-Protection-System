"""tools/package_with_model.py: pure-Python checks, plus one real zipalign/apksigner run when the Android build-tools exist."""
from pathlib import Path
import hashlib
import importlib.util
import os
import sys
import shutil
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec_ = importlib.util.spec_from_file_location('package_with_model', ROOT / 'tools/package_with_model.py')
pkg = importlib.util.module_from_spec(spec_)
sys.modules['package_with_model'] = pkg
spec_.loader.exec_module(pkg)

MODEL = b'tiny fake model, not real data\n' * 50
FAKE = pkg.ModelSpec('fake-model.gguf', len(MODEL), hashlib.sha256(MODEL).hexdigest())
SDK = Path(os.environ.get('ANDROID_HOME', '/opt/android-dev/sdk'))


def fake_apk(path: Path, signed: bool = True) -> dict:
    """A tiny zip shaped like a release APK: stored and deflated entries, a directory entry and old v1 signature files."""
    entries = {
        'AndroidManifest.xml': (b'<manifest/>' * 20, zipfile.ZIP_DEFLATED),
        'resources.arsc': (b'\x00ARSC' * 100, zipfile.ZIP_STORED),
        'classes.dex': (os.urandom(5000), zipfile.ZIP_DEFLATED),
        'lib/arm64-v8a/libigguard_risk.so': (os.urandom(3000), zipfile.ZIP_STORED),
        'assets/ig-guard-terms-of-service.md': (b'terms ' * 200, zipfile.ZIP_DEFLATED),
    }
    sigs = {'META-INF/CERT.SF': b'sf', 'META-INF/CERT.RSA': b'rsa', 'META-INF/OLD.DSA': b'dsa', 'META-INF/OLD.EC': b'ec', 'META-INF/MANIFEST.MF': b'mf'}
    with zipfile.ZipFile(path, 'w') as z:
        z.writestr(zipfile.ZipInfo('META-INF/'), b'')
        for name, (data, method) in entries.items():
            z.writestr(zipfile.ZipInfo(name, (2020, 1, 2, 3, 4, 6)), data, compress_type=method)
        if signed:
            for name, data in sigs.items():
                z.writestr(name, data)
        z.writestr('META-INF/services/keep.me', b'a service file stays')
    return entries


class Dirs(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        self.model = self.dir / 'model.gguf'
        self.model.write_bytes(MODEL)
        import contextlib, io
        self.printed = io.StringIO()
        stack = contextlib.ExitStack(); stack.enter_context(contextlib.redirect_stdout(self.printed)); self.addCleanup(stack.close)


class Spec(Dirs):
    def test_pinned_values_come_from_risk_model_kt(self):
        spec = pkg.load_spec()
        text = (ROOT / 'RiskModel.kt').read_text(encoding='utf-8')
        self.assertIn(f'"{spec.file_name}"', text)
        self.assertIn(f'"{spec.sha256}"', text)
        self.assertIn(f'{spec.size:,}'.replace(',', '_') + 'L', text)
        self.assertEqual(spec.file_name, 'qwen3-1.7b-beacon-Q4_K_M.gguf')
        self.assertEqual(pkg.asset_entry_name(spec), 'assets/risk/qwen3-1.7b-beacon-Q4_K_M.gguf')

    def test_unreadable_spec_is_an_error(self):
        bad = self.dir / 'RiskModel.kt'
        bad.write_text('object RiskModelSpec { const val FILE_NAME = "a.gguf" }')
        with self.assertRaises(pkg.PackagingError):
            pkg.load_spec(bad)

    def test_the_cli_has_no_way_to_change_the_expected_model(self):
        names = {a for action in pkg.build_parser()._actions for a in action.option_strings}
        self.assertFalse({n for n in names if 'expect' in n or 'sha' in n or 'size' in n})


class Verify(Dirs):
    def test_exact_model_passes(self):
        pkg.verify_model(self.model, FAKE, quiet=True)

    def test_hash_mismatch_is_refused(self):
        bad = bytearray(MODEL); bad[3] ^= 1
        self.model.write_bytes(bytes(bad))
        with self.assertRaisesRegex(pkg.PackagingError, 'SHA-256'):
            pkg.verify_model(self.model, FAKE, quiet=True)

    def test_size_mismatch_is_refused(self):
        self.model.write_bytes(MODEL + b'x')
        with self.assertRaisesRegex(pkg.PackagingError, 'bytes'):
            pkg.verify_model(self.model, FAKE, quiet=True)

    def test_run_refuses_before_writing_anything(self):
        apk = self.dir / 'in.apk'; fake_apk(apk)
        self.model.write_bytes(MODEL[:-1] + b'!')
        out = self.dir / 'out' / 'x.apk'
        calls = []
        with self.assertRaises(pkg.PackagingError):
            pkg.run_packaging(apk, self.model, out, FAKE, pkg.Tools('za', 'as'), pkg.SigningOptions('k', 'a', 'p', 'p'), runner=lambda c, e=None: (calls.append(c), subprocess.CompletedProcess(c, 1, '', ''))[1], check_space=False)
        self.assertEqual(calls, [])
        self.assertFalse(out.exists())
        self.assertEqual([p.name for p in out.parent.iterdir()], [])


class Rewrite(Dirs):
    def test_entries_kept_signatures_dropped_model_stored(self):
        apk = self.dir / 'in.apk'; entries = fake_apk(apk)
        out = self.dir / 'out.zip'
        stats = pkg.rewrite_apk(apk, self.model, out, FAKE)
        with zipfile.ZipFile(apk) as a, zipfile.ZipFile(out) as b:
            self.assertIsNone(b.testzip())
            names = b.namelist()
            for old in a.infolist():
                if pkg.SIGNATURE_ENTRY.match(old.filename):
                    self.assertNotIn(old.filename, names)
                    continue
                new = b.getinfo(old.filename)
                self.assertEqual(new.compress_type, old.compress_type, old.filename)
                self.assertEqual(b.read(old.filename), a.read(old.filename), old.filename)
                self.assertEqual(new.date_time, old.date_time)
            self.assertIn('META-INF/services/keep.me', names)
            for name, (data, method) in entries.items():
                self.assertEqual(b.getinfo(name).compress_type, method)
            model = b.getinfo('assets/risk/fake-model.gguf')
            self.assertEqual(model.compress_type, zipfile.ZIP_STORED)
            self.assertEqual(model.file_size, model.compress_size)
            self.assertEqual(b.read(model.filename), MODEL)
        self.assertEqual(sorted(stats['dropped']), sorted(['META-INF/CERT.SF', 'META-INF/CERT.RSA', 'META-INF/OLD.DSA', 'META-INF/OLD.EC', 'META-INF/MANIFEST.MF']))
        self.assertEqual([n for n in names if 'META-INF/' in n and pkg.SIGNATURE_ENTRY.match(n)], [])

    def test_model_is_named_by_the_pin_not_by_the_local_file_name(self):
        apk = self.dir / 'in.apk'; fake_apk(apk, signed=False)
        renamed = self.dir / 'my copy (1).gguf'; renamed.write_bytes(MODEL)
        out = self.dir / 'out.zip'
        pkg.rewrite_apk(apk, renamed, out, FAKE)
        self.assertIn('assets/risk/fake-model.gguf', zipfile.ZipFile(out).namelist())

    def test_a_model_that_changes_while_copying_is_caught(self):
        apk = self.dir / 'in.apk'; fake_apk(apk, signed=False)
        other = self.dir / 'other.gguf'; other.write_bytes(b'x' * len(MODEL))
        with self.assertRaisesRegex(pkg.PackagingError, 'changed'):
            pkg.rewrite_apk(apk, other, self.dir / 'out.zip', FAKE)

    def test_an_already_packaged_apk_is_refused(self):
        apk = self.dir / 'in.apk'; fake_apk(apk, signed=False)
        once = self.dir / 'once.zip'; pkg.rewrite_apk(apk, self.model, once, FAKE)
        with self.assertRaisesRegex(pkg.PackagingError, 'already contains'):
            pkg.rewrite_apk(once, self.model, self.dir / 'twice.zip', FAKE)

    def test_not_a_zip_is_refused(self):
        junk = self.dir / 'junk.apk'; junk.write_bytes(b'not a zip')
        with self.assertRaises(pkg.PackagingError):
            pkg.rewrite_apk(junk, self.model, self.dir / 'o.zip', FAKE)

    def test_large_entries_are_copied_in_chunks_without_loading_them(self):
        big = self.dir / 'big.gguf'
        data = os.urandom(1 << 16) * 80   # 5 MiB, several chunks
        big.write_bytes(data)
        spec = pkg.ModelSpec('big.gguf', len(data), hashlib.sha256(data).hexdigest())
        apk = self.dir / 'in.apk'; fake_apk(apk, signed=False)
        out = self.dir / 'o.zip'; pkg.rewrite_apk(apk, big, out, spec)
        with zipfile.ZipFile(out) as z:
            self.assertEqual(hashlib.sha256(z.read('assets/risk/big.gguf')).hexdigest(), spec.sha256)


class OutputPlace(Dirs):
    def repo(self) -> Path:
        r = self.dir / 'repo'; r.mkdir()
        subprocess.run(['git', 'init', '-q', str(r)], check=True)
        (r / '.gitignore').write_text('/dist/\n')
        return r

    @unittest.skipUnless(shutil.which('git'), 'git not installed')
    def test_output_inside_the_work_tree_is_refused_unless_ignored(self):
        r = self.repo()
        with self.assertRaisesRegex(pkg.PackagingError, 'not git-ignored'):
            pkg.ensure_output_allowed(r / 'build' / 'x.apk')
        with self.assertRaises(pkg.PackagingError):
            pkg.ensure_output_allowed(r / 'x.apk')
        pkg.ensure_output_allowed(r / 'dist' / 'x.apk')          # ignored directory: the file and its checksum file
        pkg.ensure_output_allowed(self.dir / 'elsewhere' / 'x.apk')   # outside any repository

    @unittest.skipUnless(shutil.which('git'), 'git not installed')
    def test_a_checksum_file_that_would_be_tracked_is_refused_too(self):
        r = self.repo()
        (r / '.gitignore').write_text('*.apk\n')   # the .apk is ignored but x.apk.sha256 is not
        with self.assertRaises(pkg.PackagingError):
            pkg.ensure_output_allowed(r / 'x.apk')

    @unittest.skipUnless(shutil.which('git'), 'git not installed')
    def test_run_refuses_a_tracked_location_before_doing_anything(self):
        r = self.repo(); apk = self.dir / 'in.apk'; fake_apk(apk)
        calls = []
        with self.assertRaises(pkg.PackagingError):
            pkg.run_packaging(apk, self.model, r / 'out' / 'x.apk', FAKE, pkg.Tools('za', 'as'), pkg.SigningOptions('k', 'a', 'p', 'p'), runner=lambda c, e=None: (calls.append(c), subprocess.CompletedProcess(c, 1, '', ''))[1], check_space=False)
        self.assertEqual(calls, [])

    def test_this_repository_ignores_the_usual_places(self):
        if not shutil.which('git'):
            self.skipTest('git not installed')
        for name in ('dist/ig-guard-with-model.apk', 'dist/ig-guard-with-model.apk.sha256', 'anything.apk', 'x/y.apk.sha256'):
            r = subprocess.run(['git', '-C', str(ROOT), 'check-ignore', '-q', '--', name])
            self.assertEqual(r.returncode, 0, name)
        self.assertEqual(subprocess.run(['git', '-C', str(ROOT), 'ls-files', '*.apk', 'dist/*', '*.gguf'], capture_output=True, text=True).stdout.strip(), '')


class Pipeline(Dirs):
    """The tool steps with an injected runner, so no Android tools are needed."""
    def fake_runner(self, log, cert='a' * 64, input_cert=None):
        def run(cmd, env=None):
            log.append((list(cmd), env))
            exe = os.path.basename(cmd[0])
            if exe == 'za' and '-c' not in cmd:
                shutil.copyfile(cmd[-2], cmd[-1])
            if exe == 'as' and cmd[1] == 'sign':
                shutil.copyfile(cmd[-1], cmd[cmd.index('--out') + 1])
            if exe == 'as' and cmd[1] == 'verify':
                if '--verbose' not in cmd:
                    return subprocess.CompletedProcess(cmd, 0 if input_cert else 1, f'Signer #1 certificate SHA-256 digest: {input_cert}\n' if input_cert else '', '')
                out = f'Verifies\nVerified using v2 scheme (APK Signature Scheme v2): true\nVerified using v3 scheme (APK Signature Scheme v3): true\nSigner #1 certificate SHA-256 digest: {cert}\n'
                return subprocess.CompletedProcess(cmd, 0, out, '')
            return subprocess.CompletedProcess(cmd, 0, '', '')
        return run

    def go(self, input_cert=None, cert='a' * 64, workflow=None, name='p.apk'):
        apk = self.dir / 'in.apk'; fake_apk(apk)
        log = []
        wf = workflow or self.dir / 'none.yml'
        result = pkg.run_packaging(apk, self.model, self.dir / 'dist' / name, FAKE, pkg.Tools('za', 'as'),
                                   pkg.SigningOptions('/k/store.jks', 'alias', 'S3CRET-STORE', 'S3CRET-KEY'),
                                   runner=self.fake_runner(log, cert, input_cert), workflow=wf, check_space=False)
        return result, log

    def test_steps_flags_and_sidecar(self):
        result, log = self.go()
        cmds = [c for c, _ in log]
        self.assertIn(['za', '-p', '-f', '4'], [c[:4] for c in cmds])
        sign = next(c for c in cmds if c[:2] == ['as', 'sign'])
        for flag, value in (('--v2-signing-enabled', 'true'), ('--v3-signing-enabled', 'true')):
            self.assertEqual(sign[sign.index(flag) + 1], value)
        self.assertIn('verify', [c[1] for c in cmds if c[0] == 'as'])
        out = result['out']
        self.assertEqual(out.with_name('p.apk.sha256').read_text(), f"{hashlib.sha256(out.read_bytes()).hexdigest()}  p.apk\n")
        self.assertEqual([p.name for p in out.parent.iterdir() if p.name.startswith('.package-')], [], 'work folder removed')
        with zipfile.ZipFile(out) as z:
            self.assertEqual(z.getinfo('assets/risk/fake-model.gguf').compress_type, zipfile.ZIP_STORED)

    def test_passwords_never_reach_the_command_line_or_the_output(self):
        result, log = self.go()
        for cmd, _env in log:
            self.assertNotIn('S3CRET', ' '.join(cmd))
        sign_cmd, env = next((c, e) for c, e in log if c[:2] == ['as', 'sign'])
        self.assertTrue(any(a.startswith('env:') for a in sign_cmd))
        self.assertEqual(sorted(env.values()), ['S3CRET-KEY', 'S3CRET-STORE'])
        pkg.report(result)
        self.assertNotIn('S3CRET', self.printed.getvalue())

    def test_different_certificate_warns_and_same_does_not(self):
        wf = self.dir / 'wf.yml'; wf.write_text('test "$certificate" = "' + 'c' * 64 + '"\n')
        result, _ = self.go(input_cert='b' * 64, cert='a' * 64, workflow=wf)
        self.assertEqual(len(result['warnings']), 2)
        pkg.report(result)
        self.assertIn('WARNING', self.printed.getvalue()); self.assertIn('is lost', self.printed.getvalue())
        same, _ = self.go(input_cert='a' * 64, cert='a' * 64, name='same.apk')
        self.assertEqual(same['warnings'], [])
        self.assertEqual(same['old_cert'], 'a' * 64)

    def test_unsigned_input_is_reported_and_compared_with_the_pinned_certificate(self):
        wf = self.dir / 'wf.yml'; wf.write_text('test "$certificate" = "' + 'a' * 64 + '"\n')
        result, _ = self.go(input_cert=None, cert='a' * 64, workflow=wf)
        self.assertIsNone(result['old_cert']); self.assertEqual(result['warnings'], [])

    def test_the_real_workflow_pins_a_certificate(self):
        self.assertRegex(pkg.pinned_certificate() or '', r'^[0-9a-f]{64}$')

    def test_a_failing_tool_stops_the_run_and_leaves_no_output(self):
        apk = self.dir / 'in.apk'; fake_apk(apk)
        def bad(cmd, env=None):
            return subprocess.CompletedProcess(cmd, 1, '', 'boom')
        out = self.dir / 'dist' / 'p.apk'
        with self.assertRaisesRegex(pkg.PackagingError, 'boom'):
            pkg.run_packaging(apk, self.model, out, FAKE, pkg.Tools('za', 'as'), pkg.SigningOptions('k', 'a', 'p', 'p'), runner=bad, check_space=False)
        self.assertEqual(list(out.parent.iterdir()), [])

    def test_existing_output_needs_force(self):
        self.go()
        apk = self.dir / 'in.apk'
        with self.assertRaisesRegex(pkg.PackagingError, '--force'):
            pkg.run_packaging(apk, self.model, self.dir / 'dist' / 'p.apk', FAKE, pkg.Tools('za', 'as'), pkg.SigningOptions('k', 'a', 'p', 'p'), runner=self.fake_runner([]), check_space=False)


class ToolLookup(Dirs):
    def test_build_tools_folder_and_sdk_detection(self):
        for v in ('33.0.2', '34.0.0', '9.0.0'):
            d = self.dir / 'sdk' / 'build-tools' / v; d.mkdir(parents=True)
            (d / 'zipalign').write_text(''); (d / 'apksigner').write_text('')
        t = pkg.find_tools(None, {'ANDROID_HOME': str(self.dir / 'sdk')})
        self.assertIn('34.0.0', t.zipalign)
        t = pkg.find_tools(str(self.dir / 'sdk' / 'build-tools' / '33.0.2'))
        self.assertIn('33.0.2', t.apksigner)
        with self.assertRaises(pkg.PackagingError):
            pkg.find_tools(None, {})
        (self.dir / 'win').mkdir(); (self.dir / 'win' / 'zipalign.exe').write_text(''); (self.dir / 'win' / 'apksigner.bat').write_text('')
        self.assertTrue(pkg.find_tools(str(self.dir / 'win')).apksigner.endswith('apksigner.bat'))

    def test_certificate_digest_parsing(self):
        self.assertEqual(pkg.parse_cert_digest('x\nSigner #1 certificate SHA-256 digest: ' + 'AB' * 32 + '\n'), 'ab' * 32)
        self.assertIsNone(pkg.parse_cert_digest('nothing'))


class SyncedFolders(Dirs):
    def test_names_and_environment_variables_are_recognised(self):
        for part in ('OneDrive', 'OneDrive - Contoso', 'Dropbox', 'Google Drive', 'iCloudDrive', 'GoogleDrive'):
            self.assertIsNotNone(pkg.synced_folder_reason(self.dir / part / 'x.apk'), part)
        self.assertIsNone(pkg.synced_folder_reason(self.dir / 'dist' / 'x.apk'))
        synced = self.dir / 'Users' / 'me' / 'Cloud'
        for var in ('OneDrive', 'OneDriveConsumer', 'OneDriveCommercial'):
            self.assertIn(var, pkg.synced_folder_reason(synced / 'dist' / 'x.apk', {var: str(synced)}))
        self.assertIsNone(pkg.synced_folder_reason(self.dir / 'other' / 'x.apk', {'OneDrive': str(synced)}))

    def test_output_or_model_in_a_synced_folder_is_refused_before_anything_is_written(self):
        apk = self.dir / 'in.apk'; fake_apk(apk)
        tools, opts = pkg.Tools('za', 'as'), pkg.SigningOptions('k', 'a', 'p', 'p')
        calls = []
        run = lambda c, e=None: (calls.append(c), subprocess.CompletedProcess(c, 1, '', ''))[1]
        out = self.dir / 'Dropbox' / 'sub' / 'x.apk'
        with self.assertRaisesRegex(pkg.PackagingError, 'cloud-synced'):
            pkg.run_packaging(apk, self.model, out, FAKE, tools, opts, runner=run, check_space=False)
        self.assertFalse((self.dir / 'Dropbox').exists())
        synced_model = self.dir / 'OneDrive' / 'model.gguf'; synced_model.parent.mkdir(); synced_model.write_bytes(MODEL)
        with self.assertRaisesRegex(pkg.PackagingError, 'model'):
            pkg.run_packaging(apk, synced_model, self.dir / 'dist' / 'x.apk', FAKE, tools, opts, runner=run, check_space=False)
        self.assertEqual(calls, [])
        self.assertFalse((self.dir / 'dist').exists())

    def test_the_override_flag_allows_it(self):
        self.assertTrue(pkg.build_parser().parse_args(['--apk', 'a', '--model', 'm', '--out', 'o.apk', '--keystore', 'k', '--allow-synced-folder']).allow_synced_folder)
        apk = self.dir / 'in.apk'; fake_apk(apk)
        out = self.dir / 'Dropbox' / 'x.apk'
        def run(cmd, env=None):
            return subprocess.CompletedProcess(cmd, 1, '', 'stop here')
        with self.assertRaisesRegex(pkg.PackagingError, 'stop here'):   # got past the synced-folder check and into the tools
            pkg.run_packaging(apk, self.model, out, FAKE, pkg.Tools('za', 'as'), pkg.SigningOptions('k', 'a', 'p', 'p'), runner=run, check_space=False, allow_synced_folder=True)


class Hardening(Dirs):
    def test_the_script_imports_no_network_modules(self):
        import ast
        tree = ast.parse((ROOT / 'tools/package_with_model.py').read_text(encoding='utf-8'))
        names = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                names |= {a.name.split('.')[0] for a in node.names}
            elif isinstance(node, ast.ImportFrom):
                names.add((node.module or '').split('.')[0])
        self.assertFalse(names & {'socket', 'urllib', 'http', 'ftplib', 'smtplib', 'requests', 'ssl', 'telnetlib', 'xmlrpc', 'asyncio'}, names)

    def test_the_keystore_passwords_are_not_passed_to_other_programs(self):
        from unittest import mock
        code = 'import os;print(os.environ.get("IG_GUARD_KEYSTORE_PASSWORD"),os.environ.get("IG_GUARD_KEY_PASSWORD"),os.environ.get("IG_PACKAGE_TOOL_KS_PASS"),os.environ.get("CUSTOM_PW"))'
        with mock.patch.dict(os.environ, {'IG_GUARD_KEYSTORE_PASSWORD': 'a', 'IG_GUARD_KEY_PASSWORD': 'b', 'CUSTOM_PW': 'c'}), mock.patch.object(pkg, 'SECRET_ENV_NAMES', set(pkg.SECRET_ENV_NAMES) | {'CUSTOM_PW'}):
            out = pkg.default_runner([sys.executable, '-c', code]).stdout.split()
            self.assertEqual(out, ['None', 'None', 'None', 'None'])
            out = pkg.default_runner([sys.executable, '-c', code], {'IG_PACKAGE_TOOL_KS_PASS': 'tool'}).stdout.split()
            self.assertEqual(out, ['None', 'None', 'tool', 'None'])

    def test_a_large_stored_entry_has_no_zip64_records_and_there_is_headroom(self):
        apk = self.dir / 'in.apk'; fake_apk(apk)
        out = self.dir / 'o.zip'; pkg.rewrite_apk(apk, self.model, out, FAKE)
        raw = out.read_bytes()
        self.assertNotIn(b'PK\x06\x06', raw); self.assertNotIn(b'PK\x06\x07', raw)   # zip64 end-of-central-directory record and locator
        with zipfile.ZipFile(out) as z:
            self.assertTrue(all(i.extra == b'' for i in z.infolist()), 'no zip64 extra fields')
        real = pkg.load_spec()
        self.assertLess(real.size * 1.05, zipfile.ZIP64_LIMIT)   # the real model is well under 2 GiB, as the docs say
        self.assertLess(real.size + 300 * 2**20, zipfile.ZIP64_LIMIT)

    def test_a_package_that_would_pass_2_gib_is_refused(self):
        apk = self.dir / 'in.apk'; fake_apk(apk)
        big = pkg.ModelSpec('big.gguf', 2**31, FAKE.sha256)
        with self.assertRaisesRegex(pkg.PackagingError, 'zip64'):
            pkg.run_packaging(apk, self.model, self.dir / 'dist' / 'x.apk', big, pkg.Tools('za', 'as'), pkg.SigningOptions('k', 'a', 'p', 'p'), runner=lambda c, e=None: None, check_space=False)

    def _main(self, out, extra=()):
        import io
        tools = self.dir / 'bt'; tools.mkdir(exist_ok=True)
        for n in ('zipalign', 'apksigner'):
            (tools / n).write_text('')
        ks = self.dir / 'k.jks'; ks.write_text('x')
        apk = self.dir / 'in.apk'; fake_apk(apk)
        from unittest import mock
        err = io.StringIO()
        with mock.patch.dict(os.environ, {'IG_GUARD_KEYSTORE_PASSWORD': 'pw'}), mock.patch('sys.stderr', err):
            code = pkg.main(['--apk', str(apk), '--model', str(self.model), '--out', str(out), '--keystore', str(ks), '--build-tools', str(tools), *extra])
        return code, err.getvalue()

    def test_an_os_error_prints_a_plain_message(self):
        blocker = self.dir / 'file'; blocker.write_text('not a folder')
        code, err = self._main(blocker / 'sub' / 'x.apk')
        self.assertEqual(code, 2); self.assertTrue(err.startswith('ERROR:'), err); self.assertNotIn('Traceback', err)

    def test_ctrl_c_message_depends_on_whether_the_output_was_written(self):
        from unittest import mock
        out = self.dir / 'dist' / 'x.apk'
        with mock.patch.object(pkg, 'run_packaging', side_effect=KeyboardInterrupt):
            code, err = self._main(out)
        self.assertEqual(code, 130); self.assertIn('No output was written', err)
        def written(*a, **k):
            out.parent.mkdir(parents=True, exist_ok=True); out.write_bytes(b'x'); raise KeyboardInterrupt
        with mock.patch.object(pkg, 'run_packaging', side_effect=written):
            code, err = self._main(out, ['--force'])
        self.assertEqual(code, 130); self.assertIn('was written', err); self.assertNotIn('No output was written', err)


def real_tools():
    bt = SDK / 'build-tools'
    if not bt.is_dir() or not shutil.which('keytool'):
        return None
    try:
        return pkg.find_tools(None, {'ANDROID_HOME': str(SDK)})
    except pkg.PackagingError:
        return None


@unittest.skipUnless(real_tools(), 'Android build-tools or keytool not available')
class RealTools(Dirs):
    """zipalign and apksigner for real, with a throwaway keystore and a tiny APK made by aapt2."""
    def keystore(self, name):
        ks = self.dir / name
        subprocess.run(['keytool', '-genkeypair', '-keystore', str(ks), '-storetype', 'PKCS12', '-storepass', 'throwaway1', '-keypass', 'throwaway1',
                        '-alias', 'igguard', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '30', '-dname', 'CN=test'], check=True, capture_output=True)
        return ks

    def base_apk(self) -> Path:
        tools = Path(real_tools().zipalign).parent
        jar = next(iter(sorted((SDK / 'platforms').glob('android-*/android.jar'))), None)
        if not (tools / 'aapt2').exists() or not jar:
            self.skipTest('aapt2 or android.jar missing')
        (self.dir / 'AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="test.pkg"><application/></manifest>')
        out = self.dir / 'base.apk'
        subprocess.run([str(tools / 'aapt2'), 'link', '-o', str(out), '--manifest', str(self.dir / 'AndroidManifest.xml'), '-I', str(jar),
                        '--min-sdk-version', '26', '--target-sdk-version', '34'], check=True, capture_output=True)
        return out

    def test_package_sign_verify_for_real(self):
        tools = real_tools()
        ks = self.keystore('a.p12'); other = self.keystore('b.p12')
        base = self.base_apk()
        opts = pkg.SigningOptions(str(ks), 'igguard', 'throwaway1', 'throwaway1')
        # an input signed with the same key: same certificate, no warning
        signed_in = self.dir / 'signed-in.apk'
        pkg.sign(tools, opts, base, signed_in, pkg.default_runner)
        out = self.dir / 'dist' / 'with-model.apk'
        result = pkg.run_packaging(signed_in, self.model, out, FAKE, tools, opts, workflow=self.dir / 'none.yml')
        self.assertEqual(result['warnings'], [])
        self.assertEqual(result['old_cert'], result['new_cert'])
        verify = subprocess.run([tools.apksigner, 'verify', '--verbose', '--print-certs', str(out)], capture_output=True, text=True)
        self.assertEqual(verify.returncode, 0, verify.stderr)
        self.assertIn('v2 scheme (APK Signature Scheme v2): true', verify.stdout)
        self.assertIn('v3 scheme (APK Signature Scheme v3): true', verify.stdout)
        aligned = subprocess.run([tools.zipalign, '-c', '-p', '4', str(out)], capture_output=True, text=True)
        self.assertEqual(aligned.returncode, 0, aligned.stdout + aligned.stderr)
        with zipfile.ZipFile(out) as z:
            info = z.getinfo('assets/risk/fake-model.gguf')
            self.assertEqual(info.compress_type, zipfile.ZIP_STORED); self.assertEqual(z.read(info), MODEL)
            self.assertFalse([n for n in z.namelist() if pkg.SIGNATURE_ENTRY.match(n)])
        self.assertEqual(out.with_name('with-model.apk.sha256').read_text().split()[0], hashlib.sha256(out.read_bytes()).hexdigest())
        # the certificate that signed the input is not the one now signing: a loud warning
        again = pkg.run_packaging(signed_in, self.model, self.dir / 'dist' / 'other.apk', FAKE, tools,
                                  pkg.SigningOptions(str(other), 'igguard', 'throwaway1', 'throwaway1'), workflow=self.dir / 'none.yml')
        self.assertEqual(len(again['warnings']), 1); self.assertNotEqual(again['old_cert'], again['new_cert'])
        # an unsigned input is accepted too
        unsigned = pkg.run_packaging(base, self.model, self.dir / 'dist' / 'u.apk', FAKE, tools, opts, workflow=self.dir / 'none.yml')
        self.assertIsNone(unsigned['old_cert'])


if __name__ == '__main__':
    unittest.main()
