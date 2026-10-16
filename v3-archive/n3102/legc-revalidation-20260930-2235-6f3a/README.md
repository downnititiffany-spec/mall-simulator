# N31-02 LEG C revalidation evidence slice

- Category/region chain RunId: `n3102cat_20260930_2235_6f3a`.
- Isolated MySQL publisher IT RunId: `n3102pub_20260930_2335_f3a`.
- Scope: synthetic Flume/HDFS ingest and Spark/ADS output, independent category/region oracle, run-scoped MySQL 3307/API/browser reconciliation, and official isolated MySQL IT result summaries.
- The 18 evidence files were copied unchanged from ignored `target/`/Surefire output. Source and destination SHA-256 values matched for every copy. `MANIFEST-SHA256SUMS.txt` is the integrity index.
- Sensitive-pattern scan of copied text evidence: 0 hits. No credentials, credrefs, database dump, complete application logs, or jar binaries are included. The MySQL test summary and per-table row counts are retained; all 37 expected base tables were counted.
- This is a **leg-level supplement**, not the final N31-02 archive. At N31-02 close, reconcile A–D, add the applicable source snapshot/bundle under D-048 controls, and verify the final manifest. Do not treat this slice as batch signoff.
- Browser evidence is a single viewport capture; the sales page's full chart layout is not shown. All technical/runtime claims remain limited to WSL single-node and run-scoped isolation; 3306 was not contacted.
