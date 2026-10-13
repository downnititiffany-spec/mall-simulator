# N31-02 Leg C evidence slice

- Run: `n3102e_20260929_173000_7c11`
- Scope: category and city-level sales ADS, isolated MySQL mirror, API, browser, independent oracle, and lineage evidence.
- This is a leg-level evidence slice, not the final frozen N31-02 archive. The final batch archive must reconcile legs A–D and include the applicable D-048 controls.
- Source evidence was copied from the ignored run directory `target/v25-it/n3102e_20260929_173000_7c11/evidence/`.
- Text evidence was scanned for credential/password/token markers before copying; no matches were found. No database dump, credentials, or full application logs are included.
- `MANIFEST-SHA256SUMS.txt` records the hashes of the 14 copied evidence files.

## Evidence interpretation

- The browser E2E sample contains 99 valid events and no malformed/quarantined rows; it does not fulfill the plan's separate 5–10 bad-row sample.
- The fixture has category IDs but no category-name dictionary, so `UNKNOWN` category labels are truthful rather than fabricated. The Spark regression separately covers unknown category and missing city-level handling.
- Browser screenshot files are viewport captures. Category and city-level headings/API payloads/canvas count were asserted; the category chart is not fully visible in the captured viewport.
- All runtime conclusions are limited to the WSL single-node test environment and the run-scoped MySQL 3307 schemas.
