param(
    [Parameter(Mandatory=$true)][ValidateSet('Start','Status','Connect','Stop')][string]$Action,
    [ValidateSet('GPU','CPU')][string]$Mode = 'GPU',
    [ValidateRange(1,120)][int]$Minutes = 30,
    [ValidateRange(1,2147483647)][int]$JobId,
    [ValidatePattern('^[A-Za-z0-9.-]+$')][string]$Server = 'logini.tongji.edu.cn',
    [ValidatePattern('^/[A-Za-z0-9_./-]+$')][string]$WorkspaceRoot
)
$ErrorActionPreference = 'Stop'
$sshOptions = @('-o','BatchMode=yes','-o','ConnectTimeout=15','-o','StrictHostKeyChecking=yes',
    '-o','HostKeyAlias=[logini.tongji.edu.cn]:10022','-p','10022')
if ($WorkspaceRoot -and ($WorkspaceRoot.Split('/') -contains '..')) { throw 'Invalid workspace root' }
$remoteAppRoot = if ($WorkspaceRoot) { $WorkspaceRoot.TrimEnd('/') + '/apps/mds-platform' } else { '/share/home/u08162/apps/mds-platform' }
$controlPrefix = 'python3 ' + $remoteAppRoot + '/session_control.py '
$command = $controlPrefix + $Action.ToLowerInvariant()
if ($WorkspaceRoot) { $command += ' --workspace-root ' + $WorkspaceRoot }
if ($JobId) { $command += " --job $JobId" }
if ($Action -eq 'Start') {
    $command += " --minutes $Minutes"
    if ($Mode -eq 'CPU') { $command += ' --cpu-only' }
}
$result = & ssh @sshOptions "u08162@$Server" $command
if ($LASTEXITCODE -ne 0) { throw 'HPC session command failed; inspect the message above.' }
$info = ($result -join "`n") | ConvertFrom-Json
if ($Action -ne 'Connect') {
    if ($Action -eq 'Status' -and -not $JobId -and (-not $info)) { Write-Host 'No active MDS jobs.' }
    else { $info }
    return
}
if (-not $info.ready -or $info.node -notmatch '^[a-z]+[0-9]+$' -or
    $info.hostKey -notmatch '^ssh-ed25519 [A-Za-z0-9+/=]+$') { throw 'Invalid connection details' }
foreach ($port in @(18888,19000)) {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback,$port)
    try { $listener.Start() } finally { $listener.Stop() }
}
$folder = Join-Path $PSScriptRoot '../../reports/hpc-sessions'
New-Item -ItemType Directory -Force -Path $folder | Out-Null
$knownHosts = [IO.Path]::GetFullPath((Join-Path $folder "known-hosts-$JobId"))
[IO.File]::WriteAllText($knownHosts,"$($info.node) $($info.hostKey)`n",[Text.Encoding]::ASCII)
$proxy = "ssh -o BatchMode=yes -o StrictHostKeyChecking=yes -o HostKeyAlias=[logini.tongji.edu.cn]:10022 -p 10022 -W %h:%p u08162@$Server"
Write-Host "HPC job $JobId on $($info.node). Open http://127.0.0.1:18888/data-space/index.html"
Write-Host "Keep this tunnel open during testing. Ctrl+C closes the tunnel; Stop releases the HPC allocation."
& ssh -N -o BatchMode=yes -o StrictHostKeyChecking=yes -o "UserKnownHostsFile=$knownHosts" `
    -o "ProxyCommand=$proxy" -o ExitOnForwardFailure=yes -o ServerAliveInterval=20 -o ServerAliveCountMax=3 `
    -L '127.0.0.1:18888:127.0.0.1:8888' -L '127.0.0.1:19000:127.0.0.1:19000' "u08162@$($info.node)"
if ($LASTEXITCODE -ne 0) {
    $statusCommand = $controlPrefix + 'status --job ' + $JobId
    if ($WorkspaceRoot) { $statusCommand += ' --workspace-root ' + $WorkspaceRoot }
    $status = & ssh @sshOptions "u08162@$Server" $statusCommand
    if ($LASTEXITCODE -eq 0 -and (($status -join "`n") | ConvertFrom-Json).active -eq $false) {
        Write-Host 'The HPC job has ended; its SSH tunnel is closed.'
    } else {
        Write-Warning 'SSH tunnel closed. Use Status to check the job, and Stop if testing is finished.'
    }
}
