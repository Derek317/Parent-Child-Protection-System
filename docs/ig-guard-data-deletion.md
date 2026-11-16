# IG Guard Data Deletion Instructions

Updated: **16 November 2026 (Hong Kong)**. These steps apply to IG Guard **4.9-beta.3**, terms revision **2026-11-16-v1**. Safe Guard handles privacy requests at [ppefyp2026@gmail.com](mailto:ppefyp2026@gmail.com).

## Declining initial terms is separate from deletion

Each phone must accept its own initial terms before collection and relay are enabled. All three confirmations start unticked. Decline or Android Back returns to Android Home without creating an acceptance receipt. Existing phone queues are held pending acceptance without being erased. Previously forwarded Cloudflare packets, parent archives and external copies are not deleted by declining and follow their own retention rules below.

Accepting saves an AES-GCM-encrypted local receipt in separate `ig_guard_consent` preferences: acceptance date, app version, terms revision and the exact terms' SHA-256. Normal launches and updates do not automatically repeat the initial page; Settings permits read-only review. Materially new scope needs separate consent, and expanded capture still requires the signed parent request and separate child-phone acknowledgment. Existing expanded acknowledgments remain; if Android permissions remain active, acceptance may resume prior capture and queued delivery. Otherwise Android access may need re-enabling. Deleting an archive record does not remove the consent receipt. Clearing app data or uninstalling on that phone removes the local receipt as well as its other local app data.

## Withdraw expanded capture and stop new capture

The current scope screen has no separate switch to withdraw expanded capture. To stop new capture, disable both Android capture permissions as described below.

To stop new capture, turn off both **Notification access** and the **Accessibility** service for IG Guard in Android Settings. You can find the relevant settings from the app’s **Permissions (權限)** tab under **Instagram notifications (Instagram 通知)** and **Advanced screen protection (進階畫面保護)**. Android labels and paths vary by phone. Turning off only one permission leaves the other capture path available.

Disabling both permissions does not delete pending ciphertext, delivered archives or external copies. Queued records may still be delivered or retried. Use the deletion steps below for existing copies.

## Delete captured records on the parent’s phone

- **One record.** Open **Alerts (提醒) → Conversation history (對話記錄)**, open the message’s full record, then tap **Delete this record (刪除此記錄)** and confirm.
- **All records for the current family.** In **Conversation history (對話記錄)**, return to the list, scroll to **Delete all evidence for this family (刪除此家庭的全部證據)**, and confirm. This removes the current family’s archived text, saved records and incomplete message fragments on that phone. Chart counts and replay-prevention identifiers may remain.
- **Archives for other families.** Open **Settings (設定) → Other families’ local archives (其他家庭的本機封存)** if that option appears, then confirm deletion.

Older unsent comment/story-reply observations remain labelled historical, with sending unconfirmed. Updating to 4.9-beta.1 does not automatically delete them. The same record/archive deletion controls apply.

## Remove IG Guard data from both phones

On each phone, open **Android Settings → Apps → IG Guard → Storage**, then choose **Clear storage** or **Clear data**. Uninstalling IG Guard also removes its local app data. Perform this separately on the teen’s phone and the parent’s phone to remove both the local delivery queue and the parent’s archive. This also removes pairing, settings and that phone's acceptance receipt. A subsequent setup requires initial acceptance again; this is different from a normal update retaining app data. Any text someone copied or shared outside the app must be removed wherever that copy is stored. Clearing a child's phone does not erase the parent's phone, and neither action alone requests relay erasure.

## Request help with data held by Safe Guard

Local deletion affects the phone where you perform it. Phone queues and parent archives have no fixed time expiry; queues can remain indefinitely while pending, and archives remain until deleted. A buffered Cloudflare relay packet is removed when consumed or expires seven days after first queuing. Duplicate submission does not extend that stored copy's expiry, but a phone holding an unconfirmed record can resubmit it after expiry. Room registration metadata (parent public key, family scope and child capability hash) has no automatic expiry or deletion route in the current relay code. Nonce replay entries are pruned on later parent authentication after their validity deadline. Provider logs/backups and support-correspondence retention have not been independently verified; the packet expiry does not establish their lifetimes. Clearing phone data does not send a relay erasure request. Messages in your Instagram account are managed through Instagram.

Email [ppefyp2026@gmail.com](mailto:ppefyp2026@gmail.com) with the subject **IG Guard data deletion request**. Say what you want removed, whether it concerns an app installation, the relay or previous support correspondence, and which IG Guard version you use if known. Teens and other people whose information appears in a record can also use this address. Safe Guard may need to verify who is making the request and which data it relates to. Keep passwords, pairing invitations, encryption keys and message content out of your initial email.

See the [IG Guard Privacy Policy](ig-guard-privacy-policy.md) for details about the different copies of your information.
