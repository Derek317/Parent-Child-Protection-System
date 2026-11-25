"""Exercise workflow verification gates; upstream Android tools return controlled metadata."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
import zipfile

WORKFLOW=Path(__file__).resolve().parents[1]/'.github/workflows/build-apk.yml'
CERT='0eb6f30787c2d6dc23136a28cfd7a08a7956e5fe0565ba142f6711be38ca3f0e'

class ApkVerification(unittest.TestCase):
    def verify(self,badging,certificate=CERT,terms_match=True):
        source=WORKFLOW.read_text().split('      - name: Verify signed APK\n',1)[1].split('\n      - name:',1)[0]
        script='\n'.join(line[10:] for line in source.split('        run: |\n',1)[1].splitlines())
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);tools=root/'sdk/build-tools/34.0.0';tools.mkdir(parents=True)
            (tools/'apksigner').write_text('#!/bin/sh\nprintf "Signer #1 certificate SHA-256 digest: %s\\n" "$TEST_CERT"\n')
            (tools/'aapt').write_text('#!/bin/sh\nprintf "%s\\n" "$TEST_BADGING"\n')
            for tool in tools.iterdir():tool.chmod(0o700)
            apk=root/'app/build/outputs/apk/release/app-release.apk';apk.parent.mkdir(parents=True)
            (root/'docs').mkdir();(root/'docs/ig-guard-terms-of-service.md').write_bytes(b'canonical terms fixture')
            with zipfile.ZipFile(apk,'w') as bundle:
                bundle.writestr('assets/ig-guard-terms-of-service.md',b'canonical terms fixture' if terms_match else b'outdated fixture')
            env={**os.environ,'ANDROID_HOME':str(root/'sdk'),'RUNNER_TEMP':temp,'GITHUB_OUTPUT':str(root/'output'),'TEST_CERT':certificate,'TEST_BADGING':badging}
            result=subprocess.run(['bash','-c',script],cwd=root,env=env,capture_output=True,text=True)
            return result.returncode,(root/'output').read_text() if (root/'output').exists() else ''

    def test_only_expected_certificate_package_version_and_non_debuggable_build_are_accepted(self):
        good="package: name='app.family.igguard' versionCode='151' versionName='6.0.1'"
        code,output=self.verify(good);self.assertEqual(0,code);self.assertIn('signed_verified=true',output)
        cases=[(good+'\napplication-debuggable',CERT),(good.replace("versionCode='151'","versionCode='150'"),CERT),
               (good.replace('app.family.igguard','other.package'),CERT),(good.replace("versionName='6.0.1'","versionName='4.7'"),CERT),(good,'0'*64)]
        for metadata,certificate in cases:
            with self.subTest(metadata=metadata,certificate=certificate):
                code,output=self.verify(metadata,certificate);self.assertNotEqual(0,code);self.assertNotIn('signed_verified=true',output)

    def test_packaged_terms_must_match_the_repository_verbatim(self):
        good="package: name='app.family.igguard' versionCode='151' versionName='6.0.1'"
        code,output=self.verify(good,terms_match=False)
        self.assertNotEqual(0,code);self.assertNotIn('signed_verified=true',output)

FAKE_PACKAGER='''import json, os, sys, zipfile
from types import SimpleNamespace
def load_spec(): return SimpleNamespace(file_name='m.gguf', size=8)
def pinned_certificate(): return os.environ['TEST_PIN']
if __name__ == '__main__':
    a = sys.argv
    keystore = open(a[a.index('--keystore')+1], 'rb').read()
    json.dump({'argv': a, 'keystore': keystore.decode(), 'tty': sys.stdin.isatty(),
               'ks_env': os.environ.get('IG_GUARD_KEYSTORE_PASSWORD'), 'b64_env': os.environ.get('APK_KEYSTORE_BASE64')}, open('observed.json', 'w'))
    if os.environ.get('TEST_PACKAGE_FAIL'): sys.exit(2)
    with zipfile.ZipFile(a[a.index('--out')+1], 'w') as z:
        z.writestr('classes.dex', b'dex')
        if not os.environ.get('TEST_NO_ENTRY'): z.write(a[a.index('--model')+1], 'assets/risk/m.gguf', zipfile.ZIP_STORED if not os.environ.get('TEST_DEFLATE') else zipfile.ZIP_DEFLATED)
'''
FAKE_CURL='''#!/usr/bin/env python3
import os, sys
if os.environ.get('TEST_CURL_FAIL'): sys.exit(22)
a = sys.argv
open(a[a.index('--output')+1], 'wb').write(b'12345678')
'''

class ModelPackagingStep(unittest.TestCase):
    """The tag-only step that puts the model into the release APK fails loudly instead of releasing without it."""
    def package(self, **env_overrides):
        source=WORKFLOW.read_text().split('      - name: Package release APK with the model\n',1)[1].split('\n      - name:',1)[0]
        script='\n'.join(line[10:] for line in source.split('        run: |\n',1)[1].splitlines())
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);tools=root/'sdk/build-tools/34.0.0';tools.mkdir(parents=True)
            (tools/'apksigner').write_text('#!/bin/sh\nprintf "Signer #1 certificate SHA-256 digest: %s\\n" "$TEST_CERT"\n');(tools/'apksigner').chmod(0o700)
            fakebin=root/'bin';fakebin.mkdir();(fakebin/'curl').write_text(FAKE_CURL);(fakebin/'curl').chmod(0o700)
            (root/'tools').mkdir();(root/'tools/package_with_model.py').write_text(FAKE_PACKAGER)
            apk=root/'app/build/outputs/apk/release/app-release.apk';apk.parent.mkdir(parents=True);apk.write_bytes(b'signed apk')
            runner=root/'runner';runner.mkdir()
            env={**os.environ,'PATH':str(fakebin)+os.pathsep+os.environ['PATH'],'ANDROID_HOME':str(root/'sdk'),'RUNNER_TEMP':str(runner),
                 'GITHUB_OUTPUT':str(root/'output'),'TEST_CERT':CERT,'TEST_PIN':CERT,
                 'MODEL_URL':'https://example.invalid/releases/download/model-beacon/m.gguf',
                 'APK_KEYSTORE_BASE64':'dGVzdC1vbmx5LWtleXN0b3Jl','IG_GUARD_KEYSTORE_PASSWORD':'test-only-pass $!','APK_KEY_ALIAS':'test-alias','IG_GUARD_KEY_PASSWORD':''}
            env.update(env_overrides)
            result=subprocess.run(['bash','-c',script],cwd=root,env=env,capture_output=True,text=True,stdin=subprocess.DEVNULL)
            output=(root/'output').read_text() if (root/'output').exists() else ''
            observed=json.loads((root/'observed.json').read_text()) if (root/'observed.json').exists() else None
            leftovers=[p.name for p in runner.rglob('*') if p.name.startswith('ig-guard-key.') or p.name=='m.gguf']
            return result,output,observed,leftovers

    def test_success_packages_with_passwords_only_in_the_environment_and_cleans_up(self):
        result,output,observed,leftovers=self.package()
        self.assertEqual(0,result.returncode,result.stdout+result.stderr)
        self.assertIn('model_packaged=true',output)
        self.assertEqual('test-only-keystore',observed['keystore'])
        self.assertEqual('test-only-pass $!',observed['ks_env'])
        self.assertIsNone(observed['b64_env'],'the encoded keystore must not reach the packager')
        if os.name!='nt':self.assertFalse(observed['tty'])  # Windows reports its NUL device as a terminal
        self.assertFalse([a for a in observed['argv'] if 'test-only-pass' in a])
        self.assertIn(str(Path('sdk/build-tools/34.0.0')),str(Path(observed['argv'][observed['argv'].index('--build-tools')+1])))
        self.assertNotIn('test-only-pass',result.stdout+result.stderr)
        self.assertEqual([],leftovers)

    def test_every_failure_is_an_error_and_never_marks_the_model_packaged(self):
        cases=[{'TEST_CURL_FAIL':'1'},{'TEST_PACKAGE_FAIL':'1'},{'TEST_CERT':'0'*64},{'TEST_PIN':'-'},{'TEST_NO_ENTRY':'1'},
               {'TEST_DEFLATE':'1'},{'MODEL_URL':'https://example.invalid/releases/download/model-beacon/other.gguf'}]
        for case in cases:
            with self.subTest(case=case):
                result,output,_,leftovers=self.package(**case)
                self.assertNotEqual(0,result.returncode)
                self.assertIn('::error title=Release APK with the model::',result.stdout)
                self.assertNotIn('model_packaged=true',output)
                self.assertEqual([],leftovers)

if __name__=='__main__':unittest.main()
