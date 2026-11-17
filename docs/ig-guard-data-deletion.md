# IG Guard Data Deletion Instructions

Updated: **18 November 2026 (Hong Kong)**. These steps describe the **4.9-beta.4 security hardening source draft**, bundled Terms revision **2026-11-18-v2**. Publication, relay deployment and the [remaining integration checks](releases/4.9-beta.4.md) are separate. Safe Guard handles privacy requests at [ppefyp2026@gmail.com](mailto:ppefyp2026@gmail.com).

## Declining initial terms is separate from deletion

Each phone must accept its own initial terms before collection and relay are enabled. All three confirmations start unticked. Decline or Android Back returns to Android Home without creating an acceptance receipt. Existing phone queues are held pending acceptance within their deadlines. Previously forwarded Cloudflare packets, parent archives and external copies are not deleted by declining and follow their own retention rules below.

Accepting saves an AES-GCM-encrypted local receipt in separate `ig_guard_consent` preferences: acceptance date, app version, terms revision and the exact terms' SHA-256. Normal launches and updates do not automatically repeat the initial page; Settings permits read-only review and keeps the original receipt. Acceptance does not cancel a child's persistent stop choice. Expanded capture still requires the signed parent request and child-phone acknowledgment; resuming after withdrawal or widening scope requires renewed acknowledgment. Deleting an archive record does not erase the receipt. Clearing app data or uninstalling on that phone removes the receipt and its other local app data.

## Stop capture and pending local transfer

On the **child phone**, open **擷取範圍與停止** from the protection overview. Choose either:

- **停止擷取及傳送，並捨棄待傳內容**: persistently stop new capture and local pending transfer, and discard queued content for this family on this phone.
- **停止擷取及傳送，暫存待傳內容**: persistently stop both while holding pending content within its existing 72-hour deadline.

Stop clears the local expanded-scope acknowledgment. To restart, explicitly choose **清除舊待傳記錄並重新開始傳入警報**. This discards old pending content, including anything previously held, and restarts incoming alerts. It does not resend that queue or restore expanded capture; expansion needs fresh child acknowledgment.

The same screen has separate choices for sent outgoing messages and ordinary trusted-contact context within acknowledged expanded capture. Saving choices discards old-scope pending content; widening a choice requires renewed acknowledgment. Turning off ordinary trusted context does not suppress a qualifying safety alert from that contact. Composer drafts remain excluded.

The in-app stop does not revoke Android grants. To remove both capture paths, additionally disable **Notification access** and **Accessibility** for IG Guard in Android Settings. Shortcuts are in the app's **Permissions (權限)** tab; Android labels vary. Turning off only one leaves the other available. Permission revocation alone does not discard a queue or provide the in-app pending-transfer hold.

Content already forwarded or buffered by the relay may still arrive. Neither stop nor permission withdrawal recalls delivered archives, Instagram content or external copies. Use the separate deletion controls below. Locking the **parent UI** also does not stop background delivery or delete anything.

## Delete captured records on the parent’s phone

Authenticate with the parent phone's Android biometric/device credential first. Leaving the app relocks its UI while background reception continues.

- **One record.** Open **Alerts (提醒) → Conversation history (對話記錄)**, open the message’s full record, then tap **Delete this record (刪除此記錄)** and confirm.
- **All records for the current family.** In **Conversation history (對話記錄)**, return to the list, scroll to **Delete all evidence for this family (刪除此家庭的全部證據)**, and confirm. This removes the current family’s archived text, saved records and incomplete message fragments on that phone. Chart counts and replay-prevention identifiers may remain.
- **Archives for other families.** Open **Settings (設定) → Other families’ local archives (其他家庭的本機封存)** if that option appears, then confirm deletion.

Older unsent comment/story-reply observations remain labelled historical, with sending unconfirmed. They are subject to the same deletion controls and legacy-retention preview.

## Retention and older archives

New archive records are assigned a 30-day expiry by default, with shorter periods supported. In record details, **明確保留供檢視（一次，90 天）** sets a one-time 90-day preservation period from that action; it cannot repeatedly renew the deadline. The ordinary saved/bookmark flag does not extend expiry. Expired content is cleaned during app activity and archive processing; the app cannot promise immediate on-disk removal while stopped. Do not rely on a deadline alone as proof of forensic erasure.

Open **Settings → 保留期限與舊記錄預覽** to see the number of legacy records and how many would expire under the selected period. Legacy archives are not immediately deleted on upgrade. **預覽後，將期限套用至舊記錄**, followed by **確認採用**, adopts retention and can immediately remove old records. Cancel to leave them unchanged. Preserve an individual legacy record before adopting retention if it is needed for review. Pending outboxes and incomplete assemblies have 72-hour deadlines, including while held; existing pending rows receive a 72-hour transition deadline on schema migration. Retry and routine rotation do not renew deadlines. Content-free identifiers can remain to prevent replay of discarded, deleted or expired events.

## Remove IG Guard data from both phones

On each phone, open **Android Settings → Apps → IG Guard → Storage**, then choose **Clear storage** or **Clear data**. Uninstalling IG Guard also removes its local app data. Perform this separately on the teen’s phone and the parent’s phone to remove both the local delivery queue and the parent’s archive. This also removes pairing, settings and that phone's acceptance receipt. A subsequent setup requires initial acceptance again; this is different from a normal update retaining app data. Any text someone copied or shared outside the app must be removed wherever that copy is stored. Clearing a child's phone does not erase the parent's phone, and neither action alone requests relay erasure.

## Request help with data held by Safe Guard

Local deletion affects the phone where you perform it. Updated relay source gives packets 72-hour lifetimes from first acceptance, with mailbox ciphertext removed sooner on consumption. Duplicate submissions do not renew that deadline. Room registration retires after 90 days without qualifying activity. Authenticated parent retirement/purge removes the room's queued ciphertext and registration and closes its connections; a retirement marker remains for a further 90 days. Content-free packet replay metadata can remain for 90 days after packet expiry. These source changes require separate deployment verification. Clearing phone data or deleting an archive does not itself request relay purge, and routine key rotation must not be treated as erasure.

For suspected compromise, the authenticated parent screen **家長主密碼與金鑰 → 秘密可能外洩：重新安全配對** offers **先刪除舊中繼房間，再建立新配對**. It requests old-room retirement before creating fresh family secrets and signing identity. **無法連線：只建立新配對** requires separate confirmation and does not confirm relay deletion; old ciphertext may remain until expiry. Establish that both devices are safe, stop old child capture locally, and re-pair in person with full security-code verification. Fresh pairing keeps old local archives isolated and discards old pending content and trust settings; it does not erase delivered archives or copies already made. Use the archive deletion controls separately.

Provider logs, hosting regions, backups and support-correspondence retention have not been independently verified; app/relay deadlines do not establish their lifetimes. Neither logical deletion nor compaction guarantees forensic erasure of flash storage or previous copies. Messages in your Instagram account are managed through Instagram. Explicit app copies are marked sensitive and the app attempts to clear its still-current clipboard item after 60 seconds, but external clipboard history, selected-text copies and shared copies need separate handling.

Email [ppefyp2026@gmail.com](mailto:ppefyp2026@gmail.com) with the subject **IG Guard data deletion request**. Say what you want removed, whether it concerns an app installation, the relay or previous support correspondence, and which IG Guard version you use if known. Teens and other people whose information appears in a record can also use this address. Safe Guard may need to verify who is making the request and which data it relates to. Keep passwords, pairing invitations, encryption keys and message content out of your initial email.

See the [IG Guard Privacy Policy](ig-guard-privacy-policy.md) for details about the different copies of your information.
