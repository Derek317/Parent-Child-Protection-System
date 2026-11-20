# Data collection method for AI analysis: automatic capture or manual upload

Design, 20 November 2026. Decisions marked **Derek** are his, given in conversation on 20 November 2026. Lines marked **(my call)** are Claude's and can be overruled. Nothing here is legal advice, and no wording below may claim Meta approval or legal clearance.

## Intent

AI analysis (the scores-only family mode) needs the teen's conversations. Until now the only source was automatic capture through Notification access and Accessibility, which conflicts with Instagram's Terms of Use. Families must be able to choose a source that does not: the teen uploads Instagram's own data export by hand.

## Decisions

- **Derek:** two methods. *Automatic capture* (today's behaviour) and *manual upload*.
- **Derek:** the **parent selects** the method. The choice is sent to the child's phone through the relay, and the **child is asked for consent**. Nothing changes until the child agrees.
- **Derek:** choosing automatic capture shows a warning that it may breach Meta's terms and that the user accepts responsibility for that choice.
- **Derek:** with automatic capture, the AI uses the captured chats and never asks for an upload.
- **Derek:** messages are given to the model oldest first, newest last.
- **Derek:** when chat data is wiped, score reports may stay. Chat text must always be purged; a score report holds no message text. (A report does carry the contact's account label, which is still personal data.)
- **(my call)** In manual-upload method the app captures nothing from Instagram: no message is stored, scored, forwarded or alerted on from notifications or the screen. That is what makes it the route inside Meta's terms. Real-time keyword and unapproved-contact alerts are therefore unavailable in this method, and both phones say so.
- **(my call)** The parent still receives scores only, in both methods.

## How it works

### Choosing the method (parent)

- The method lives inside the existing scores-only mode setting. The existing signed `scores_mode_policy` command gains one field, `method`, with the value `capture` or `upload`. A command without the field means `capture`, so phones on an older build keep today's behaviour.
- Changing the method issues a new policy id, exactly as switching the mode on does, and requires fresh parent authentication like other sensitive parent actions.
- Selecting `capture` first shows the warning below with an unticked box. The command is not sent until the box is ticked.

### Consent (child)

- A new policy id puts the child in the existing "waiting for agreement" state. The existing scores-mode consent page states the method the parent chose, what it means and what the parent receives. For `capture` it repeats the warning in plain words.
- The stored consent receipt records the method. A method change always needs the consent page again.
- Until the child agrees to a new policy, the previously agreed method stays in effect. With no previous agreement, nothing is collected for analysis.
- The parent sees "waiting for your child to agree" and cannot override a refusal (existing behaviour).

### Automatic capture (`capture`)

Unchanged from the merged scores-only mode: captured one-to-one messages go into the child-side encrypted store and are scored on the phone. No upload control is shown.

### Manual upload (`upload`)

- Capture from Instagram is off (see decisions). The child app tells the teen they may now switch off Notification access and Accessibility; Android does not let the app revoke them.
- The child app shows an **Import Instagram export** action, using the system file picker (the same mechanism as the model import). No storage permission is requested.
- Accepted input: the file Instagram's "Download your information" produces, as a `.zip`, or a single conversation file from it. Both export formats are supported: HTML (`message_N.html`) and JSON (`message_N.json`).
- For each one-to-one conversation the import stores messages in the same child-side encrypted store that capture uses, so scoring, limits (latest 60 messages per conversation, nothing older than 14 days), reporting, pause and withdraw all behave identically. Group conversations are skipped and counted, as with capture.
- The uploaded file is read as a stream and never copied into the app's storage. Only message text, direction, time and the conversation label are kept.
- After an import the normal scoring run is scheduled. Importing the same export again must not duplicate messages.

### Order of messages

The prompt already lists a conversation's latest 30 messages oldest first. Imported messages are ordered by their own timestamps. A test pins this for both sources.

### Wipes

Every wipe trigger (mode off, withdraw, model removed, collection stopped, method change, role or family change, room deletion) deletes the chat text. Queued score reports are kept, except when the family itself ends (role change, new family, room deletion), where they could never be delivered.

## Warning wording (draft for Derek to read; to be used on the parent's selection sheet and, shortened, on the child's consent page)

> **自動擷取可能違反 Instagram 使用條款**
> 此方式會讀取子女手機上的 Instagram 通知及對話畫面。Instagram 的使用條款禁止未經其許可以自動方式取得資料。子女的 Instagram 帳戶可能因此被限制或停用。IG Guard 與 Meta 無關，亦未獲 Meta 批准。選擇此方式即表示你明白上述風險，並由你自行決定及承擔使用此方式的責任。如不接受，請改用「手動上載」。
>
> **Automatic capture may breach Instagram's Terms of Use**
> This method reads Instagram notifications and conversation screens on your child's phone. Instagram's Terms of Use prohibit collecting information in an automated way without its permission. Your child's Instagram account could be restricted or disabled as a result. IG Guard is independent of Meta and has no Meta approval. By choosing this method you confirm that you understand this risk and that the decision to use it, and the responsibility for it, are yours. If you do not accept this, choose Manual upload.

This wording informs the user. It does not by itself transfer the developer's own legal responsibility, and a Hong Kong lawyer should read it.

## Security requirements for the import

- Treat the file as hostile input: cap the number of entries read, the size of each entry and the total bytes read; reject path traversal in zip entry names; never write entries to disk; never execute or render HTML.
- Parse with bounded memory; a malformed or oversized file must fail with a plain message and store nothing from that file.
- Text from the file reaches only the encrypted store and the on-phone model. It is never logged, never sent to the relay and never sent to the parent.

## Non-goals

- Requesting the export from Instagram on the teen's behalf, or any automation of Instagram.
- Group conversations, media, voice messages.
- Changing the live Terms of Service, privacy policy or deletion guide. They need a new revision that Derek reads first; a draft is produced separately.
- Peer-to-peer transport or any relay change.

## Tests

- Parent: selecting `capture` without ticking the warning sends nothing; `upload` needs no warning; each change needs fresh authentication and issues a new policy id.
- Command validation: `method` accepted only as `capture` or `upload`; absent means `capture`.
- Child: no method takes effect before consent; the receipt records the method; a method change asks again.
- `upload`: a notification or screen observation stores nothing, scores nothing, sends nothing; the import action is offered. `capture`: no import action.
- Import: HTML and JSON samples (invented chats only) give the same stored messages; group threads are skipped and counted; re-import does not duplicate; oversized, malformed, traversal and too-many-entries files store nothing.
- Order: the prompt for an imported conversation and for a captured one lists messages oldest first.
- Wipes: chat text gone after every trigger; queued score reports kept except when the family ends.
