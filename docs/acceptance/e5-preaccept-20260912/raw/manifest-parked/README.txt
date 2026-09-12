===== 我的采集批次 44 的 manifest 归档（park）记录 =====
时间 2026-09-12 22:04:12
原因：总控硬约束——避免其它泳道的流水线运行经 findReadyManifest 取走本批次（READY 且 accepted+quarantined>0 的 max batchId）。

--- park 之前，只读核对：我采集之后是否已有别的 pipeline_run 消费过批次 44 ---
mysql: [Warning] Using a password on the command line interface can be insecure.
max_run_id	runs_after_47
NULL	0
id	status	target_snapshot_id	created_at
47	SUCCESS	S20260901_47	2026-09-12 21:26:36.119
46	FAILED	S20260901_46	2026-09-12 21:17:06.219
45	FAILED	S20260901_45	2026-09-12 21:06:48.256

--- park 动作 ---
源文件: D:\Develop_code\GraduationProject\landing\manifests\44.json  size=730 B  sha256=B74A5A63138539F6936A4AC1C9CB8CFECFBC1A76007AAA8D92F33556CEF82921
已移动至: D:\Develop_code\GraduationProject\docs\acceptance\e5-preaccept-20260912\raw\manifest-parked\44.json  size=730 B  sha256=B74A5A63138539F6936A4AC1C9CB8CFECFBC1A76007AAA8D92F33556CEF82921
哈希一致: True
landing\manifests 现有文件数 = 43（park 前 44 个：原有 43 + 我的 44）
landing\manifests 中是否还存在 44.json = False

--- 还原命令（如需让该批次重新可被流水线取用）---
Copy-Item -LiteralPath 'D:\Develop_code\GraduationProject\docs\acceptance\e5-preaccept-20260912\raw\manifest-parked\44.json' -Destination 'D:\Develop_code\GraduationProject\landing\manifests\44.json' -Force
校验: (Get-FileHash 'D:\Develop_code\GraduationProject\landing\manifests\44.json' -Algorithm SHA256).Hash -eq 'B74A5A63138539F6936A4AC1C9CB8CFECFBC1A76007AAA8D92F33556CEF82921'

--- 未 park、仍在原地的本次采集其它产物（如需清理请裁决）---
landing\events\2026091221.jsonl（本次页面加购事件，非既有 61 个文件之一）
landing\accepted\44\（1 条记录副本）; landing\quarantine\44\; analytics_meta.ingestion_batch id=44
