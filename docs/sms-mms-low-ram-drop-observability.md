# SMS/MMS low‑RAM drop observability update

## Old behavior
- `ReceiveSmsWorker` and `ReceiveMmsWorker` logged basic lifecycle messages like `started` and `finished`, plus a few ad-hoc error logs. There was no standardized exit reason to distinguish success from block/filter outcomes or transient failures. 
- Worker logs did not include timestamps or duration data to measure how long work took.
- Logs lacked device memory metadata, so it was difficult to correlate failures with low‑RAM devices.

## Changes made
- Added structured start/end logs (`worker_start`, `worker_end`) with timestamps and durations for both SMS and MMS receive workers.
- Standardized an `exitReason` field with the values: `success`, `filtered`, `blocked`, `transient error`, and `permanent error`.
- Added device memory metadata to the logs: `memoryClass`, `largeMemoryClass`, `lowRam`, and a `ramTier` classification.

## New behavior
- Each receive worker now emits a start log that includes a timestamp and memory metadata.
- Each exit path emits an end log that includes duration, a standardized exit reason, and the same memory metadata.
- Blocking and filtering outcomes are explicitly logged as `blocked` or `filtered` rather than blending into generic failure states.

## Why this helps “dropped” message reports
- Low‑RAM devices can now be identified directly from worker logs via `lowRam` and `ramTier`, enabling correlation between memory-constrained devices and dropped message reports.
- Standardized `exitReason` values make it clear when a message was filtered or blocked versus when a worker hit a transient or permanent error, reducing false “drop” diagnoses.
- Start/end timestamps and durations provide a concrete timeline for each worker execution, which helps determine whether work is stalling, being killed, or failing quickly under memory pressure.
