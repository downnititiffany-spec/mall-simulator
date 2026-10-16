# N31-02 leg D evidence slice

- Source run: `target/v25-it/n3102hms_20260929_114700/`.
- Scope: selected logs for the WSL single-node shared Hive/Spark Metastore check, including Hive DDL, Spark cross-write, Hive cross-read, HDFS table listing, schema details, Metastore and run summary.
- Result interpretation: `PASS_WITH_CLEANUP_CORRECTION`, not unconditional PASS. Functional shared-Metastore criteria passed; the one-shot wrapper returned 15 and did not release port 9083, so the run result retains that cleanup limitation.
- The eight files were copied byte-for-byte; original/copy SHA-256 values are recorded in `MANIFEST-SHA256SUMS.txt`.
- Credential-pattern scan of copied logs: 0 hits. The source `hive-site.xml` was deliberately excluded because it contains a database password; no config, credential file, DB dump, `.git`, `.zcode`, or backup is included.
- This is a leg-level evidence slice, not the D-048 full-batch source snapshot or final N31-02 signoff.

Authoritative interpretation: `docs/verification/results/BATCH-N31-02-LEGD-RESULT.md`.
