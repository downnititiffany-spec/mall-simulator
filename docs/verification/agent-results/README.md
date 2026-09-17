# Code Agent Result Drop

This directory is the only GitHub write target granted to the Code Agent execution-tester role.

Rules:

- Branch: `verification-results` only.
- Allowed result path: `docs/verification/agent-results/<batch-id>-RESULT.md`.
- One batch result per commit is preferred.
- Do not modify source code, tests, scripts, project docs, or any other path from this role.
- Do not push `main` or `feature/v3-development`.
- Before commit/push, verify staged paths contain only the intended result file.

ChatGPT reviews these raw execution reports and copies accepted evidence into `docs/verification/results/` on the development branch.
