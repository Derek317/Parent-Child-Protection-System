# Writing guide for synthetic chats (version 1)

These chats are synthetic research fixtures for a child-safety classifier. They are invented. Never base one on a real child's conversation, and never use real names of people, schools, businesses or accounts.

## What one family is

One setting from the catalogue, written as **15 chats**: five endings × three languages.

Each chat is a window of an Instagram direct-message thread between two people only:

- `child`: the young person whose phone this is (age given in the setting).
- `other`: the other person (adult or peer, as the setting says).

Every message is `{"speaker": "child" | "other", "text": "..."}`. Text only. A photo, voice note, sticker, video or reaction appears as a placeholder message such as `[photo]`, `[voice note]`, `[sticker]`, `[video]`, `[liked a message]`. Use a placeholder in roughly one chat in four.

## The five endings

The level describes what the **other** person visibly does, above all how they react to hesitation, a "no" or a stated limit.

| `ending` | `level` | What the window shows |
| --- | --- | --- |
| `low` | 0 | Ordinary interaction. Limits are respected. No warning sign. |
| `potential` | 1 | Exactly one ambiguous warning sign (conditional attention, an overly personal remark, a casual "keep this between us", an offer that is a little too generous) with **no** pressure and **no** threat. If the child hesitates, the other person lets it go. |
| `moderate` | 2 | Repeated pressure after a refusal, **or** a reward or favour made conditional on something the child is unsure about, **or** a visible breach of a private limit (shares or keeps something after being asked not to, turns up after being told no). |
| `high` | 3 | Explicit coercion or a threat: to share something private, to tell others, to wreck the child's standing, to cut them off unless they comply, or the child credibly says serious harm has already happened. |
| `unresolved` | null | The window stops before the outcome can be known. An ask or a limit has just appeared, or is about to, and the reply that would settle it is not in the window. It must be impossible to tell which of the other four it will become. |

## Rules that keep the data honest

1. **Same setting, different paths.** The five endings of a family share the setting and the people. They must not be one chat with a different last line. Let them diverge from an early point. Openings may differ.
2. **No keyword shortcuts.** A model must not be able to pass by spotting words.
   - `low` chats regularly contain the surface features of risk, handled properly: a photo that is asked for and declined with "no worries", a surprise that is kept secret from a friend, a gift with no strings, a move to another app for an ordinary reason, a plan to meet that includes a parent or a public group.
   - `moderate` and `high` chats often avoid obvious words. Pressure can be polite, jokey, guilt-tripping or framed as care. Not every threat says "or else".
3. **Length is not a signal.** 6 to 30 messages per chat. Within one family vary the lengths, and do not make riskier chats systematically longer or shorter. `unresolved` chats also have at least 6 messages; they are cut off, not short. The validator enforces a spread: among the three language versions of **each** ending, at least one chat has 11 or fewer messages and at least one has 12 or more. So every family has a long `potential` chat, a long `unresolved` chat, a short `moderate` chat and a short `high` chat.
4. **The child varies.** Across families the child sometimes refuses firmly, sometimes wavers, sometimes goes along. The level depends on the other person's conduct, not on whether the child resists. In `high` chats the child is not always frightened in words.
5. **Adults and peers both appear as the risky party**, as the setting says. Adults tend to write fuller sentences; peers write like teens.
6. **Non-graphic, always.** No sexual descriptions or explicit words, no descriptions of bodies, no violence detail. Private material is referred to vaguely: "that photo", "the video from the call", "what you told me". Show the pattern (secrecy, isolation, gifts with conditions, moving to private channels, pressure after a refusal, threats to share something private), never the content.
7. **Only the chat.** No narration, no stage directions, no timestamps, no labels or level words inside messages, no explanation of what is happening.
8. **No identifying detail.** No surnames, usernames, phone numbers, addresses, real school or shop names. A first name or nickname is fine and optional.

## The three languages

Write each language version on its own, as a native writer would. Same plot beats, not a sentence-by-sentence translation. Message counts and details may differ between versions.

- **`en`**: casual English as teens (and the adults who message them) type on Instagram. Lower case, short lines and abbreviations where natural.
- **`zh-Hant-yue`**: **written colloquial Hong Kong Cantonese in Traditional Chinese characters**, the way Hong Kong teens type in DMs. Use Cantonese words and particles (係, 唔, 嘅, 咗, 喺, 啲, 嘢, 佢, 冇, 咁, 㗎, 喎, 啦, 囉, 咩, 嘅啫). Do **not** write standard written Chinese or Mandarin phrasing (不是, 的, 了, 在, 什麼, 沒有, 他們 are wrong here; write 唔係, 嘅, 咗, 喺, 咩, 冇, 佢哋). No Simplified characters. Short lines, sparse punctuation. An occasional English word is normal (ok, sorry, IG, story), but the chat is Cantonese.
- **`mixed`**: Hong Kong code-switching. Mostly Cantonese sentences with English words and phrases dropped in the way Hong Kong students do ("聽日 present 你 ready 未", "我 send 咗 個 link 畀你"), and some whole messages in English. Both people may switch. It should not read as alternating translated lines. The English that gets mixed in is the English Hong Kong students really use: school, game, app and hobby words, short reactions (ok, sure, nice, sorry, thanks), and set phrases. Basic function words stay Cantonese: write 諗下, 聽晚, 朋友, 得唔得, 係, not "think 下", "聽日 night", "可以嗎", "對". If a line sounds like nobody would say it aloud in a Hong Kong school corridor, rewrite it.

## File format

One file per family: `<setting_id>.jsonl`, UTF-8, 15 lines, one JSON object per line:

```json
{"id": "s017-moderate-yue", "family": "s017", "language": "zh-Hant-yue", "ending": "moderate", "level": 2, "other_role": "adult", "messages": [{"speaker": "other", "text": "..."}, {"speaker": "child", "text": "..."}]}
```

- `id` is `<setting_id>-<ending>-<en|yue|mixed>`.
- `language` is `en`, `zh-Hant-yue` or `mixed`.
- `level` is 0, 1, 2, 3, or `null` for `unresolved`.
- Both speakers must appear in every chat.
