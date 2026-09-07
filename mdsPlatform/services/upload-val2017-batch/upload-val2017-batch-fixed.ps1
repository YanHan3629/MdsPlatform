param(
    [string]$BackendBase = "http://localhost:8080",
    [string]$Token = "",
    [string]$DatasetId = "",
    [string]$VersionId = "",
    [string]$LocalRoot = "",
    [string]$FolderAlias = "val2017",
    [string]$BasePath = "/images",
    [int]$BatchSize = 500,
    [int]$ExpireSeconds = 3600,
    [int]$RetryCount = 2,
    [int]$BeginUploadTimeoutSec = 900,
    [int]$PutTimeoutSec = 120
)

$ErrorActionPreference = "Stop"

# 默认从独立存储目录读取；DATASPACE_STORAGE_ROOT 可覆盖存储根目录。
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($LocalRoot)) {
    $storageRoot = $env:DATASPACE_STORAGE_ROOT
    if ([string]::IsNullOrWhiteSpace($storageRoot)) {
        $storageRoot = Join-Path $scriptDir "../../../../Multimodal Data Space Platform"
    }
    $LocalRoot = Join-Path $storageRoot "dataset/val2017"
}

function Normalize-SlashPath {
    param([string]$Path)
    return ($Path -replace '\\','/').Trim()
}

function Invoke-BeginUpload {
    param(
        [string]$Url,
        [string]$BearerToken,
        [object]$RequestBody,
        [int]$TimeoutSec
    )

    $json = $RequestBody | ConvertTo-Json -Depth 8

    return Invoke-RestMethod `
        -Uri $Url `
        -Method POST `
        -Headers @{ Authorization = "Bearer $BearerToken" } `
        -ContentType "application/json" `
        -TimeoutSec $TimeoutSec `
        -Body $json
}

function Get-HttpErrorDetail {
    param([System.Exception]$Exception)

    $statusCode = $null
    $body = $null

    try {
        $response = $Exception.Response
        if ($response) {
            if ($response.StatusCode) {
                $statusCode = [int]$response.StatusCode
            }
            $stream = $response.GetResponseStream()
            if ($stream) {
                $reader = New-Object System.IO.StreamReader($stream)
                $body = $reader.ReadToEnd()
                $reader.Close()
            }
        }
    }
    catch {
        # ignore secondary parsing errors
    }

    if ([string]::IsNullOrWhiteSpace($body)) {
        $body = $Exception.Message
    }

    return @{
        StatusCode = $statusCode
        Body = $body
    }
}

function Get-VersionStatus {
    param(
        [string]$BackendBaseUrl,
        [string]$BearerToken,
        [string]$DatasetIdValue,
        [string]$VersionIdValue
    )

    $url = "$BackendBaseUrl/api/mm/datasets/$DatasetIdValue/versions/$VersionIdValue"
    $resp = Invoke-RestMethod `
        -Uri $url `
        -Method GET `
        -Headers @{ Authorization = "Bearer $BearerToken" }

    return $resp.versionStatus
}

function Upload-FileByPresignedUrl {
    param(
        [string]$UploadUrl,
        [string]$LocalFile,
        [string]$ContentType,
        [int]$MaxRetry,
        [int]$TimeoutSec
    )

    $attempt = 0
    do {
        try {
            Invoke-WebRequest `
                -Uri $UploadUrl `
                -Method PUT `
                -InFile $LocalFile `
                -ContentType $ContentType `
                -TimeoutSec $TimeoutSec `
                -UseBasicParsing | Out-Null

            return @{
                Success = $true
                ErrorMessage = ""
            }
        }
        catch {
            $attempt++
            $msg = $_.Exception.Message
            if ($attempt -gt $MaxRetry) {
                return @{
                    Success = $false
                    ErrorMessage = $msg
                }
            }
            Start-Sleep -Seconds ([Math]::Min(2 * $attempt, 8))
        }
    } while ($true)
}

if ([string]::IsNullOrWhiteSpace($Token)) {
    throw "Missing -Token"
}
if ([string]::IsNullOrWhiteSpace($DatasetId)) {
    throw "Missing -DatasetId"
}
if ([string]::IsNullOrWhiteSpace($VersionId)) {
    throw "Missing -VersionId"
}
if (-not (Test-Path -LiteralPath $LocalRoot)) {
    throw "Local folder not found: $LocalRoot"
}
if ($BatchSize -le 0) {
    throw "BatchSize must be greater than 0"
}
if ($BeginUploadTimeoutSec -le 0) {
    throw "BeginUploadTimeoutSec must be greater than 0"
}
if ($PutTimeoutSec -le 0) {
    throw "PutTimeoutSec must be greater than 0"
}

$currentVersionStatus = Get-VersionStatus `
    -BackendBaseUrl $BackendBase `
    -BearerToken $Token `
    -DatasetIdValue $DatasetId `
    -VersionIdValue $VersionId

if ([string]::IsNullOrWhiteSpace($currentVersionStatus)) {
    throw "Cannot fetch version status for versionId=$VersionId"
}

Write-Host ("Current version status: {0}" -f $currentVersionStatus)
if ($currentVersionStatus -ne "DRAFT") {
    throw ("Version {0} is {1}. Upload is only allowed when version status is DRAFT. Create a new version and retry." -f $VersionId, $currentVersionStatus)
}

Write-Host "Scanning folder: $LocalRoot"

$jpgFiles = Get-ChildItem -LiteralPath $LocalRoot -Recurse -File | Where-Object {
    $_.Extension -match '^\.(jpg|jpeg)$'
} | Sort-Object FullName

if (-not $jpgFiles -or $jpgFiles.Count -eq 0) {
    throw "No jpg/jpeg files found"
}

Write-Host ("Found {0} jpg/jpeg files" -f $jpgFiles.Count)

$fileItems = New-Object System.Collections.Generic.List[object]
$localMap = @{}

foreach ($file in $jpgFiles) {
    $relativeInsideFolder = $file.FullName.Substring($LocalRoot.Length).TrimStart('\') -replace '\\','/'
    $relativePath = "$FolderAlias/$relativeInsideFolder"
    $relativePath = Normalize-SlashPath $relativePath

    $localMap[$relativePath] = @{
        FullName = $file.FullName
        ContentType = "image/jpeg"
        Size = $file.Length
    }

    $fileItems.Add([PSCustomObject]@{
        relativePath = $relativePath
        contentType  = "image/jpeg"
    })
}

$totalFiles = $fileItems.Count
$totalBatches = [Math]::Ceiling($totalFiles / $BatchSize)
$beginUrl = "$BackendBase/api/mm/datasets/$DatasetId/versions/$VersionId/folders:begin-upload"

$globalSuccess = 0
$globalFailed = 0
$batchNo = 0
$failedItems = New-Object System.Collections.Generic.List[object]
$batchSummaries = New-Object System.Collections.Generic.List[object]

for ($offset = 0; $offset -lt $totalFiles; $offset += $BatchSize) {
    $batchNo++
    $take = [Math]::Min($BatchSize, $totalFiles - $offset)
    $batch = $fileItems.GetRange($offset, $take)

    Write-Host ""
    Write-Host ("========== Batch {0} / {1} ==========" -f $batchNo, $totalBatches)
    Write-Host ("File range: {0} - {1}" -f ($offset + 1), ($offset + $take))

    $requestBody = @{
        basePath = $BasePath
        expireSeconds = $ExpireSeconds
        files = @(
            $batch | ForEach-Object {
                @{
                    relativePath = $_.relativePath
                    contentType  = $_.contentType
                }
            }
        )
    }

    $beginStartedAt = Get-Date
    Write-Host ("Begin-upload request sending... timeout={0}s, fileCount={1}" -f $BeginUploadTimeoutSec, $take)
    try {
        $beginResp = Invoke-BeginUpload `
            -Url $beginUrl `
            -BearerToken $Token `
            -RequestBody $requestBody `
            -TimeoutSec $BeginUploadTimeoutSec
    }
    catch {
        $detail = Get-HttpErrorDetail -Exception $_.Exception
        if ($detail.StatusCode) {
            $err = "HTTP $($detail.StatusCode): $($detail.Body)"
        } else {
            $err = [string]$detail.Body
        }
        Write-Warning ("Batch {0} begin-upload failed: {1}" -f $batchNo, $err)

        foreach ($item in $batch) {
            $globalFailed++
            $failedItems.Add([PSCustomObject]@{
                BatchNo = $batchNo
                RelativePath = $item.relativePath
                LocalFile = $localMap[$item.relativePath].FullName
                Stage = "begin-upload"
                ErrorMessage = $err
            })
        }

        $batchSummaries.Add([PSCustomObject]@{
            BatchNo = $batchNo
            BatchSize = $take
            SuccessCount = 0
            FailedCount = $take
            BeginUploadOk = $false
        })

        continue
    }
    $beginElapsed = ((Get-Date) - $beginStartedAt).TotalSeconds
    Write-Host ("Begin-upload response received in {0:N1}s" -f $beginElapsed)

    if (-not $beginResp.files) {
        $err = "Response missing files field"
        Write-Warning ("Batch {0} invalid response: {1}" -f $batchNo, $err)

        foreach ($item in $batch) {
            $globalFailed++
            $failedItems.Add([PSCustomObject]@{
                BatchNo = $batchNo
                RelativePath = $item.relativePath
                LocalFile = $localMap[$item.relativePath].FullName
                Stage = "begin-upload-response"
                ErrorMessage = $err
            })
        }

        $batchSummaries.Add([PSCustomObject]@{
            BatchNo = $batchNo
            BatchSize = $take
            SuccessCount = 0
            FailedCount = $take
            BeginUploadOk = $false
        })

        continue
    }

    $batchSuccess = 0
    $batchFailed = 0

    for ($idx = 0; $idx -lt $beginResp.files.Count; $idx++) {
        $uploadItem = $beginResp.files[$idx]
        $relativePath = $uploadItem.relativePath
        $uploadUrl = $uploadItem.url

        if (($idx + 1) -eq 1 -or (($idx + 1) % 25 -eq 0) -or ($idx + 1) -eq $beginResp.files.Count) {
            Write-Host ("Uploading {0}/{1}: {2}" -f ($idx + 1), $beginResp.files.Count, $relativePath)
        }

        if ([string]::IsNullOrWhiteSpace($relativePath) -or [string]::IsNullOrWhiteSpace($uploadUrl)) {
            $batchFailed++
            $globalFailed++
            $failedItems.Add([PSCustomObject]@{
                BatchNo = $batchNo
                RelativePath = $relativePath
                LocalFile = ""
                Stage = "upload-url"
                ErrorMessage = "relativePath or url is empty"
            })
            continue
        }

        if (-not $localMap.ContainsKey($relativePath)) {
            $batchFailed++
            $globalFailed++
            $failedItems.Add([PSCustomObject]@{
                BatchNo = $batchNo
                RelativePath = $relativePath
                LocalFile = ""
                Stage = "local-map"
                ErrorMessage = "Cannot find local file for relativePath"
            })
            continue
        }

        $localInfo = $localMap[$relativePath]
        $localFile = $localInfo.FullName
        $contentType = $localInfo.ContentType

        $uploadResult = Upload-FileByPresignedUrl `
            -UploadUrl $uploadUrl `
            -LocalFile $localFile `
            -ContentType $contentType `
            -MaxRetry $RetryCount `
            -TimeoutSec $PutTimeoutSec

        if ($uploadResult.Success) {
            $batchSuccess++
            $globalSuccess++
            Write-Host ("[OK]   {0}" -f $relativePath)
        }
        else {
            $batchFailed++
            $globalFailed++
            Write-Warning ("[FAIL] {0} :: {1}" -f $relativePath, $uploadResult.ErrorMessage)

            $failedItems.Add([PSCustomObject]@{
                BatchNo = $batchNo
                RelativePath = $relativePath
                LocalFile = $localFile
                Stage = "put-object"
                ErrorMessage = $uploadResult.ErrorMessage
            })
        }
    }

    $batchSummaries.Add([PSCustomObject]@{
        BatchNo = $batchNo
        BatchSize = $take
        SuccessCount = $batchSuccess
        FailedCount = $batchFailed
        BeginUploadOk = $true
    })

    Write-Host ("Batch {0} done: success={1}, failed={2}" -f $batchNo, $batchSuccess, $batchFailed)
}

Write-Host ""
Write-Host "==================== Summary ===================="
Write-Host ("Total files : {0}" -f $totalFiles)
Write-Host ("Success     : {0}" -f $globalSuccess)
Write-Host ("Failed      : {0}" -f $globalFailed)

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$summaryPath = Join-Path (Get-Location) ("upload_summary_{0}.json" -f $timestamp)
$failedPath = Join-Path (Get-Location) ("upload_failed_{0}.json" -f $timestamp)

$summaryObj = [PSCustomObject]@{
    backendBase = $BackendBase
    datasetId = $DatasetId
    versionId = $VersionId
    localRoot = $LocalRoot
    folderAlias = $FolderAlias
    basePath = $BasePath
    batchSize = $BatchSize
    expireSeconds = $ExpireSeconds
    retryCount = $RetryCount
    totalFiles = $totalFiles
    successCount = $globalSuccess
    failedCount = $globalFailed
    batches = $batchSummaries
    generatedAt = (Get-Date).ToString("s")
}

$summaryObj | ConvertTo-Json -Depth 8 | Out-File -FilePath $summaryPath -Encoding utf8
$failedItems | ConvertTo-Json -Depth 8 | Out-File -FilePath $failedPath -Encoding utf8

Write-Host ("Summary file: {0}" -f $summaryPath)
Write-Host ("Failed file : {0}" -f $failedPath)
