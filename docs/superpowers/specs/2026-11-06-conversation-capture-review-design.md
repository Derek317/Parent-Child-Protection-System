# Two-way captured conversations and parent review

**Status:** Approved by the user; implementation authorized locally.
**Date:** 6 November 2026, Hong Kong time.
**Baseline:** `f2a7a45` (app code `a5b87c6`). Work remains local; no publication is requested.

## Intent and decisions supplied by the user

The user now explicitly wants both the child's outgoing DMs and other people's incoming messages, including ordinary messages from trusted contacts. This supersedes the earlier outgoing-DM exclusion for the new mode. Parents remain the final decision makers. The user selected **full captured archive plus a separate review queue**, rather than alert-centred context only. Retention remains until explicit deletion, with the existing app-data/uninstall caveat.

The user additionally confirmed a DM-style conversation list grouped by conversation partner/object. Order conversations by latest activity and messages chronologically inside each conversation; use bounded older-message pages rather than one unbounded list.

The task is improving capture and organization, not replacing the app's family/security/dashboard features or adding an unverified personal-account Instagram API. The on-device Android capture approach remains because a supported personal/teen inbox API has not been established. No new backend, microphone, screenshot, photo-library access, restricted permission or cloud classifier is proposed.

## Approaches considered

1. **Full captured archive plus review queue — selected.** All exposed, captured conversation messages can reach the parent; ordinary records stay separate from actionable review items. Provides the requested two-way context with greater volume and a broader disclosed privacy scope.
2. **Alerts with nearby context only.** Smaller volume and less routine disclosure, but would not provide the ordinary trusted-contact history the user requested. Not selected.

The implementation uses the existing native Android Views, SQLite, encryption/outbox/receipt pipeline and local rules. Native libraries first; no new runtime dependency or speculative AI/scoring service.

## Capture guarantees and limits

- The archive contains **all accepted captures**, not a guaranteed complete Instagram transcript. Notifications may omit text; accessibility cannot see hidden history or an outgoing message never exposed on screen. No invented recovery of earlier missing content.
- Process all relevant message entries in a notification bundle, not just its last entry. Use structured sender/time metadata where available, with conservative fallback handling. Do not infer that a conversation title is the child's identity.
- In an identified DM thread, inspect visible message bubbles across the usable conversation area, rather than only the latest bottom-left bubble. Identify incoming/outgoing using available sender/accessibility metadata and layout evidence. Exclude composer text, timestamps, reaction controls, status labels, feed/reel content and unrelated apps. Do not auto-scroll or interact to fetch hidden history.
- Capture a displayed outgoing bubble; do not treat text merely typed into a DM composer as a sent message. A displayed bubble is not proof of server delivery/read status. Expose delivery status only when actually known.
- Direction is `incoming`, `outgoing` or `unknown`. If authorship/direction is ambiguous, preserve that uncertainty; do not label it confidently as the child or the conversation partner. In group threads, preserve the conversation label separately from the actual author; do not invent an individual author from the group title.
- Existing flagged-chat/mute UI actions are preserved where independently supported by incoming evidence. Capture a visible incoming snapshot before an existing navigation action where feasible; do not promise subsequent unseen content.
- The existing typed comment/story-reply path is a distinct source context, not a sent DM. New records must label it as a draft/reply observation with submission unverified. Ordinary such observations are context, not evidence that another person sent an unapproved message. Existing keyword safety actions remain; no new keystroke/draft capture is added for DMs.

### Duplicate and time handling

Message identity must not be just lowercase text plus a timeout: two genuinely repeated messages must not disappear solely because they have the same words. Prefer source message identifiers/timestamps and author/direction, with an occurrence discriminator for identical entries in one bundle. Use bounded per-conversation visible-sequence overlap for screen refreshes. Keep ingestion identities across ordinary process restarts where evidence supports stable identity.

Cross-source merging is allowed only with sufficient shared identity evidence. When uncertain, retain a possible duplicate with a source/uncertainty label rather than deleting a potentially distinct message. Do not claim perfect cross-source deduplication. Parent lists count captured records, not certified unique Instagram messages; grouped/throttled notifications prevent uncertainty producing repeated interruptions.

Keep separate fields for known source message time, child capture time and parent receipt time. Unknown message time remains unknown; it must not be displayed as if capture time were the actual send time. Timeline ordering uses a documented fallback and shows which time was used.

## Record and delivery model

A focused helper may normalize capture, identity and rule signals; avoid another framework or multiple interchangeable adapters. Keep the original captured text and existing stable event ID.

New conversation records carry:

- Schema version, family/account scope and conservative conversation key/label.
- Author label/identifier when exposed, direction and attribution uncertainty.
- Source context (`notification`, `visible_dm`, `comment_story_draft`) and available source identity.
- Original text, source time if known, capture time and parent receipt time.
- Contact approval snapshot and structured review reasons with matched field/phrase/rule identifiers where applicable.

Use an explicitly supported new wire record type, e.g. `captured_message`, while retaining legacy `alert` handling. Generalize existing encrypted durable framing, reassembly and signed receipts to a narrow allowlist of both content types. Do not allow fragments to reassemble privileged control commands. Ordinary conversation records must not fall through into unknown-`other` alert statistics or automatic alert notifications.

Full conversation capture increases relay volume. Persist accepted ordinary captures before upload, prioritize actionable/device work ahead of a large ordinary backlog, and use bounded batches/backoff without silently dropping context or permanently starving it. Expose pending, throttled and storage/delivery-error states. Do not assume a particular public-provider quota or promise instant delivery. The builder may select the smallest fair scheduling/batch mechanism compatible with existing ciphertext/framing/receipt guarantees; no new infrastructure is implied.

New full-capture mode requires updated parent and child support. Use a narrowly validated, existing-parent-key-signed capture-policy/capability exchange and child acknowledgment. Before expanded capture starts, the child phone presents and locally acknowledges the new scope: incoming and outgoing visible messages, trusted contacts, draft/reply observations, parent recipient and local retention. This is a product transparency control, not a claim that a checkbox establishes legal consent.

Until support/local acknowledgment is established, retain existing incoming-alert behavior and display that full conversation capture is not enabled. An old app or unsigned/wrong-family policy must not silently enable broader capture. Both phones need updating. No embedded app/API secret and no developer plaintext receiver are introduced.

## Classification: describe the evidence, not a verdict

Keep ordinary capture separate from actionable alerts:

1. **Conversation context:** ordinary incoming/outgoing messages, including trusted contacts. Stored/searchable without generating a warning per message.
2. **Keyword matches:** labels such as sexual language, image requests or secrecy from parents derived from the existing rule vocabulary. Show the matched phrase and whether it occurred in the message or sender label. These are rule matches, not confirmation of grooming or the sender's identity/age/intent. One message may have several descriptive tags but counts as at most one actionable event.
3. **Unapproved incoming contact:** preserve ordinary incoming alerts for contacts outside the parent-approved list. Approval is not a claim of safety. An ordinary outgoing message provides context rather than being falsely attributed as an incoming contact alert.
4. **Follow-up from an already flagged contact:** distinguish this from a fresh keyword match when the new incoming text contains no match. Preserve relevant monitoring; do not invent a matched phrase.
5. **Device and delivery:** permission/connection/unlock/offline observations remain separate from conversation content. Missed reporting is not proof of uninstalling or wrongdoing.

Keywords in a child's outgoing message may create a parent review item attributed to the child, but must not add the other person to the offender list or trigger partner-specific blocking solely on that child's text. Unknown authorship can be reviewed but cannot justify such attribution-dependent actions. Retain the existing incoming keyword/approved-list safety behavior where the new scope does not require a change.

## Parent workflow

Preserve the existing status header, four-section navigation, light/dark styling, charts, family controls, advice, search, saved records and bounded 30-item paging. Within the existing evidence section provide three clear destinations:

- **Needs review:** actionable records grouped by contact/conversation, with reason, latest relevant time, new-item count and a short exact-text preview. Keyword matches are prioritized over ordinary unapproved-contact items; no fabricated probability/severity score.
- **Conversations:** all captured context by account/contact, opening a two-way timeline with clear author/direction/source/time and matching highlights. Unknown attribution and draft/reply observations have explicit labels. Users can filter/search without loading an endless list.
- **Device & delivery:** system events and connection/delivery state, keeping technical notices out of the message timeline by default.

Each actionable event has `new`, `follow_up` or `reviewed` state, independent of bookmark status. The contact queue aggregates these event states. Marking existing items reviewed never reviews future captures; a new actionable event reappears. Review state is local to the parent archive and is not communicated as a finding against the child/sender. No separate case-management/notes system is required in this iteration.

Ordinary messages do not inflate alert charts, raise help sheets or generate per-message heads-up notifications. Multiple descriptive tags do not multiply chart counts. Use one primary existing chart category per actionable event; retain historical totals rather than reinterpreting old data. Parent review actions do not remove evidence or chart totals. Normal records never trigger automatic advice. Existing keyword advice remains available and acknowledgment semantics are preserved.

For background alerts, preserve generic lock-screen text and family-scoped deep links. Group/throttle bursts by conversation; a newly observed keyword may elevate the grouped notification. Never combine different family/account scopes.

## Storage, upgrade and boundaries

Upgrade the existing SQLite schema explicitly and transactionally; retain full text, bookmarks, existing help acknowledgment, tombstones, statistics, old-family isolation, pending encrypted work and key-rotation recovery. Add only the metadata/indexes needed for scoped direction/type/review queries. Legacy records default to unknown direction/source precision; never reconstruct a child's side or claim old keyword details that were not retained. Migration must not produce a new advice/notification storm.

The parent still retains records until deletion. Deletion does not promise remote-cache/export erasure. Explain volume/storage implications of collecting ordinary conversations. Keep UI queries off the main thread and preserve stale-family/result guards. Bound capture traversal/batches and transport sizes; expose deferred/incomplete/error state instead of silently claiming full capture. Do not change signing, version, role security, cryptography or unrelated UX.

## Verification required after design approval

- Notification bundle: multiple incoming entries, interleaved self/outgoing entries, trusted ordinary content, null sender, group labels, repeated identical texts and genuine distinct timestamps; no sender inferred from body prefix.
- Screen snapshots: both bubble directions, composer/status exclusion, content outside the former bottom strip, refresh/scroll overlap, repeated words, ambiguous/group attribution and unrelated screens.
- Classification: known keyword fixtures maintain incoming behavior; ordinary records remain context; outgoing keywords do not falsely flag the partner; previously flagged follow-up does not invent a new match; multi-tag records count once.
- Protocol: new record framing/reassembly/receipt persistence, malformed payload rejection, signed policy validation and local acknowledgment, old-version fallback, family/account switching, restart/rotation and scope separation.
- Load/failure: burst capture, ordinary backlog versus actionable/device priority, simulated rate limiting, bounded work, eventual ordinary delivery and honest storage/error reporting; no provider quota is assumed.
- Archive/UI: upgrade with old records/outbox, two-way timeline/time fallbacks, all filters and pagination, parent review states/new arrivals, saved records/deletion, chart/noise separation, private grouped notifications and advice behavior.
- Run meaningful RED/GREEN tests, full Android regression suite, build/lint and native light/dark inspection for the affected screens. Synthetic records belong only in tests/previews. Physical Samsung S26/Instagram validation is still required for actual exposed UI layouts, keyboard/font/orientation changes and background behavior; mocks cannot establish complete capture.

## Builder discretion and non-goals

The builder can choose helper/file names, query/index details, bounded overlap mechanics and native control placement within these behaviors. No API-only migration, automated scrolling, cloud AI, image/voice classifier, invented grooming diagnosis, central content database, full-history guarantee or GitHub push belongs to this change. The design remains a proposal until the user reviews it.

## Independent builder check

A fresh Android/Kotlin reviewer checked the written design against the existing implementation and reported **nothing important left** to clarify before planning. The reviewer identified relay-volume handling as an implementation concern rather than a missing user decision; the delivery-priority/backpressure requirements above address it. No code was implemented during this check.
