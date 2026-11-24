# IG Guard Data Deletion Instructions

Updated: **24 November 2026 (Hong Kong)**. These steps describe **IG Guard 6.0**, bundled Terms revision **2026-11-24-v8**. See the [release notes and known limits](releases/6.0.md). Safe Guard handles privacy requests at [ppefyp2026@gmail.com](mailto:ppefyp2026@gmail.com).

## Declining initial terms is separate from deletion

Each phone must accept its own initial terms before collection and relay are enabled. All three confirmations start unticked. Decline or Android Back returns to Android Home without creating an acceptance receipt. Existing phone queues are held pending acceptance within their deadlines. Previously forwarded Cloudflare packets, parent archives and external copies are not deleted by declining and follow their own retention rules below.

Accepting saves an AES-GCM-encrypted local receipt in separate `ig_guard_consent` preferences: acceptance date, app version, terms revision and the exact terms' SHA-256. Normal launches and updates do not automatically repeat the initial page; Settings permits read-only review and keeps the original receipt. Acceptance does not cancel a child's persistent stop choice. Expanded capture still requires the signed parent request and child-phone acknowledgment; resuming after withdrawal or widening scope requires renewed acknowledgment. Deleting an archive record does not erase the receipt. Clearing app data or uninstalling on that phone removes the receipt and its other local app data.

## Stop capture and pending local transfer

On the **child phone**, open **擷取範圍與停止** (it first sends the parent one content-free notice, waiting up to about 6 seconds; nothing is sent afterwards) from the protection overview. Choose either:

- **停止擷取及傳送，並捨棄待傳內容**: persistently stop new capture and local pending transfer, and discard queued content for this family on this phone.
- **停止擷取及傳送，暫存待傳內容**: persistently stop both while holding pending content within its existing 72-hour deadline.

Stop clears the local expanded-scope acknowledgment. To restart, explicitly choose **清除舊待傳記錄並重新開始傳入警報**. This discards old pending content, including anything previously held, and restarts incoming alerts. It does not resend that queue or restore expanded capture; expansion needs fresh child acknowledgment.

The same screen has separate choices for sent outgoing messages and ordinary trusted-contact context within acknowledged expanded capture. Saving choices discards old-scope pending content; widening a choice requires renewed acknowledgment. Turning off ordinary trusted context does not suppress a qualifying safety alert from that contact. Composer drafts remain excluded.

The in-app stop does not revoke Android grants. To remove both capture paths, additionally disable **Notification access** and **Accessibility** for IG Guard in Android Settings. Shortcuts are in the app's **Permissions (權限)** tab; Android labels vary. Turning off only one leaves the other available. Permission revocation alone does not discard a queue or provide the in-app pending-transfer hold.

Content already forwarded or buffered by the relay may still arrive. Neither stop nor permission withdrawal recalls delivered archives, Instagram content or external copies. Use the separate deletion controls below. Locking the **parent UI** also does not stop background delivery or delete anything.

## AI risk analysis (scores-only mode): pause, withdraw and scores

In this mode the child's phone keeps chat text for analysis in its own encrypted store (latest 60 messages per conversation, nothing older than 14 days). Safe Guard and the relay never hold this text.

- **Pause** (child's phone, **AI 風險分析 / AI risk analysis**): storing and analysis stop at once and the parent is told. Pausing does not delete the stored messages.
- **Withdraw** (child's phone, same place): deletes the stored chat text on the child's phone and tells the parent. Alerts stay without message text until the parent switches the mode off.
- **The stored chat text is also deleted** when the parent switches the mode off, the model is removed, the collection method changes (when the child agrees to the new one), the child stops capture with **擷取範圍與停止**, the phone leaves the child role or joins another family, or the room is deleted.
- **Scores already sent stay on the parent's phone.** They hold no message text but do hold the other person's account label, which is personal data. They expire with the archive's retention period, counted from when they were scored. To delete them sooner, use **Delete all evidence for this family (刪除此家庭的全部證據)** below. Scores still queued on the child's phone are kept after a wipe, except when the family itself ends (role change, new family, room deletion).
- **Manual upload.** The Instagram file the teen imports is read but not copied into the app, so there is no copy of the file to delete inside IG Guard. The file itself stays wherever the teen saved it; delete it there if it is no longer needed. If the teen set up Instagram's optional repeating export to Google Drive or Dropbox (Meta's feature), the copies sit in the teen's own cloud drive: delete them there, and stop the repeating export in Instagram's own settings. The time of the last successful import, kept on the child phone for the weekly reminder, is deleted with the stored chat text. So is the time of a parent's request for a fresh export, if one was shown, with whether its card was closed; a successful import or the child's **知道了 / OK** on the card only closes the card, and the time stays until the wipe to limit repeated requests.
- **Capture hold.** If the child had agreed to manual upload and the parent switches the mode off, IG Guard does not read Instagram again until the child allows it on their own phone.

## Delete captured records on the parent’s phone

Authenticate with the parent phone's Android biometric/device credential first. Leaving the app relocks its UI while background reception continues.

- **One record.** Open **Alerts (提醒) → Conversation history (對話記錄)**, open the message’s full record, then tap **Delete this record (刪除此記錄)** and confirm.
- **All records for the current family.** In **Conversation history (對話記錄)**, return to the list, scroll to **Delete all evidence for this family (刪除此家庭的全部證據)**, and confirm. This removes the current family’s archived text, saved records, incomplete message fragments and AI risk scores on that phone. Chart counts and replay-prevention identifiers may remain. This also clears the phone's connection diagnostics log.
- **Archives for other families.** Open **Settings (設定) → Other families’ local archives (其他家庭的本機封存)** if that option appears, then confirm deletion.

Older unsent comment/story-reply observations remain labelled historical, with sending unconfirmed. They are subject to the same deletion controls and legacy-retention preview.

## Retention and older archives

New archive records are assigned a 30-day expiry by default, with shorter periods supported. In record details, **明確保留供檢視（一次，90 天）** sets a one-time 90-day preservation period from that action; it cannot repeatedly renew the deadline. The ordinary saved/bookmark flag does not extend expiry. Expired content is cleaned during app activity and archive processing; the app cannot promise immediate on-disk removal while stopped. Do not rely on a deadline alone as proof of forensic erasure.

Open **Settings → 保留期限與舊記錄預覽** to see the number of legacy records and how many would expire under the selected period. Legacy archives are not immediately deleted on upgrade. **預覽後，將期限套用至舊記錄**, followed by **確認採用**, adopts retention and can immediately remove old records. Cancel to leave them unchanged. Preserve an individual legacy record before adopting retention if it is needed for review. Pending outboxes and incomplete assemblies have 72-hour deadlines, including while held; existing pending rows receive a 72-hour transition deadline on schema migration. Retry and routine rotation do not renew deadlines. Content-free identifiers can remain to prevent replay of discarded, deleted or expired events.

## Remove IG Guard data from both phones

On each phone, open **Android Settings → Apps → IG Guard → Storage**, then choose **Clear storage** or **Clear data**. Uninstalling IG Guard also removes its local app data. Perform this separately on the teen’s phone and the parent’s phone to remove both the local delivery queue and the parent’s archive. This also removes pairing, settings and that phone's acceptance receipt, and on the child's phone the scores-only store, the child's agreement record and the app's copy of the model. A downloaded copy of the release APK or of the model file (for example in Downloads) is not removed by clearing app data; delete it separately. A subsequent setup requires initial acceptance again; this is different from a normal update retaining app data. Any text someone copied or shared outside the app must be removed wherever that copy is stored. Clearing a child's phone does not erase the parent's phone, and neither action alone requests relay erasure.

## Request help with data held by Safe Guard

Local deletion affects the phone where you perform it. The relay keeps buffered packets for up to seven days after first queuing, removing mailbox ciphertext sooner on consumption; duplicate submissions do not renew that deadline. Room registration metadata and any waiting packets are deleted when neither phone has connected for 90 consecutive days, or sooner when an authenticated parent deletes the room (see below). Clearing phone data or deleting an archive does not itself request relay deletion, and routine key rotation must not be treated as erasure.

For suspected compromise, the authenticated parent screen **家長主密碼與金鑰 → 秘密可能外洩：重新安全配對** creates fresh family secrets and a fresh signing identity. **嘗試刪除舊中繼房間，再建立新配對** first sends the child phone a signed release, which makes a child phone that verifies it remove the old room's key and unsent records, then asks the relay to delete the old room and reports whether the relay confirmed it. On confirmation the relay removes that room's registration metadata and waiting ciphertext at once, disconnects both phones and retires the room for good, keeping only a content-free marker with no expiry. A child phone that was offline is cleared when it is paired again. The parent's saved archive is not deleted. If the relay does not confirm, the existing pairing is kept and **只建立新配對** remains available; old ciphertext may then stay on the relay until it expires. Establish that both devices are safe, stop old child capture locally, and re-pair in person with the QR code, typing on the parent phone the 6 digits shown on the child phone. Fresh pairing keeps old local archives isolated and discards old pending content and trust settings; it does not erase delivered archives or copies already made. Use the archive deletion controls separately.

**What a reset or replaced pairing deletes about the child phone's signing key.** For a pairing made with the QR code, the child phone has a signing key in its Android Keystore and the parent phone keeps a copy of the matching public key. When the pairing is reset or replaced (including a remote reset, the parent phone leaving its role, or a fresh family created from the recovery screen), the child phone deletes its signing key when it receives the signed reset or release, and the parent phone removes its stored copy of the child's public key. When the child phone itself leaves the family by switching its role, it deletes its signing key and the parent phone receives a notice; the parent phone keeps its copy of the public key, which nothing can sign for any more, until a new QR pairing replaces it, the parent phone leaves its role, or its app data is cleared. Clearing a phone's app data or uninstalling also removes the key or the stored copy on that phone. The public key the relay stores for that family is not removed by the phones' own deletion; it goes with the room, when an authenticated parent deletes the room or after 90 days with no connection (see above). The public key holds no message content and no private key. A short-lived relay room used while a QR code is shown is retired when pairing completes or the code expires. The camera on the child phone is used only to read the QR code; no image is stored or sent, so there is no image to delete.

Provider logs, hosting regions, backups and support-correspondence retention have not been independently verified; app/relay deadlines do not establish their lifetimes. Neither logical deletion nor compaction guarantees forensic erasure of flash storage or previous copies. Messages in your Instagram account are managed through Instagram. Explicit app copies are marked sensitive and the app attempts to clear its still-current clipboard item after 60 seconds, but external clipboard history, selected-text copies and shared copies need separate handling.

Email [ppefyp2026@gmail.com](mailto:ppefyp2026@gmail.com) with the subject **IG Guard data deletion request**. Say what you want removed, whether it concerns an app installation, the relay or previous support correspondence, and which IG Guard version you use if known. Teens and other people whose information appears in a record can also use this address. Safe Guard may need to verify who is making the request and which data it relates to. Keep passwords, pairing invitations, encryption keys and message content out of your initial email.

See the [IG Guard Privacy Policy](ig-guard-privacy-policy.md) for details about the different copies of your information.
