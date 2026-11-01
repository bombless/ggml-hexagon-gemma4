param(
    [Parameter(Mandatory=$true)][ValidateSet('fastrpc-mempool','dspqueue')][string]$Backend,
    [Parameter(Mandatory=$true)][string]$Apk,
    [string]$Serial
)
$ErrorActionPreference = 'Stop'
$Apk = (Resolve-Path $Apk).Path
$adb = (Get-Command adb -ErrorAction Stop).Source
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
$temp = Join-Path ([System.IO.Path]::GetTempPath()) ("gemma4-hexagon-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
try {
    $metadataEntry = $zip.GetEntry('assets/hexagon/backend.properties')
    if (-not $metadataEntry) { throw 'APK is missing assets/hexagon/backend.properties' }
    $reader = [IO.StreamReader]::new($metadataEntry.Open())
    $metadataText = $reader.ReadToEnd(); $reader.Dispose()
    $metadata = @{}
    foreach ($line in ($metadataText -split "`r?`n")) {
        if ($line -match '^([^=]+)=(.*)$') { $metadata[$matches[1]] = $matches[2] }
    }
    if ($metadata.backend -ne $Backend -or $metadata.dsp -ne 'HTP-v75' -or $metadata.abi -ne 'arm64-v8a') {
        throw "APK identity mismatch: backend=$($metadata.backend), dsp=$($metadata.dsp), abi=$($metadata.abi); requested $Backend / HTP-v75 / arm64-v8a"
    }
    if ($metadata.upstream_commit -ne 'a5e6be5e032eaf22e6a1154e2b6ab34a76a13a8d') { throw 'APK upstream pin mismatch' }

    $apEntries = @($zip.Entries | Where-Object { $_.FullName -like 'lib/arm64-v8a/*.so' })
    foreach ($required in @('libgemma4_jni.so','libllama.so','libggml.so','libggml-base.so','libggml-hexagon.so')) {
        if (-not ($apEntries | Where-Object { $_.Name -eq $required })) { throw "APK missing AP library $required" }
    }
    $skeletonEntry = $zip.GetEntry('assets/hexagon/dsp/libggml-htp-v75.so')
    if (-not $skeletonEntry -or $skeletonEntry.Length -le 0) { throw 'APK missing non-empty HTP v75 DSP skeleton' }
    $skeletonPath = Join-Path $temp 'libggml-htp-v75.so'
    [IO.Compression.ZipFileExtensions]::ExtractToFile($skeletonEntry, $skeletonPath, $true)
    $configPath = $null
    if ($Backend -eq 'fastrpc-mempool') {
        $configEntry = $zip.GetEntry('assets/hexagon/ap/ggml-hexagon.cfg')
        if (-not $configEntry -or $configEntry.Length -le 0) { throw 'FastRPC APK missing its pinned ggml-hexagon.cfg' }
        $configPath = Join-Path $temp 'ggml-hexagon.cfg'
        [IO.Compression.ZipFileExtensions]::ExtractToFile($configEntry, $configPath, $true)
    }

    $adbArgs = @(); if ($Serial) { $adbArgs += @('-s', $Serial) }
    $devices = & $adb @adbArgs devices
    if ($LASTEXITCODE -ne 0 -or -not ($devices | Select-String '\sdevice$')) { throw 'No authorized ADB device is connected' }
    $abi = (& $adb @adbArgs shell getprop ro.product.cpu.abi).Trim()
    if ($abi -ne 'arm64-v8a') { throw "Device ABI is $abi, expected arm64-v8a" }
    $soc = (& $adb @adbArgs shell getprop ro.soc.model).Trim()
    if ($soc -and $soc -notmatch 'SM8650') { throw "Device SoC reports '$soc', expected SM8650" }

    # Install the APK first so its AP-side JNI/llama/ggml .so set is installed atomically.
    & $adb @adbArgs install -r $Apk
    if ($LASTEXITCODE -ne 0) { throw 'adb install failed' }
    & $adb @adbArgs shell mkdir -p /data/local/tmp
    if ($LASTEXITCODE -ne 0) { throw 'Cannot prepare /data/local/tmp' }
    & $adb @adbArgs push $skeletonPath /data/local/tmp/libggml-htp-v75.so
    if ($LASTEXITCODE -ne 0) { throw 'Failed to deploy HTP v75 skeleton' }
    if ($configPath) {
        & $adb @adbArgs push $configPath /data/local/tmp/ggml-hexagon.cfg
        if ($LASTEXITCODE -ne 0) { throw 'Failed to deploy FastRPC config' }
    } else {
        & $adb @adbArgs shell rm -f /data/local/tmp/ggml-hexagon.cfg
    }
    & $adb @adbArgs shell chmod 644 /data/local/tmp/libggml-htp-v75.so
    if ($configPath) { & $adb @adbArgs shell chmod 644 /data/local/tmp/ggml-hexagon.cfg }
    & $adb @adbArgs shell ls -l /data/local/tmp/libggml-htp-v75.so
    if ($LASTEXITCODE -ne 0) { throw 'HTP skeleton deployment verification failed' }
    if ($configPath) {
        & $adb @adbArgs shell ls -l /data/local/tmp/ggml-hexagon.cfg
        if ($LASTEXITCODE -ne 0) { throw 'FastRPC config deployment verification failed' }
    }
    Write-Host "Installed matched $Backend AP APK + HTP v75 skeleton. AP .so files remain APK-managed; DSP files are in /data/local/tmp."
    Write-Host 'This verifies deployment only, not successful DSP execution. Inspect logcat / DSP diagnostics after inference.'
    Write-Host ('AP ELF entries: ' + (($apEntries | ForEach-Object Name | Sort-Object -Unique) -join ', '))
}
finally {
    $zip.Dispose()
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
}