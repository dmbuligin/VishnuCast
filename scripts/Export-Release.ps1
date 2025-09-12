<# Export-Release.ps1
Копирует и переименовывает релизные APK/AAB в версионированную папку.
Использует output-metadata.json от AGP для получения versionName / versionCode.
#>

param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot)  # по умолчанию ../ от scripts/
)

function Get-Json($path)
{
    if (-not (Test-Path $path))
    {
        return $null
    }
    Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
}

$AppDir = Join-Path $ProjectRoot "app"
$ApkMeta = Join-Path $AppDir "build\outputs\apk\release\output-metadata.json"
$AabMeta = Join-Path $AppDir "build\outputs\bundle\release\output-metadata.json"

# Читаем метаданные (AGP кладёт их после сборки)
$meta = Get-Json $ApkMeta
if (-not $meta)
{
    Write-Host "Не найден $ApkMeta. Сначала собери: .\gradlew.bat assembleRelease" -ForegroundColor Yellow
    exit 1
}

# Вытаскиваем версию (берём первую запись)
$el = $meta.elements | Select-Object -First 1
$versionName = $el.versionName
$versionCode = $el.versionCode
if (-not $versionName)
{
    $versionName = "0.0.0"
}
if (-not $versionCode)
{
    $versionCode = 0
}

# Куда складываем релизы (можно поменять путь под себя)
$Dist = Join-Path (Split-Path $ProjectRoot -Parent) "_Android_builds\VishnuCast\$versionName"
New-Item -ItemType Directory -Force -Path $Dist | Out-Null

# Источники
$ApkDir = Join-Path $AppDir "build\outputs\apk\release"
$AabDir = Join-Path $AppDir "build\outputs\bundle\release"

# Ищем APK (signed или unsigned)
$apk = Get-ChildItem -LiteralPath $ApkDir -Filter "*.apk" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($apk)
{
    $isUnsigned = ($apk.Name -match "unsigned")
    $apkName = if ($isUnsigned)
    {
        "VishnuCast-$versionName($versionCode)-release-unsigned.apk"
    }
    else
    {
        # "VishnuCast-$versionName($versionCode)-release.apk"
        "VishnuCast-$versionName-release.apk"
    }
    Copy-Item -LiteralPath $apk.FullName -Destination (Join-Path $Dist $apkName) -Force
    Write-Host "APK: $apkName" -ForegroundColor Cyan
}
else
{
    Write-Host "APK не найден в $ApkDir (собери assembleRelease)" -ForegroundColor Yellow
}

# Ищем AAB
if (Test-Path $AabDir)
{
    $aab = Get-ChildItem -LiteralPath $AabDir -Filter "*.aab" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($aab)
    {
        $aabName = "VishnuCast-$versionName($versionCode)-release.aab"
        Copy-Item -LiteralPath $aab.FullName -Destination (Join-Path $Dist $aabName) -Force
        Write-Host "AAB: $aabName" -ForegroundColor Cyan
    }
}

# Чексуммы (SHA-256)
$files = Get-ChildItem -LiteralPath $Dist -Include *.apk,*.aab -File
if ($files.Count -gt 0)
{
    $out = Join-Path $Dist "checksums.txt"
    "" | Out-File -LiteralPath $out
    foreach ($f in $files)
    {
        $sha = (Get-FileHash -Algorithm SHA256 -LiteralPath $f.FullName).Hash.ToLower()
        "$sha  $( $f.Name )" | Out-File -Append -LiteralPath $out
    }
    Write-Host "checksums.txt создан" -ForegroundColor Green
}

Write-Host "Готово. Папка релиза: $Dist" -ForegroundColor Green
