@echo off
set HTTPS_PROXY=http://127.0.0.1:7890
set HTTP_PROXY=http://127.0.0.1:7890
set GH_PROMPT_DISABLED=1
"C:\Program Files\GitHub CLI\gh.exe" auth login --hostname github.com --git-protocol https --web > "%~dp0gh_auth_out.txt" 2> "%~dp0gh_auth_err.txt"
