# Blocked message drop diagnostics and notifications

## Old behavior
- SMS and MMS workers applied blocking rules and message content filters, but dropped messages were not explicitly surfaced to users beyond existing blocking UI or content filters lists.
- When a message was dropped by a rule, the workers returned early after deleting the message, with only general logging, which made it easy to confuse a rule-based drop with delivery or background reliability issues.
- Users had no optional notification to distinguish policy-based drops from delivery failures or system delays.

## Changes
- Added structured logging for rule-based drops in SMS and MMS workers, including the message ID, thread ID, rule type (blocking vs. content filter), and rule reason when available.
- Added an opt-in preference to notify users when a message is blocked or dropped, exposed as a toggle in the Blocking settings screen.
- Added a dedicated notification channel and message text for blocked-message alerts so the user can tell when a drop was caused by rules rather than delivery problems.

## New behavior
- When a message is dropped by blocking rules or content filters, the app now records a targeted log entry in addition to deleting the message.
- If the user enables blocked-message notifications, a user-visible alert is posted to explain that a rule blocked the message, including the rule class and optional reason.
- This provides an explicit, low-overhead diagnostic surface for rule-based drops while keeping the feature optional for users who prefer silent blocking.

## Why this helps with "dropped" texts
- Low-memory and background-restriction cases can already delay or interrupt worker processing; without explicit logging or notifications, users may interpret a drop as a delivery failure.
- By logging the specific rule and optionally surfacing a notification, rule-based drops become observable and clearly differentiated from delivery issues.
- This aligns with the low-RAM drop fixes guidance by improving observability first, which makes it easier to diagnose real delivery failures versus intentional filtering.
