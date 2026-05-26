param(
    [ValidateSet("app-image", "exe")]
    [string]$Type = "app-image",
    [string]$AppName = "Dicom3DViewer",
    [string]$AppVersion = "1.0.0",
    [string]$Vendor = "Shibaykin"
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Resolve-Path (Join-Path $scriptDir "..")
Set-Location $projectRoot

Write-Host "[1/4] Building fat JAR..."
mvn -q -DskipTests clean package

$fatJar = Get-ChildItem -Path "target" -Filter "*-all.jar" |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1
if (-not $fatJar) {
    throw "Fat JAR was not created. Expected target/*-all.jar"
}

$jpackageCmd = Get-Command jpackage -ErrorAction SilentlyContinue
if (-not $jpackageCmd) {
    throw "jpackage is not available. Install JDK 17+ and run again."
}

$distDir = Join-Path $projectRoot "dist"
New-Item -ItemType Directory -Path $distDir -Force | Out-Null

$jpackageInputDir = Join-Path $projectRoot "target\jpackage-input"
if (Test-Path $jpackageInputDir) {
    Remove-Item $jpackageInputDir -Recurse -Force
}
New-Item -ItemType Directory -Path $jpackageInputDir -Force | Out-Null
Copy-Item $fatJar.FullName (Join-Path $jpackageInputDir $fatJar.Name) -Force

$portableJar = Join-Path $distDir "$AppName-portable.jar"
Copy-Item $fatJar.FullName $portableJar -Force
$runBat = "@echo off`r`njava -jar ""%~dp0$AppName-portable.jar""`r`n"
Set-Content -Path (Join-Path $distDir "run-portable.bat") -Value $runBat -Encoding Ascii

if ($Type -eq "app-image") {
    $appImageDir = Join-Path $distDir $AppName
    if (Test-Path $appImageDir) {
        Remove-Item $appImageDir -Recurse -Force
    }
}

$jpackageArgs = @(
    "--type", $Type,
    "--dest", (Resolve-Path $distDir).Path,
    "--name", $AppName,
    "--app-version", $AppVersion,
    "--vendor", $Vendor,
    "--input", (Resolve-Path $jpackageInputDir).Path,
    "--main-jar", $fatJar.Name,
    "--main-class", "com.shibaykin.dicom3d.App"
)

if ($Type -eq "exe") {
    $candle = Get-Command candle.exe -ErrorAction SilentlyContinue
    $light = Get-Command light.exe -ErrorAction SilentlyContinue
    if (-not $candle -or -not $light) {
        throw "WiX Toolset is required for -Type exe. Install WiX and add candle.exe/light.exe to PATH."
    }
    $jpackageArgs += "--win-shortcut"
    $jpackageArgs += "--win-menu"
}

Write-Host "[2/4] Running jpackage ($Type)..."
& $jpackageCmd.Source @jpackageArgs

$expectedAppExe = Join-Path $distDir "$AppName\$AppName.exe"
$expectedInstaller = Join-Path $distDir "$AppName-$AppVersion.exe"
if ($Type -eq "app-image" -and -not (Test-Path $expectedAppExe)) {
    throw "Packaging failed: expected launcher not found at $expectedAppExe"
}
if ($Type -eq "exe" -and -not (Test-Path $expectedInstaller)) {
    throw "Packaging failed: expected installer not found at $expectedInstaller"
}

Write-Host "[3/4] Artifacts are in dist/"
if ($Type -eq "app-image") {
    Write-Host "Run app: dist/$AppName/$AppName.exe"
} else {
    Write-Host "Installer: dist/$AppName-$AppVersion.exe"
}
Write-Host "[4/4] Done."
