package com.graduation.analytics.pipeline.spark;

import com.graduation.analytics.runtime.RuntimeProfileSnapshot;
import com.graduation.analytics.warehouse.WarehouseNamespace;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * R6：把 RuntimeProfile 快照 + 作业参数转成 spark-submit 命令数组（纯函数，无 IO）。
 * 契约（与 spark-jobs JobRunner 对齐）：
 *   <sparkSubmitPath> [--master X] [--deploy-mode Y] [--queue Z]
 *   [--conf k=v ...]           ← 必须在 --class 之前，由调用方传入（如 warehouse/shard 分区）
 *   --class com.graduation.analytics.job.JobRunner <jarUri>
 *   --runtimeProfileId=N --jobCode=C --businessDate=yyyyMMdd --attemptNo=N
 *   [--inputVersion=...] [--outputSnapshotId=...] [--k=v ...extra 透传]
 * LOCAL 的 jar 若为相对路径会绝对化为 file:///（历史教训：No FileSystem for scheme "D"）；
 * REMOTE 的 jar 是 hdfs:// URI，原样保留。List<String> 天然保证路径带空格为单个参数。
 *
 * R6-10：入参改为不可变 {@link RuntimeProfileSnapshot}——一次运行冻结一份环境配置，
 * 运行中途管理员改档案不会造成"半个 run 用 A 环境、半个 run 用 B 环境"。
 */
public final class JobCommandBuilder {

    public static final String MAIN_CLASS = "com.graduation.analytics.job.JobRunner";

    private JobCommandBuilder() {
    }

    /**
     * 便捷版：inputVersion/outputSnapshotId 并入 extraArgs 由调用方决定。
     */
    public static List<String> build(RuntimeProfileSnapshot profile, String jobCode, String businessDate,
                                     long runtimeProfileId, int attemptNo,
                                     Map<String, String> extraArgs, Map<String, String> confs) {
        return build(profile, jobCode, businessDate, runtimeProfileId, attemptNo,
                null, null, extraArgs, confs);
    }

    /**
     * 完整版：显式 inputVersion/outputSnapshotId，与 extraArgs 一并拼到 --key=value 参数段。
     */
    public static List<String> build(RuntimeProfileSnapshot profile, String jobCode, String businessDate,
                                     long runtimeProfileId, int attemptNo,
                                     String inputVersion, String outputSnapshotId,
                                     Map<String, String> extraArgs, Map<String, String> confs) {
        List<String> cmd = new ArrayList<>();
        String submitPath = profile.sparkSubmitPath();
        if (submitPath == null || submitPath.isBlank()) {
            throw new IllegalArgumentException("RuntimeProfile.sparkSubmitPath 不能为空");
        }
        cmd.add(submitPath);

        // P1-04：数仓库名空间由唯一所有者解析。非法前缀在这里（spark-submit 之前）失败，
        // 本方法只被提交路径调用，因此“非法前缀绝不进入 Spark”是结构保证，不靠下游再校验。
        WarehouseNamespace namespace = WarehouseNamespace.ofNullable(profile.hiveDatabasePrefix());

        boolean local = profile.isLocal();
        String master = profile.sparkMaster();
        if (master == null || master.isBlank()) {
            master = local ? "local[*]" : "yarn";
        }
        cmd.add("--master");
        cmd.add(master);

        // LOCAL 不需要 deploy-mode/yarn-queue；集群环境按档案配置
        if (!local && profile.deployMode() != null && !profile.deployMode().isBlank()) {
            cmd.add("--deploy-mode");
            cmd.add(profile.deployMode());
        }
        if (!local && profile.yarnQueue() != null && !profile.yarnQueue().isBlank()) {
            cmd.add("--queue");
            cmd.add(profile.yarnQueue());
        }

        // --conf 必须在 --class 之前（spark-submit 解析顺序）
        if (confs != null) {
            for (Map.Entry<String, String> e : confs.entrySet()) {
                cmd.add("--conf");
                cmd.add(e.getKey() + "=" + e.getValue());
            }
        }

        cmd.add("--class");
        cmd.add(MAIN_CLASS);

        cmd.add(resolveJarUri(profile, local));

        cmd.add("--runtimeProfileId=" + runtimeProfileId);
        cmd.add("--jobCode=" + jobCode);
        cmd.add("--businessDate=" + businessDate);
        cmd.add("--attemptNo=" + attemptNo);
        // 解析后的前缀（缺省 dw）显式下发：作业侧 JobRunner 启动前复核，两侧同一份规格
        cmd.add("--" + WarehouseNamespace.ARG_KEY + "=" + namespace.prefix());
        if (inputVersion != null && !inputVersion.isBlank()) {
            cmd.add("--inputVersion=" + inputVersion);
        }
        if (outputSnapshotId != null && !outputSnapshotId.isBlank()) {
            cmd.add("--outputSnapshotId=" + outputSnapshotId);
        }
        if (extraArgs != null) {
            for (Map.Entry<String, String> e : extraArgs.entrySet()) {
                if (e.getValue() == null) {
                    continue;
                }
                cmd.add("--" + e.getKey() + "=" + e.getValue());
            }
        }
        return cmd;
    }

    private static String resolveJarUri(RuntimeProfileSnapshot profile, boolean local) {
        String jar = profile.sparkJobJarUri();
        if (jar == null || jar.isBlank()) {
            throw new IllegalArgumentException("RuntimeProfile.sparkJobJarUri 不能为空");
        }
        boolean hasScheme = jar.startsWith("file:") || jar.startsWith("hdfs:") || jar.startsWith("s3");
        if (hasScheme) {
            return jar;
        }
        if (local) {
            // 相对路径 → file:/// 绝对路径（不加前缀会 No FileSystem for scheme "D"）
            File abs = new File(jar).getAbsoluteFile();
            return Path.of(abs.toURI()).normalize().toUri().toString();
        }
        // 非 LOCAL 且无 scheme：按相对/绝对路径处理为 file:///（本地提交模式）
        File abs = new File(jar).getAbsoluteFile();
        return Path.of(abs.toURI()).normalize().toUri().toString();
    }
}