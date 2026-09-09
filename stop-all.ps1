$ErrorActionPreference = 'Stop'
& docker compose -f (Join-Path $PSScriptRoot 'compose.yml') stop
if ($LASTEXITCODE -ne 0) { throw '停止项目失败' }
Write-Host '数据空间、检索、问答和模型服务均已停止；容器与存储数据保留。'
