param([switch]$NoBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'mdsPlatform/services/qa-service/Qwen3.5-0.8B/config.json'))) {
    throw '未找到 Qwen3.5-0.8B 模型，请按 README 放置本地权重。'
}
Push-Location $projectRoot
try {
    if (-not $NoBuild) {
        Push-Location 'mdsPlatform/services/backend'
        try {
            & mvn package
            if ($LASTEXITCODE -ne 0) { throw '后端构建或测试失败' }
        } finally { Pop-Location }
        & docker compose -f compose.yml up -d --build
    } else {
        & docker compose -f compose.yml up -d --no-build
    }
    if ($LASTEXITCODE -ne 0) { throw '统一项目启动失败，请检查 docker compose logs' }
    Write-Host '项目：multimodal-dataspace'
    Write-Host '统一入口：http://localhost:8888/data-space/index.html#qa'
} finally { Pop-Location }
