[CmdletBinding()]
param(
    # Public HTTPS link where the APK will be downloadable (a GitHub release asset or a raw.githubusercontent.com file).
    [Parameter(Mandatory = $true)][string]$ApkUrl,
    # Release notes shown in the update sheet and the notification. Use `n for new lines.
    [string]$Notes = '',
    [ValidateSet('release', 'debug')][string]$Variant = 'release',
    [int]$MinSdk = 26
)

# Builds update-<applicationId>.json next to the APK from the latest Gradle build output.
# Upload the APK to $ApkUrl and the JSON to the manifest branch; the app picks it up on its next check.

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$outDir = Join-Path $projectRoot "app/build/outputs/apk/$Variant"
$meta = Get-Content -LiteralPath (Join-Path $outDir 'output-metadata.json') -Raw | ConvertFrom-Json
$element = $meta.elements[0]
$apk = Join-Path $outDir $element.outputFile

$uri = [Uri]$ApkUrl
$trusted = @('github.com', 'raw.githubusercontent.com', 'objects.githubusercontent.com', 'release-assets.githubusercontent.com')
if ($uri.Scheme -ne 'https' -or $trusted -notcontains $uri.Host) {
    throw "ApkUrl must be https on one of: $($trusted -join ', '). The app refuses any other host."
}

$manifest = [ordered]@{
    packageName = $meta.applicationId
    versionCode = [long]$element.versionCode
    versionName = $element.versionName
    apkUrl      = $ApkUrl
    sha256      = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    size        = (Get-Item -LiteralPath $apk).Length
    notes       = $Notes
    minSdk      = $MinSdk
    channel     = 'stable'
}
$target = Join-Path $outDir "update-$($meta.applicationId).json"
[IO.File]::WriteAllText($target, ($manifest | ConvertTo-Json), (New-Object Text.UTF8Encoding $false))
Write-Host "APK:      $apk"
Write-Host "Manifest: $target"
Write-Host "versionCode $($manifest.versionCode), versionName $($manifest.versionName), $([math]::Round($manifest.size / 1MB, 1)) MB"
