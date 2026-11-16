# IG Guard Privacy Policy

Updated: **16 November 2026 (Hong Kong)**. This notice describes IG Guard **4.9-beta.2**. Safe Guard operates the project and can be reached at [ppefyp2026@gmail.com](mailto:ppefyp2026@gmail.com).

## What IG Guard does

IG Guard pairs a teen’s Android phone with a parent or legal guardian’s Android phone for private family use. When the Android permissions are enabled in Child Mode, the app processes Instagram notifications and selected text exposed on the phone’s screen. It uses that information to flag selected words and messages from contacts outside the family’s approved list, deliver captured records to the paired parent, and report whether protection and delivery are working. The app uses Android services for capture. Instagram passwords and Meta API access tokens are outside this flow.

Use is limited to Instagram accounts meeting Instagram's applicable minimum age: at least 13, or higher where required. IG Guard is independent of Meta and has no Meta approval. Official Instagram API and portability integrations are not implemented.

In incoming-alert mode, the app sends qualifying incoming alerts. Expanded two-way capture requires a signed request from the parent device and a separate acknowledgment on the teen’s device. It can include exposed incoming and outgoing direct messages, including ordinary conversations with approved contacts. Version 4.9-beta.1 removes the typed comment/story-reply sender and does not read DM composer bodies to filter outgoing messages. Screen capture excludes composer contents and descendants; layout/hint metadata may identify a DM screen. Captures depend on the notifications and view Instagram exposes, so records may be incomplete or have uncertain author or account labels. There is no guarantee of 100% capture or protection.

Older builds could forward unsent comment/story-reply drafts. Those retained records remain labelled historical, with sending unconfirmed. The update does not automatically delete them; normal archive deletion controls still apply.

## Information handled and who receives it

| Information | Use and handling |
| --- | --- |
| Captured text, Instagram account or conversation labels, message direction, available timestamps and alert reasons | The teen’s phone processes these locally, encrypts records selected for delivery, and sends them to the paired parent’s phone. The parent’s phone decrypts and stores received records in app-private storage. |
| Approved-contact settings, pairing and authentication material, and protection or device status | The phones use these to establish and authenticate the family pairing, apply the selected rules, and check whether the devices can communicate. The family decryption key stays with the paired devices. Pairing invitations contain a secret and should be shared only with the intended device owner. |
| Encrypted packets, room identifiers, delivery metadata and network connection information | Safe Guard’s relay runs on Cloudflare. Safe Guard can administer the relay’s ciphertext and routing data. Cloudflare processes traffic and connection information to operate the relay. The relay receives encrypted message contents without the family decryption key. |
| Device diagnostics and support correspondence | The app keeps limited diagnostics on the phone, which can include Instagram handles and connection or delivery status. If you email Safe Guard, the project receives your email address and the details you choose to send for the request. |

To stop new capture, turn off both IG Guard **Notification access** and **Accessibility** in Android Settings on the teen's phone. Turning off only one leaves the other capture path available. The scope screen currently has no separate switch to withdraw expanded capture. Disabling access does not erase pending ciphertext or delivered records; existing queues may still be delivered or retried. Parents can read delivered text and choose to copy it elsewhere. Those copies are managed by whoever holds them.

## Storage, retention and deletion

- **Parent phone.** Captured records stay on the parent’s phone until the parent deletes them or the app’s local data is removed. Archive deletion can leave chart counts and record identifiers used to prevent deleted items from reappearing. Captured message content and account/conversation labels are encrypted at rest with a device-bound Android Keystore key; keyed lookup indexes support grouping. Timestamps, categories, opaque IDs and workflow flags remain visible metadata. Preferences, including the parent password, family secret and encoded control-signing key, are also encrypted at rest. The app decrypts data during authorised use. Android app isolation and device security remain necessary. Existing records migrate when opened; the update does not erase copies made earlier. Losing the device's Keystore keys can make local data unreadable, and the app does not silently reset it.
- **Teen’s phone.** Encrypted records awaiting confirmation can be kept locally and retried. Clearing IG Guard’s app storage removes the local queue, settings and pairing data from that device.
- **Cloudflare relay.** Each buffered encrypted packet expires seven days after it is first queued and is removed sooner when consumed. Duplicate submissions do not extend that stored copy's expiry, but a phone can resubmit a retained record after expiry. Room registration metadata (parent public key, family scope and child capability hash) has no automatic expiry or deletion route in the current relay code. Nonce replay entries have a validity deadline and are pruned on subsequent parent authentication, rather than by a timed metadata purge. Contact Safe Guard for a request involving relay data; local deletion does not send a relay erasure request.
- **Provider and support records.** Cloudflare logging, hosting regions and backups have not been independently verified. No fixed support-correspondence retention period has been verified. The seven-day packet expiry does not establish those records' lifetimes.

For device controls and help with data held by the project, see the [Data Deletion Instructions](ig-guard-data-deletion.md).

## Contact and privacy requests

If your information appears in IG Guard, including in a captured conversation, email [ppefyp2026@gmail.com](mailto:ppefyp2026@gmail.com) to ask about access, correction, or deletion. Describe what you are asking for and whether it concerns a phone, the relay, or prior support correspondence. Safe Guard may need to verify your identity or authority before disclosing information or acting on a request. Share only what is necessary. Keep passwords, pairing invitations, encryption keys and private message text out of your initial email.
