"""Exercise relay configuration and the external health-check boundary."""
import importlib.util
import io
from contextlib import redirect_stdout, redirect_stderr
from unittest.mock import patch
import urllib.error
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/relay-config.py'

class RelayConfiguration(unittest.TestCase):
    def run_config(self, endpoint, *args):
        with tempfile.TemporaryDirectory() as directory:
            output=Path(directory)/'config.json'
            env=os.environ.copy();env['RELAY_BASE_URL']=endpoint
            result=subprocess.run(['python3',str(SCRIPT),'--config',str(Path(directory)/'absent.json'),'--output',str(output),*args],env=env,capture_output=True,text=True)
            data=json.loads(output.read_text()) if output.exists() else None
            return result,data

    def test_missing_endpoint_allows_local_debug_configuration_but_blocks_release_check(self):
        result,data=self.run_config('')
        self.assertEqual(0,result.returncode);self.assertEqual({'base_url':''},data)
        result,data=self.run_config('','--require')
        self.assertNotEqual(0,result.returncode);self.assertIsNone(data)
        self.assertIn('[RELAY_CONFIG]',result.stderr)

    def test_public_https_root_is_normalized_without_exposing_credentials(self):
        result,data=self.run_config('https://ig-guard-relay.family.workers.dev/','--require')
        self.assertEqual(0,result.returncode);self.assertEqual({'base_url':'https://ig-guard-relay.family.workers.dev'},data)
        for bad in ('http://example.com','https://user:password@example.com','https://example.com/?token=secret','https://example.com/path','https://127.0.0.1','https://localhost','https://example.test','https://example.com:8443','https://example.com/#secret'):
            with self.subTest(endpoint=bad):
                result,data=self.run_config(bad,'--require')
                self.assertNotEqual(0,result.returncode);self.assertIsNone(data)
                self.assertNotIn('password',result.stderr);self.assertNotIn('token=secret',result.stderr)

    def test_health_failures_have_safe_actionable_ci_annotations(self):
        spec=importlib.util.spec_from_file_location('relay_config',SCRIPT)
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        cases=[(urllib.error.HTTPError('https://private.invalid/?token=secret',404,'private detail',{},None),'RELAY_HTTP_404'),
               (urllib.error.URLError('password=secret'),'RELAY_NETWORK'),
               (ValueError('Relay protocol does not match this app.'),'RELAY_PROTOCOL'),
               (json.JSONDecodeError('private response','secret',0),'RELAY_RESPONSE')]
        for error,code in cases:
            with self.subTest(code=code), tempfile.TemporaryDirectory() as temp:
                out=io.StringIO();err=io.StringIO()
                with patch.dict(os.environ,{'RELAY_BASE_URL':'https://relay.workers.dev','GITHUB_ACTIONS':'true'}), \
                     patch('sys.argv',['relay-config','--config',str(Path(temp)/'absent'),'--require','--probe']), \
                     patch.object(module,'probe',side_effect=error),redirect_stdout(out),redirect_stderr(err):
                    self.assertEqual(2,module.main())
                report=out.getvalue()+err.getvalue()
                self.assertIn('::error',report);self.assertIn(code,report)
                self.assertNotIn('secret',report);self.assertNotIn('private.invalid',report)

if __name__=='__main__':unittest.main()
