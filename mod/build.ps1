# Build PhoneCam mod using the local Gradle 9.2.0 install.
param(
    [switch]$RunClient
)

$ErrorActionPreference = 'Stop'
$gradle = 'F:\workspace\tools\gradle\gradle-9.2.0\bin\gradle.bat'
if (!(Test-Path $gradle)) {
    throw "Gradle not found at $gradle"
}

Set-Location $PSScriptRoot
if ($RunClient) {
    & $gradle runClient
} else {
    & $gradle build
}
