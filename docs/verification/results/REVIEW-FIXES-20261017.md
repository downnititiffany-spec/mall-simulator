# 独立复核发现修复与验收证据恢复

日期：2026-10-17（Asia/Shanghai）。开发工作树：`D:\Develop_code\GraduationProject-wt\v3-dev`，基线`a70ae13c085f45e479a6d8198fee6150a4dfb468`；与远端main@d9cdd6b业务代码一致。本轮修复与测试在该基线上进行，冻结V3.0文档未修改。

## R1 来源路径校验

根因：为了兼容POSIX原生文件系统编码，isRepoRelative忽略所有InvalidPathException，导致NUL及Windows非法路径字符也被接受。登记create/update依赖该判据。

修复：在trim前拒绝控制符、非法文件名字符和不完整Unicode；词法判定不再调用受系统编码影响的Path.of，保留中文合法引用；真实resolveUnderRoot的原生解析失败转为脱敏PARAM_INVALID，不静默通过。

回归补入已有SourceProfileValidatorTest与SourceRegistryServiceTest，覆盖NUL居中/末尾、控制符、非法字符、不完整Unicode、中文合法路径，以及非法登记在写入前被拒绝；没有增加或放宽计数基线。

- 旧代码：44 tests、2 failures、0 errors，两个新反例确实触发失败。
- 修复后：44/44通过、0 failure/error/skipped，Reactor BUILD SUCCESS。
- 完整default：1560收集、1558通过、2个Windows符号链接用例跳过，F0/E0，1400/26/134各基线MATCH，exit0。与fresh检出时的3跳过不同：本工作树有历史Landing，历史巡检实际通过。

## R2 Web相对日志目录

根因：入口在调用者目录创建相对LogDir，Web档切到web后Tee-Object仍使用相对路径，因而写错目录。

修复：目录创建后、任何档位切目录前Resolve-Path为绝对ProviderPath；日志输出、互斥锁与后续汇总共用解析结果。

用原先会失败的相对参数重跑：

```powershell
pwsh -NoProfile -File scripts/run-tests.ps1 -Suite web -RunId reviewfix20261017web -LogDir '.verify\review-fixes-20261017\web-relative'
```

450/450通过、0失败/跳过、Vite构建成功，exit0；日志实际写入仓库根下指定目录。

## 浏览器补验与视觉观察

使用当前构建、临时loopback preview与headless Chromium：

- 12条匿名受保护路由全部跳转/login，0平台API请求、0页面错误。
- 经营周报与决策效果mock流程：6模板、2次合成创建、11请求，意外请求0、页面错误0。
- RuntimeProfile mock流程：新建→测试→确认→激活，11请求，页面错误0、密码输入框0。
- 截图观察：当前云白界面在1280宽视口下导航、模板卡、列表及摘要均可见，未观察到主要控件遮挡。表格长日期/快照值换行；该视觉观察不替代不同视口、可访问性或真实服务走查。
- helper结束后曾留下Windows子Node；按本轮创建时间、完整命令及端口PID归属核验后仅停止该预览PID，5187已无监听。没有操作既有项目服务。

以上仅为客户端、模拟身份/API证据；没有真实平台认证、数据库变更或profile切换。截图与新日志见 [本轮证据目录](../../acceptance/review-fixes-20261017/README.md)。

## R3 原报告接收与日志缺口

用户提供原ZIP，原容器SHA256、65个普通文件逐哈希核对结果、幸存main日志已归档到 [WP-F/收官原件目录](../../acceptance/wp-f-close-archive-20261017/README.md)。此前指出的9个明确notes路径在接收包中均能找到原件，已建立统一映射。

用户确认早期/tmp日志随云电脑更换丢失，不能补造。幸存main default日志可复算1560（1558通过、2跳过）；已收到的工作包按“原报告+PROJECT_STATUS登记”引用。原包未含WP-N1/N2与WP-F1原报告，这些早期真链目前仍只有状态登记可在仓库查阅；不宣称本轮重新执行或原件已经交付。

动态完成清单新增当前汇总；原有逐日条目保留其时点语义。正式残余RES-01—11另集中登记于 [剩余验收清单](../RESIDUAL_ACCEPTANCE.md)。

## 结论与未执行范围

本轮已修复两项复现缺陷，测试通过，并恢复用户提供的原始报告及幸存日志的可移植交付。没有修改验收定性：真实LLM成功、真实认证页面、共享HMS/远程集群、正式3306迁移等仍需各自条件。

Spark代码未改；同日本次独立复核main的389/389、49 suites结果仅作为未改Spark代码的参考，不登记为修复后新跑Spark档。本轮未访问3306、未执行数据库清理、未读取/外发业务数据或调用外部LLM。
