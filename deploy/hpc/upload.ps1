param(
    [Parameter(Mandatory=$true)][string[]]$Files,
    [string]$Server = 'logini.tongji.edu.cn'
)
$ErrorActionPreference = 'Stop'
$batch = Join-Path $PSScriptRoot ('test-results/sftp-' + [guid]::NewGuid().ToString('N') + '.txt')
New-Item -ItemType Directory -Force (Split-Path $batch) | Out-Null
$sshOptions = @('-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-o', 'HostKeyAlias=[logini.tongji.edu.cn]:10022', '-o', 'ConnectTimeout=20', '-o', 'ServerAliveInterval=20', '-o', 'ServerAliveCountMax=9')
foreach ($file in $Files) {
    $item = Get-Item -LiteralPath $file
    if ($item.Name -notmatch '^[A-Za-z0-9_.-]+$') { throw 'Unsupported transfer filename' }
    $local = $item.FullName.Replace('\','/')
    $remote = 'packages/mds-platform/' + $item.Name
    $complete = $false
    for ($attempt=1; $attempt -le 3; $attempt++) {
        $probe = "if test -f $remote; then stat -c %s $remote; else echo MISSING; fi"
        $size = & ssh @sshOptions -p 10022 "u08162@$Server" $probe
        if ($LASTEXITCODE -ne 0) { continue }
        if ($size -eq 'MISSING') {
            $verb = 'put'
        } elseif ($size -match '^\d+$') {
            if ([long]$size -gt $item.Length) { throw "Remote file is larger than the source: $remote" }
            if ([long]$size -eq $item.Length) {
                Write-Output "$($item.Name): size complete; remote SHA-256 verification still required."
                $complete = $true
                break
            }
            $verb = 'reput'
        } else { throw "Unexpected remote size response for $remote" }
        [IO.File]::WriteAllLines($batch, @($verb + ' "' + $local + '" "' + $remote + '"'), [Text.UTF8Encoding]::new($false))
        & sftp @sshOptions -P 10022 -b $batch "u08162@$Server"
        if ($LASTEXITCODE -eq 0) { $complete = $true; break }
        Write-Output "$($item.Name): interrupted attempt $attempt of 3; partial file retained."
    }
    if (-not $complete) { throw "Upload failed for $($item.Name); partial file retained." }
}
