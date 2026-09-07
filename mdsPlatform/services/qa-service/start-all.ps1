param(
    [string]$ComposeFile = "docker-compose.yml"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

if (-not (Test-Path (Join-Path $root "Qwen3.5-0.8B\config.json"))) {
    throw "未找到模型权重目录: $root\Qwen3.5-0.8B（请先复制 Qwen3.5 权重）"
}

Write-Host "构建并启动 vLLM + 问答服务..."
docker compose -f $ComposeFile up -d --build
if ($LASTEXITCODE -ne 0) { throw "docker compose up 失败" }

Write-Host "等待 vLLM 就绪..."
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 5
    try {
        $m = Invoke-RestMethod -Uri "http://localhost:8000/v1/models" -TimeoutSec 5
        if ($m.data.Count -gt 0) { $ready = $true; break }
    } catch { }
}
if (-not $ready) {
    Write-Warning "vLLM 可能仍在加载模型，请稍后访问 http://localhost:18081/health 查看状态"
} else {
    Write-Host "vLLM 已就绪，模型: $($m.data[0].id)"
}

Write-Host "问答服务:  http://localhost:18081"
Write-Host "健康检查:  http://localhost:18081/health"
Write-Host "vLLM API:  http://localhost:8000/v1/models"
