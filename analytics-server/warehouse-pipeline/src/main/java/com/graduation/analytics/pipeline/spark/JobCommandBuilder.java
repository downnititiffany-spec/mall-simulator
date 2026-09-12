package com.graduation.analytics.pipeline.spark;

import com.graduation.analytics.runtime.RuntimeProfileSnapshot;
import com.graduation.analytics.warehouse.RunSourceIdentity;
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
 *
 * P2-07（D-070/D-074）：数仓命名空间**不再是本类算出来的**，而是由调用方从"本次运行所用的源"
 * 解析后传入（唯一解析链 {@code WarehouseNamespaceProvider.runSource(sourceId)}）。
 * 本类仍是纯函数（无 IO、不读库、不知道源是什么），只是把它当输入参数收下来。
 *
 * A12（P2-01 对齐）：同一份源身份还带出源编码，与 {@code --hiveDatabasePrefix} 并列下发
 * {@code --sourceSystem}（spark-jobs 侧用它填 ODS 的 {@code source_system}，缺失即失败）。
 * 两个参数取自**同一个** {@link RunSourceIdentity}，故不可能一个来自 A 源、一个来自 B 源。
 */
public final class JobCommandBuilder {

    public static final String MAIN_CLASS = "com.graduation.analytics.job.JobRunner";

    /**
     * 「本次运行的源编码」参数名（值 = {@code source_registry.source_code}）。
     *
     * <p>跨进程字面量：spark-jobs 侧的同名常量是 {@code OdsLoadSql.ArgSourceSystem}
     * （{@code --sourceSystem}），两侧无法共享一个 Java 常量（不同构建、不同语言），
     * 故此处以常量的形式钉住，并由两件事共同保证不走偏：① E2 逐参数断言本类产出的
     * {@code --sourceSystem=<source_code>}；② spark-jobs 侧"缺参即 Left(...)"，
     * 名字写错会在第一个 ODS 作业处直接失败，而不是静默丢字段。</p>
     */
    public static final String ARG_SOURCE_SYSTEM = "sourceSystem";

    private JobCommandBuilder() {
    }

    /**
     * 便捷版：inputVersion/outputSnapshotId 并入 extraArgs 由调用方决定。
     */
    public static List<String> build(RuntimeProfileSnapshot profile, RunSourceIdentity source,
                                     String jobCode, String businessDate,
                                     long runtimeProfileId, int attemptNo,
                                     Map<String, String> extraArgs, Map<String, String> confs) {
        return build(profile, source, jobCode, businessDate, runtimeProfileId, attemptNo,
                null, null, extraArgs, confs);
    }

    /**
     * 完整版：显式 inputVersion/outputSnapshotId，与 extraArgs 一并拼到 --key=value 参数段。
     *
     * @param source 本次运行所用源的源身份（源编码 + 数仓命名空间；非 null；由调用方按源解析，
     *               非法/缺失在前一步就 fail-closed，不会走到这里）
     */
    public static List<String> build(RuntimeProfileSnapshot profile, RunSourceIdentity source,
                                     String jobCode, String businessDate,
                                     long runtimeProfileId, int attemptNo,
                                     String inputVersion, String outputSnapshotId,
                                     Map<String, String> extraArgs, Map<String, String> confs) {
        List<String> cmd = new ArrayList<>();
        String submitPath = profile.sparkSubmitPath();
        if (submitPath == null || submitPath.isBlank()) {
            throw new IllegalArgumentException("RuntimeProfile.sparkSubmitPath 不能为空");
        }
        if (source == null) {
            throw new IllegalArgumentException(
                    "RunSourceIdentity 不能为空：库名前缀与源编码必须由调用方按本次运行的源解析后传入");
        }
        cmd.add(submitPath);

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
        // 按源解析出的前缀显式下发：作业侧 JobRunner 启动前复核，两侧同一份规格。
        // 参数名/语义保持 P1-04 冻结的形状（`--hiveDatabasePrefix`），故 spark-jobs 侧零改动。
        cmd.add("--" + WarehouseNamespace.ARG_KEY + "=" + source.namespace().prefix());
        // A12：源编码与库名并列下发，取自同一个 RunSourceIdentity（见 ARG_SOURCE_SYSTEM 注释）。
        // 这里不做"缺值就跳过"：RunSourceIdentity 构造时就拒了空值，故参数必然存在。
        cmd.add("--" + ARG_SOURCE_SYSTEM + "=" + source.sourceCode());
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