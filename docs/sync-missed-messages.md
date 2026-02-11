# Sync missed messages: behavior change overview

## Old behavior
- Incoming SMS/MMS handled by background workers (`ReceiveSmsWorker`/`ReceiveMmsWorker`) which call
  `ConversationRepository.updateConversations` for the affected thread and then update notifications/badges.
- If background work is delayed or killed under memory pressure, the worker flow may not run and users can be
  left with stale conversation metadata and unread counts.
- There was no user-triggered action to re-run that conversation update flow without a full database sync.

## New behavior
- A **“Sync missed messages”** settings action is available to users.
- When triggered, the app:
  1. Fetches all thread IDs from the local message store.
  2. Calls `ConversationRepository.updateConversations(threadIds)` to refresh conversation metadata.
  3. Updates badge/widget counts.
- The UI surfaces progress and completion feedback (started, completed, no updates, failed) so users can
  confirm that the action ran and whether it found updates.

## Why this should help
- The action reuses the same conversation update path used by the workers, so it directly targets the
  metadata that gets stale when the worker flow is skipped.
- It is lightweight compared to the full `Sync messages` operation, making it safe to run on-demand when
  users notice missing or unread conversations.
- The explicit feedback helps users validate that the sync ran, matching the mitigation suggested in
  `docs/sms-mms-low-ram-drop-fixes.md`.
