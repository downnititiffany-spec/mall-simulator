# WP-F8b-FIX2 修复报告

## 1. 改动背景与根因
在第二轮巡检中，`ArchivedSnapshotIsolationTest` 运行 4 个测试时出现 1 个错误：
- **报错类型**：`org.mockito.exceptions.misusing.UnnecessaryStubbingException`
- **报错定位**：`ArchivedSnapshotIsolationTest.setUp(ArchivedSnapshotIsolationTest.java:78)`
- **根因分析**：
  在 `@BeforeEach setUp()` 中统一设置了 `qualityGate.statusForRun(any())` 桩。该桩为测试 `AnalysisService` 链路的 3 个测试用例所需；而第 4 个用例 `mySqlMetricStoreQueryEnsuresSnapshotIsolation()` 直接测试 `MySqlMetricStore` 读侧快照隔离性，不经过 `AnalysisService`。
  由于使用了 `@ExtendWith(MockitoExtension.class)`，严格模式下未在单个测试用例中消费的桩会被判定为多余桩（UnnecessaryStubbing）。

## 2. 改动位置
- **目标文件**：`analytics-server/metric-analysis/src/test/java/com/graduation/analytics/analysis/ArchivedSnapshotIsolationTest.java`
- **改动位置 1**：静态导入区域（约 L29）
- **改动位置 2**：`setUp()` 方法（约 L76-L80）

## 3. 改动前后文本

### 3.1 静态导入
#### 改动前
```java
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
```

#### 改动后
```java
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
```

### 3.2 setUp() 方法
#### 改动前
```java
    @BeforeEach
    void setUp() {
        analysisService = new AnalysisService(adsReader, metricStore, qualityGate, definitionMapper, rfmService);
        when(qualityGate.statusForRun(any())).thenReturn(MetricQualityGate.PASS);
    }
```

#### 改动后
```java
    @BeforeEach
    void setUp() {
        analysisService = new AnalysisService(adsReader, metricStore, qualityGate, definitionMapper, rfmService);
        lenient().when(qualityGate.statusForRun(any())).thenReturn(MetricQualityGate.PASS);
    }
```

## 4. 影响与结论
1. 将 `qualityGate.statusForRun(any())` 声明为 `lenient()`，使 MockitoExtension 允许该桩在不经过 `AnalysisService` 的底层 store 隔离性测试用例中不被触发，消除 `UnnecessaryStubbingException`。
2. 既有用例的断言、测试行为完全保持不变，其余 3 个依赖质量门禁的业务分析用例仍然正常消费此桩。
3. 严格遵循规约约束：未改动任何生产代码，未修改其他交付文件，改动精准且最小化。
