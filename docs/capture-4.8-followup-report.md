# Instagram capture follow-up for 4.8

Status: independent review and local verification complete; signed publication pending.

## Findings

The user confirmed Cloudflare delivery works, child two-way capture is enabled, and Instagram's composer says `發送訊息..`. The existing signed policy/local-consent handshake was exercised through the actual queued encrypted envelopes and works. This repair preserves that handshake and the relay.

Reproduced reader faults:

- The generic account/inbox title could displace the peer in the DM toolbar. Filtering the child's own handle after selecting that title left no header at all. Header selection now excludes known self labels first and prefers the actual back-button row; ambiguous titles remain unassigned.
- Clickable semantic titles were rejected, and composers above 42% of the window were rejected even with a recognized ID. Named/semantic input boxes remain valid when raised by a keyboard. Additional localized placeholder forms are supported, although the user's reported placeholder was already recognized.
- Blank composer containers without an editable flag were omitted. Recognized composer containers and their descendants are excluded from sent-message collection, including unsent drafts.
- A content-change event within 450 ms was discarded permanently. Coalesced content/scroll updates now get a delayed scan. It checks the original family session and current foreground package, and is canceled on service teardown.
- Message text mentioning mute settings could trigger the settings-protection branch and abort capture. Confirmed DM composers distinguish those messages from the existing mute-settings protection.
- Notification conversation titles could name the child instead of the peer. Notification-local self display/person metadata now identifies own labels; a unique structured peer can replace a self title. Explicit group titles are preserved; ambiguous peers are not invented. Self metadata is not stored as an Instagram account handle.

## Scope and privacy

The Cloudflare relay, signing identity, archive schema, capture consent, filtering/review features and unrelated UI are unchanged. Captured records retain existing overlap handling and chronological ordering. Incoming-only mode remains incoming-only; two-way mode still requires the existing parent policy and explicit child consent.

The two-way disclosure now describes Cloudflare, temporary encrypted cloud buffering and metadata access instead of ntfy.sh. Existing connection diagnostics show content-free capture state and counts, so a missing header/composer or unavailable accessibility tree can be distinguished from delivery failure without sharing messages.

Previously mislabelled records remain as originally captured. Their true recipients cannot be reconstructed reliably, so this update does not silently rename or redistribute historical evidence.

## Review and validation

An independent read-only review checked context gating, drafts, toolbar ambiguity, session changes, notification self/peer metadata and chronology. A call control must not act as a Back anchor, and an unstructured notification must not invent incoming authorship from a known self title. Both are covered by targeted regressions.

The final Android suite contains **221 tests across 26 classes**, with zero failures, errors or skipped tests. The 30 Python workflow/configuration/signing checks and actionlint also passed. Debug and unsigned release assembly passed. Debug lint reported zero errors and 60 existing warnings. Signed artifact verification follows publication. Synthetic Android accessibility fixtures exercise the reproduced faults; no physical Samsung S26 or live Instagram accessibility tree was available here. Only loaded text Instagram exposes to Android can be captured. If capture is still absent, use the child's **設定 → 連接與診斷 → 畫面擷取** status; do not erase app data.

Implementation decisions: preserve the confirmed-working consent and relay algorithms; keep notification self display metadata local to the notification instead of treating it as an account handle; skip ambiguous peer labels rather than fabricate an identity; retain historical evidence instead of guessing its original conversation. The independent review's plain-notification finding was reproduced before the final correction. No remaining minor review findings were reported.
