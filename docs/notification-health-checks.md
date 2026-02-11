# Notification health checks and banners

## Old behavior
- The app could quietly skip notification delivery when app-level notifications were disabled, a channel was muted, or a thread had notifications turned off.
- `NotificationManagerImpl.update()` returned early when notifications were disabled or permissions were missing, but there was no user-facing indication in the UI that alerts were being suppressed.
- Users had to discover the settings state themselves (system notification settings or per-thread preferences).

## New behavior
- The main screen now performs a health check for notification permissions and app/channel enablement on resume, surfacing a banner-style snackbar with direct links to system settings when needed.
- The compose screen performs a health check on resume or thread changes, showing an in-app banner if notifications are disabled, the relevant channel is muted, or thread notifications are turned off. The banner provides a shortcut to the appropriate settings screen.
- Notification settings shortcuts include app notification settings, channel settings, and per-thread notification preferences.

## Why this should accomplish the goal
- The health checks make notification suppression visible immediately after onboarding/first run or after system settings changes.
- In-app banners provide clear, contextual guidance and a direct path to re-enable notifications, reducing “missing notification” confusion noted in low-RAM diagnostics.
- The compose screen’s thread-specific checks address per-conversation settings, ensuring users can restore alerts for muted threads without leaving the context.
