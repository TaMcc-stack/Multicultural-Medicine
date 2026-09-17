@echo off
rem offline compile check (same as restart-backend.bat, compile only)
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%~dp0"
call "%~dp0mvnw.cmd" -q -o -Dmaven.repo.local="%~dp0.mvn-home\repo" compile
