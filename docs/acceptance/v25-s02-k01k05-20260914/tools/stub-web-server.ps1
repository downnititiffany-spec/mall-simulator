# V25-S02/K-04 取证用：**本地替身 HTTP 服务**
#
# 为什么需要：run-demo.ps1 的 [0]/[0.1] 会先探活三个真程序（8090/8091/8092）并登录，
# 末尾还会读 outbox/pipeline 状态。要单独逼出「清场门禁」这一段而又不起真程序、不碰真库，
# 就需要一个只回答这几个端点的替身。它只监听回环地址上的指定端口，返回固定 JSON，
# 不访问文件系统、不连数据库。
#
# ⚠️ 证据边界（必须写进 README）：本替身返回的是**固定假数据**，
#   因此只有下面两项是本驱动的有效判据：
#     (1) 清场门禁的拒绝/放行结论（看 [0.5] 的行文）；
#     (2) mysql 客户端替身被调用的**次数与参数**（raw/k04-stub-invocations.tsv）。
#   脚本后半段打印的阶段数/行数/合计**不具证据力**（那是对替身假数据的回显）。
param([int]$Port = 18099)
$ErrorActionPreference = 'Stop'
$listener = [System.Net.HttpListener]::new()
$listener.Prefixes.Add("http://127.0.0.1:$Port/")
$listener.Start()
Write-Output "stub-web-server listening on http://127.0.0.1:$Port/"
try {
  while ($listener.IsListening) {
    $ctx = $listener.GetContext()
    $path = $ctx.Request.Url.AbsolutePath
    $body = switch -Regex ($path) {
      'auth/login'      { '{"code":0,"message":"ok","data":{"token":"stub-token","user":{"realName":"stub-admin","role":"ADMIN"}}}' }
      'outbox/status'   { '{"code":0,"message":"ok","data":{"pendingCount":0,"latestFile":null}}' }
      # 顺序要紧：pipeline-runs 必须先于其它规则匹配（switch -Regex 取首个命中）
      'pipeline-runs'   { '{"code":0,"message":"ok","data":{"status":"SUCCESS","currentStage":"PUBLISH","targetSnapshotId":"S20260914_STUB","runId":0}}' }
      'ingestion'       { '{"code":0,"message":"ok","data":{"batchId":"STUB-BATCH","rows":0,"quarantine":0}}' }
      '/$'              { '{"code":0,"message":"ok","data":"stub-mall-root"}' }
      default           { '{"code":0,"message":"ok","data":{"stub":true}}' }
    }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    $ctx.Response.StatusCode = 200
    $ctx.Response.ContentType = 'application/json; charset=utf-8'
    $ctx.Response.ContentLength64 = $bytes.Length
    $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length)
    $ctx.Response.Close()
    Write-Output "served $($ctx.Request.HttpMethod) $path"
  }
} finally {
  $listener.Stop()
}
