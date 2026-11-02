param(
    [Parameter(Mandatory = $true)][string]$Model,
    [string]$Output,
    [string]$Upstream = "D:\\mcp-agent-workspace\\ggml-hexagon"
)

$ErrorActionPreference = "Stop"
$Repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Upstream = (Resolve-Path $Upstream).Path
$Model = (Resolve-Path $Model).Path
$TiledCacheHeader = Join-Path $Upstream "ggml\src\ggml-hexagon\htp-tiled-cache.h"
$TiledCacheBackend = Join-Path $Upstream "ggml\src\ggml-hexagon\ggml-hexagon-fastrpc.cpp"
if (!(Test-Path $TiledCacheHeader) -or !(Select-String -Path $TiledCacheBackend -Pattern 'GGML_HEXAGON_TILED_CACHE' -Quiet)) {
    $TiledCachePatch = Join-Path $Repo "patches\htp-tiled-cache.patch"
    if (!(Test-Path $TiledCachePatch)) { throw "Missing upstream tiled-cache patch: $TiledCachePatch" }
    & git -C $Upstream apply --check $TiledCachePatch
    if ($LASTEXITCODE -ne 0) { throw "Cannot apply tiled-cache patch to upstream $Upstream." }
    & git -C $Upstream apply $TiledCachePatch
    if ($LASTEXITCODE -ne 0) { throw "Failed to apply tiled-cache patch to upstream $Upstream." }
}
if ([string]::IsNullOrWhiteSpace($Output)) { $Output = "$Model.htpbin" }
$BuildDir = Join-Path $Repo "build\\htp-tiled-converter"

cmake -S (Join-Path $Repo "tools\\htp-tiled-converter") -B $BuildDir "-DLLAMA_CPP_DIR=$Upstream"
if ($LASTEXITCODE -ne 0) { throw "Converter CMake configure failed." }
cmake --build $BuildDir --config Release --target gguf-htp-tiled-convert -j 8
if ($LASTEXITCODE -ne 0) { throw "Converter build failed." }

$Converter = Join-Path $BuildDir "Release\\gguf-htp-tiled-convert.exe"
if (!(Test-Path $Converter)) { throw "Converter executable missing: $Converter" }
& $Converter $Model $Output
if ($LASTEXITCODE -ne 0) { throw "GGUF to HTP tiled conversion failed." }
Write-Host "Created sidecar: $Output"
