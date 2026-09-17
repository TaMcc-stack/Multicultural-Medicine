@echo off
rem One-click demo launcher.
rem The real logic lives in start-demo.ps1 -- PowerShell is needed for
rem parsing the tunnel URL out of cloudflared's output and for clipboard access.
rem This file is intentionally ASCII-only so cmd can never mis-decode it.
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-demo.ps1"
