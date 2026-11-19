# Scores-only family mode: automatic scoring on the child's phone

Design, 19 November 2026. Status: approved by Derek on 19 November 2026. Nothing is built.

Lines marked **(my call)** are choices Claude made. The rest is what Derek said or picked.

## Intent

The risk model needs the child's conversations. Asking the child to hand them over each time does not work: they refuse. Derek wants the process smooth and automatic, with as few manual steps as possible, without breaking Meta's terms or Hong Kong law, and with a relay that families feel safe using.

Derek decided that with AI scoring, **parents receive scores only and no message text**. That is a change to what the app does today, so this design covers more than scheduling.

## How the app works today

- The child's phone captures Instagram messages through notification access and the accessibility service, encrypts each one and sends it to the parent's phone over the relay.
- The parent's phone decrypts and archives the text. The parent reads it there.
- The child's phone keeps no readable copy: only content-free markers that stop the same message being sent twice (`docs/privacy-data-flow.md`).

So there is nothing on the child's phone to score yet. This design adds a small store there and stops sending text to the parent.

## Goals

1. After a one-time setup, scoring needs no action from child or parent.
2. Message text stays on the child's phone. The parent receives scores and content-free alerts.
3. The child knows analysis is on, at all times, and can pause it.
4. Reports travel over the app's existing end-to-end encrypted relay.

## Non-goals

- Changing how messages are read from Instagram. The existing capture is used as it is.
- A new relay, new encryption or a new pairing step.
- Scoring on the parent's phone, on a server, or message by message.
- Downloading the model. It is imported by hand (decided earlier).
- Group chats.

## Anti-goals

- Analysis running without the child having agreed, or without a visible sign that it is on.
- Any message text, quote or summary reaching the parent's phone in this mode.
- A pause the parent is not told about, or a pause the child cannot use.
- Describing this as approved by Meta or as legally cleared.
- Silently deleting the parent's existing archive.

## Approaches considered

How the child agrees:

| Approach | Trade-off |
| --- | --- |
| **Standing consent, automatic scoring (chosen).** The child agrees once, sees a permanent status, and can pause. | Smoothest, and honest with the child. The child is not asked each time. |
| Per-request approval. The parent sends an encrypted request; the child approves or denies each one. | More control for the child, but a child who refuses to upload will also tap "deny". |
| Instagram's own data export, imported by hand. | The only route clearly inside Meta's terms. Fully manual. |

What the parent receives:

| Approach | Trade-off |
| --- | --- |
| **Scores only (chosen).** Text stays on the child's phone, which stores recent messages and scores them. | Matches the privacy goal. Needs a new store on the child's phone and the model there. Parents lose the ability to read messages. |
| Keep forwarding text, score on the parent's phone. | Least work. Parents keep reading messages, so scoring adds no privacy. |
| Scores, plus text for flagged conversations only. | A middle path. The child must be told flagged messages are shown. |

Derek picked the first in each table.

## How it works

### Setup, once

1. **Model on the child's phone.** During setup the parent copies the model file onto the child's phone and imports it in the child app. The app checks size 1,107,408,736 bytes and SHA-256 `14f2176d569772bcc8e0e0dcd97e59f7ac60d8c4f6e1cb5f6a45159b1892c293` and refuses anything else. The file is too large for the relay (512 packets per mailbox), so it is not sent between phones **(my call)**.
2. **Parent switches on "Scores-only mode".** The switch is disabled until the child's phone has reported a valid model. It sends a signed, encrypted policy message, the same way the existing capture-scope policy is sent (`capture_policy` and `capture_ack` in `MainActivity.kt`).
3. **The child agrees once.** The child's phone shows a plain-language page: what is analysed, that it happens on this phone, what the parent receives, and that it can be paused. Nothing is stored or scored until the child accepts. Acceptance is a separate receipt like the existing Terms receipt (`AppConsent.kt`) and is acknowledged to the parent.
4. **If the child does not accept**, the parent sees "waiting for your child to agree". The parent cannot override it, and the app does not nag **(my call)**. The app's earlier mode keeps running until the child accepts, so declining does not switch protection off.

### Day to day

5. **Storing.** Each captured message from a one-to-one thread goes into an encrypted store on the child's phone, using the same device-bound keys as the existing local vault. The store keeps the latest 60 messages per thread and nothing older than 14 days **(my call)**. Threads whose kind is unknown are not stored or scored, and are counted in the report.
6. **Scoring.** While the phone is idle and charging, the app scores each thread that has new messages. A thread is scored on its latest 30 messages, and its score is kept as the highest seen in the last 14 days **(my call)**. If a run is interrupted, the thread in progress starts again next time. If no run has happened for 48 hours, the next run needs charging only, not idle **(my call)**.
7. **The report.** After each run the child's phone sends, per conversation: the contact's account label, a score from 0 to 100 or "needs review", and the time. No text. Reports use the existing relay and encryption and wait in the phone's queue if the relay is down.
8. **Content-free alerts.** The existing keyword and unapproved-contact rules keep running on the child's phone. In this mode their alerts carry the rule category, the account label and the time, with no message text **(my call)**.
9. **Parent side.** A list of conversations with band, score and date, and a notification when a conversation first reaches a score of 50. If no report has arrived for 72 hours the page says the scores are out of date.

### The child's controls

10. **Always visible.** The child app shows that analysis is on, when it last ran and how many conversations were scored.
11. **Pause.** Takes effect at once on the phone, including a run in progress, and stops storing as well as scoring **(my call)**. The parent is told as soon as the relay is reachable and sees "paused by your child" and when. Resuming needs no new consent.
12. **Withdraw.** Deletes the message store and any unsent scores on the child's phone and tells the parent. Scores the parent already received stay on the parent's phone. Turning the mode on again needs the consent page again.

What the child sees:

```
AI risk analysis                          ON
Runs on this phone while charging.
Your parent sees who you chat with and a risk
score. They do not see your messages.
Last run: today 03:12 · 14 conversations
[ Pause ]            [ What is analysed? ]
```

## Two things Derek should confirm

- **The report names the contact (my call).** A score is useless if the parent cannot tell which conversation it belongs to, and the child's phone and parent's phone may not share a thread identifier. So the report carries the contact's account label, as today's alerts already do. The child is told this on the consent page. The alternative is anonymous scores, which a parent cannot act on.
- **Existing families lose message reading.** Switching a paired family to this mode stops new text reaching the parent. The parent's existing archive stays until the parent deletes it. Both phones show what changes before the switch.

## Terms and law

This section records the position; it is not legal advice.

- **Meta.** The app does not use Meta's developer APIs. Instagram's Terms of Use restrict automated collection, and the README already states the app has no Meta approval. This design relies on the existing capture, so it neither fixes nor worsens that.
- **Google Play.** Monitoring through the accessibility service has its own policy rules. Not checked against current wording.
- **Hong Kong (Cap. 486).** Keeping text on the child's phone and sending scores collects less personal data than forwarding text. It also adds a new 14-day store on the child's phone, which the consent page and privacy policy must describe. Open points: the other person in each chat is also a data subject, and the family exemption in s52 covers the parent, not the developer.
- **Before release**, a Hong Kong privacy lawyer should confirm the Meta and Cap. 486 points. The Terms of Service, Privacy Policy and data-flow document need updating for this mode. The in-app wording must not claim approval or legal clearance.

## Constraints

- **Phones.** The model needs a phone with about 8 GB of memory. On a phone below that the mode is unavailable and the app says so **(my call)**.
- Android Views in code, no Compose; reuse `ui-res`. Traditional Chinese and English wording.
- **Scoring contract.** Already fixed by the research and not changed here. The model is Qwen3 1.7B with the v3 add-on, 4-bit, run with llama.cpp build b12503. The prompt is `render("qwen3", messages)` in `research/train_lora.py`, with `research/prompts/instruction-v1.txt` and `user_prompt` in `research/igrisk/scorers.py`; speakers are `child` and `other`, both sides included, oldest first. The five answer tokens and the score formula are `_from_probs` in the same file. "Needs review" means the model's most likely answer is `U`.

## Left to the builder

- The scheduler (Android's standard job scheduling with idle and charging conditions).
- Message type names and exact report fields, following the existing relay conventions.
- How the account label and thread kind are taken from the existing capture record.
- Wording of the consent and change-of-mode pages, reviewed by Derek before release.
- Tests: no storing or scoring before acceptance; pause stops both and reaches the parent; reports and alerts contain no message text; store limits of 60 messages and 14 days hold; withdrawal empties the store; a wrong model file is refused.

## Known risks

- **Parents can no longer read messages**, including in a real emergency. The score and the alert category are all they get.
- **A child who pauses often** gives the parent a "paused" signal instead of scores. That is intended.
- **Battery, heat and speed** on a real phone are unmeasured for this model.
- **Capture is incomplete** by the app's own account, so scores cover only what was captured.
- **The model was tested only on invented chats.** The score page must say so.
