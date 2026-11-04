# Minimal family UI — review design

Status: prototype approved in chat. User authorised native implementation and GitHub publication, preserving existing functions and removing runtime sample data.

## Intent

A minimal, modern, professional child-protection app that feels calm and family-oriented. Role choice must be the first screen. Navigation and feedback should transition smoothly. Keep unrelated code intact.

## Audit of current UI

The current native Activity starts with role setup forms together, uses a popup menu, and presents dense helper copy, decorative emoji, thick borders and broad colour blocks. Role state, monitoring, permission ownership and parental verification are already implemented and must be preserved.

## Recommended direction

A role-first entry screen, short separate setup flows, fixed concise connection status, and four bottom-navigation destinations per role. Parent: Overview, Alerts, Family, Settings. Child: Overview, Permissions, Unlock, Settings. Weekly chart views share one compact space using an explicit trend/type selector. Child permission descriptions explain why access is needed before the real system handoff.

The alternative of a cosmetic palette-only refresh would leave the crowded setup and navigation issues. A framework migration adds unnecessary risk to the single-file native Android build. The prototype demonstrates the recommended native-compatible arrangement using a standalone HTML file solely to make design review easy; it is not a proposal to embed a WebView.

## Visual and motion rules

White and off-white surfaces; dark charcoal copy; one restrained sage/forest accent. Red/amber are semantic safety indicators, not primary action fills. Fine dividers, modest radii, clear typography, consistent icons, enough whitespace to distinguish sections. Headlines remain direct and family-oriented; small-print technical copy moves into explanations and settings.

Page changes use approximately 230–300ms opacity/translation transitions. Sheets enter and exit in about 360ms. Controls respond promptly and reduced-motion settings suppress movement. Keep drafts and destinations through ordinary navigation. Give errors inline and preserve keyboard focus.

## Role-entry behavior

The prototype always opens at role choice. In the Android implementation, this entry screen must not silently reset an existing pairing or permit a protected child device to become a parent device without the existing parent authentication. Selecting the already paired role should continue safely; selecting a different protected role must retain the current authentication gate. Monitoring should not stop merely because an entry screen is visible.

## Approved implementation scope

Translate the approved prototype to native Views using real stored state. Keep the existing monitoring engine, encryption, pairing protocol, safety accounting and four security matching symbols. Remove the example-account fallback on new installs without deleting explicitly saved user trust lists. The standalone review prototype is archived outside the repository; its synthetic data is excluded from the source and APK. Package native font/icon/theme resources through the existing GitHub Actions project-generation step.

Parent actions and confirmation dialogs belong to the role and family session in which they opened. Dismiss them and clear drafts when that session changes; reject retained callbacks before accessing credentials or performing privileged operations. Offer a real randomly generated strong password during parent setup.

GitHub publication is authorised by the user after design review.
