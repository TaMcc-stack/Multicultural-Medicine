@echo off
cd /d "%~dp0"
"D:\TAMCC\AppData\Python\Python312\python.exe" -m uvicorn app.main:app --port 8000
