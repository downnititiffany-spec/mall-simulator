package com.graduation.analytics.mapping;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * canonical 契约的**一次快照**（已装载的契约 + 契约文件原始字节的 sha256）（S2-03）。
 *
 * <p><b>为什么需要它</b>：dry-run 报告里写着 {@code contractChecksum}，激活时要求"当前契约与 dry-run 时一致"。
 * 若两处各自实现"契约文件怎么定位、校验和怎么算"，就会出现两个所有者——同一个契约文件在两处算出不同值
 * 时，报告与激活会互相指控漂移。故把"契约字节的 sha256"收进这一个静态工厂：
 * dry-run 与激活都只能从这里拿快照，**契约版本取自契约本身**（不解析第二处）。</p>
 *
 * <p>不做进程内缓存：缓存是调用方的选择（dry-run 自己缓存一次，激活每次现读——
 * 激活本来就要发现"契约在预览之后被改过"，缓存住反而会掩盖漂移）。</p>
 */
public record ContractSnapshot(CanonicalContract contract, String checksum) {

    /**
     * 装载契约并计算其**原始字节**的 sha256。
     *
     * @param contractPath 契约文件绝对路径（{@code platform.mapping.contract-path}）
     * @throws IllegalStateException 契约文件不存在（启动配置错误，属平台自身问题，不是调用方参数问题）
     */
    public static ContractSnapshot load(Path contractPath) {
        if (!Files.isRegularFile(contractPath)) {
            throw new IllegalStateException("canonical 契约文件不存在（platform.mapping.contract-path）：" + contractPath);
        }
        try {
            byte[] bytes = Files.readAllBytes(contractPath);
            return new ContractSnapshot(CanonicalContractLoader.load(contractPath), MappingHash.sha256Hex(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException("读取 canonical 契约失败: " + contractPath, e);
        }
    }
}
