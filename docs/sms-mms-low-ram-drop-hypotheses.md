# Why SMS/MMS can appear to be "dropped" on low‑RAM devices (hypotheses)

This doc outlines plausible, **device- and OS-level** reasons a low‑RAM phone might _appear_ to never receive texts in QUIK. It is intentionally speculative and meant to guide debugging. It focuses on the parts of the receive pipeline we own (receivers + workers + notifications) and where low‑memory constraints can interfere.

## Framing: what “dropped” can mean
A user may say the app “never received” a message when one of these happened instead:
- The **broadcast was delivered** but processing failed before a notification or UI update surfaced.
- The message **was stored in the system provider**, but QUIK did not sync it, or failed to refresh the conversation list in time.
- The **notification was suppressed** (blocked conversation, filters, or notification settings), making it look like the message didn’t arrive.

## Hypotheses tied to the receive pipeline

### 1) Broadcast delivery vs. background execution limits
On low‑RAM devices, Android may aggressively limit background execution. QUIK relies on `BroadcastReceiver`s to enqueue WorkManager jobs:
- SMS: `SmsReceivedReceiver` parses the broadcast, stores the SMS, then enqueues `ReceiveSmsWorker`.【F:data/src/main/java/com/moez/QKSMS/receiver/SmsReceivedReceiver.kt†L35-L74】
- MMS: `MmsReceivedReceiver` enqueues `ReceiveMmsWorker` after the payload is downloaded.【F:data/src/main/java/com/moez/QKSMS/receiver/MmsReceivedReceiver.kt†L60-L95】

If the OS throttles the app immediately after receiving the broadcast, the worker may not execute promptly (or at all), making it look like the message never arrived.

### 2) WorkManager job execution delays or failures
After the receiver writes the SMS or captures the MMS download metadata, the app does the real work in a worker:
- `ReceiveSmsWorker` checks blocking rules, updates conversations, and posts the notification.【F:data/src/main/java/com/moez/QKSMS/worker/ReceiveSmsWorker.kt†L54-L138】
- `ReceiveMmsWorker` persists/syncs the MMS, performs blocking/filtering, updates conversations, and posts notifications.【F:data/src/main/java/com/moez/QKSMS/worker/ReceiveMmsWorker.kt†L118-L250】

On low‑RAM devices, WorkManager can be delayed, evicted, or killed under memory pressure. If the worker fails before it updates conversations or notifications, the user sees no indication of a new message.

### 3) Message is dropped by blocking or content filtering
If the sender is blocked or the message matches content filters, QUIK intentionally removes it:
- The SMS worker deletes blocked messages when "drop blocked" is enabled and exits early.【F:data/src/main/java/com/moez/QKSMS/worker/ReceiveSmsWorker.kt†L54-L93】
- The MMS worker performs the same blocking/filtering and deletes the MMS if it matches the rules.【F:data/src/main/java/com/moez/QKSMS/worker/ReceiveMmsWorker.kt†L162-L214】

If a user forgets those settings, it can appear as if messages never arrived.

### 4) Conversation is blocked or archived, suppressing notifications
Both workers avoid showing notifications for blocked conversations and unarchive threads as needed. On low‑RAM devices, if the worker is killed before unarchiving or updating notifications, it can appear like nothing arrived.
- SMS: skips notification for blocked conversations and unarchives as needed before notifying.【F:data/src/main/java/com/moez/QKSMS/worker/ReceiveSmsWorker.kt†L107-L133】
- MMS: same behavior after syncing the message into the repository.【F:data/src/main/java/com/moez/QKSMS/worker/ReceiveMmsWorker.kt†L216-L250】

### 5) Notification permission or channel issues
Even when processing succeeds, notifications can be suppressed by OS‑level permissions. `NotificationManagerImpl` returns early if the notification permission is missing or notifications are disabled for the thread, so the user may never see a shade entry even though the message exists in the database.【F:presentation/src/main/java/com/moez/QKSMS/common/util/NotificationManagerImpl.kt†L105-L129】

### 6) MMS payload handling is heavier on memory
The MMS flow buffers the downloaded payload into memory before persisting/syncing it. On low‑RAM devices, large payloads can fail or be evicted mid‑processing, which could short‑circuit the worker and leave the message unsurfaced.【F:data/src/main/java/com/moez/QKSMS/worker/ReceiveMmsWorker.kt†L87-L146】

## Observability gaps that can worsen the perception
- If the worker fails silently or is killed, the app may not surface a user‑visible error.
- If the device is under memory pressure, the system may delay sync or restrict background access, which can make message arrival seem intermittent.

---

> These hypotheses are a starting point. The companion document `sms-mms-low-ram-drop-fixes.md` lists remediation ideas and targeted investigations.
