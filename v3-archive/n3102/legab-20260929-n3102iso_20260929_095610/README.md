# N31-02 legs A/B evidence slice

- Source runs: `n3102iso_20260929_095610` under the ignored `target/v25-it/` tree.
- Scope: selected, unmodified leg-A continuous-chain evidence and leg-B second-batch/no-op/failure-recovery evidence.
- This is a leg-level evidence slice only. It is not the D-048 whole-batch source snapshot, not a Git backup, and not N31-02 acceptance/signoff.
- Files were copied byte-for-byte from `target/v25-it/n3102iso_20260929_095610/evidence/` and its `logs/` directory. Source/destination SHA-256 comparison: 40/40 matched.
- The preserved harness-order failure is retained beside the corrected leg-B result to keep the execution history honest.
- Text/JSON/log evidence scan for credential-like patterns: 0 hits. No credential references, config, SQL scripts, DB dump, platform log, jar, `.git`, `.zcode`, or backup files are included.
- The overview screenshot is a single viewport and does not show the complete chart area; retain the limitation recorded in the leg-C result and batch plan.
- Verify files with `Get-FileHash -Algorithm SHA256` against `MANIFEST-SHA256SUMS.txt`.

The authoritative per-leg interpretations remain in `docs/verification/results/BATCH-N31-02-LEGA-RESULT.md` and `BATCH-N31-02-LEGB-RESULT.md`.
