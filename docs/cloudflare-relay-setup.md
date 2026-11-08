# Cloudflare relay setup preparation

Current status: Cloudflare's free backend was selected. The [migration design](superpowers/specs/2026-11-08-cloudflare-relay-design.md) has passed its independent builder check and is awaiting written-design review. Backend/app implementation and deployment are not complete. There is no new APK or public relay URL yet.

## What the developer needs

1. Create or sign in to your own Cloudflare account at <https://dash.cloudflare.com/>.
2. Use **Workers Free**. The design uses **SQLite-backed Durable Objects**, supported on that plan. Paid Workers, a purchased domain and a Firebase project are not required.
3. In **Workers & Pages**, complete the account's `workers.dev` subdomain setup if requested. It provides the public HTTPS/WSS address for the later deployment.
4. Keep account login, API tokens and APK signing secrets in your own account/secret settings. The app will contain only the public relay endpoint, with each family authenticated separately by its existing pairing and signing identity.

No Cloudflare account is needed for the parents or children using the app. Both phones keep using IG Guard.

## What follows after implementation

The repository will include the backend source, pinned deployment tooling, configuration and runtime tests. The deployment guide will provide the exact test and deploy commands for those files once they exist. Deploy the reviewed backend to your free account and use its resulting public `https://…workers.dev` endpoint in the APK build configuration. That endpoint is safe to share; a Cloudflare token, signing key, pairing code or family secret is not needed in chat.

The endpoint must be tested before the signed release build replaces the current 4.8 APK. Both phones must use the updated build. For an existing secure V43 pairing, open the updated parent app once to register its relay room, then reconnect the updated child app. The design preserves the existing connection token, consent, archive and pending encrypted records.

## What remains to verify

The free account has shared compute, storage and request limits. The design targets around 100 families but must be tested with representative traffic and offline periods before capacity is claimed. A connected relay is separate from a signed parent-device delivery receipt. Phones must retain unconfirmed evidence and show queue/backoff state when either the service or Android cannot deliver it.

The cloud environment's earlier Firebase-documentation network draft does not configure or deploy Cloudflare. The actual chosen relay hostname will need outbound access for live integration testing here. No environment publication or backend deployment has been performed by preparing this document.
