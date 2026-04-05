# Round Table Desktop

リアルタイム日本語音声文字起こしアプリ（Windows デスクトップ）

## 概要

- マイク入力を監視し、発話検出（VAD）後に自動で文字起こし
- CPU / GPU (CUDA) の切り替え、マイクデバイスの選択、ゲイン調整をサポート
- 音声は WAV として保存され、30分単位で ZIP アーカイブ

## アーキテクチャ

```
AsrApp (エントリポイント)
 └─ DeviceSelector  … 起動時デバイス選択ダイアログ
 └─ MainWindow      … Swing UI + 録音ループ
      ├─ VadEngine        … 音量ベース VAD 状態機械
      ├─ WhisperRunner    … whisper-cli プロセス管理
      ├─ WavArchiver      … WAV → ZIP アーカイブ
      ├─ AppConfig        … パス / デバイス設定
      └─ PcmUtils         … PCM ユーティリティ（定数・変換）
```

## 必要要件（配布先マシン）

| コンポーネント | バージョン |
|---|---|
| Java | 17 以上 |
| OS | Windows 10/11 x64 |
| CUDA (GPU 利用時) | 12.x |

## ディレクトリ構成

```
dist/
├── AsrApp.jar          ← ビルド済み JAR（要ビルドまたはリリースから取得）
├── launch.bat          ← ユーザー向け起動スクリプト
├── bin/
│   ├── cpu/            ← whisper-cli.exe (CPU 版) + DLL ← 別途配置
│   └── cuda/           ← whisper-cli.exe (GPU 版) + DLL ← 別途配置
├── models/
│   ├── ggml-large-v3.bin       ← Whisper モデル ← 別途配置
│   └── ggml-silero-v6.2.0.bin  ← Silero VAD モデル ← 別途配置
├── wav/                ← 録音 WAV / ZIP アーカイブの保存先
└── logs/               ← 文字起こしログ / statistics.json の保存先
```

## セットアップ（開発者向け）

```bat
# 1. リポジトリをクローン
git clone https://github.com/<org>/round_table_desktop.git
cd round_table_desktop

# 2. whisper.cpp バイナリとモデルファイルを配置
#    dist\bin\cpu\whisper-cli.exe
#    dist\bin\cuda\whisper-cli.exe (GPU使用時)
#    dist\models\ggml-large-v3.bin
#    dist\models\ggml-silero-v6.2.0.bin

# 3. ビルド (Java 17+ が必要)
build.bat

# 4. 起動
dist\launch.bat
```

## ビルド

```bat
build.bat          # Windows
bash build.sh      # Linux / CI
```

出力: `dist/AsrApp.jar`

## テスト

TBD

## ライセンス

MIT
