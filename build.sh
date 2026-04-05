#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
echo "[INFO] コンパイル中..."
mkdir -p out
javac -encoding UTF-8 -d out src/*.java

mkdir -p dist
jar --create --file dist/AsrApp.jar -C out .
rm -rf out
echo "[INFO] ビルド成功: dist/AsrApp.jar"
