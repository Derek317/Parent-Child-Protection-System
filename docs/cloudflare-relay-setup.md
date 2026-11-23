# Cloudflare relay setup preparation

The [migration design](superpowers/specs/2026-11-08-cloudflare-relay-design.md) is approved and the backend and Android integration are implemented. Deployment and signed publication are gated by the workflow's tests and live verification. The configured Worker is **parent-child-protection-system** at <https://parent-child-protection-system.dere2005317.workers.dev>.

## What the developer needs

1. Create or sign in to your own Cloudflare account at <https://dash.cloudflare.com/>.
2. Use **Workers Free**. The design uses **SQLite-backed Durable Objects**, supported on that plan. Paid Workers, a purchased domain and a Firebase project are not required.
3. In **Workers & Pages**, use the `dere2005317.workers.dev` subdomain and the existing `parent-child-protection-system` Worker for this project.
4. Keep account login, API tokens and APK signing secrets in your own account/secret settings. The app will contain only the public relay endpoint, with each family authenticated separately by its existing pairing and signing identity.

No Cloudflare account is needed for the parents or children using the app. Both phones keep using IG Guard.

## Deploy through GitHub Actions

1. Create a scoped Cloudflare API token with **Account → Workers Scripts → Edit** and **Account → Account Settings → Read**, limited to your account.
2. Create a GitHub environment named **`relay-prod`** (restricted to `main` and `4.8-hotfix.*` tags, with a required reviewer) and save the token there as secret **CLOUDFLARE_API_TOKEN**; set **CLOUDFLARE_ACCOUNT_ID** as a variable of that environment (a secret of that name is also supported). Do not leave the token as a repository secret: any branch pusher could then read it. Steps are in [ci-secrets-hardening.md](ci-secrets-hardening.md). The four APK signing secrets belong in the separate `release` environment.
3. On every push the workflow tests the backend (job `relay-test`). **Only** a push to `main` or a `4.8-hotfix.N` tag also runs the `relay-deploy` job, which deploys to that account (production, the live endpoint) and then checks the public endpoint and a synthetic authenticated offline exchange with a signed parent receipt. Other branches and tags never deploy and never contact the production endpoint. If deployment credentials are absent, the deploy job checks an already-deployed endpoint instead. A failed test or deployment blocks the APK build. Release tags other than `4.8-hotfix.N` do not deploy.
4. The public endpoint is tracked in [relay/endpoint.json](../relay/endpoint.json). An optional repository variable **RELAY_BASE_URL** can override it. It is public configuration, not a secret. Both Android roles use the same configured endpoint.
5. A `4.8-hotfix.N` source tag updates the existing 4.8 release only when the signed APK passes verification. It does not move the original `4.8` tag. Failed hotfix builds leave the existing release notes and assets intact.

The signed-APK verification runs only on `main` and release tags. It verifies the existing replacement signing certificate, package `app.family.igguard`, version name `5.2` (the value hard-coded in the workflow's verify step; update it with each release), a version code greater than 150 and a non-debuggable release. It never reads the private signing key outside the temporary signing step.

## Deploy from Codespaces instead

After pulling this source, use Node.js 24 and run:

```bash
cd relay
npm ci
npm test
npx wrangler login
npm run deploy
npm run smoke
```

Complete Cloudflare's browser login in your own account. The reviewed `wrangler.jsonc` declares SQLite-backed Durable Objects supported on Workers Free. The smoke command uses a new synthetic family and temporary test signing identity, not a user's pairing or messages. Neither account credentials nor family keys are printed.

Both phones must use the updated signed build. For an existing secure V43 pairing, open the updated parent app once to register its relay room, then reconnect the updated child app. The migration preserves the existing connection token, consent, archive and pending encrypted records. Pairings without a pinned parent key need the normal adult-authorized invitation upgrade.

## Abuse limits

The relay creates a family's storage only after a valid parent signature, so unauthenticated requests to made-up room addresses write nothing. Each room may buffer at most 2,000 ordinary-data frames per UTC day; beyond that the relay answers `queue_full` with a 30-second retry time for that data frame, which the app already treats as "keep this record on the phone and carry on". The budget applies only to ordinary data: urgent alerts, controls and receipts are never refused by it, and they are not affected by the data budget in either direction. (The data budget itself is one counter per room shared by both directions; a refused data frame is benign because the app keeps the record and retries.) They remain limited only by the existing 512-row mailbox per direction (answered with the existing 30-second `queue_full`). There is no per-IP rate limit in front of the Worker. Because every request counts against the shared Cloudflare Free quotas, a third party who knows the public address can still exhaust them and interrupt every family until the 00:00 UTC reset. See [ci-secrets-hardening.md](ci-secrets-hardening.md) for the owner-side mitigation.

## What remains to verify

The free account has shared compute, storage and request limits. The design targets around 100 families but must be tested with representative traffic and offline periods before capacity is claimed. A connected relay is separate from a signed parent-device delivery receipt. Phones must retain unconfirmed evidence and show queue/backoff state when either the service or Android cannot deliver it.

The cloud environment's network draft includes the exact Worker host, but saving that draft does not apply or publish it. Direct checks here require that access to be activated. The deployment workflow performs its own live verification before APK publication. A Cloudflare connector can also be used if it exposes account/deployment actions; selecting it alone does not establish that those tools are available.
