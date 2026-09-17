@echo off
rem 一键启动三服务：前端5173 / 后端8080 / AI8000
start "frontend-5173" cmd /k call "%~dp0..\frontend\restart-frontend.bat"
start "backend-8080" cmd /k call "%~dp0..\backend\restart-backend.bat"
start "ai-8000" cmd /k call "%~dp0..\ai-service\restart-ai.bat"
