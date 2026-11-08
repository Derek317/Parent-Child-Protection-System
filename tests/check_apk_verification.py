"""Exercise workflow verification gates; upstream Android tools return controlled metadata."""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

WORKFLOW=Path(__file__).resolve().parents[1]/'.github/workflows/build-apk.yml'
CERT='0eb6f30787c2d6dc23136a28cfd7a08a7956e5fe0565ba142f6711be38ca3f0e'

class ApkVerification(unittest.TestCase):
    def verify(self,badging,certificate=CERT):
        source=WORKFLOW.read_text().split('      - name: Verify signed APK\n',1)[1].split('\n      - name:',1)[0]
        script='\n'.join(line[10:] for line in source.split('        run: |\n',1)[1].splitlines())
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);tools=root/'sdk/build-tools/34.0.0';tools.mkdir(parents=True)
            (tools/'apksigner').write_text('#!/bin/sh\nprintf "Signer #1 certificate SHA-256 digest: %s\\n" "$TEST_CERT"\n')
            (tools/'aapt').write_text('#!/bin/sh\nprintf "%s\\n" "$TEST_BADGING"\n')
            for tool in tools.iterdir():tool.chmod(0o700)
            apk=root/'app/build/outputs/apk/release/app-release.apk';apk.parent.mkdir(parents=True);apk.write_bytes(b'upstream signature-tool fixture')
            env={**os.environ,'ANDROID_HOME':str(root/'sdk'),'RUNNER_TEMP':temp,'GITHUB_OUTPUT':str(root/'output'),'TEST_CERT':certificate,'TEST_BADGING':badging}
            result=subprocess.run(['bash','-c',script],cwd=root,env=env,capture_output=True,text=True)
            return result.returncode,(root/'output').read_text() if (root/'output').exists() else ''

    def test_only_expected_certificate_package_version_and_non_debuggable_build_are_accepted(self):
        good="package: name='app.family.igguard' versionCode='151' versionName='4.8'"
        code,output=self.verify(good);self.assertEqual(0,code);self.assertIn('signed_verified=true',output)
        cases=[(good+'\napplication-debuggable',CERT),(good.replace("versionCode='151'","versionCode='150'"),CERT),
               (good.replace('app.family.igguard','other.package'),CERT),(good.replace("versionName='4.8'","versionName='4.7'"),CERT),(good,'0'*64)]
        for metadata,certificate in cases:
            with self.subTest(metadata=metadata,certificate=certificate):
                code,output=self.verify(metadata,certificate);self.assertNotEqual(0,code);self.assertNotIn('signed_verified=true',output)

if __name__=='__main__':unittest.main()
