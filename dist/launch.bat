@echo off
setlocal enabledelayedexpansion
chcp 65001 > nul

set "APPDIR=%~dp0"
REM 末尾の \ を除去 (Java 引数パースで \" が quote-escape になるのを防ぐ)
set "APPDIR=%APPDIR:~0,-1%"

where javaw >nul 2>&1
if !errorlevel! neq 0 (
    mshta "javascript:var s=new ActiveXObject('WScript.Shell');s.Popup('Java が見つかりません。Java 17 以上をインストールしてください。\nhttps://adoptium.net/',0,'ASR App エラー',16);close()"
    exit /b 1
)
if not exist "%APPDIR%\models\ggml-large-v3.bin" (
    mshta "javascript:var s=new ActiveXObject('WScript.Shell');s.Popup('モデルファイルが見つかりません:\nmodels\\ggml-large-v3.bin',0,'ASR App エラー',16);close()"
    exit /b 1
)

if exist "%APPDIR%\bin\cuda\cudart64_12.dll" (
    set "PATH=%APPDIR%\bin\cuda;!PATH!"
)

start "AsrApp" /B javaw ^
    -cp "%APPDIR%\AsrApp.jar" ^
    "-Dapp.dir=%APPDIR%" ^
    -Dfile.encoding=UTF-8 ^
    app.AsrApp

endlocal
