# Potential fixes for low‑RAM "dropped" SMS/MMS reports

This document suggests mitigation strategies that map directly to the hypotheses in `sms-mms-low-ram-drop-hypotheses.md`. The goal is to improve reliability and observability on low‑memory devices.

## 1) Reduce reliance on immediate background work
**Problem addressed:** WorkManager jobs are delayed or killed under memory pressure.

**Ideas:**
- Consider **lightweight, synchronous work in the receiver** to validate/enqueue messages (while keeping work short). Currently, SMS handling stores the message then defers processing to `ReceiveSmsWorker`.【F:data/src/main/java/com/moez/QKSMS/receiver/SmsReceivedReceiver.kt†L35-L74】
- Add **explicit retry logic** for failed workers or a “catch‑up” sync on app launch to reconcile missed messages.

## 2) Strengthen resiliency in workers
**Problem addressed:** Worker failures prevent conversation updates and notifications.

**Ideas:**
- Add **structured logging** or analytics hooks around early returns in `ReceiveSmsWorker` and `ReceiveMmsWorker` to track failure reasons (e.g., missing input data, IO errors).【F:data/src/main/java/com/moez/QKSMS/worker/ReceiveSmsWorker.kt†L54-L76】【F:data/src/main/java/com/moez/QKSMS/worker/ReceiveMmsWorker.kt†L87-L156】
- If a worker fails due to transient conditions, consider returning `Result.retry()` instead of `Result.failure()` so WorkManager can re-run it later.

## 3) Provide a manual “sync missed messages” action
**Problem addressed:** Messages are stored in the system provider, but the app did not surface them.

**Ideas:**
- Add a **user‑initiated sync** that refreshes conversations and unread messages to catch up after background restrictions.
- Hook that action into existing conversation update logic used by both workers. (Both workers already call `conversationRepo.updateConversations` before notifying.)【F:data/src/main/java/com/moez/QKSMS/worker/ReceiveSmsWorker.kt†L95-L123】【F:data/src/main/java/com/moez/QKSMS/worker/ReceiveMmsWorker.kt†L216-L250】

## 4) Make blocking & filtering outcomes explicit
**Problem addressed:** Messages are dropped by blocking or content filters without a user-visible cue.

**Ideas:**
- Add a **debug/diagnostic screen** or log entry for “message dropped due to rule.”
- Provide a **notification or snackbar** that a message was blocked (optional setting), so users don’t confuse rule-based drops with delivery failures.

(Blocking and filtering occur in both workers.)【F:data/src/main/java/com/moez/QKSMS/worker/ReceiveSmsWorker.kt†L54-L93】【F:data/src/main/java/com/moez/QKSMS/worker/ReceiveMmsWorker.kt†L162-L214】

## 5) Improve notification reliability and visibility
**Problem addressed:** Notifications are missing due to permission or channel settings.

**Ideas:**
- Add a **health check** in onboarding to verify notification permissions and channel configuration.
- Surface **in‑app banners** when notifications are disabled, since `NotificationManagerImpl` will return early if notification permission is missing or per-thread notifications are off.【F:presentation/src/main/java/com/moez/QKSMS/common/util/NotificationManagerImpl.kt†L105-L129】

## 6) Reduce MMS memory pressure
**Problem addressed:** MMS processing buffers payload data, which can be heavy on low‑RAM devices.

**Ideas:**
- Stream MMS payloads instead of loading the entire file into memory if possible.
- Add safeguards for large payloads (e.g., size checks with graceful fallback or lower‑memory decoding paths).

`ReceiveMmsWorker` currently reads the whole file into a byte array before persisting. On low‑RAM devices, this can be costly.【F:data/src/main/java/com/moez/QKSMS/worker/ReceiveMmsWorker.kt†L87-L146】

## 7) Add low‑RAM specific monitoring
**Problem addressed:** Difficult to reproduce or confirm failures.

**Ideas:**
- Instrument worker start/end timings and the reason for exit (success, filtered, blocked, error).
- Tag logs with memory class or device RAM tier so reliability issues can be correlated to device constraints.

---

> These fixes are intentionally scoped to be additive and low risk. Start with observability (logs/diagnostics), then tackle background reliability and memory-heavy MMS handling.
