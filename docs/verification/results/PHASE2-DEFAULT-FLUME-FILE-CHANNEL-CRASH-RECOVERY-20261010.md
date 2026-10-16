# 阶段2/7 默认 File Channel 隔离故障恢复子例（2026-10-10）

## 测试范围

- RunId：`cloud_261010_040051_b2ab54`。测试 agent 使用该 RunId 下独立的 spool、checkpoint、data 和 HDFS 目标；默认在线 Flume agent 本身未被中断。
- File Channel 配置采用 `type=file`、RunId 专属且分离的 checkpoint/data 目录、`useDualCheckpoints=false`、`checkpointInterval=30000`、`capacity=2000`、`transactionCapacity=500`，与当前默认 agent 的核心持久队列参数一致。输入走 Spooling Directory Source。
- 合成输入 1,500 行，每行 event_id 唯一，源文件 SHA-256 `64c5a285ca4b8acc4c07a45d3da9810071ea241a158c57c4d555095238292656`。第一阶段将 sink 指向未监听的 loopback Avro 端口 19999；source 将源文件改为 `.COMPLETED`，输入进入专属 File Channel。随后用 PID、JDK 路径、进程组、cwd 和 `--conf-file` 均匹配的身份检查确认测试 agent，再对该 agent 注入一次 SIGKILL。
- 恢复阶段用同一 File Channel checkpoint/data 重启 agent，sink 指向专属 HDFS `/landing/cloud_261010_040051_b2ab54/flume-recovery-attempt4`。结果为 1,500 行、1,500 个唯一 event_id、missing=0、extra=0；源与 HDFS 导出文件 SHA-256 均为 `64c5a285ca4b8acc4c07a45d3da9810071ea241a158c57c4d555095238292656`。
- 恢复 agent 已优雅停止；默认 Flume agent 未中断且经复核仅 1 个匹配当前 RunId 配置的实例。默认业务 Landing 仍为此前 1 行；没有把恢复 fixture 写入默认 Landing。MySQL/商城 API/平台摄取未调用，3306 未连接。

## 结论边界

本子例证明当前 RunId 的隔离 File Channel 设置可在单次 agent 硬中断后重放已入队事件，并由恢复后的 HDFS sink 完整交付该组 fixture。测试 agent 与默认 agent 使用不同 channel 数据目录；因此不声称默认在线 agent PID 本身经过崩溃恢复，也不证明所有故障窗口、任意并发、多批或 exactly-once 语义。默认配置单行实际投递见 `PHASE2-DEFAULT-FLUME-FILE-CHANNEL-20261010.md`；业务链仍需 Mall API→Outbox→平台摄取分别验证。

## 执行器纠正记录

第一次验证器迭代因 cwd/PID 登记规则和测试目录初始化问题提前中止；过程中创建的额外默认 agent 已通过进程命令路径、RunId、进程组及输出日志身份核实后停止。未触碰非 task-owned PID。最终复核默认 agent 数为 1、默认 Landing 仍 1 行、隔离恢复目标 1 文件/1,500 行。早期失败尝试不计入本报告 PASS。
