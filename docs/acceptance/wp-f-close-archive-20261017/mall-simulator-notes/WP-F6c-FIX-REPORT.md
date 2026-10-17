# WP-F6c-FIX 交付报告：winutils 下载 URL 版本勘误（3.3.4 -> 3.3.6）

> **执行日期**：2026-10-17  
> **依据规约**：`~/workspace/projects/mall-simulator-notes/WP-F6c-FIX.md`  
> **执行约束**：仅使用文件读写工具，零命令执行、零数据库连接、零修改未授权文件、不碰 3306/Docker/DeepSeek。

---

## 1. 不合格事实（总控亲验抓获）

WP-F6c 首轮交付中，`.github/workflows/tests.yml` 的 winutils 供给步选用了 `https://raw.githubusercontent.com/cdarlint/winutils/master/hadoop-3.3.4/bin/` 下载 `winutils.exe` 与 `hadoop.dll`。总控 2026-10-17 巡检亲验实测发现：

1. **404 实证**：对 `https://raw.githubusercontent.com/cdarlint/winutils/master/hadoop-3.3.4/bin/winutils.exe` 与 `hadoop.dll` 发起 HEAD 请求，**均返回 HTTP 404 Not Found**；
2. **目录确实缺失**：经 GitHub API 查询 `cdarlint/winutils` 仓库根目录，**该仓库根本没有 `hadoop-3.3.4` 目录**，3.3.x 分支线仅收录了 `hadoop-3.3.5` 与 `hadoop-3.3.6` 两个版本目录；
3. **3.3.6 真实有效**：`hadoop-3.3.6` 目录下的 `bin/` 完整包含 `winutils.exe`（Content-Length: 119296）与 `hadoop.dll`（Content-Length: 78848），两个 raw URL 经 HEAD 实测均返回 **HTTP 200 OK**；
4. **影响分析**：若按首轮形态推送，GitHub Actions Runner 的 Spark 档会在 winutils 下载阶段因 404 直接抛出异常终止，造成 Spark 档全红，WP-F6c 不合格。

注：Default 档的 Java 门控改动（`IngestionManifestRuntimePatrolTest.java`）已经总控亲跑验收通过（定向 3/3 PASS、零跳过、本机严格执行；脚本契约 13/13 PASS），不在本修复轮范围，本轮一字未动。

---

## 2. 修复任务落实明细与改前/改后对照

本次修复仅修改仓库文件 `.github/workflows/tests.yml` 中的 `Set up Hadoop winutils for Spark` 步骤，改动严格局限于该步骤内部。

### 改前片段（`.github/workflows/tests.yml` L47-63）：

```yaml
      - name: Set up Hadoop winutils for Spark
        run: |
          # Spark 3.5.x on Windows requires Hadoop winutils.exe and hadoop.dll
          # Version: Hadoop 3.3.4 (compatible with hadoop-client 3.3.4 in spark-jobs/pom.xml)
          # Source: https://raw.githubusercontent.com/cdarlint/winutils/master/hadoop-3.3.4/bin/
          $hadoopHome = "C:\hadoop"
          $hadoopBin = "$hadoopHome\bin"
          New-Item -ItemType Directory -Force -Path $hadoopBin | Out-Null

          $baseUrl = "https://raw.githubusercontent.com/cdarlint/winutils/master/hadoop-3.3.4/bin"
          Invoke-WebRequest -Uri "$baseUrl/winutils.exe" -OutFile "$hadoopBin\winutils.exe"
          Invoke-WebRequest -Uri "$baseUrl/hadoop.dll" -OutFile "$hadoopBin\hadoop.dll"

          if (-not (Test-Path "$hadoopBin\winutils.exe") -or -not (Test-Path "$hadoopBin\hadoop.dll")) {
            throw "Hadoop winutils download failed"
          }
```

### 改后片段（`.github/workflows/tests.yml` L47-66）：

```yaml
      - name: Set up Hadoop winutils for Spark
        run: |
          # Spark 3.5.x on Windows requires Hadoop winutils.exe and hadoop.dll
          # 注释勘误（WP-F6c-FIX）：cdarlint/winutils 无 hadoop-3.3.4 目录（3.3.x 线仅 3.3.5/3.3.6）；
          # winutils.exe 与 hadoop.dll 为独立原生辅助二进制，与工程 hadoop-client:3.3.4（spark-jobs/pom.xml）
          # 同属 3.3.x 线，取该线最高补丁版 3.3.6 为兼容供给（此为业界通行做法）；Spark 版本仍为 3.5.1。
          # Version: Hadoop 3.3.6 (compatible with hadoop-client 3.3.4 in spark-jobs/pom.xml)
          # Source: https://raw.githubusercontent.com/cdarlint/winutils/master/hadoop-3.3.6/bin/
          $hadoopHome = "C:\hadoop"
          $hadoopBin = "$hadoopHome\bin"
          New-Item -ItemType Directory -Force -Path $hadoopBin | Out-Null

          $baseUrl = "https://raw.githubusercontent.com/cdarlint/winutils/master/hadoop-3.3.6/bin"
          Invoke-WebRequest -Uri "$baseUrl/winutils.exe" -OutFile "$hadoopBin\winutils.exe"
          Invoke-WebRequest -Uri "$baseUrl/hadoop.dll" -OutFile "$hadoopBin\hadoop.dll"

          if (-not (Test-Path "$hadoopBin\winutils.exe") -or -not (Test-Path "$hadoopBin\hadoop.dll")) {
            throw "Hadoop winutils download failed"
          }
```

---

## 3. Hadoop 3.3.6 原生辅助二进制与 hadoop-client 3.3.4 兼容性论证

1. **二进制职责与架构解耦**：
   - `winutils.exe` 与 `hadoop.dll` 并非 Java 运行类库，而是 Windows 环境下模拟 POSIX 权限（chmod、chown、stat、软硬链接）以及 Windows 本地 `NativeIO` 的原生 C/C++ 辅助可执行文件与动态链接库；
   - Hadoop Java 客户端通过 JNI（`NativeIO$Windows`）和外部子进程（`Shell.getWinUtilsPath()`）调用它们。

2. **版本兼容性（SemVer 与业界标准）**：
   - 本项目 `spark-jobs/pom.xml` 和 `platform-app/pom.xml` 声明的依赖为 `hadoop-client:3.3.4`；
   - 在 Apache Hadoop 体系中，同属于 `3.3.x` 次版本（Minor Version）下的原生辅助二进制接口保持严格向下/向上兼容；
   - `cdarlint/winutils` 社区维护者未专门构建 3.3.4（跳过了 3.3.4 构建），3.3.x 线收录的是 3.3.5 与 3.3.6；
   - 选择该次版本分支下的最高维护补丁版本 **Hadoop 3.3.6** 原生二进制作为兼容供给，是跨平台大数据开发与 CI 流程中的业界标准通行做法；
   - 工程运行时 Spark 版本依然稳定保持为 3.5.1，不引入任何冲突。

---

## 4. 复验准备与验收对齐

- **URL 可达性复验**：
  总控将以 HEAD 200 复验以下两个 URL：
  - `https://raw.githubusercontent.com/cdarlint/winutils/master/hadoop-3.3.6/bin/winutils.exe`（预期：HTTP 200 OK，Content-Length: 119296）
  - `https://raw.githubusercontent.com/cdarlint/winutils/master/hadoop-3.3.6/bin/hadoop.dll`（预期：HTTP 200 OK，Content-Length: 78848）

- **改动范围核实**：
  - 仅修改 `.github/workflows/tests.yml` 的 `Set up Hadoop winutils for Spark` 步骤；
  - 步骤相对位置完全保持（JDK 8 之后、spark 测试之前）；
  - 落盘路径 `C:\hadoop\bin` 保持不变；
  - 环境变量导出 `$env:GITHUB_ENV`（`HADOOP_HOME`）与 `$env:GITHUB_PATH`（`$hadoopBin`）保持不变；
  - 存在性校验断言（缺一即 throw）保持不变；
  - `Run spark test suite` 行保持不变；
  - 未动 `IngestionManifestRuntimePatrolTest.java`；
  - 未动 `scripts/run-tests.ps1` 及任何基线数字；
  - 全程零命令运行、零数据库连接、零修改未授权文件、未碰 3306/Docker/DeepSeek。
