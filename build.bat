@echo off
setlocal
chcp 65001 > nul
cd /d "%~dp0"

echo [INFO] コンパイル中...
if not exist out mkdir out
javac -encoding UTF-8 -d out src\*.java
if %errorlevel% neq 0 (
    echo [ERROR] コンパイル失敗
    exit /b 1
)

if not exist dist mkdir dist
jar --create --file dist\AsrApp.jar -C out .
if %errorlevel% neq 0 (
    echo [ERROR] JAR 生成失敗
    exit /b 1
)

rmdir /s /q out
echo [INFO] ビルド成功: dist\AsrApp.jar
endlocal
