package app;

import java.nio.file.Path;

/**
 * アプリ全体のパス・デバイス設定を保持するシングルトン的設定クラス。
 * - パスは起動時に System.getProperty で初期化される（変更不可）
 * - WHISPER_CLI / DEVICE_LABEL はデバイス選択ダイアログ後に一度だけ設定される
 */
public final class AppConfig {

    private AppConfig() {}

    // ── ディレクトリ / モデルパス (起動時確定・不変) ──────────────────────────
    public static final Path   APP_DIR   =
            Path.of(System.getProperty("app.dir", ".")).toAbsolutePath().normalize();
    public static final String MODEL     =
            System.getProperty("model",
                APP_DIR.resolve("models/ggml-large-v3.bin").toString());
    public static final String VAD_MODEL =
            System.getProperty("vad.model",
                APP_DIR.resolve("models/ggml-silero-v6.2.0.bin").toString());
    public static final Path   WAV_DIR   =
            Path.of(System.getProperty("wav.dir",
                APP_DIR.resolve("wav").toString())).toAbsolutePath().normalize();
    public static final Path   LOG_DIR   =
            Path.of(System.getProperty("log.dir",
                APP_DIR.resolve("logs").toString())).toAbsolutePath().normalize();

    // ── デバイス (DeviceSelector で一度だけ書き込み) ──────────────────────────
    private static volatile String whisperCli;
    private static volatile String deviceLabel;

    /** DeviceSelector から呼ぶ。録音中は呼び出さないこと。 */
    public static synchronized void setDevice(String cli, String label) {
        whisperCli  = cli;
        deviceLabel = label;
    }

    public static String getWhisperCli() {
        if (whisperCli == null) throw new IllegalStateException("Device not selected yet.");
        return whisperCli;
    }

    public static String getDeviceLabel() {
        if (deviceLabel == null) throw new IllegalStateException("Device not selected yet.");
        return deviceLabel;
    }
}
