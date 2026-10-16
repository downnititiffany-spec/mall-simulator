# N31-02 WAIT_LANDING acceptedStorageUri readback

- RunId: `n3102waituri_20260930_2042_a1`.
- Evidence source: a read-only query against `n3102waituri_20260930_2042_a1_analytics_meta.pipeline_stage_run` on the isolated WSL MySQL 3307 instance, after the platform run.
- Persisted output: `evidence/pipeline-stage-run-readback.tsv` (one row; the actual `WAIT_LANDING` stage is SUCCESS, batchId 1, acceptedRecords 99, sourceId 1, and the storage URI is a full `hdfs://` URI without userinfo/query/fragment).
- No credentials, configuration, database dump, or source run logs are included. The output is a materialized readback, not a claim that the query was re-run from this archive.
- Deliberate scope limit: the following `INIT_SCHEMA` stage used a nonexistent isolated Spark job JAR and failed as intended. This proves only the persisted WAIT_LANDING URI field; it does not prove Spark processing, ADS publication, or whole-pipeline SUCCESS.
- Verify with `Get-FileHash -Algorithm SHA256` against `MANIFEST-SHA256SUMS.txt`.

Full scope and historical limitations: `docs/verification/results/BATCH-N31-02-WAIT-LANDING-URI-DB-PROOF-20260930.md`.
