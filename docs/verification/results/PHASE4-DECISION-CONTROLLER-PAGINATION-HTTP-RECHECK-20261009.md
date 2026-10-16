# 阶段 4 决策分页控制器 HTTP 契约复核

日期：2026-10-09  
范围：当前工作树 `DecisionController` 分页路由的响应序列化与会话保护。

## 验证

Windows JDK 17.0.12 / Maven 3.9.14，离线定向命令：

```powershell
mvn -o -f analytics-server/pom.xml -pl platform-app -am `
  '-Dtest=DecisionControllerPaginationHttpTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

`DecisionControllerPaginationHttpTest` 1/1 PASS，0 failure、0 error、0 skipped；七模块 Maven reactor `BUILD SUCCESS`。MockMvc 在模拟有效 Bearer 会话下请求 `/api/v1/decisions/page`，检查 items/page/size/total/totalPages/sort 信封字段；匿名请求返回 HTTP 401。

## 证据边界

测试用 Mockito `DecisionService`，通过 standalone MockMvc 装配控制器和 `AuthInterceptor`；不证明完整 Spring Boot Filter/权限矩阵配置、真实服务认证、数据库非空分页/排序或浏览器展示。没有连接数据库、启停服务或访问 3306；阶段 4 保持开放。
