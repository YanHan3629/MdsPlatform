#Requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('Start','Status','Connect','Stop','SetupAdmin')][string]$Action,
    [ValidateSet('CPU','GPU')][string]$Mode = 'GPU',
    [ValidateSet('standard','long')][string]$QaProfile = 'standard',
    [ValidateRange(1,120)][int]$Minutes = 60,
    [ValidateRange(1,2147483647)][int]$JobId,
    [ValidatePattern('^[A-Za-z0-9.-]+$')][string]$Server = '192.168.212.236'
)
$ErrorActionPreference = 'Stop'
$deliveryRoot = '/share/home/u08162/apps/mds-manual-20261119'
$sshOptions = @('-o','BatchMode=yes','-o','ConnectTimeout=15','-o','StrictHostKeyChecking=yes',
    '-o','HostKeyAlias=[logini.tongji.edu.cn]:10022','-p','10022')
if ($Action -in @('Start','SetupAdmin')) {
    $pin = & ssh @sshOptions "u08162@$Server" "sha256sum $deliveryRoot/apps/mds-platform/runtime-bindings.json"
    if ($LASTEXITCODE -ne 0 -or (($pin -join '') -split '\s+')[0] -ne '993f88fe2548959d09bc360d4f6c7027f971f8292de8f979941af172e368f93a') {
        throw 'Delivery 3733654 binding mismatch. No session or account change was started.'
    }
}
if ($Action -eq 'SetupAdmin') {
    if (-not $JobId) { throw 'SetupAdmin requires the current running JobId.' }
    & ssh -t @sshOptions "u08162@$Server" "python3 $deliveryRoot/manual-admin.py --job $JobId --action setup"
    if ($LASTEXITCODE -ne 0) { throw 'Administrator password setup did not complete.' }
    return
}
if ($Action -eq 'Start' -and $Mode -eq 'CPU') {
    $result = & ssh @sshOptions "u08162@$Server" "python3 $deliveryRoot/manual-cpu-start.py --minutes $Minutes"
    if ($LASTEXITCODE -ne 0) { throw 'Delivery CPU session submission failed.' }
    ($result -join "`n") | ConvertFrom-Json
    return
}
$options = @{Action=$Action;WorkspaceRoot=$deliveryRoot;Server=$Server}
if ($JobId) { $options.JobId=$JobId }
if ($Action -eq 'Start') {
    $options.Mode=$Mode; $options.Minutes=$Minutes
    $options.GpuType='A800'; $options.QaProfile=$QaProfile
}
& (Join-Path $PSScriptRoot 'session.ps1') @options
