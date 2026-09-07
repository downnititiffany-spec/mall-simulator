package com.graduation.analytics.runtime;

import com.graduation.analytics.runtime.entity.RuntimeProfile;

import java.util.List;

/**
 * 运行环境服务（§8.2）：create/update/test/activate/disable/getActive。
 * 激活流程（§8.3）：DRAFT → 测试四项 → ACTIVE（原 ACTIVE 变 DISABLED，version 递增）。
 */
public interface RuntimeProfileService {

    RuntimeProfile create(RuntimeProfile profile);

    RuntimeProfile update(RuntimeProfile profile);

    /**
     * 连通测试：按 profile.type 执行适用项（Landing 读写 / Hive SELECT 1 /
     * 最小 Spark 任务 / MetricStore 查询），结果不落 ACTIVE，仅置 TESTING 并返回明细。
     */
    TestResult test(Long profileId);

    /** 四项适用项全部通过 → 旧 ACTIVE 变 DISABLED，本环境 ACTIVE，version+1 */
    RuntimeProfile activate(Long profileId);

    RuntimeProfile disable(Long profileId);

    /** 当前唯一 ACTIVE 环境（无则抛异常） */
    RuntimeProfile getActive();

    RuntimeProfile get(Long id);

    List<RuntimeProfile> list();

    record TestResult(Long profileId, boolean allPassed,
                      List<CheckDetail> checks, String summary) {
    }

    /**
     * 连通检查项：applicable=false 表示该环境类型不适用此项（如 LOCAL 无 Hive），
     * 不阻断激活，但 summary 中如实标注 SKIPPED；applicable=true 且未通过 → 阻断。
     */
    record CheckDetail(String name, boolean passed, boolean applicable, String detail) {

        public CheckDetail(String name, boolean passed, String detail) {
            this(name, passed, true, detail);
        }
    }
}