# Chat reader repair

Approved intent: fix the released reader's wrong room name (`返回`), merged rooms, missing child messages, restricted capture area and chronology. Preserve consent, existing protection, encryption, release signing and unrelated UI.

1. Reproduce with realistic accessibility trees: toolbar Back/call controls before peer title; nested and description-only message rows; wide outgoing bubbles; text outside the old strip; more than 180 decorative nodes; shifted window bounds; composer descendants and unrelated windows excluded.
2. Select a contextual conversation header using semantic title/heading information and navigation-row alignment. Reject navigation controls and ambiguous/no-header frames instead of assigning a generic room. Read the exposed message region using structural message metadata and bubble bounds; preserve uncertain direction rather than invent authorship.
3. Preserve top-to-bottom snapshot order. Where overlapping screen windows establish relative chat order, retain a separate logical position and first-observation anchor without fabricating send times. Keep original source/capture/receipt timestamps, account/family scopes, deletion, migration and stable paging.
4. Make full versus incoming-only mode explicit. Do not enable broader capture without the existing signed policy and child acknowledgment. Keep mislabelled historical evidence, marked as unidentified; do not guess its original rooms.
5. Run focused regressions, one fresh scoped review, full Android/build/lint/workflow checks and native affected-view inspection. Document actual results and device limits. Keep changes local until verification is complete.

No automatic scrolling, hidden-history recovery, new permission, API migration, speculative model or central message storage is introduced.

Completed and included in version 4.8. See [repair report](../../chat-reader-repair-report.md) for reproduced causes, the single review pass, 174-test/build/lint results, setup steps and remaining device/cross-source limits.
