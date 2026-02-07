# MMS payload handling: streaming + safeguards

## Old behavior
- `ReceiveMmsWorker` read the entire downloaded MMS file into a single `ByteArray` based on file length.
- A single large allocation meant memory spikes on low‑RAM devices, increasing the chance of OOM or worker death.
- There were no explicit size checks before attempting to buffer the payload.

## Changes
- Use buffered, chunked reads to stream the payload into memory rather than a single monolithic read.
- Add explicit size safeguards:
  - Hard fail if the payload exceeds `MmsConfig.getMaxMessageSize()`.
  - Defer processing (WorkManager retry) if the payload is larger than an in‑memory safety limit.
  - Guard against `Int` overflow when the payload length exceeds addressable array sizes.
- Keep the downloaded file when deferring, so a retry can attempt processing later without re‑downloading.

## New behavior
- MMS payloads are read using `BufferedInputStream` with a fixed read buffer and accumulated in memory.
- Oversized payloads are either rejected (over carrier max size) or deferred (over in‑memory limit).
- The worker is more defensive about memory pressure and avoids deleting the download file if it needs to retry.

## Why this helps prevent "dropped" messages
- Streaming reads reduce peak memory pressure, lowering the risk that the worker is killed or fails during receive.
- Size checks ensure oversized payloads do not cause out‑of‑memory crashes.
- Deferred processing keeps the payload available for later, improving reliability on low‑RAM devices that may need retries to succeed.
