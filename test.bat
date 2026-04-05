@echo off
setlocal enabledelayedexpansion
chcp 65001 > nul
cd /d "%~dp0"

echo [INFO] テストコンパイル中...
if not exist test-out mkdir test-out

rem ── ソースファイル列挙 ─────────────────────────────────────────────────────
set SRC_FILES=
for /r src %%f in (*.java) do set SRC_FILES=!SRC_FILES! "%%f"
set TEST_FILES=
for /r test %%f in (*.java) do set TEST_FILES=!TEST_FILES! "%%f"

rem ── 本体コンパイル ──────────────────────────────────────────────────────────
javac -encoding UTF-8 -d test-out !SRC_FILES!
if %errorlevel% neq 0 (
    echo [ERROR] 本体コンパイル失敗
    exit /b 1
)

rem ── テストコンパイル ────────────────────────────────────────────────────────
javac -encoding UTF-8 -cp "test-out;lib\junit.jar" -d test-out !TEST_FILES!
if %errorlevel% neq 0 (
    echo [ERROR] テストコンパイル失敗
    exit /b 1
)

echo [INFO] テスト実行中...
rem ── app.dir を一時ディレクトリに設定 ────────────────────────────────────────
set TEST_WORK=%TEMP%\rt_test_%RANDOM%
mkdir "%TEST_WORK%"

java -cp "test-out;lib\junit.jar" ^
     -Djava.awt.headless=true ^
     -Dapp.dir="%TEST_WORK%" ^
     -Dwav.dir="%TEST_WORK%\wav" ^
     -Dlog.dir="%TEST_WORK%\logs" ^
     org.junit.platform.console.ConsoleLauncher ^
     --scan-class-path ^
     --classpath "test-out" ^
     --include-classname ".*Test"

set EXIT_CODE=%errorlevel%

rem ── 一時ディレクトリを削除 ──────────────────────────────────────────────────
rmdir /s /q "%TEST_WORK%" 2>nul

if %EXIT_CODE% neq 0 (
    echo [ERROR] テスト失敗 (exit code: %EXIT_CODE%)
    exit /b %EXIT_CODE%
)

echo [INFO] 全テスト成功
endlocal
