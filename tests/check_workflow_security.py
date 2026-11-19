"""Static checks that keep production secrets, deployment and signing on protected refs only."""
from pathlib import Path
import re
import subprocess
import unittest

import yaml

REPO = Path(__file__).resolve().parents[1]
TEXT = (REPO / '.github/workflows/build-apk.yml').read_text()
WORKFLOW = yaml.safe_load(TEXT)
JOBS = WORKFLOW['jobs']
# Actions that could not be resolved to a commit SHA must be listed here (and in the report). None today.
UNPINNED_ALLOWED = set()
PROTECTED = "needs.plan.outputs.protected == 'true'"
DEPLOY = "github.ref == 'refs/heads/main' || startsWith(github.ref, 'refs/tags/4.8-hotfix.')"


def steps(job):
    return JOBS[job].get('steps', [])


class SigningOnlyOnProtectedRefs(unittest.TestCase):
    def test_every_step_using_apk_secrets_is_gated_on_protected_refs(self):
        found = 0
        for name, job in JOBS.items():
            self.assertNotIn('APK_', yaml.safe_dump({k: v for k, v in job.items() if k != 'steps'}), f'job {name} exposes signing secrets job-wide')
            for step in job.get('steps', []):
                if 'secrets.APK_' in yaml.safe_dump(step):
                    found += 1
                    self.assertIn(PROTECTED, step.get('if', ''), f"step {step.get('name')!r} uses signing secrets without the protected-ref condition")
                    self.assertEqual("${{ needs.plan.outputs.environment }}", job.get('environment'), f'job {name} is not bound to the release environment')
        self.assertGreaterEqual(found, 1)

    def test_steps_on_other_refs_never_receive_secrets(self):
        for name, job in JOBS.items():
            for step in job.get('steps', []):
                dumped = yaml.safe_dump(step)
                if 'secrets.' in dumped and 'secrets.APK_' not in dumped:
                    self.assertEqual('relay-prod', job.get('environment'), f"{step.get('name')!r} uses a secret outside relay-prod")
                    self.assertIn(DEPLOY, job.get('if', ''))

    def plan_script(self):
        step = next(s for s in steps('plan') if s.get('id') == 'ref')
        return step['run']

    def classify(self, ref):
        result = subprocess.run(['bash', '-c', self.plan_script()], env={'GITHUB_REF': ref, 'GITHUB_OUTPUT': '/dev/stdout', 'PATH': '/usr/bin:/bin'},
                                capture_output=True, text=True, check=True)
        return dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)

    def test_plan_accepts_only_main_and_release_tags(self):
        for ref in ('refs/heads/main', 'refs/tags/4.9', 'refs/tags/4.9.1', 'refs/tags/4.9-beta.3', 'refs/tags/4.8-hotfix.4'):
            self.assertEqual('true', self.classify(ref)['protected'], ref)
        for ref in ('refs/heads/experiment', 'refs/heads/main-2', 'refs/heads/release/4.9', 'refs/tags/foo', 'refs/tags/4.9-rc.1',
                    'refs/tags/4.8-hotfix.0', 'refs/tags/v4.9', 'refs/pull/1/merge', 'refs/tags/4.9-beta.1x'):
            self.assertEqual('false', self.classify(ref)['protected'], ref)
        self.assertEqual('release', self.classify('refs/heads/main')['environment'])
        self.assertEqual('ci-unprotected', self.classify('refs/heads/experiment')['environment'])

    def test_unsigned_artifact_and_short_retention_on_other_refs(self):
        self.assertIn('ig-guard-unsigned-apk', TEXT)
        upload = next(s for s in steps('build') if s.get('uses', '').startswith('actions/upload-artifact@'))
        self.assertEqual(7, upload['with']['retention-days'])


class DeploymentIsolation(unittest.TestCase):
    def test_cloudflare_secrets_only_in_the_deploy_job_bound_to_relay_prod(self):
        for name, job in JOBS.items():
            has = 'secrets.CLOUDFLARE' in yaml.safe_dump(job)
            self.assertEqual(name == 'relay-deploy', has, name)
        deploy = JOBS['relay-deploy']
        self.assertEqual('relay-prod', deploy['environment'])
        self.assertEqual(DEPLOY, deploy['if'].replace('${{ ', '').replace(' }}', '').strip())

    def test_live_smoke_runs_only_where_deploy_runs(self):
        for name, job in JOBS.items():
            if 'npm run smoke' in yaml.safe_dump(job):
                self.assertEqual('relay-deploy', name)
        self.assertNotIn('smoke', yaml.safe_dump(JOBS['relay-test']))

    def test_concurrency_keeps_refs_apart_and_serialises_deploys(self):
        self.assertIn('github.ref', WORKFLOW['concurrency']['group'])
        self.assertEqual('ig-guard-relay-deploy', JOBS['relay-deploy']['concurrency']['group'])
        self.assertFalse(JOBS['relay-deploy']['concurrency']['cancel-in-progress'])

    def test_release_requires_successful_tests_and_deploy_and_not_always(self):
        condition = JOBS['release']['if']
        self.assertNotIn('always()', condition)
        for needle in ("needs.relay-test.result == 'success'", PROTECTED, "refs/tags/"):
            self.assertIn(needle, condition)
        self.assertIn("needs.relay-deploy.result", condition)
        self.assertIn("needs.build.result", condition)


class ThirdPartyActionsArePinned(unittest.TestCase):
    def test_every_uses_is_a_full_commit_sha_with_a_tag_comment(self):
        for number, line in enumerate(TEXT.splitlines(), 1):
            match = re.search(r'\buses:\s*(\S+)(.*)$', line)
            if not match:
                continue
            action, rest = match.groups()
            if action.startswith('./') or action in UNPINNED_ALLOWED:
                continue
            self.assertRegex(action, r'^[\w.-]+/[\w./-]+@[0-9a-f]{40}$', f'line {number}: {action} is not pinned to a commit SHA')
            self.assertRegex(rest, r'#\s*v\d', f'line {number}: missing tag comment')


if __name__ == '__main__':
    unittest.main()
