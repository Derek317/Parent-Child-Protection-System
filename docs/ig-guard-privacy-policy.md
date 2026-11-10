# IG Guard Privacy Policy

This notice applies to the IG Guard Android app, including the 4.8 Cloudflare relay hotfix. Safe Guard operates the project and can be reached at [ppefyp2026@gmail.com](mailto:ppefyp2026@gmail.com).

## What IG Guard does

IG Guard pairs a teen’s Android phone with a parent or legal guardian’s Android phone for private family use. When the Android permissions are enabled in Child Mode, the app processes Instagram notifications and selected text exposed on the phone’s screen. It uses that information to flag selected words and messages from contacts outside the family’s approved list, deliver captured records to the paired parent, and report whether protection and delivery are working. The app uses Android services for capture. Instagram passwords and Meta API access tokens are outside this flow.

In incoming-alert mode, the app sends qualifying incoming alerts. Expanded capture requires a signed request from the parent device and a separate acknowledgment on the teen’s device. It can include exposed incoming and outgoing direct messages, including ordinary conversations with approved contacts. A separate Instagram comment and story-reply check can capture text from an unsent draft and labels it separately. The direct-message typing box is excluded from that capture. Captures are limited to what the phone exposes, so records may be incomplete or have uncertain author or account labels.

## Information handled and who receives it

| Information | Use and handling |
| --- | --- |
| Captured text, Instagram account or conversation labels, message direction, available timestamps and alert reasons | The teen’s phone processes these locally, encrypts records selected for delivery, and sends them to the paired parent’s phone. The parent’s phone decrypts and stores received records in app-private storage. |
| Approved-contact settings, pairing and authentication material, and protection or device status | The phones use these to establish and authenticate the family pairing, apply the selected rules, and check whether the devices can communicate. The family decryption key stays with the paired devices. Pairing invitations contain a secret and should be shared only with the intended device owner. |
| Encrypted packets, room identifiers, delivery metadata and network connection information | Safe Guard’s relay runs on Cloudflare. Safe Guard can administer the relay’s ciphertext and routing data. Cloudflare processes traffic and connection information to operate the relay. The relay receives encrypted message contents without the family decryption key. |
| Device diagnostics and support correspondence | The app keeps limited diagnostics on the phone, which can include Instagram handles and connection or delivery status. If you email Safe Guard, the project receives your email address and the details you choose to send for the request. |

Android notification access and the accessibility service are optional system permissions. Turning either off stops the capture that depends on it. Parents can read delivered text on their paired device and can choose to copy it elsewhere. Those copies are then managed by whoever holds them.

## Storage, retention and deletion

- **Parent phone.** Captured records stay on the parent’s phone until the parent deletes them or the app’s local data is removed. Archive deletion can leave chart counts and record identifiers used to prevent deleted items from reappearing. Android app isolation and device security protect the local archive.
- **Teen’s phone.** Encrypted records awaiting confirmation can be kept locally and retried. Clearing IG Guard’s app storage removes the local queue, settings and pairing data from that device.
- **Cloudflare relay.** Each buffered encrypted packet expires seven days after it is first queued and can be removed sooner when consumed. A phone can resubmit a record it still holds. Room registration and security metadata, such as the parent public key and child capability hash, are stored separately from the seven-day packet queue. Contact Safe Guard for a request involving relay data.

For device controls and help with data held by the project, see the [Data Deletion Instructions](ig-guard-data-deletion.md).

## Contact and privacy requests

If your information appears in IG Guard, including in a captured conversation, email [ppefyp2026@gmail.com](mailto:ppefyp2026@gmail.com) to ask about access, correction, or deletion. Describe what you are asking for and whether it concerns a phone, the relay, or prior support correspondence. Safe Guard may need to verify your identity or authority before disclosing information or acting on a request. Share only what is necessary. Keep passwords, pairing invitations, encryption keys and private message text out of your initial email.
