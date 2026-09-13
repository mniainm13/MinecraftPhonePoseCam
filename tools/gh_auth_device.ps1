$ErrorActionPreference = 'Continue'
$env:HTTPS_PROXY = 'http://127.0.0.1:7890'
$env:HTTP_PROXY = 'http://127.0.0.1:7890'
$gh = 'C:\Program Files\GitHub CLI\gh.exe'
$out = Join-Path $PSScriptRoot 'gh_auth_out.txt'
$err = Join-Path $PSScriptRoot 'gh_auth_err.txt'
Remove-Item $out, $err -Force -ErrorAction SilentlyContinue

$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = $gh
$psi.Arguments = 'auth login --hostname github.com --git-protocol https --web'
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.CreateNoWindow = $true
$psi.Environment['HTTPS_PROXY'] = 'http://127.0.0.1:7890'
$psi.Environment['HTTP_PROXY'] = 'http://127.0.0.1:7890'

$p = [System.Diagnostics.Process]::Start($psi)
$deadline = (Get-Date).AddSeconds(12)
$stdout = New-Object System.Text.StringBuilder
$stderr = New-Object System.Text.StringBuilder

# async read
$outTask = $p.StandardOutput.ReadToEndAsync()
$errTask = $p.StandardError.ReadToEndAsync()

# send Enter after short delay so gh opens the device page
Start-Sleep -Milliseconds 2500
try { $p.StandardInput.WriteLine(''); $p.StandardInput.Flush() } catch {}

# wait up to 20s for exit or output
if (-not $p.WaitForExit(20000)) {
  # still running - ok, login poll continues
}
try { $outText = $outTask.Result } catch { $outText = '' }
try { $errText = $errTask.Result } catch { $errText = '' }
Set-Content -Path $out -Value $outText -Encoding utf8
Set-Content -Path $err -Value $errText -Encoding utf8
Write-Host "=== stdout ==="
Write-Host $outText
Write-Host "=== stderr ==="
Write-Host $errText
if ($outText -match '([A-Z0-9]{4}-[A-Z0-9]{4})') {
  Write-Host "DEVICE_CODE=$($Matches[1])"
}
Write-Host "pid=$($p.Id) hasExited=$($p.HasExited)"
