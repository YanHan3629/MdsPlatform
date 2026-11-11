#Requires -Version 7.0
[CmdletBinding()]
param(
 [Parameter(Mandatory)][ValidateSet('BootstrapAdmin','UpgradeAdmin','RecoverAdmin','EnterMaintenance','ExitMaintenance','Inspect')][string]$Action,
 [Parameter(Mandatory)][ValidatePattern('^\d+$')][string]$JobId,
 [Parameter(Mandatory)][string]$Server,
 [Parameter(Mandatory)][string]$User,
 [Parameter(Mandatory)][string]$Instance,
 [Parameter(Mandatory)][string]$Database,
 [int]$Port=10022,
 [string]$TargetUserId,
 [string]$ReasonCode='PLANNED_ADMINISTRATOR_MAINTENANCE',
 [string]$RemoteRoot,
 [string]$WorkspaceRoot,
 [switch]$ReactivateOrganization,
 [switch]$RequestStdin
)
$ErrorActionPreference='Stop'
$python=Get-Command python -ErrorAction SilentlyContinue
if (-not $python) { throw 'Python 3 is required for SSH control; no local application is started.' }
$arguments=@((Join-Path $PSScriptRoot 'accounts.py'),'--action',$Action,'--job-id',$JobId,
 '--server',$Server,'--user',$User,'--port',"$Port",'--instance',$Instance,'--database',$Database,
 '--reason-code',$ReasonCode)
if ($TargetUserId) { $arguments+=@('--target-user-id',$TargetUserId) }
if ($RemoteRoot) { $arguments+=@('--remote-root',$RemoteRoot) }
if ($WorkspaceRoot) { $arguments+=@('--workspace-root',$WorkspaceRoot) }
if ($ReactivateOrganization) { $arguments+='--reactivate-organization' }
if ($RequestStdin) {
 $arguments+='--request-stdin'
 $maintenanceProcessInfo=[Diagnostics.ProcessStartInfo]::new()
 $maintenanceProcessInfo.FileName=$python.Source
 $maintenanceProcessInfo.UseShellExecute=$false
 $maintenanceProcessInfo.CreateNoWindow=$true
 $maintenanceProcessInfo.RedirectStandardInput=$true
 $maintenanceProcessInfo.RedirectStandardOutput=$true
 $maintenanceProcessInfo.RedirectStandardError=$true
 foreach ($maintenanceArgument in $arguments) { [void]$maintenanceProcessInfo.ArgumentList.Add($maintenanceArgument) }
 $maintenanceProcess=[Diagnostics.Process]::new()
 $maintenanceProcess.StartInfo=$maintenanceProcessInfo
 $maintenanceStarted=$false
 try {
  [void]$maintenanceProcess.Start()
  $maintenanceStarted=$true
  $maintenanceOutput=$maintenanceProcess.StandardOutput.ReadToEndAsync()
  $maintenanceError=$maintenanceProcess.StandardError.ReadToEndAsync()
  # Copy bytes directly. JSON and credentials never become shell arguments,
  # command history, PowerShell pipeline objects, or temporary files.
  [Console]::OpenStandardInput().CopyTo($maintenanceProcess.StandardInput.BaseStream)
  $maintenanceProcess.StandardInput.Close()
  $maintenanceProcess.WaitForExit()
  [Console]::Out.Write($maintenanceOutput.GetAwaiter().GetResult())
  [Console]::Error.Write($maintenanceError.GetAwaiter().GetResult())
  $maintenanceExitCode=$maintenanceProcess.ExitCode
 } finally {
  if ($maintenanceStarted -and -not $maintenanceProcess.HasExited) { $maintenanceProcess.Kill($true); $maintenanceProcess.WaitForExit() }
  $maintenanceProcess.Dispose()
 }
 if ($maintenanceExitCode -ne 0) { throw 'HPC administrator maintenance did not complete; inspect the safe result.' }
 return
}
& $python.Source @arguments
if ($LASTEXITCODE -ne 0) { throw 'HPC administrator maintenance did not complete; inspect the safe result.' }
