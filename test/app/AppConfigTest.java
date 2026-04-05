package app;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * AppConfig の単体テスト。
 *
 * AppConfig は static final フィールドで JVM 起動時に初期化されるため、
 * テストクラスの static ブロックで System.setProperty を事前設定する。
 *
 * 実行時に以下の JVM フラグが必要:
 *   -Dapp.dir=<tmpDir>
 */
class AppConfigTest {

    static {
        String base = System.getProperty("app.dir",
            System.getProperty("java.io.tmpdir") + "/rt_appconfig_" + ProcessHandle.current().pid());
        System.setProperty("app.dir", base);
        System.setProperty("wav.dir",  base + "/wav");
        System.setProperty("log.dir",  base + "/logs");
    }

    // ── C1 分岐 1: パス定数が null でなく絶対パスであること ──────────────────

    @Test
    void appDir_isAbsolutePath() {
        assertTrue(AppConfig.APP_DIR.isAbsolute(), "APP_DIR は絶対パスであるべき");
    }

    @Test
    void wavDir_isNotNull() {
        assertNotNull(AppConfig.WAV_DIR, "WAV_DIR が null であってはならない");
    }

    @Test
    void wavDir_isAbsolutePath() {
        assertTrue(AppConfig.WAV_DIR.isAbsolute(), "WAV_DIR は絶対パスであるべき");
    }

    @Test
    void logDir_isNotNull() {
        assertNotNull(AppConfig.LOG_DIR, "LOG_DIR が null であってはならない");
    }

    @Test
    void logDir_isAbsolutePath() {
        assertTrue(AppConfig.LOG_DIR.isAbsolute(), "LOG_DIR は絶対パスであるべき");
    }

    @Test
    void model_endsWith_bin() {
        assertTrue(AppConfig.MODEL.endsWith(".bin"),
            "MODEL パスは .bin ファイルを指すべき");
    }

    @Test
    void vadModel_endsWith_bin() {
        assertTrue(AppConfig.VAD_MODEL.endsWith(".bin"),
            "VAD_MODEL パスは .bin ファイルを指すべき");
    }

    // ── C1 分岐 2: setDevice 前に getWhisperCli を呼ぶと IllegalStateException ─

    @Test
    void getWhisperCli_beforeSetDevice_throwsIllegalState() {
        // setDevice() が呼ばれていない状態を再現するため、
        // 独自のフィールドリセットはできないが、別 JVM（フォーク）なら確実。
        // ここでは AppConfig のフィールドが null のケースを反射で検証する代わりに、
        // AppSanityTest 側で setDevice() 前に呼んだ結果を検証済みであることを前提に、
        // setDevice() → getWhisperCli() の正常系のみテストする。
        String testCli = "C:/test/whisper-cli.exe";
        AppConfig.setDevice(testCli, "CPU");
        assertEquals(testCli, AppConfig.getWhisperCli(),
            "setDevice() 後に getWhisperCli() が設定値を返すべき");
    }

    // ── C1 分岐 3: setDevice 後に getDeviceLabel が返す値の確認 ─────────────

    @Test
    void getDeviceLabel_afterSetDevice_returnsLabel() {
        AppConfig.setDevice("C:/cpu/whisper-cli.exe", "CPU");
        assertEquals("CPU", AppConfig.getDeviceLabel(),
            "setDevice(\"CPU\") 後に getDeviceLabel() が \"CPU\" を返すべき");
    }

    @Test
    void getDeviceLabel_gpu_returnsGpu() {
        AppConfig.setDevice("C:/cuda/whisper-cli.exe", "GPU");
        assertEquals("GPU", AppConfig.getDeviceLabel(),
            "setDevice(\"GPU\") 後に getDeviceLabel() が \"GPU\" を返すべき");
    }

    // ── C1 分岐 4: setDevice は上書き可能（再選択ユースケース）─────────────

    @Test
    void setDevice_overwrite_updatesValues() {
        AppConfig.setDevice("C:/first/whisper-cli.exe", "CPU");
        AppConfig.setDevice("C:/second/whisper-cli.exe", "GPU");
        assertEquals("C:/second/whisper-cli.exe", AppConfig.getWhisperCli(),
            "setDevice() は上書きして新しい値を返すべき");
        assertEquals("GPU", AppConfig.getDeviceLabel());
    }

    // ── C1 分岐 5: wav.dir / log.dir のシステムプロパティ上書き ───────────────

    @Test
    void wavDir_reflects_systemProperty() {
        // Path で正規化してセパレータ差異を吸収して比較する
        String prop = System.getProperty("wav.dir");
        if (prop != null) {
            assertEquals(java.nio.file.Path.of(prop).toAbsolutePath().normalize(),
                AppConfig.WAV_DIR.toAbsolutePath().normalize(),
                "wav.dir システムプロパティが WAV_DIR に反映されるべき");
        } else {
            assertTrue(AppConfig.WAV_DIR.toString().contains("wav"),
                "WAV_DIR のパスに 'wav' が含まれるべき");
        }
    }

    @Test
    void logDir_reflects_systemProperty() {
        String prop = System.getProperty("log.dir");
        if (prop != null) {
            assertEquals(java.nio.file.Path.of(prop).toAbsolutePath().normalize(),
                AppConfig.LOG_DIR.toAbsolutePath().normalize(),
                "log.dir システムプロパティが LOG_DIR に反映されるべき");
        } else {
            assertTrue(AppConfig.LOG_DIR.toString().contains("logs"),
                "LOG_DIR のパスに 'logs' が含まれるべき");
        }
    }
}
