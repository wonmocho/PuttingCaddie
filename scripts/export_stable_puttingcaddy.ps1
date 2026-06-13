# Recreates the PuttingCaddy (4/3 stable) app in a sibling directory.
# Default: <parent of this repo>/PuttingCaddy
# Override: env PUTTINGCADDY_STABLE_DIR = full path (legacy name, still supported).
#
# Run from repo root:  powershell -File scripts/export_stable_puttingcaddy.ps1

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $repoRoot

$repoLeaf = Split-Path $repoRoot -Leaf

$zip = Join-Path $repoRoot "stable403_export.zip"
$commit = "3e10423"
$over = Join-Path $repoRoot "stable_snapshot_overrides\android"

$dest =
    if ($env:PUTTINGCADDY_STABLE_DIR -and $env:PUTTINGCADDY_STABLE_DIR.Trim().Length -gt 0) {
        $env:PUTTINGCADDY_STABLE_DIR.Trim()
    } else {
        Join-Path (Split-Path $repoRoot -Parent) "PuttingCaddy"
    }

if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
New-Item -ItemType Directory -Path $dest | Out-Null

git archive --format=zip -o $zip $commit android pubspec.yaml pubspec.lock lib analysis_options.yaml .metadata
if (-not (Test-Path $zip)) { throw "git archive failed" }
Expand-Archive -Path $zip -DestinationPath $dest -Force
Remove-Item $zip

if (-not (Test-Path $over)) { throw "Missing stable_snapshot_overrides/android — commit packaging templates." }
Copy-Item (Join-Path $over "app\build.gradle.kts") (Join-Path $dest "android\app\build.gradle.kts") -Force
Copy-Item (Join-Path $over "settings.gradle.kts") (Join-Path $dest "android\settings.gradle.kts") -Force
Copy-Item (Join-Path $over "app\src\main\res\values\strings.xml") (Join-Path $dest "android\app\src\main\res\values\strings.xml") -Force
Copy-Item (Join-Path $over "app\src\main\res\values-ko\strings.xml") (Join-Path $dest "android\app\src\main\res\values-ko\strings.xml") -Force

$kotlinOver = Join-Path $over "app\src\main\kotlin\com\wmcho\puttingcaddie"
$kotlinDest = Join-Path $dest "android\app\src\main\kotlin\com\wmcho\puttingcaddie"
$layoutOver = Join-Path $over "app\src\main\res\layout\activity_distance_measurement.xml"
$layoutDest = Join-Path $dest "android\app\src\main\res\layout\activity_distance_measurement.xml"
if (Test-Path (Join-Path $kotlinOver "PracticeModeController.kt")) {
    New-Item -ItemType Directory -Force -Path $kotlinDest | Out-Null
    Copy-Item (Join-Path $kotlinOver "PracticeModeController.kt") (Join-Path $kotlinDest "PracticeModeController.kt") -Force
}
if (Test-Path (Join-Path $kotlinOver "DistanceMeasurementActivity.kt")) {
    New-Item -ItemType Directory -Force -Path $kotlinDest | Out-Null
    Copy-Item (Join-Path $kotlinOver "DistanceMeasurementActivity.kt") (Join-Path $kotlinDest "DistanceMeasurementActivity.kt") -Force
}
if (Test-Path $layoutOver) {
    Copy-Item $layoutOver $layoutDest -Force
}

$marker = Join-Path $dest "OPEN_AS_CURSOR_WORKSPACE_PUTTINGCADDY.txt"
$markerLines = @(
    "================================================================================",
    "PuttingCaddy (4/3 stable) ONLY. Separate directory.",
    "================================================================================",
    "Launcher name: PuttingCaddy",
    "applicationId: com.wmcho.puttingcaddy",
    "",
    "Open THIS folder (PuttingCaddy) alone as the Cursor / IDE workspace root.",
    "Source: sibling folder PuttingCaddy (same parent as this folder).",
    "",
    "Flutter: from this folder root: flutter pub get ; flutter build ...",
    "If android/local.properties is missing, copy from ../PuttingCaddy/android/local.properties",
    "================================================================================"
)
$markerLines | Set-Content -Path $marker -Encoding ascii

Write-Host "Done: $dest (commit $commit) + packaging overrides. Open this folder alone for PuttingCaddy stable."
