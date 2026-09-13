# Build phonecam_enc.exe
# Portable: finds VS Build Tools / VC via vswhere or common paths.
# Optional env: VCVARS=path\to\vcvars64.bat

$ErrorActionPreference = 'Stop'
$srcDir = $PSScriptRoot
$cpp = Join-Path $srcDir 'phonecam_enc.cpp'

function Find-VcVars {
    if ($env:VCVARS -and (Test-Path $env:VCVARS)) { return $env:VCVARS }
    $vswhere = @(
        "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe",
        "${env:ProgramFiles}\Microsoft Visual Studio\Installer\vswhere.exe"
    ) | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($vswhere) {
        $root = & $vswhere -latest -products * `
            -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 `
            -property installationPath 2>$null
        if ($root) {
            $c = Join-Path $root 'VC\Auxiliary\Build\vcvars64.bat'
            if (Test-Path $c) { return $c }
        }
    }
    $candidates = @(
        'F:\workspace\tools\vs\VC\Auxiliary\Build\vcvars64.bat',
        'C:\Program Files\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat',
        'C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat',
        'C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat',
        'C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat'
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { return $c }
    }
    return $null
}

$vcvars = Find-VcVars
if (-not $vcvars) {
    throw "MSVC not found. Install VS Build Tools (C++), or set VCVARS to vcvars64.bat"
}

# Optional NVENC headers (only needed if not using system SDK); d3d11/d3dcompiler come with VS+Windows SDK
$extraInc = @()
foreach ($inc in @(
    'F:\workspace\tools\nvenc-headers\nv-codec-headers-master\include',
    (Join-Path $srcDir 'third_party\nvenc-headers\include')
)) {
    if (Test-Path $inc) { $extraInc += "/I`"$inc`"" }
}

$t = [IO.File]::ReadAllText($cpp)
$t = $t -replace '[^\x00-\x7F]', '?'
[IO.File]::WriteAllText($cpp, $t, (New-Object Text.UTF8Encoding $false))

$incArgs = ($extraInc -join ' ')
cmd /c "`"$vcvars`" && cd /d `"$srcDir`" && cl /nologo /O2 /EHsc /std:c++17 $incArgs phonecam_enc.cpp /Fe:phonecam_enc.exe"
if ($LASTEXITCODE -ne 0) { throw "cl failed" }
Write-Host "Built: $srcDir\phonecam_enc.exe"
Write-Host "Distribute: ship phonecam_enc.exe (needs NVIDIA GPU + driver NVENC)."
