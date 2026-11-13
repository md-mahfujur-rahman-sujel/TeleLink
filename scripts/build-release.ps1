param(
    [switch]$SkipSignatureVerification
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$androidRoot = Join-Path $repoRoot 'android\tv'
$gradle = Join-Path $androidRoot 'gradlew.bat'
$releaseDir = Join-Path $repoRoot 'builds\release'
$signingPropertiesFile = Join-Path $androidRoot 'keystore.properties'
if (-not (Test-Path -LiteralPath $signingPropertiesFile)) {
    throw 'Private signing configuration is missing. Restore android/tv/keystore.properties and the private keystore backup.'
}
$storeFileLine = Get-Content -LiteralPath $signingPropertiesFile | Where-Object { $_ -match '^storeFile=(.+)$' } | Select-Object -First 1
if (-not $storeFileLine -or $storeFileLine -notmatch '^storeFile=(.+)$') {
    throw 'Private signing configuration does not specify storeFile.'
}
$keystorePath = Join-Path $androidRoot $Matches[1]
if (-not (Test-Path -LiteralPath $keystorePath)) {
    throw "Private signing keystore is missing: $keystorePath"
}

Push-Location $androidRoot
try {
    & $gradle ':app:assembleRelease' ':mobile:assembleRelease'
    if ($LASTEXITCODE -ne 0) { throw "Gradle release build failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

$artifacts = @(
    @{ Source = Join-Path $androidRoot 'app\build\outputs\apk\release\app-release.apk'; Name = 'TeleLink-TV-1.0.apk' },
    @{ Source = Join-Path (Join-Path $repoRoot 'android\mobile') 'build\outputs\apk\release\mobile-release.apk'; Name = 'TeleLink-Remote-1.0.apk' }
)
New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
foreach ($artifact in $artifacts) {
    if (-not (Test-Path -LiteralPath $artifact.Source)) {
        throw "Expected signed release APK was not produced: $($artifact.Source)"
    }
    $destination = Join-Path $releaseDir $artifact.Name
    Copy-Item -LiteralPath $artifact.Source -Destination $destination -Force
}

$sdkPath = $null
$localPropertiesPath = Join-Path $androidRoot 'local.properties'
if (Test-Path -LiteralPath $localPropertiesPath) {
    $localProperties = Get-Content -LiteralPath $localPropertiesPath -Raw
    $sdkMatch = [regex]::Match($localProperties, '(?m)^sdk\.dir=(.+)$')
    if ($sdkMatch.Success) {
        $sdkPath = $sdkMatch.Groups[1].Value.Replace('\:', ':').Replace('\\', '\')
    }
}
if (-not $sdkPath) { $sdkPath = $env:ANDROID_SDK_ROOT }
if (-not $sdkPath) { $sdkPath = $env:ANDROID_HOME }
$apksigner = $null
if ($sdkPath) {
    $buildTools = Join-Path $sdkPath 'build-tools'
    if (Test-Path $buildTools) {
        $latest = Get-ChildItem -LiteralPath $buildTools -Directory | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
        if ($latest) { $apksigner = Join-Path $latest.FullName 'apksigner.bat' }
    }
}
if (-not $SkipSignatureVerification -and $apksigner -and (Test-Path $apksigner)) {
    foreach ($artifact in $artifacts) {
        $destination = Join-Path $releaseDir $artifact.Name
        & $apksigner verify --verbose $destination
        if ($LASTEXITCODE -ne 0) { throw "Signature verification failed: $($artifact.Name)" }
    }
} elseif (-not $SkipSignatureVerification) {
    throw 'Android SDK apksigner was not found. The APKs were built and copied, but verification could not run.'
}

if (-not $sdkPath) {
    throw 'Android SDK path was not found. APK package metadata could not be verified.'
}
$buildToolsDir = Join-Path $sdkPath 'build-tools'
$metadataTool = $null
if (Test-Path -LiteralPath $buildToolsDir) {
    $latestBuildTools = Get-ChildItem -LiteralPath $buildToolsDir -Directory |
        Sort-Object { [version]$_.Name } -Descending |
        Select-Object -First 1
    if ($latestBuildTools) {
        $candidate = Join-Path $latestBuildTools.FullName 'aapt.exe'
        if (Test-Path -LiteralPath $candidate) { $metadataTool = $candidate }
    }
}
if (-not $metadataTool) {
    throw 'Android SDK aapt was not found. APK package metadata could not be verified.'
}

$expectedPackages = @(
    @{ Name = 'TeleLink-TV-1.0.apk'; Package = 'com.telelink.tv'; Launcher = 'com.telelink.tv.MainActivity'; Tv = $true },
    @{ Name = 'TeleLink-Remote-1.0.apk'; Package = 'com.telelink.remote'; Launcher = 'com.telelink.remote.MainActivity'; Tv = $false }
)
foreach ($expected in $expectedPackages) {
    $apkPath = Join-Path $releaseDir $expected.Name
    $badging = (& $metadataTool dump badging $apkPath 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Could not inspect APK metadata: $($expected.Name)`n$badging" }

    $requiredLines = @(
        "(?m)^package: name='$([regex]::Escape($expected.Package))' versionCode='1' versionName='1\.0'",
        "(?m)^sdkVersion:'26'$",
        "(?m)^targetSdkVersion:'37'$",
        "(?m)^launchable-activity: name='$([regex]::Escape($expected.Launcher))'"
    )
    if ($expected.Tv) {
        $requiredLines += "(?m)^leanback-launchable-activity: name='$([regex]::Escape($expected.Launcher))'"
    }
    foreach ($pattern in $requiredLines) {
        if ($badging -notmatch $pattern) {
            throw "APK metadata verification failed for $($expected.Name); expected '$pattern'.`n$badging"
        }
    }
}

$hashLines = foreach ($artifact in $artifacts) {
    $file = Join-Path $releaseDir $artifact.Name
    $hash = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $($artifact.Name)"
}
[System.IO.File]::WriteAllLines((Join-Path $releaseDir 'SHA256SUMS.txt'), $hashLines)
Get-ChildItem -LiteralPath $releaseDir -File | Select-Object Name, Length
