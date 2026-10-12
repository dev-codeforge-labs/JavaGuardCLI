@echo off
rem JavaGuardCLI portable launcher (needs Java 21 or newer). Hooks need stdin, stdout and the exit code to pass through unchanged.
setlocal
set "HERE=%~dp0"
set "JAR="
for %%f in ("%HERE%javaguard-*-all.jar") do set "JAR=%%f"
if not defined JAR (
  echo javaguard: no javaguard-*-all.jar next to %~f0 1>&2
  exit /b 4
)
if defined JAVA_HOME (set "JAVA=%JAVA_HOME%\bin\java.exe") else (set "JAVA=java")
"%JAVA%" -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xss1m -Xmx1g ^
  --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED ^
  --add-exports=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED ^
  --add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED ^
  --add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED ^
  --add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED ^
  --add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED --add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED ^
  -jar "%JAR%" %*
exit /b %ERRORLEVEL%
