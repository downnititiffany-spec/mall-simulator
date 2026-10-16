# 当前工作树四应用离线构建（2026-10-10）

## 命令与结果

| 应用 | 命令 | 结果 |
|---|---|---|
| analytics-server | `mvn -o -f analytics-server/pom.xml -DskipTests compile` | Reactor `BUILD SUCCESS`；platform-app 本次编译28个源码文件，其它模块根据增量状态编译/保持最新 |
| mall-simulator | `mvn -o -f mall-simulator/pom.xml -DskipTests compile` | `BUILD SUCCESS`；编译67个源码文件 |
| synthetic-data-generator | `mvn -o -f synthetic-data-generator/pom.xml -DskipTests compile` | `BUILD SUCCESS`；增量目标为最新 |
| web | `npm run build`（cwd=`web/`） | Vite 5.4.21，转换690个模块，构建成功 |

Web 首次从仓库根目录执行因该目录没有 package.json 而失败；改在 `web/` 执行后成功。此失败不是 Web 构建失败。

## 边界

只覆盖四个应用的当前源码编译/前端打包，不运行测试，不代表运行服务、数据库迁移、浏览器验收或阶段7/8全链路通过。没有部署或连接数据库/3306。项目总体阶段与总控验收状态不因此改变。

动态文档编辑前备份后缀：`bak-20261010-full-app-offline-build`。
