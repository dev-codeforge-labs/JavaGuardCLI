@echo off
rem Development launcher for JavaGuardCLI (until Phase 19 produces javaguard.exe).
rem Prepare once, from the repository root:
rem   mvn -q -pl javaguard-cli -am install -DskipTests
rem   mvn -q -pl javaguard-cli dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
rem Never prints anything itself: in hook mode stdout/stderr belong to the agent protocol.
setlocal
set "ROOT=%~dp0.."
set "CPFILE=%ROOT%\javaguard-cli\target\classpath.txt"
if not exist "%CPFILE%" exit /b 0
for /f "usebackq delims=" %%i in ("%CPFILE%") do set "CP=%%i"
java -cp "%ROOT%\javaguard-cli\target\javaguard-cli.jar;%CP%" com.devmanchego.javaguard.cli.Main %*
exit /b %ERRORLEVEL%
