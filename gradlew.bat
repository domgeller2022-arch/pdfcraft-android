@rem
@echo off
setlocal

rem Gradle startup script for Gradle 8.12.1 (Windows)

set APP_NAME=gradle
set APP_HOME=%~dp0

set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

if defined JAVA_HOME (
    set JAVACMD=%JAVA_HOME%\bin\java.exe
) else (
    set JAVACMD=java.exe
)

if not "%JAVA_HOME%" == "" set JAVACMD=%JAVA_HOME%\bin\java.exe

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo Error: JAVA_HOME is set to an invalid directory.
    exit /b 1
)

%JAVACMD% %DEFAULT_JVM_OPTS% -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
