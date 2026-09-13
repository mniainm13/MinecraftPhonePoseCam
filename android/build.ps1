# Build PhoneCam Android APK (debug)
param(
    [switch]$Install
)

$ErrorActionPreference = 'Stop'
$gradle = 'F:\workspace\tools\gradle\gradle-9.2.0\bin\gradle.bat'
$env:ANDROID_HOME = 'F:\workspace\tools\android-sdk'
$env:JAVA_HOME = 'D:\Program Files\Java\jdk-21'

Set-Location $PSScriptRoot
& $gradle assembleDebug

$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
$dist = Join-Path (Split-Path $PSScriptRoot -Parent) 'dist'
New-Item -ItemType Directory -Path $dist -Force | Out-Null
Copy-Item $apk (Join-Path $dist 'PhoneCam-0.1.0-debug.apk') -Force
Write-Host "APK: $dist\PhoneCam-0.1.0-debug.apk"

if ($Install) {
    $adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
    & $adb devices
    & $adb install -r $apk
}
