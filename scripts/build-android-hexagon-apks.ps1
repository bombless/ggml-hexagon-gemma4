param(
    [string]$Upstream = "D:\mcp-agent-workspace\ggml-hexagon",
    [string]$SdkRoot = $env:HEXAGON_SDK_ROOT,
    [string]$Output = "artifacts\apk"
)

$ErrorActionPreference = "Stop"
$Repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Upstream = (Resolve-Path $Upstream).Path
$ExpectedCommit = "a5e6be5e032eaf22e6a1154e2b6ab34a76a13a8d"
$ActualCommit = (& git -C $Upstream rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $ActualCommit -ne $ExpectedCommit) {
    throw "Upstream checkout must be exactly $ExpectedCommit (found $ActualCommit)."
}
if ([string]::IsNullOrWhiteSpace($SdkRoot) -or !(Test-Path (Join-Path $SdkRoot "build\cmake\hexagon_arch.cmake"))) {
    throw "Set HEXAGON_SDK_ROOT to the licensed Qualcomm Hexagon SDK 6.7.0.0 installation."
}
$SdkConfig = Join-Path $SdkRoot "hexagon_sdk.json"
if (!(Test-Path $SdkConfig)) {
    throw "Hexagon SDK metadata not found: $SdkConfig"
}
$SdkMetadata = Get-Content $SdkConfig -Raw | ConvertFrom-Json
$ToolsRelative = $SdkMetadata.root.tools.info[0].path
if ([string]::IsNullOrWhiteSpace($ToolsRelative)) {
    throw "Hexagon SDK metadata does not declare the bundled Hexagon Tools path."
}
$ToolsRoot = Join-Path $SdkRoot $ToolsRelative
$HexagonCompiler = Join-Path $ToolsRoot "Tools\bin\hexagon-clang.exe"
if (!(Test-Path $HexagonCompiler -PathType Leaf)) {
    throw "Bundled Hexagon compiler not found: $HexagonCompiler (SDK metadata path: $ToolsRelative)"
}
Write-Host "Using bundled Hexagon Tools: $ToolsRoot"

$OutputRoot = Join-Path $Repo $Output
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
$Variants = @(
    @{ Name = "fastrpc-mempool"; Backend = "fastrpc-mempool" },
    @{ Name = "dspqueue"; Backend = "dspqueue" }
)

Push-Location $Repo
try {
    foreach ($Variant in $Variants) {
        Write-Host "=== Building $($Variant.Name) ==="
        # AGP otherwise reuses one .cxx cache for two incompatible transport
        # configurations. Remove only generated native intermediates.
        $NativeCache = Join-Path $Repo "app\.cxx"
        if (Test-Path $NativeCache) { Remove-Item $NativeCache -Recurse -Force }
        & .\gradlew.bat --no-daemon :app:assembleDebug `
            "-PggmlHexagonSource=$Upstream" `
            "-PggmlHexagonBackend=$($Variant.Backend)" `
            "-PhexagonSdkRoot=$SdkRoot" `
            "-PhexagonToolsRoot=$ToolsRoot"
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed for $($Variant.Name)." }

        $Apk = Join-Path $Repo "app\build\outputs\apk\debug\app-debug.apk"
        if (!(Test-Path $Apk)) { throw "APK missing for $($Variant.Name)." }
        $Listing = & jar tf $Apk
        if ($LASTEXITCODE -ne 0) { throw "Unable to inspect APK $Apk." }
        foreach ($Required in @(
            "lib/arm64-v8a/libgemma4_jni.so",
            "lib/arm64-v8a/libggml-hexagon.so",
            "assets/hexagon/dsp/libggml-htp-v75.so",
            "assets/hexagon/backend.properties"
        )) {
            if ($Listing -notcontains $Required) { throw "$($Variant.Name) APK lacks $Required" }
        }
        if ($Variant.Name -eq "fastrpc-mempool" -and $Listing -notcontains "assets/hexagon/ap/ggml-hexagon.cfg") {
            throw "FastRPC APK lacks assets/hexagon/ap/ggml-hexagon.cfg"
        }
        Copy-Item $Apk (Join-Path $OutputRoot "gemma4-$($Variant.Name)-debug.apk") -Force
        Get-FileHash (Join-Path $OutputRoot "gemma4-$($Variant.Name)-debug.apk") -Algorithm SHA256 |
            Format-List | Out-Host
    }
} finally {
    Pop-Location
}

Write-Host "Both transport APKs passed package-content checks: $OutputRoot"