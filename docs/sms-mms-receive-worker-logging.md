# SMS/MMS receive worker observability updates

## Old behavior
- `ReceiveSmsWorker` and `ReceiveMmsWorker` returned `Result.failure()` for most early exits, but the logs were minimal and not consistently structured. Missing inputs, content filter drops, and blocking outcomes were often only visible as generic log lines or silent failures.
- `ReceiveMmsWorker` logged exceptions but treated most failures the same, which made it harder to distinguish transient I/O issues from permanent problems like missing input data.
- Operators could not easily correlate "dropped" SMS/MMS reports with concrete failure reasons or retry eligibility, especially on low‑RAM devices where WorkManager jobs can be delayed or killed.

## Changes in this update
- Added structured early-exit logging for both receive workers, covering missing input data, block/drop decisions, content filtering, missing conversations, and blocked conversations.
- Added explicit logging for I/O exceptions and unexpected failures to make these cases traceable.
- Classified transient failures (notably I/O exceptions and MMS persistence/empty payload issues) to return `Result.retry()` so WorkManager can re-run the job, while keeping `Result.failure()` for permanent issues like missing inputs or filtered/blocked messages.

## New behavior
- SMS and MMS worker logs now include a consistent `receive_*_early_exit` entry with reason and key identifiers (message id, subscription id, thread id, location URL) whenever work stops early.
- Transient failures are retried automatically:
  - MMS I/O exceptions, empty payloads, or null persistence results lead to `Result.retry()`.
  - SMS I/O exceptions lead to `Result.retry()`.
- Permanent outcomes (missing inputs, content filter drops, blocked conversations, or unexpected failures) still return `Result.failure()` so WorkManager does not keep retrying a known-bad task.

## Why this helps low‑RAM “dropped” message reports
- Low‑RAM devices are more likely to interrupt or starve background work. Retrying transient I/O or persistence failures gives WorkManager a chance to recover when memory pressure eases.
- Structured logs make it easier to differentiate real delivery problems from intentional drops (blocking/filtering) or missing inputs, which prevents misdiagnosing user reports.
- Combined, this improves observability and reliability while keeping permanent failures from looping, which aligns with the mitigations outlined in `docs/sms-mms-low-ram-drop-fixes.md`.
