param(
    [int]$SearchHostPort
)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

Set-Location $scriptDir

if ($PSBoundParameters.ContainsKey("SearchHostPort")) {
    $env:MM_SEARCH_HOST_PORT = "$SearchHostPort"
    Write-Host "Using search-service host port: $SearchHostPort"
}

Write-Host "Starting backend stack (db/minio/redis/backend/search-service)..."
Write-Host "Prewarming index-builder image via docker compose one-shot service..."
docker compose up -d --build

if ($LASTEXITCODE -ne 0) {
    throw "docker compose up failed with exit code $LASTEXITCODE"
}

Write-Host "Multimodal stack is ready."
