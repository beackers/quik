# SMS low‑RAM resilience: old behavior vs new behavior

## Old behavior (before this change)
- `SmsReceivedReceiver` parsed the inbound broadcast, immediately persisted the SMS, and then enqueued `ReceiveSmsWorker` in a single flow.
- If the process was killed or WorkManager execution was delayed under memory pressure, there was no automatic catch‑up on launch to reconcile missed processing.

## Changes introduced
- **Preflight validation + lightweight persistence:** the receiver now performs a short synchronous preflight (validate address/body) and enqueues a dedicated persistence worker that stores the SMS and outputs its message ID.
- **Chained processing:** `ReceiveSmsWorker` is chained after persistence so heavy processing runs only after the message is safely stored.
- **Catch‑up on launch:** a new launch-time worker triggers a message sync and, once complete, enqueues processing for unread/unseen SMS to reconcile missed work.
- **Retry/backoff wiring:** both the persistence and processing workers use backoff and retry so transient failures can be re-run.

## New behavior (current)
- On broadcast receipt, the app validates address/body, enqueues a persistence worker, and chains `ReceiveSmsWorker` after persistence completes.
- On app launch, the catch‑up worker runs: it syncs messages, waits for the sync to finish, and enqueues processing for recent unread/unseen SMS.
- Worker retries are configured so missing records or transient failures can be retried rather than permanently dropped.

## Why this helps “dropped” messages on low‑RAM devices
- **Short receiver work:** the receiver does minimal work and avoids long IO, reducing ANR risk when the system is under memory pressure.
- **Durable persistence step:** the persistence worker stores the inbound SMS before downstream processing, ensuring the message exists even if later work is delayed or killed.
- **Launch reconciliation:** a catch‑up sync on startup makes sure messages that arrived while the app was killed are pulled in and reprocessed for notifications and conversation updates.
- **Retries for transient failures:** backoff and retry behavior allow worker execution to recover from temporary resource constraints instead of giving up on processing.
