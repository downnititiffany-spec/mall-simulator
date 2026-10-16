# Backend Working Tree Regression - 2026-10-08

## Scope

- Repository: `D:\Develop_code\GraduationProject-wt\v3-dev`
- Branch: `codex/cloud-v3-development`
- HEAD: `64c93b9d9c19dd9b745422207c4ac7fd739f8d4f`
- After fetching `origin/codex/cloud-v3-development`, ahead/behind was `0/0`.
- The working tree had 707 status entries before this evidence update; existing changes were preserved.
- Command: `mvn -o -f analytics-server/pom.xml test` (Windows JDK 17, offline, seven-module Maven reactor).

## Result

| Module | Suites | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| platform-common | 11 | 119 | 0 | 0 | 0 |
| connection-ingestion | 50 | 404 | 0 | 0 | 2 |
| warehouse-pipeline | 17 | 209 | 0 | 0 | 0 |
| metric-analysis | 23 | 152 | 0 | 0 | 0 |
| ai-decision | 23 | 208 | 0 | 0 | 0 |
| platform-app | 46 | 219 | 0 | 0 | 0 |
| **Total** | **170** | **1,311** | **0** | **0** | **2** |

Maven reported `BUILD SUCCESS`. The two skipped cases are POSIX symbolic-link tests unsupported by this Windows run. `git diff --check` exited 0; Git reported existing LF-to-CRLF conversion warnings for dirty files.

## Limits

This is offline backend regression evidence only. No application service or database was started or contacted; no browser-authenticated pagination, RuntimeProfile switch, real HiveServer2 health probe, true LLM provider, generator/mall transaction chain, Spark production job, or end-to-end acceptance was tested here. In particular, it does not close V3.0 stage 4, 6, 7, 8, or project acceptance. Final acceptance remains with the project controller.

The evidence reflects the working tree at test time and does not imply a commit or push. No source or schema change was made for this test run.
