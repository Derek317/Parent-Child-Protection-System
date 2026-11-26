# Protecting the signing key and the Cloudflare token (owner checklist)

**Read this first.** The workflow now signs and deploys only on `main` and on release tags. That alone is **not a security boundary**. While the signing and Cloudflare secrets are still *repository* secrets, anyone who can push a branch can edit `.github/workflows/build-apk.yml` on that branch so that it reads them and sends them somewhere. GitHub runs the workflow file from the pushed branch. The boundary only exists once you finish the steps below in the GitHub website. Code cannot do them for you.

**State on 26 November 2026, as shown by the owner's own settings page (not checked by any code).** The `relay-prod` environment exists and allows branch `main` and tags `*-hotfix.*`. It has no required reviewer and no wait timer, and administrators may bypass its rules. It holds no secrets and no variables, so the Cloudflare token the deploy uses is still a repository secret. Steps 1 and 2 below are therefore only partly done: the warning above still applies to the Cloudflare token, and a deploy starts without anyone approving it. The `release` environment was not shown and is not described here.

Replace `OWNER/REPO` below with `Derek317/Parent-Child-Protection-System`.

## What the workflow does now

| Ref pushed | Relay deploy | APK | Artifact |
| --- | --- | --- | --- |
| Any other branch | No | Built and tested, **never signed**, no secrets in any step | `ig-guard-unsigned-apk`, kept 7 days |
| `main` | Yes, in environment `relay-prod` | Signed in environment `release` | `ig-guard-signed-apk`, kept 7 days |
| Release tag (`4.9`, `4.9.1`, `4.9-beta.N`, `4.8-hotfix.N`) | Only hotfix tags (`4.8-hotfix.N`, `6.0.2-hotfix.N`) | Signed in environment `release`, then published | `ig-guard-signed-apk`, kept 7 days, plus the GitHub release |

Other tag names (for example `foo`) are treated like a branch: unsigned, nothing published. The tag patterns are the ones in `scripts/publish-release.sh`.

## Step 1. Create the two environments

1. In the repository open **Settings → Environments → New environment**.
2. Create **`relay-prod`**. Under **Deployment branches and tags** choose **Selected branches and tags** and add: branch `main`, tag `*-hotfix.*` (before 6.0.3 this was `4.8-hotfix.*`; with the old pattern GitHub refuses the deploy of any other hotfix tag). Tick **Required reviewers** and add yourself (and one other trusted person if you have one). Optionally tick **Prevent self-review** if there are two reviewers.
3. Create **`release`**. Add deployment rules: branch `main`, tags `[0-9]*.[0-9]*` (this covers `4.9`, `4.9.1`, `4.9-beta.3` and `4.8-hotfix.4`; GitHub tag patterns are simple globs, so the workflow, not this pattern, enforces the exact format). Tick **Required reviewers**.
4. Once reviewers are set, **every push to `main` needs two approvals**: one for `relay-prod` (the deploy) and one for `release` (the signing build). A release tag needs one (`release`), or two for a hotfix tag. Approving is the point: nothing signs or deploys unless a person says yes.
5. **A rejected approval is not a clean stop for tags.** GitHub reports a rejected deployment as a failed job, and the release job deliberately still publishes the documented notes-only release when the build fails (see `docs/releases/4.7.md`). So rejecting the `release` approval on a release tag can publish release notes without an APK (never an APK). I could not find documentation of a separate result for a rejected deployment, so this is documented rather than prevented. The tag ruleset in Step 3 is what stops unwanted tags.
6. A release-looking tag on any commit still gets a signed build and release, and a tag run executes the workflow file from that commit. The tag ruleset and the environment reviewer are the only defence; read the workflow at the tagged commit before approving.
7. The workflow also names a third environment, `ci-unprotected`, on other refs. GitHub creates it automatically on first use. Leave it empty: no secrets, no rules. Never add secrets to it.

## Step 2. Move the secrets into the environments

1. In **`release`** add the four secrets `APK_KEYSTORE_BASE64`, `APK_KEYSTORE_PASSWORD`, `APK_KEY_ALIAS`, `APK_KEY_PASSWORD` (see [signing.md](signing.md); `gh secret set NAME --env release --repo OWNER/REPO` also works).
2. In **`relay-prod`** add `CLOUDFLARE_API_TOKEN`. Add `CLOUDFLARE_ACCOUNT_ID` as an environment variable (or secret) there as well; the repository variable `RELAY_BASE_URL` can stay a normal repository variable.
3. Only after both environments work (run the workflow on `main` and approve it), **delete the same names from Settings → Secrets and variables → Actions → Repository secrets**. If you skip this deletion, the secrets stay readable from any branch.
4. Do this in order: if the `release` environment has no secrets when main builds, the build silently falls back to an *unsigned* APK and the signed-APK verification and release steps will not run.

## Step 3. Protect `main` and the release tags

1. **Settings → Rules → Rulesets → New branch ruleset**: target `main`; enable **Restrict deletions**, **Block force pushes**, **Require a pull request before merging** (at least 1 approval if there is a second person; otherwise at least require the PR) and **Require status checks** (`relay-test`, `app-test`, `build`). List `app-test` itself: a skipped `build` counts as passed, so failed app tests could otherwise still merge. Add yourself as the only bypass actor if needed.
2. **New tag ruleset**: target the patterns `[0-9]*.[0-9]*`; enable **Restrict creations**, **Restrict updates** and **Restrict deletions**, with only you as bypass actor. This is what stops other contributors from creating a release or hotfix tag, which would sign and publish.
3. Restrict who has **write** access under **Settings → Collaborators and teams**. Anyone with write access can push branches and edit the workflow on them.
4. Settings → Actions → General: set **Fork pull request workflows** to require approval, and **Workflow permissions** to read-only (the workflow already declares `contents: read` and only the release job gets write).

## Step 4. Delete signed artifacts from non-release branches

Older runs on experimental branches may have uploaded `ig-guard-signed-apk`, kept 90 days, with a version code that can install over a family's production app. List them:

```sh
gh api --paginate "repos/OWNER/REPO/actions/artifacts?name=ig-guard-signed-apk&per_page=100" \
  --jq '.artifacts[] | [.id, (.workflow_run.head_branch // ""), .created_at, .expired] | @tsv'
```

Delete every one whose branch is not `main` and not a version-like tag name. First collect the ids into a file and read the list; nothing is deleted yet:

```sh
gh api --paginate "repos/OWNER/REPO/actions/artifacts?name=ig-guard-signed-apk&per_page=100" \
  --jq '.artifacts[] | select(.expired | not) | select((.workflow_run.head_branch // "") != "main") | select((.workflow_run.head_branch // "") | test("^[0-9]+\\.[0-9]+") | not) | [.id, (.workflow_run.head_branch // ""), .created_at] | @tsv' > to-delete.tsv
cat to-delete.tsv
```

Only when the list contains nothing you want to keep, delete from the file (never while still paginating), then list again and repeat until the file is empty:

```sh
cut -f1 to-delete.tsv | while read -r id; do gh api -X DELETE "repos/OWNER/REPO/actions/artifacts/$id" && echo "deleted $id"; done
```

If any experimental branch build was ever installed on a real family's phone, treat that phone as running an untrusted build and reinstall the verified release APK.

## Step 5. Consider rotating secrets

- **Cloudflare token.** Because it was a repository secret, any branch pusher could have read it. Create a new token (Workers Scripts: Edit and Account Settings: Read, restricted to your account), save it in `relay-prod`, then delete the old token in the Cloudflare dashboard.
- **Signing key.** If you have reason to think the keystore secrets were read, a new key means family phones must reinstall (see [signing.md](signing.md)); weigh this before rotating. At minimum review **Settings → Actions** run history for branches you do not recognise.

## Step 6. Rate limiting in front of the relay (owner action)

The relay now stores nothing for unauthenticated requests and caps buffered writes per family per day, but every request, including a rejected one, still counts against the Cloudflare Workers Free quotas (requests per day, Durable Object requests). Anyone who knows the public `workers.dev` address can exhaust them, and the relay then stops serving every family until the next 00:00 UTC reset. Code in this repository does not stop that: Cloudflare's per-IP Rate Limiting binding was **not** added, because the current documentation page for the binding does not say it is available on the Workers Free plan, and a deploy that failed because of it would stop production releases.

> **Warning: do not turn off `workers.dev`.** The address `https://parent-child-protection-system.dere2005317.workers.dev` (from `relay/endpoint.json`) is built into every installed APK. Disabling it, or deleting that Worker route, stops every installed app from reaching the relay. A custom domain with a WAF rule protects **only builds that point at the new domain**, and switching means releasing an app update and installing it on both phones of every family at the same time. Keep `workers.dev` on unless you are running that coordinated migration.

If this risk matters to you, the options are on your side of Cloudflare: monitor request counts in the dashboard and react; or, as part of a planned migration, attach a custom domain you own to the Worker, add a WAF rate-limiting rule for `/v1/rooms/*` in that domain's dashboard (needs a domain on Cloudflare, not a paid Workers plan), and ship an app version using the new domain. Moving to a paid plan raises the limits but does not remove the abuse possibility.
