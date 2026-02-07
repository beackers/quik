# Branch overview (compared to `main`)

## Important limitation
This repository snapshot only contains a single local branch (`work`) and no `main` branch or remotes. As a result, a true branch‑by‑branch comparison against `main` is not possible in this environment. The notes below document what **can** be inferred from the available history and explain the gap.

## Branches present

### `work`
**What it appears to add:**
- Documentation about the SMS/MMS receive pipeline and the low‑RAM “dropped message” hypotheses/fixes.

**Evidence from commit history:**
- The branch tip includes commits that add three documentation files related to inbound message processing and reliability on low‑RAM devices.

## Missing `main`
No `main` branch exists locally, and no remote is configured. To complete a true comparison:
1. Fetch or add a remote that contains `main`.
2. Compare `work` against `main` (e.g., `git log main..work` and `git diff main..work`).

---

> If you can provide access to a remote or a `main` branch, we can update this document with a full, file‑level comparison.
