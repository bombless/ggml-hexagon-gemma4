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

    # The app owns the DSP runtime bundle and extracts it into Context.filesDir on GGUF initialization.
    & $adb @adbArgs install -r $Apk
    if ($LASTEXITCODE -ne 0) { throw 'adb install failed' }
    & $adb @adbArgs shell monkey -p com.example.gemmaapp 1 | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Could not launch the installed app' }
    Write-Host "Installed matched $Backend APK. DSP skeleton/config are APK-managed and will be extracted to the app-private filesDir when GGUF initialization starts."
    Write-Host 'App launch does not prove DSP loading; select a GGUF model and inspect GemmaHexagonRuntime / DSP diagnostics.'
    Write-Host ('AP ELF entries: ' + (($apEntries | ForEach-Object Name | Sort-Object -Unique) -join ', '))
}
finally {
    $zip.Dispose()
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
}