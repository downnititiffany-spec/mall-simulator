package com.graduation.analytics.pipeline.spark;

import com.graduation.analytics.runtime.entity.RuntimeProfile;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * R6：把 RuntimeProfile + 作业参数转成 spark-submit 命令数组（纯函数，无 IO）。
 * 契约（与 spark-jobs JobRunner 对齐）：
 *   <sparkSubmitPath> [--master X] [--deploy-mode Y] [--queue Z]
 *   [--conf k=v ...]           ← 必须在 --class 之前，由调用方传入（如 warehouse/shard 分区）
 *   --class com.graduation.analytics.job.JobRunner <jarUri>
 *   --runtimeProfileId=N --jobCode=C --businessDate=yyyyMMdd --attemptNo=N
 *   [--inputVersion=...] [--outputSnapshotId=...] [--k=v ...extra 透传]
 * LOCAL 的 jar 若为相对路径会绝对化为 file:///（历史教训：No FileSystem for scheme "D"）；
 * REMOTE 的 jar 是 hdfs:// URI，原样保留。List<String> 天然保证路径带空格为单个参数。
 */
public final class JobCommandBuilder {

    public static final String MAIN_CLASS = "com.graduation.analytics.job.JobRunner";

    private JobCommandBuilder() {
    }

    /**
     * 便捷版：inputVersion/outputSnapshotId 并入 extraArgs 由调用方决定。
     */
    public static List<String> build(RuntimeProfile profile, String jobCode, String businessDate,
                                     long runtimeProfileId, int attemptNo,
                                     Map<String, String> extraArgs, Map<String, String> confs) {
        return build(profile, jobCode, businessDate, runtimeProfileId, attemptNo,
                null, null, extraArgs, confs);
    }

    /**
     * 完整版：显式 inputVersion/outputSnapshotId，与 extraArgs 一并拼到 --key=value 参数段。
     */
    public static List<String> build(RuntimeProfile profile, String jobCode, String businessDate,
                                     long runtimeProfileId, int attemptNo,
                                     String inputVersion, String outputSnapshotId,
                                     Map<String, String> extraArgs, Map<String, String> confs) {
        List<String> cmd = new ArrayList<>();
        String submitPath = profile.getSparkSubmitPath();
        if (submitPath == null || submitPath.isBlank()) {
            throw new IllegalArgumentException("RuntimeProfile.sparkSubmitPath 不能为空");
        }
        cmd.add(submitPath);

        boolean local = RuntimeProfile.TYPE_LOCAL.equals(profile.getType());
        String master = profile.getSparkMaster();
        if (master == null || master.isBlank()) {
            master = local ? "local[*]" : "yarn";
        }
        cmd.add("--master");
        cmd.add(master);

        // LOCAL 不需要 deploy-mode/yarn-queue；集群环境按档案配置
        if (!local && profile.getDeployMode() != null && !profile.getDeployMode().isBlank()) {
            cmd.add("--deploy-mode");
            cmd.add(profile.getDeployMode());
        }
        if (!local && profile.getYarnQueue() != null && !profile.getYarnQueue().isBlank()) {
            cmd.add("--queue");
            cmd.add(profile.getYarnQueue());
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

    private static String resolveJarUri(RuntimeProfile profile, boolean local) {
        String jar = profile.getSparkJobJarUri();
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