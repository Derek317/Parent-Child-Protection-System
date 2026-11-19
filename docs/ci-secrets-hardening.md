# Protecting the signing key and the Cloudflare token (owner checklist)

**Read this first.** The workflow now signs and deploys only on `main` and on release tags. That alone is **not a security boundary**. While the signing and Cloudflare secrets are still *repository* secrets, anyone who can push a branch can edit `.github/workflows/build-apk.yml` on that branch so that it reads them and sends them somewhere. GitHub runs the workflow file from the pushed branch. The boundary only exists once you finish the steps below in the GitHub website. Code cannot do them for you.

Replace `OWNER/REPO` below with `Derek317/Parent-Child-Protection-System`.

## What the workflow does now

| Ref pushed | Relay deploy | APK | Artifact |
| --- | --- | --- | --- |
| Any other branch | No | Built and tested, **never signed**, no secrets in any step | `ig-guard-unsigned-apk`, kept 7 days |
| `main` | Yes, in environment `relay-prod` | Signed in environment `release` | `ig-guard-signed-apk`, 90 days |
| Release tag (`4.9`, `4.9.1`, `4.9-beta.N`, `4.8-hotfix.N`) | Only `4.8-hotfix.N` | Signed in environment `release`, then published | `ig-guard-signed-apk`, 90 days, plus the GitHub release |

Other tag names (for example `foo`) are treated like a branch: unsigned, nothing published. The tag patterns are the ones in `scripts/publish-release.sh`.

## Step 1. Create the two environments

1. In the repository open **Settings → Environments → New environment**.
2. Create **`relay-prod`**. Under **Deployment branches and tags** choose **Selected branches and tags** and add: branch `main`, tag `4.8-hotfix.*`. Tick **Required reviewers** and add yourself (and one other trusted person if you have one). Optionally tick **Prevent self-review** if there are two reviewers.
3. Create **`release`**. Add deployment rules: branch `main`, tags `[0-9]*.[0-9]*` (this covers `4.9`, `4.9.1`, `4.9-beta.3` and `4.8-hotfix.4`; GitHub tag patterns are simple globs, so the workflow, not this pattern, enforces the exact format). Tick **Required reviewers**.
4. Expect an approval prompt on every push to `main` and on every release tag, because the build job runs in `release`. Approving is the point: nothing signs or deploys unless a person says yes.
5. The workflow also names a third environment, `ci-unprotected`, on other refs. GitHub creates it automatically on first use. Leave it empty: no secrets, no rules. Never add secrets to it.

## Step 2. Move the secrets into the environments

1. In **`release`** add the four secrets `APK_KEYSTORE_BASE64`, `APK_KEYSTORE_PASSWORD`, `APK_KEY_ALIAS`, `APK_KEY_PASSWORD` (see [signing.md](signing.md); `gh secret set NAME --env release --repo OWNER/REPO` also works).
2. In **`relay-prod`** add `CLOUDFLARE_API_TOKEN`. Add `CLOUDFLARE_ACCOUNT_ID` as an environment variable (or secret) there as well; the repository variable `RELAY_BASE_URL` can stay a normal repository variable.
3. Only after both environments work (run the workflow on `main` and approve it), **delete the same names from Settings → Secrets and variables → Actions → Repository secrets**. If you skip this deletion, the secrets stay readable from any branch.
4. Do this in order: if the `release` environment has no secrets when main builds, the build silently falls back to an *unsigned* APK and the signed-APK verification and release steps will not run.

## Step 3. Protect `main` and the release tags

1. **Settings → Rules → Rulesets → New branch ruleset**: target `main`; enable **Restrict deletions**, **Block force pushes**, **Require a pull request before merging** (at least 1 approval if there is a second person; otherwise at least require the PR) and **Require status checks** (`relay-test`, `build`). Add yourself as the only bypass actor if needed.
2. **New tag ruleset**: target the patterns `[0-9]*.[0-9]*`; enable **Restrict creations**, **Restrict updates** and **Restrict deletions**, with only you as bypass actor. This is what stops other contributors from creating a release or hotfix tag, which would sign and publish.
3. Restrict who has **write** access under **Settings → Collaborators and teams**. Anyone with write access can push branches and edit the workflow on them.
4. Settings → Actions → General: set **Fork pull request workflows** to require approval, and **Workflow permissions** to read-only (the workflow already declares `contents: read` and only the release job gets write).

## Step 4. Delete signed artifacts from non-release branches

Older runs on experimental branches may have uploaded `ig-guard-signed-apk`, kept 90 days, with a version code that can install over a family's production app. List them:

```sh
gh api --paginate "repos/OWNER/REPO/actions/artifacts?name=ig-guard-signed-apk&per_page=100" \
  --jq '.artifacts[] | [.id, .workflow_run.head_branch, .created_at, .expired] | @tsv'
```

Delete every one whose branch is **not** `main` and not a release tag name (inspect the list first; release tags appear as the tag name in `head_branch`):

```sh
gh api --paginate "repos/OWNER/REPO/actions/artifacts?name=ig-guard-signed-apk&per_page=100" \
  --jq '.artifacts[] | select(.workflow_run.head_branch != "main") | .id' |
while read -r id; do gh api -X DELETE "repos/OWNER/REPO/actions/artifacts/$id" && echo "deleted $id"; done
```

If any experimental branch build was ever installed on a real family's phone, treat that phone as running an untrusted build and reinstall the verified release APK.

## Step 5. Consider rotating secrets

- **Cloudflare token.** Because it was a repository secret, any branch pusher could have read it. Create a new token (Workers Scripts: Edit and Account Settings: Read, restricted to your account), save it in `relay-prod`, then delete the old token in the Cloudflare dashboard.
- **Signing key.** If you have reason to think the keystore secrets were read, a new key means family phones must reinstall (see [signing.md](signing.md)); weigh this before rotating. At minimum review **Settings → Actions** run history for branches you do not recognise.

## Step 6. Rate limiting in front of the relay (owner action)

The relay now stores nothing for unauthenticated requests and caps buffered writes per family per day, but every request, including a rejected one, still counts against the Cloudflare Workers Free quotas (requests per day, Durable Object requests). Anyone who knows the public `workers.dev` address can exhaust them, and the relay then stops serving every family until the next 00:00 UTC reset. Code in this repository does not stop that: Cloudflare's per-IP Rate Limiting binding was **not** added, because the current documentation page for the binding does not say it is available on the Workers Free plan, and a deploy that failed because of it would stop production releases.

If this risk matters to you, the options are on your side of Cloudflare: attach a custom domain you own to the Worker, turn off `workers.dev` for it, and add a WAF rate-limiting rule for `/v1/rooms/*` in that domain's dashboard (these need a domain on Cloudflare, not a paid Workers plan); or monitor request counts in the dashboard and react. Moving to a paid plan raises the limits but does not remove the abuse possibility.
