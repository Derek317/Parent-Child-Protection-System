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
DEPLOY = "needs.plan.outputs.protected == 'true' && (github.ref == 'refs/heads/main' || contains(github.ref, '-hotfix.'))"


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
                    'refs/tags/4.8-hotfix.0', 'refs/tags/v4.9', 'refs/pull/1/merge', 'refs/tags/4.9-beta.1x',
                    'refs/tags/model-beacon'):
            self.assertEqual('false', self.classify(ref)['protected'], ref)
        self.assertEqual('release', self.classify('refs/heads/main')['environment'])
        self.assertEqual('ci-unprotected', self.classify('refs/heads/experiment')['environment'])

    def test_unsigned_artifact_and_short_retention_on_other_refs(self):
        self.assertIn('ig-guard-unsigned-apk', TEXT)
        upload = next(s for s in steps('build') if s.get('uses', '').startswith('actions/upload-artifact@'))
        self.assertEqual(7, upload['with']['retention-days'])

    def test_the_5_0_tag_is_a_protected_release_tag(self):
        self.assertEqual({'protected': 'true', 'environment': 'release'}, self.classify('refs/tags/5.0'))

    def test_model_beacon_tag_never_signs_or_releases(self):
        # The model's storage release must stay inert: no release environment, so no secrets, no packaging, no publish.
        self.assertEqual({'protected': 'false', 'environment': 'ci-unprotected'}, self.classify('refs/tags/model-beacon'))
        self.assertIn(PROTECTED, JOBS['release']['if'])


class ReleaseCarriesTheModel(unittest.TestCase):
    def step(self, job, name):
        return next(s for s in steps(job) if s.get('name') == name)

    def test_model_is_packaged_only_on_verified_version_tag_builds(self):
        package = self.step('build', 'Package release APK with the model')
        for needle in (PROTECTED, "startsWith(github.ref, 'refs/tags/')", "steps.verify.outputs.signed_verified == 'true'"):
            self.assertIn(needle, package['if'])
        names = [s.get('name') for s in steps('build')]
        self.assertLess(names.index('Verify signed APK'), names.index('Package release APK with the model'))
        # Ordinary main and branch builds never fetch the model: the only download is in this tag-only step.
        self.assertEqual(1, TEXT.count('$MODEL_URL"'))
        for name, job in JOBS.items():
            for other in job.get('steps', []):
                if other is not package:
                    self.assertNotIn('curl', other.get('run', ''), other.get('name'))
                    self.assertNotIn('package_with_model', other.get('run', ''), other.get('name'))

    def test_model_url_is_defined_once_and_matches_the_pinned_file_name(self):
        url = JOBS['build']['env']['MODEL_URL']
        self.assertEqual('https://github.com/Derek317/Parent-Child-Protection-System/releases/download/model-beacon/', url.rsplit('/', 1)[0] + '/')
        name = re.search(r'const\s+val\s+FILE_NAME\s*=\s*"([^"]+)"', (REPO / 'RiskModel.kt').read_text()).group(1)
        self.assertEqual(name, url.rsplit('/', 1)[1])
        self.assertEqual(1, TEXT.count('releases/download/model-beacon'))

    def test_passwords_reach_the_packager_only_through_its_environment(self):
        package = self.step('build', 'Package release APK with the model')
        self.assertEqual({'APK_KEYSTORE_BASE64': '${{ secrets.APK_KEYSTORE_BASE64 }}',
                          'IG_GUARD_KEYSTORE_PASSWORD': '${{ secrets.APK_KEYSTORE_PASSWORD }}',
                          'APK_KEY_ALIAS': '${{ secrets.APK_KEY_ALIAS }}',
                          'IG_GUARD_KEY_PASSWORD': '${{ secrets.APK_KEY_PASSWORD }}'}, package['env'])
        run = package['run']
        for forbidden in ('--ks-pass', '--key-pass', 'PASSWORD"', 'set -x', 'echo "$APK', 'echo "$IG_GUARD'):
            self.assertNotIn(forbidden, run)
        self.assertIn('mktemp "$RUNNER_TEMP/ig-guard-key.', run)
        self.assertIn('trap \'rm -f "$keystore"', run)
        self.assertIn('--build-tools "$ANDROID_HOME/build-tools/34.0.0"', run)
        self.assertIn('< /dev/null', run)
        self.assertNotIn('secrets.', yaml.safe_dump(JOBS['release']))

    def test_release_publishes_only_the_apk_with_the_model(self):
        upload = self.step('build', 'Upload release APK with the model')
        self.assertEqual('ig-guard-release-apk', upload['with']['name'])
        self.assertEqual(1, upload['with']['retention-days'])
        self.assertEqual('error', upload['with']['if-no-files-found'])
        downloads = [s for s in steps('release') if s.get('uses', '').startswith('actions/download-artifact@')]
        self.assertEqual(['ig-guard-release-apk'], [s['with']['name'] for s in downloads])
        self.assertIn("needs.build.outputs.model_packaged == 'true'", downloads[0]['if'])
        publish = self.step('release', 'Publish release or notes-only fallback')
        self.assertEqual('${{ needs.build.outputs.model_packaged }}', publish['env']['MODEL_PACKAGED'])


class DeploymentIsolation(unittest.TestCase):
    def test_cloudflare_secrets_only_in_the_deploy_job_bound_to_relay_prod(self):
        for name, job in JOBS.items():
            has = 'secrets.CLOUDFLARE' in yaml.safe_dump(job)
            self.assertEqual(name == 'relay-deploy', has, name)
        deploy = JOBS['relay-deploy']
        self.assertEqual('relay-prod', deploy['environment'])
        self.assertEqual(DEPLOY, deploy['if'].replace('${{ ', '').replace(' }}', '').strip())
        # The deploy rule leans on the plan job's tag check, so a tag such as foo-hotfix.x never deploys.
        self.assertIn('plan', deploy['needs'])

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

    def test_deploy_and_signing_wait_for_the_app_tests_which_hold_no_secrets(self):
        tests = JOBS['app-test']
        self.assertNotIn('environment', tests)
        self.assertNotIn('needs', tests)
        self.assertNotIn('secrets.', yaml.safe_dump(tests))
        self.assertIn('Run regression checks', [s.get('name') for s in tests['steps']])
        # The signing job no longer runs the tests itself: it only starts after they passed.
        self.assertNotIn('Run regression checks', [s.get('name') for s in steps('build')])
        for job in ('relay-deploy', 'build'):
            self.assertIn('app-test', JOBS[job]['needs'], job)
        self.assertNotIn('always()', str(JOBS['relay-deploy'].get('if')))
        self.assertNotIn('!cancelled()', str(JOBS['relay-deploy'].get('if')))  # default: every needed job must succeed
        self.assertIn("needs.app-test.result == 'success'", JOBS['build']['if'])


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
