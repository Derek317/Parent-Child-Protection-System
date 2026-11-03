# Permission setup and dashboard design

Approved in chat: retain one APK and full child capabilities. Device: Samsung S26; HSBC warns only in child mode. Exact Android version and Play Protect message remain unknown. Uploaded Kotlin and current main were verified identical (208fe426c5f70f69f04903da0cd4d8fb9f9945ec).

## Approach

Keep native Android Views and the existing single-file Kotlin build. Accessibility remains necessary for foreground Instagram reading and anti-mute protection. Notification access is separate from POST_NOTIFICATIONS and can also be restricted. No public API silently grants either special access. Restrict child services to child mode; explain the capabilities and system-owned approval steps. Do not disguise monitoring, impersonate an installer or disable Play Protect. Neither bank nor Play Protect acceptance can be guaranteed by source changes.

Use an explicit checklist with Android Settings links and conditional Samsung restricted-settings help: first try the toggle; if blocked, follow the device's App Info instructions, then retry. Remove permission-navigation calls that grant temporary unlock. Parent notification prompts should be user initiated. Preserve pairing, allowlist, encryption, interception and unlock functions.

## Interface

Fixed compact status strip outside the content scroller. Parent menu: Overview, Alerts, Family, Settings. Child menu: Overview, Permissions, Unlock, Settings. Preserve screen and editable drafts on refresh/rotation. Keep lists scrollable for small screens/large fonts. Use dp spacing, 48dp targets, explicit labels, and tested 4.5:1 text contrast. Richer red, restrained amber, distinct button foreground tokens.

## Statistics

Today and six preceding calendar days; bar totals by safety-alert type and daily total line. Exclude routine heartbeats, pairing and test events, label exclusions. Store day/type counts independently of the 60-message inbox and deduplicate replay persistently for the reporting window. Include locally generated offline alerts. Migrate available history once, label incomplete historical coverage, zero-fill days, and keep statistics when clearing inbox. Statistics reset preserves replay deduplication. Use native Canvas plus textual equivalents. Record reporting timezone so changing device timezone cannot silently shift historical buckets. Family changes clear family-specific data; key rotation preserves it.

## Scope and validation

Limit code edits to requested permission/service paths, data accounting, navigation/theme, and directly related defects. No encryption or classifier rewrite. Test statistics boundaries, duplicates, migration/reset, contrast and permission guards; build APK and verify signature. Physical Samsung/HSBC/Play Protect validation remains required. A separate parent APK and notification-only child mode were rejected because the user requested one APK and all child features.
